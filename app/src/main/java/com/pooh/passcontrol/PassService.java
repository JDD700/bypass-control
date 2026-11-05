package com.pooh.passcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.app.AlarmManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.Calendar;

/**
 * Automation core. Primary trigger is ACTION_BATTERY_CHANGED (event-driven), plus:
 *  - a 5-minute Handler watchdog WHILE PLUGGED (re-asserts bypass if firmware reset it,
 *    e.g. screen-off, and warns if % rose while bypass was supposed to be ON);
 *  - window-edge alarms at the next Active-duration start/end that re-trigger evaluation.
 * Config (charge threshold, window) is re-read from SharedPreferences on every
 * evaluation, so in-app changes take effect without restarting the service. Alarms
 * are (re)scheduled in onStartCommand, which is the common path for boot,
 * config-change pokes and alarm fires.
 *
 * Rule priority while plugged in (highest first):
 *  1. HARD CAP (Pc.CAP_PCT = 95%), only while the user's cap toggle
 *     (Pc.capEnabled, default ON) is on: the phone must never charge above
 *     95%. At >= 95 bypass is forced ON; once engaged it stays engaged until
 *     the level falls to Pc.CAP_RELEASE (93%), then normal rules resume. The
 *     cap overrides everything - outside-window force-off, the Auto threshold
 *     rule, and even Off mode's enforced-off. Toggle off = no cap at all.
 *  2. Off mode enforcement (master == false): any pass_through == 1 observed
 *     below the cap is written back to 0.
 *  3. Window: outside it, pass_through is forced 0 (normal charging).
 *  4. Charge threshold (the single battery number, pref "holdPct"): inside the
 *     window the rule is a pure function of the level - below the threshold the
 *     phone charges (bypass OFF); at or above it, it doesn't charge (bypass
 *     ON). There is no resume margin / second threshold; integer-% granularity
 *     is the only deadband. Hard floor: never ON below 25%.
 * The unplug safety reset runs before all of this, regardless of mode.
 *
 * Config-change pokes carry EXTRA_FRESH: a fresh evaluation inside the window
 * applies the same threshold rule strictly (which it now does anyway) - the
 * distinction matters because a fresh poke must re-decide from the CURRENT
 * settings immediately, not wait for drift.
 *
 * Warnings are real notifications as of v7: the watchdog warnings (rise/drain
 * while bypass ON) and any WRITE FAILED post a HIGH-importance alert on the
 * "Bypass Control warnings" channel (notification ID 2), in addition to the log
 * line. The alert clears when a write succeeds, when bypass is verified
 * holding within +/-1 of baseline, on unplug, or in Off mode.
 */
public class PassService extends Service {
    private static final String CHANNEL = "passcontrol";
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_WARN = "passcontrol_warn";
    private static final int WARN_NOTIF_ID = 2;
    private static final String ACTION_EDGE = "com.pooh.passcontrol.EDGE";
    /** Notification toggle action (v8): the persistent notification carries one
     *  action button that flips the mode - labelled "Turn Off" while Auto,
     *  "Turn On (Auto)" while Off. See handleNotifToggle(). */
    private static final String ACTION_NOTIF_TOGGLE = "com.pooh.passcontrol.NOTIF_TOGGLE";
    /** Poke extra: this start was triggered by a CONFIG CHANGE (slider drag end
     *  or a mode switch), not a battery event. A fresh evaluation inside the
     *  window re-decides strictly from the current settings - stale state from
     *  before the change must not survive it (e.g. threshold raised above the
     *  current level while bypass was on must turn bypass OFF immediately). */
    static final String EXTRA_FRESH = "com.pooh.passcontrol.FRESH";
    private static final long WATCHDOG_MS = 5 * 60 * 1000L;
    private String stateText = "starting";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean watchdogActive = false;
    private int lastPlugged = 0;
    private int onBaselineLevel = -1; // battery % when bypass ON was (last) asserted
    private boolean capActive = false; // hard-cap latch: set at >= CAP_PCT, cleared at <= CAP_RELEASE
    private String activeWarningKey = null; // de-dup key of the warning alert currently showing

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            Intent sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) evaluate(sticky, "watchdog", false);
            if (lastPlugged != 0) {
                handler.postDelayed(this, WATCHDOG_MS);
            } else {
                watchdogActive = false;
            }
        }
    };

    private final BroadcastReceiver battReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) { evaluate(i, "event", false); }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startFg("starting");
        Intent sticky = registerReceiver(battReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) evaluate(sticky, "start", false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg(stateText);
        boolean fresh = intent != null && intent.getBooleanExtra(EXTRA_FRESH, false);
        if (intent != null && ACTION_NOTIF_TOGGLE.equals(intent.getAction())) {
            fresh = handleNotifToggle() || fresh;
        }
        scheduleWindowAlarms(); // covers boot, config change, and post-alarm recompute
        boolean fromAlarm = intent != null && ACTION_EDGE.equals(intent.getAction());
        if (fromAlarm) Pc.log(this, "Window-edge alarm fired - re-evaluating");
        Intent sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) evaluate(sticky, fromAlarm ? "alarm" : "start", fresh);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(watchdog);
        watchdogActive = false;
        try { unregisterReceiver(battReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    /** Notification toggle (v8). Tapping the persistent notification's action
     *  button in Auto does exactly what the in-app Off does: the mode pref
     *  flips FIRST, then a verified pass_through=0 write forces bypass off.
     *  Tapping it in Off returns to Auto and asks the caller for a strict
     *  (fresh) re-evaluation. The 95% hard cap, when toggled on, still
     *  outranks Off in the evaluation that follows - same as in-app.
     *  @return true when the caller should evaluate fresh (Off -> Auto). */
    private boolean handleNotifToggle() {
        if (Pc.MODE_AUTO.equals(Pc.mode(this))) {
            Pc.setMode(this, Pc.MODE_OFF);
            Pc.WriteResult r = Pc.write(this, 0);
            String msg = r.verified(0)
                    ? "Notification toggle: Off - bypass forced OFF VERIFIED readback=0"
                    : "Notification toggle: Off - bypass forced OFF WRITE FAILED readback=" + r.readback
                            + (r.error != null ? " err=" + r.error : "");
            Pc.log(this, msg);
            clearWarning();
            return false;
        }
        Pc.setMode(this, Pc.MODE_AUTO);
        Pc.log(this, "Notification toggle: Auto");
        return true;
    }

    // ---------- plugged watchdog ----------

    private void syncWatchdog(int plugged) {
        lastPlugged = plugged;
        if (plugged != 0 && !watchdogActive) {
            watchdogActive = true;
            handler.postDelayed(watchdog, WATCHDOG_MS);
        } else if (plugged == 0 && watchdogActive) {
            watchdogActive = false;
            handler.removeCallbacks(watchdog);
        }
    }

    // ---------- window-edge alarms ----------

    private long nextBoundaryMillis(int minutesOfDay) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutesOfDay / 60);
        c.set(Calendar.MINUTE, minutesOfDay % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    private PendingIntent edgeIntent(int requestCode) {
        Intent i = new Intent(this, PassService.class).setAction(ACTION_EDGE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, requestCode, i, flags);
        return PendingIntent.getService(this, requestCode, i, flags);
    }

    private void scheduleOne(AlarmManager am, long atMillis, int requestCode) {
        PendingIntent pi = edgeIntent(requestCode);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
                } catch (SecurityException se) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
                }
            } else {
                am.set(AlarmManager.RTC_WAKEUP, atMillis, pi);
            }
        } catch (SecurityException se) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private void scheduleWindowAlarms() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        if (!Pc.schedEnabled(this) || Pc.startMin(this) == Pc.endMin(this)) {
            // 24/7 % rule: no edges to schedule; cancel any stale ones.
            try { am.cancel(edgeIntent(2001)); } catch (Exception ignored) {}
            try { am.cancel(edgeIntent(2002)); } catch (Exception ignored) {}
            return;
        }
        scheduleOne(am, nextBoundaryMillis(Pc.startMin(this)), 2001);
        scheduleOne(am, nextBoundaryMillis(Pc.endMin(this)), 2002);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Bypass Control", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Pass-through automation status");
            nm.createNotificationChannel(ch);
            NotificationChannel warn = new NotificationChannel(CHANNEL_WARN, "Bypass Control warnings", NotificationManager.IMPORTANCE_HIGH);
            warn.setDescription("Bypass not holding, or a pass_through write failed");
            nm.createNotificationChannel(warn);
        }
    }

    private Notification buildNotif(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // v8: one action button - the Off/Auto toggle. Its label flips with
        // the current mode: "Turn Off" while Auto, "Turn On (Auto)" while Off.
        boolean auto = Pc.MODE_AUTO.equals(Pc.mode(this));
        Intent toggle = new Intent(this, PassService.class).setAction(ACTION_NOTIF_TOGGLE);
        int tflags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) tflags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tpi = Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(this, 3001, toggle, tflags)
                : PendingIntent.getService(this, 3001, toggle, tflags);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Bypass Control")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_lock_power_off,
                        auto ? "Turn Off" : "Turn On (Auto)", tpi)
                .build();
    }

    private void startFg(String text) {
        stateText = text;
        Notification n = buildNotif(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotif(String text) {
        stateText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotif(text));
    }

    // ---------- warning alerts (v7) ----------

    /** Post (or refresh) the HIGH-importance warning alert. De-duped by key:
     *  an alert with the same key already showing is not re-posted. Watchdog
     *  warnings use their message text as the key (their once-per-drift
     *  re-baselining already limits frequency); write failures use the failed
     *  target state, so a repeatedly failing write to the same target alerts
     *  at most once until cleared. The log remains the full record - this is
     *  only the alert surface. */
    private void postWarning(String body, String key) {
        if (key != null && key.equals(activeWarningKey)) return;
        activeWarningKey = key;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        PendingIntent pi = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_WARN)
                .setContentTitle("Bypass Control warning")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .build();
        nm.notify(WARN_NOTIF_ID, n);
    }

    /** Clear the warning alert, if any. Called when a write succeeds, when
     *  bypass is verified holding within +/-1 of baseline, on unplug, and in
     *  Off mode - i.e. when the condition that raised it no longer applies. */
    private void clearWarning() {
        activeWarningKey = null;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(WARN_NOTIF_ID);
    }

    private String autoSummary() {
        return "Auto: " + Pc.fmtTime(Pc.startMin(this)) + "–" + Pc.fmtTime(Pc.endMin(this))
                + " · Charges below " + Pc.holdPct(this) + "%"
                + (Pc.capEnabled(this) ? " · Cap " + Pc.capPct(this) + "%" : "");
    }

    private void evaluate(Intent i, String source, boolean fresh) {
        if (i == null) return;
        int levelRaw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (levelRaw < 0 || scale <= 0) return;
        int level = Math.round(levelRaw * 100f / scale);
        syncWatchdog(plugged);

        Integer cur = Pc.read(this);
        String pt = cur == null ? "unset" : String.valueOf(cur);

        if (plugged == 0) {
            // Safety reset: unplug always clears bypass, in Auto AND Off mode.
            // Any warning condition ends with the plug.
            clearWarning();
            if (level <= Pc.capRelease(this)) capActive = false;
            if (cur != null && cur == 1) {
                doWrite(0, level, "unplugged reset");
            } else {
                updateNotif(level + "%  unplugged  pass_through=" + pt);
            }
            return;
        }

        // ---- HARD CAP: never charge above CAP_PCT (95%) - but only while
        // the user toggle (Pc.capEnabled, default ON) is on; with the toggle
        // off there is no cap at all. When on, this is the highest priority
        // while plugged: it overrides the window, the threshold rule AND Off
        // mode's enforced-off. Latched: engages at >= CAP_PCT, releases only
        // at <= CAP_RELEASE (93%), so between 94 and 95 the cap state depends
        // on where the level came from. While latched the desired state is ON.
        if (Pc.capEnabled(this)) {
            if (level >= Pc.capPct(this)) {
                capActive = true;
            } else if (level <= Pc.capRelease(this)) {
                if (capActive) Pc.log(this, "Hard cap released at " + level + "% (<= " + Pc.capRelease(this) + "%) - normal rules resume");
                capActive = false;
            }
            if (capActive) {
                if (cur != null && cur == 1) {
                    onBaselineLevel = level; // keep post-cap drift tracking fresh
                    updateNotif(level + "%  hard cap " + Pc.capPct(this) + "% - bypass ON (not charging)  " + autoSummary());
                } else {
                    doWrite(1, level, "hard cap: never charge above " + Pc.capPct(this) + "%");
                }
                return;
            }
        } else {
            capActive = false;
        }

        if (!Pc.master(this)) {
            // Off mode (master == false) is AUTHORITATIVE below the cap, not a
            // passive no-op: if anything left bypass on - a leftover state,
            // another tool, a firmware quirk - force it back off. Runs on
            // every battery event and every watchdog tick while plugged.
            clearWarning(); // mode Off ends any warning condition
            if (cur != null && cur == 1) {
                doWrite(0, level, "mode OFF");
            } else {
                updateNotif(level + "%  plugged=" + plugged + "  pass_through=" + pt + "  bypass OFF (mode Off)");
            }
            return;
        }

        // Config is re-read from SharedPreferences on EVERY event, so in-app changes
        // (threshold, window) take effect on the next battery event with no service restart.
        Calendar cal = Calendar.getInstance();
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        boolean inActive = Pc.inActiveWindow(this, nowMin);
        int hold = Pc.holdPct(this); // the charge threshold (pref key kept from v6)

        if (!inActive) {
            // Outside the bypass window: force normal charging.
            if (cur != null && cur == 1) {
                doWrite(0, level, "outside window " + Pc.fmtTime(Pc.startMin(this)) + "-" + Pc.fmtTime(Pc.endMin(this)));
            } else {
                updateNotif(level + "%  outside window (charging)  " + autoSummary());
            }
            return;
        }
        if (fresh) {
            // STRICT re-evaluation after a settings change: decide purely from
            // the CURRENT level vs the CURRENT threshold. Stale state from
            // before the change must not survive it.
            int desired = (level >= hold) ? 1 : 0;
            if (desired == 1 && level < 25) { // hard safety floor, as below
                Pc.log(this, "Refused ON at " + level + "% (< 25% floor)");
                updateNotif(level + "%  below safety floor");
                return;
            }
            if (cur == null || cur != desired) {
                doWrite(desired, level, desired == 1
                        ? "settings changed: at/above " + hold + "% - not charging"
                        : "settings changed: below " + hold + "% - charging");
            } else {
                updateNotif(level + "%  pass_through=" + desired + " (settings re-eval, no change)");
            }
            return;
        }
        boolean isOn = cur != null && cur == 1;
        if (level >= hold) {
            // At/above the threshold: the phone doesn't charge.
            if (level < 25) { // hard safety floor (defensive; threshold is >= 30)
                Pc.log(this, "Refused ON at " + level + "% (< 25% floor)");
                updateNotif(level + "%  below safety floor");
                return;
            }
            if (!isOn) {
                String why = ("watchdog".equals(source) && cur != null && cur == 0)
                        ? "Re-asserted bypass (was reset - possibly screen-off)"
                        : "at/above " + hold + "% - not charging";
                doWrite(1, level, why);
            } else {
                trackOnBaseline(level);
                updateNotif(level + "%  bypass ON (not charging)  " + autoSummary());
            }
            return;
        }
        // Below the threshold: the phone charges.
        if (isOn) {
            doWrite(0, level, "below " + hold + "% - charging");
        } else {
            updateNotif(level + "%  charging (below " + hold + "%)  " + autoSummary());
        }
    }

    /** While bypass is ON the level should hold flat; track the level at
     *  (last) assertion and warn on either failure signature:
     *  - a RISE of >=2 points: bypass was lost, the battery is charging again
     *    (e.g. firmware dropped bypass while the screen was off);
     *  - a DROP of >=3 points: the battery is draining despite bypass - the
     *    charger is not keeping up with the load, or bypass is not holding.
     *  Either warning re-baselines to the new level, so each logs once per
     *  assertion/drift, never on every watchdog tick. As of v7 each warning
     *  also posts the warning notification; a level back within +/-1 of the
     *  baseline means bypass is verified holding again and clears it. */
    private void trackOnBaseline(int level) {
        if (onBaselineLevel < 0) {
            onBaselineLevel = level;
            return;
        }
        if (Math.abs(level - onBaselineLevel) <= 1) {
            clearWarning(); // verified holding - the warning condition is over
        }
        if (level >= onBaselineLevel + 2) {
            String w = "WARNING: % rose while bypass ON (" + onBaselineLevel + "% -> " + level
                    + "%) - bypass may not hold (screen off?)";
            Pc.log(this, w);
            postWarning(w, w);
            onBaselineLevel = level;
        } else if (level <= onBaselineLevel - 3) {
            String w = "WARNING: battery draining while bypass ON (" + onBaselineLevel + "% -> " + level
                    + "%) - charger may not be keeping up, or bypass not holding";
            Pc.log(this, w);
            postWarning(w, w);
            onBaselineLevel = level;
        }
    }

    private void doWrite(int v, int level, String why) {
        Pc.WriteResult r = Pc.write(this, v);
        if (r.verified(v)) {
            onBaselineLevel = (v == 1) ? level : -1;
            clearWarning(); // a successful write ends any warning condition
            Pc.log(this, "Set pass_through=" + v + " (" + why + ", batt " + level + "%) VERIFIED readback=" + r.readback);
            updateNotif(level + "%  pass_through=" + v + " VERIFIED");
        } else {
            String msg = "WRITE FAILED pass_through->" + v + " (" + why + ") readback=" + r.readback
                    + (r.error != null ? " err=" + r.error : "");
            Pc.log(this, msg);
            // The most important alert there is: the app tried to change the
            // bypass state and couldn't. De-duped per failed target state.
            postWarning(msg, "WRITE FAILED->" + v);
            updateNotif(level + "%  WRITE FAILED -> " + v);
        }
    }
}

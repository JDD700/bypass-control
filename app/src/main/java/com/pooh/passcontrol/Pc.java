package com.pooh.passcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Shared config, pass_through read/write with readback verification, and event log. */
public final class Pc {
    public static final String KEY = "pass_through";
    private static final String CONFIG = "config";
    private static final String LOGP = "applog";
    private static final Object LOCK = new Object();
    private static final int SENTINEL = -999;

    private Pc() {}

    public static SharedPreferences cfg(Context c) {
        return c.getSharedPreferences(CONFIG, Context.MODE_PRIVATE);
    }

    public static boolean master(Context c)      { return cfg(c).getBoolean("master", false); }

    // v5 mode: Auto | Off. Manual mode was removed - its only real use was "off".
    // "mode" is the UI-facing pref; "master" stays the service-facing gate and is
    // kept in sync by setMode() (automation runs iff mode is auto).
    public static final String MODE_AUTO = "auto";
    public static final String MODE_OFF = "off";
    private static final String MODE_MANUAL_V4 = "manual"; // v4 stored value

    /** Current mode. A stored v4 "manual" migrates to "off". If "mode" was never
     *  set, derive it from the legacy master state: master ON -> auto, OFF -> off. */
    public static String mode(Context c) {
        SharedPreferences p = cfg(c);
        String m = p.getString("mode", null);
        if (MODE_AUTO.equals(m) || MODE_OFF.equals(m)) return m;
        if (MODE_MANUAL_V4.equals(m)) return MODE_OFF;
        return p.getBoolean("master", false) ? MODE_AUTO : MODE_OFF;
    }

    /** Switch mode, keeping "master" in sync: automation runs iff mode is auto. */
    public static void setMode(Context c, String m) {
        cfg(c).edit().putString("mode", m).putBoolean("master", MODE_AUTO.equals(m)).apply();
    }
    /** Legacy v1/v2 single threshold. Kept only as a migration source for holdPct. */
    public static int threshold(Context c)       { return cfg(c).getInt("threshold", 70); }

    /** Hard cap default (v7-v9: the cap was fixed at 95%). v10: the cap
     *  LEVEL is user-selectable (80-95 in 5% steps, see capPct) - 95 stays
     *  the default so existing installs behave exactly as before. While the
     *  cap toggle is on, the phone must NEVER charge above the cap level,
     *  under any circumstance - any mode, any window, any threshold setting.
     *  Enforced by PassService as the highest-priority rule while plugged. */
    public static final int CAP_PCT = 95; // default cap level
    /** The user's cap level (pref "capPct"), clamped to 80-95. */
    public static int capPct(Context c) {
        int v = cfg(c).getInt("capPct", CAP_PCT);
        return Math.max(80, Math.min(CAP_PCT, v));
    }
    /** Cap release margin (internal only, not a UI control): once the cap
     *  has engaged it stays engaged until the level falls 2 points below
     *  the cap, then normal rules resume. Charging back up towards the cap
     *  is allowed; charging above it never is. */
    public static int capRelease(Context c) { return capPct(c) - 2; }

    /** The hard cap is a user TOGGLE (v7), default ON: James asked for the
     *  feature, the switch exists so he can turn it off. When false there is
     *  no cap at all - the phone may charge to 100% and every other rule
     *  behaves exactly as before. */
    public static boolean capEnabled(Context c) { return cfg(c).getBoolean("capEnabled", true); }

    /** v7 single battery control: the CHARGE THRESHOLD. Below this level the
     *  phone charges; at or above it, it doesn't (bypass on). A pure function
     *  of the level - there is no resume margin / second threshold any more.
     *  The pref key stays "holdPct" from v6 so the stored value survives the
     *  rename (migration chain: "holdPct" -> v3-v5 "onPct" -> v1/v2
     *  "threshold" -> default 80). Clamped to the slider range 30-95 while
     *  the hard cap is ON (a threshold above the cap would be meaningless),
     *  or 30-100 when the cap is OFF. Read-side only - once the user moves
     *  the battery slider the key is written explicitly. */
    public static int holdPct(Context c) {
        SharedPreferences p = cfg(c);
        int v;
        if (p.contains("holdPct")) v = p.getInt("holdPct", 80);
        else if (p.contains("onPct")) v = p.getInt("onPct", 80);
        else if (p.contains("threshold")) v = p.getInt("threshold", 80);
        else v = 80;
        return Math.max(30, Math.min(capEnabled(c) ? capPct(c) : 100, v));
    }

    // ---------- write executor (v10) ----------
    // Two ways to write pass_through:
    //  - DIRECT (default): Settings.System.putInt from the app itself. Works
    //    on devices where the legacy targetSdk-22 exemption applies (proven
    //    on James's Fold 8). Needs no helper app and survives reboots.
    //  - SHIZUKU: the write is executed by the Shizuku server with shell
    //    identity (`settings put ...`), which the SettingsProvider always
    //    accepts. For devices where the direct write is rejected. Requires
    //    the Shizuku app installed, running, and permission granted to this
    //    app; Shizuku itself must be re-started after a reboot (its own
    //    limitation, not ours).
    // The pref is only ever switched by the user (setup card), never
    // automatically: a silent executor swap would mask real failures.
    public static boolean useShizuku(Context c) { return cfg(c).getBoolean("useShizuku", false); }
    public static void setUseShizuku(Context c, boolean v) { cfg(c).edit().putBoolean("useShizuku", v).apply(); }
    public static String executorName(Context c) { return useShizuku(c) ? "Shizuku" : "Direct"; }

    // Bypass window: automation (% rule) runs INSIDE it; outside it pass_through is forced 0.
    // Default enabled, 07:00-23:00 placeholder; user-configurable in SharedPreferences.
    public static boolean schedEnabled(Context c){ return cfg(c).getBoolean("sched", true); }
    public static int startMin(Context c)        { return cfg(c).getInt("startMin", 7 * 60); }
    public static int endMin(Context c)          { return cfg(c).getInt("endMin", 23 * 60); }

    /** @return current pass_through value, or null if unset/unreadable. */
    public static Integer read(Context c) {
        try {
            int v = Settings.System.getInt(c.getContentResolver(), KEY, SENTINEL);
            return v == SENTINEL ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    public static final class WriteResult {
        public final boolean putOk;
        public final Integer readback;
        public final String error;
        WriteResult(boolean p, Integer r, String e) { putOk = p; readback = r; error = e; }
        public boolean verified(int want) { return readback != null && readback == want; }
    }

    /** Write pass_through and always verify by reading it back. Never throws.
     *  Holds a PARTIAL_WAKE_LOCK only transiently for the write+readback (10s timeout
     *  backstop, released in finally) - never held continuously. */
    public static WriteResult write(Context c, int v) {
        android.os.PowerManager.WakeLock wl = null;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "BypassControl:write");
                wl.setReferenceCounted(false);
                wl.acquire(10_000L);
            }
        } catch (Exception ignored) { wl = null; }
        try {
            boolean ok;
            String err = null;
            if (useShizuku(c)) {
                // Shizuku executor: shell-identity `settings put` via the
                // Shizuku server. Success = the command ran (exit 0); the
                // readback below is still the real verification.
                int exit = ShizukuExec.putPassThrough(v);
                ok = exit == 0;
                if (!ok) err = "Shizuku write failed (exit=" + exit + ") - is Shizuku running, with permission granted to Bypass Control?";
            } else {
                try {
                    ok = Settings.System.putInt(c.getContentResolver(), KEY, v);
                } catch (SecurityException se) {
                    ok = false;
                    err = "SecurityException: " + se.getMessage();
                } catch (Exception e) {
                    ok = false;
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                if (!ok && err == null) err = "putInt returned false (permission not granted?)";
            }
            Integer rb = read(c);
            return new WriteResult(ok, rb, err);
        } finally {
            try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
        }
    }

    public static void log(Context c, String msg) {
        synchronized (LOCK) {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            List<String> lines = new ArrayList<>();
            String old = c.getSharedPreferences(LOGP, Context.MODE_PRIVATE).getString("log", "");
            if (old != null && !old.isEmpty()) {
                for (String s : old.split("\n")) lines.add(s);
            }
            lines.add(ts + "  " + msg);
            while (lines.size() > 50) lines.remove(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(lines.get(i));
            }
            c.getSharedPreferences(LOGP, Context.MODE_PRIVATE).edit().putString("log", sb.toString()).apply();
        }
    }

    public static String getLog(Context c) {
        String s = c.getSharedPreferences(LOGP, Context.MODE_PRIVATE).getString("log", "");
        return (s == null || s.isEmpty()) ? "(no events yet)" : s;
    }

    public static String fmtTime(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Inside the bypass window? Handles overnight wrap (e.g. 22:00-07:00).
     *  Start inclusive, end exclusive. Disabled window, or start==end (full day),
     *  => always inside, i.e. the % rule governs 24/7. */
    public static boolean inActiveWindow(Context c, int nowMin) {
        if (!schedEnabled(c)) return true;
        int s = startMin(c), e = endMin(c);
        if (s == e) return true;
        if (s < e) return nowMin >= s && nowMin < e;
        return nowMin >= s || nowMin < e;
    }
}

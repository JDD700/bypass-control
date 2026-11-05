package com.pooh.passcontrol;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * v8 UI: MODE design - Auto | Off, never both on screen at the same time.
 *  - Status card (always): ONLY the battery % and BYPASS ON/OFF, and the whole
 *    card is the indicator - BLUE when bypass is ON, BLACK when OFF. Below it
 *    a big segmented "Auto | Off" selector.
 *  - AUTO screen: only the two slider cards (24h bypass window, battery charge
 *    threshold). The battery card states the rule in plain charge language:
 *    a big % number above the slider with "Below N% - phone charges" /
 *    "Above N% - phone doesn't charge" directly under the number.
 *  - OFF screen: only a simple status card ("Bypass is OFF") - nothing to operate.
 *    Entering Off forces pass_through to 0 with a verified write, and the
 *    service keeps enforcing 0 while plugged (see PassService).
 *  - Log card (always, at the bottom) with footer actions (Test Write / Modify
 *    system settings / Allow background run).
 * The "mode" pref is kept in sync with the legacy "master" boolean by
 * Pc.setMode(): automation runs iff mode is Auto.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0E1116;
    private static final int CARD = 0xFF171C24;
    private static final int SEG_BG = 0xFF11161D;
    private static final int STROKE = 0xFF232A35;
    private static final int ACCENT = 0xFF2DD4BF;
    private static final int STATUS_BLUE = 0xFF2563EB; // status card while bypass ON
    private static final int STATUS_BLACK = 0xFF000000; // status card while bypass OFF
    private static final int TEXT = 0xFFE8ECF1;
    private static final int SUB = 0xFF9AA4B2;

    private TextView battPctText, bypassStateText, logText;
    private GradientDrawable statusBg;
    private TextView winOnText, winOffText, winLenText, holdText, thresholdLines;
    private TextView modeAutoText, modeOffText;
    private TextView setupVerdictText, setupLinesText, setupExecBtn;
    private Switch capSwitch;
    private TextView capTitleText, capLineText, capLevelText;
    private LinearLayout autoContainer, offContainer;
    private RangeSliderView timeSlider, battSlider;

    private final BroadcastReceiver battUi = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshStatus(i); }
    };

    // v9: while the app is open, re-read the status every 2 s. Battery-change
    // broadcasts alone are too sparse: at a held/flat battery the next one can
    // be many minutes away, which made the status card (blue/black) lag far
    // behind the real pass_through state after an Off -> Auto switch.
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            refreshStatus(null);
            uiHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        // First run after an update: normalise the stored mode. v4's "manual"
        // migrates to "off"; if "mode" was never set, Pc.mode() derives it from
        // the legacy master state. Persisting it keeps "mode"/"master" in sync.
        String storedMode = Pc.cfg(this).getString("mode", null);
        if (!Pc.MODE_AUTO.equals(storedMode) && !Pc.MODE_OFF.equals(storedMode)) {
            Pc.setMode(this, Pc.mode(this));
        }
        buildUi();
        bindValues();
        startPassService();
        refreshStatus(null);
        refreshLog();
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(battUi, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        // The mode / cap may have been changed from the notification toggle
        // while the app was in the background - re-sync the controls.
        applyModeUi();
        applyCapUi();
        refreshSetup();
        refreshStatus(null);
        refreshLog();
        uiHandler.removeCallbacks(statusPoll);
        uiHandler.postDelayed(statusPoll, 2000);
    }

    @Override protected void onPause() {
        uiHandler.removeCallbacks(statusPoll);
        try { unregisterReceiver(battUi); } catch (Exception ignored) {}
        super.onPause();
    }

    // ---------- small helpers ----------

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), STROKE);
        c.setBackground(g);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(14));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView cardTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(SUB);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView label(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView footerBtn(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(ACCENT);
        t.setPadding(dp(8), dp(10), dp(8), dp(4));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private static String fmtClock(int minutes) {
        if (minutes >= 1440) return "24:00";
        return Pc.fmtTime(minutes);
    }

    private static int normDay(int minutes) {
        return minutes >= 1440 ? 0 : minutes;
    }

    // ---------- UI construction ----------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView appTitle = label("Bypass Control", 22, TEXT, true);
        appTitle.setPadding(0, 0, 0, dp(14));
        root.addView(appTitle);

        // (a) STATUS card - always visible, deliberately minimal (v8): just
        // the battery percentage and BYPASS ON/OFF. The whole card is the
        // indicator - BLUE when bypass is ON, BLACK when it is OFF (the
        // background is repainted in updateStatusCard()).
        LinearLayout status = card();
        statusBg = new GradientDrawable();
        statusBg.setColor(STATUS_BLACK);
        statusBg.setCornerRadius(dp(20));
        statusBg.setStroke(dp(1), STROKE);
        status.setBackground(statusBg);
        status.setGravity(Gravity.CENTER);
        battPctText = label("--%", 56, TEXT, true);
        battPctText.setGravity(Gravity.CENTER);
        status.addView(battPctText);
        bypassStateText = label("BYPASS ?", 20, TEXT, true);
        bypassStateText.setGravity(Gravity.CENTER);
        bypassStateText.setLetterSpacing(0.08f);
        bypassStateText.setPadding(0, dp(4), 0, dp(2));
        status.addView(bypassStateText);
        root.addView(status);

        // (a2) SETUP card (v10) - the first-run checklist, between the status
        // card and the mode selector. Answers, in plain language: does this
        // phone have the switch, can we write it (and via which path), are
        // the grants in place. Collapses to one line once everything passes.
        LinearLayout setup = card();
        setup.addView(cardTitle("SETUP CHECK"));
        setupVerdictText = label("", 15, TEXT, true);
        setup.addView(setupVerdictText);
        setupLinesText = label("", 12, SUB, false);
        setupLinesText.setPadding(0, dp(6), 0, 0);
        setup.addView(setupLinesText);
        LinearLayout suRow = new LinearLayout(this);
        suRow.setOrientation(LinearLayout.HORIZONTAL);
        suRow.setPadding(0, dp(4), 0, 0);
        TextView suRun = footerBtn("Run check");
        TextView suGrant = footerBtn("Shizuku permission");
        setupExecBtn = footerBtn("");
        suRow.addView(suRun);
        suRow.addView(suGrant);
        suRow.addView(setupExecBtn);
        setup.addView(suRow);
        root.addView(setup);
        suRun.setOnClickListener(v -> testWrite());
        suGrant.setOnClickListener(v -> {
            ShizukuExec.requestPermission(1001);
            Pc.log(MainActivity.this, "Requested Shizuku permission (if Shizuku is running)");
            refreshSetup();
            refreshLog();
        });
        setupExecBtn.setOnClickListener(v -> toggleExecutor());

        // (b) MODE selector - always visible, directly under the status card.
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(SEG_BG);
        segBg.setCornerRadius(dp(18));
        segBg.setStroke(dp(1), STROKE);
        seg.setBackground(segBg);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.setMargins(0, 0, 0, dp(14));
        seg.setLayoutParams(segLp);
        modeAutoText = label("Auto", 17, SUB, true);
        modeAutoText.setGravity(Gravity.CENTER);
        modeAutoText.setPadding(0, dp(11), 0, dp(11));
        modeAutoText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modeAutoText.setClickable(true);
        modeAutoText.setFocusable(true);
        seg.addView(modeAutoText);
        modeOffText = label("Off", 17, SUB, true);
        modeOffText.setGravity(Gravity.CENTER);
        modeOffText.setPadding(0, dp(11), 0, dp(11));
        modeOffText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modeOffText.setClickable(true);
        modeOffText.setFocusable(true);
        seg.addView(modeOffText);
        root.addView(seg);

        // (b2) HARD CAP toggle card - always visible, in BOTH modes, because
        // the cap outranks modes (it can force bypass ON even in Off mode).
        LinearLayout capCard = card();
        LinearLayout capRow = new LinearLayout(this);
        capRow.setOrientation(LinearLayout.HORIZONTAL);
        capRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout capTexts = new LinearLayout(this);
        capTexts.setOrientation(LinearLayout.VERTICAL);
        capTexts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        capTitleText = label("", 15, TEXT, true);
        capTexts.addView(capTitleText);
        capLineText = label("", 12, SUB, false);
        capLineText.setPadding(0, dp(3), dp(8), 0);
        capTexts.addView(capLineText);
        capRow.addView(capTexts);
        capSwitch = new Switch(this);
        capRow.addView(capSwitch);
        capCard.addView(capRow);
        // v10: cap LEVEL stepper (80-95, 5% steps). The level is a number,
        // not a rule, so a simple - value + row is all it needs.
        LinearLayout capLvlRow = new LinearLayout(this);
        capLvlRow.setOrientation(LinearLayout.HORIZONTAL);
        capLvlRow.setGravity(Gravity.CENTER_VERTICAL);
        capLvlRow.setPadding(0, dp(8), 0, 0);
        TextView capLvlLabel = label("Cap level", 13, SUB, false);
        capLvlLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        capLvlRow.addView(capLvlLabel);
        TextView capMinus = label("−", 22, ACCENT, true);
        capMinus.setGravity(Gravity.CENTER);
        capMinus.setPadding(dp(14), dp(2), dp(14), dp(2));
        capMinus.setClickable(true);
        capMinus.setFocusable(true);
        capLvlRow.addView(capMinus);
        capLevelText = label("--%", 17, TEXT, true);
        capLevelText.setGravity(Gravity.CENTER);
        capLvlRow.addView(capLevelText);
        TextView capPlus = label("+", 22, ACCENT, true);
        capPlus.setGravity(Gravity.CENTER);
        capPlus.setPadding(dp(14), dp(2), dp(14), dp(2));
        capPlus.setClickable(true);
        capPlus.setFocusable(true);
        capLvlRow.addView(capPlus);
        capCard.addView(capLvlRow);
        capMinus.setOnClickListener(v -> stepCapLevel(-5));
        capPlus.setOnClickListener(v -> stepCapLevel(5));
        root.addView(capCard);

        // (c) AUTO screen - only the two slider cards.
        autoContainer = new LinearLayout(this);
        autoContainer.setOrientation(LinearLayout.VERTICAL);
        autoContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout when = card();
        when.addView(cardTitle("BYPASS WINDOW"));
        LinearLayout wRow = new LinearLayout(this);
        wRow.setOrientation(LinearLayout.HORIZONTAL);
        winOnText = label("ON --:--", 26, ACCENT, true);
        winOnText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wRow.addView(winOnText);
        winOffText = label("OFF --:--", 26, TEXT, true);
        winOffText.setGravity(Gravity.END);
        winOffText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wRow.addView(winOffText);
        when.addView(wRow);
        winLenText = label("", 13, SUB, false);
        winLenText.setGravity(Gravity.CENTER);
        winLenText.setPadding(0, dp(2), 0, dp(2));
        when.addView(winLenText);
        timeSlider = new RangeSliderView(this);
        timeSlider.configure(0, 1440, 15, 15, true, true);
        timeSlider.setTicks(new int[]{0, 360, 720, 1080, 1440}, new String[]{"0", "6", "12", "18", "24"});
        timeSlider.setFormatter(new RangeSliderView.Formatter() {
            @Override public String format(int v) { return fmtClock(v); }
        });
        when.addView(timeSlider);
        autoContainer.addView(when);

        LinearLayout batt = card();
        batt.addView(cardTitle("BATTERY"));
        // v7 framing: the single number is a CHARGE THRESHOLD, stated in plain
        // charge language ABOVE the slider: the big number, then the two rule
        // lines ("Below N% - phone charges" / "Above N% - phone doesn't
        // charge", updated live). No "hold" caption, no top-up hint.
        holdText = label("--%", 40, ACCENT, true);
        batt.addView(holdText);
        thresholdLines = label("", 14, TEXT, false);
        thresholdLines.setPadding(0, dp(2), 0, dp(6));
        batt.addView(thresholdLines);
        battSlider = new RangeSliderView(this);
        battSlider.configure(30, 100, 1, 1, false, false); // max rebuilt by the cap toggle: 95 with cap ON, 100 with cap OFF
        battSlider.setSingleMode(true);
        battSlider.setFormatter(new RangeSliderView.Formatter() {
            @Override public String format(int v) { return v + "%"; }
        });
        batt.addView(battSlider);
        autoContainer.addView(batt);
        root.addView(autoContainer);

        // (d) OFF screen - one static status card, nothing to operate.
        offContainer = new LinearLayout(this);
        offContainer.setOrientation(LinearLayout.VERTICAL);
        offContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout off = card();
        off.addView(cardTitle("BYPASS"));
        off.addView(label("Bypass is OFF", 26, TEXT, true));
        TextView offNote = label("Automation is paused. The phone charges normally.", 13, SUB, false);
        offNote.setPadding(0, dp(6), 0, 0);
        off.addView(offNote);
        offContainer.addView(off);
        root.addView(offContainer);

        // (e) LOG card - always visible, at the bottom
        LinearLayout log = card();
        log.addView(cardTitle("LOG"));
        logText = label("-", 11, SUB, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        log.addView(logText);
        LinearLayout fRow = new LinearLayout(this);
        fRow.setOrientation(LinearLayout.HORIZONTAL);
        fRow.setPadding(0, dp(8), 0, 0);
        TextView testBtn = footerBtn("Test Write");
        TextView permBtn = footerBtn("Modify system settings");
        TextView bgBtn = footerBtn("Allow background run");
        fRow.addView(testBtn);
        fRow.addView(permBtn);
        fRow.addView(bgBtn);
        log.addView(fRow);
        root.addView(log);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);

        // ---------- listeners ----------

        modeAutoText.setOnClickListener(v -> setMode(Pc.MODE_AUTO));
        modeOffText.setOnClickListener(v -> setMode(Pc.MODE_OFF));

        capSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // The guard compares against the stored pref, so programmatic
            // syncs from applyCapUi() (which set the same value) are no-ops.
            if (isChecked == Pc.capEnabled(MainActivity.this)) return;
            onCapToggle(isChecked);
        });

        timeSlider.setListener(new RangeSliderView.Listener() {
            @Override public void onChanged(int a, int b) {
                // Saved on every change; the service re-reads prefs per evaluation.
                Pc.cfg(MainActivity.this).edit()
                        .putInt("startMin", normDay(a)).putInt("endMin", normDay(b)).apply();
                updateWindowLabels();
            }
            @Override public void onChangeFinished(int a, int b) {
                int s = normDay(a), e = normDay(b);
                Pc.cfg(MainActivity.this).edit().putInt("startMin", s).putInt("endMin", e).apply();
                timeSlider.setValues(s, e);
                updateWindowLabels();
                Pc.log(MainActivity.this, "Bypass window set to " + Pc.fmtTime(s) + "-" + Pc.fmtTime(e));
                startPassService(true); // fresh poke: strict re-eval (no band hold) + reschedules window alarms
                refreshStatus(null);
                refreshLog();
            }
        });

        battSlider.setListener(new RangeSliderView.Listener() {
            // Single-thumb slider: a == b == the charge threshold.
            @Override public void onChanged(int a, int b) {
                Pc.cfg(MainActivity.this).edit().putInt("holdPct", a).apply();
                updateHoldLabel();
            }
            @Override public void onChangeFinished(int a, int b) {
                Pc.cfg(MainActivity.this).edit().putInt("holdPct", a).apply();
                updateHoldLabel();
                Pc.log(MainActivity.this, "Charge threshold set: " + a + "%");
                startPassService(true); // fresh poke: strict re-eval against the NEW threshold
                refreshStatus(null);
                refreshLog();
            }
        });

        testBtn.setOnClickListener(v -> testWrite());
        permBtn.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        bgBtn.setOnClickListener(v -> {
            // Samsung is aggressive about killing background services; this exemption helps.
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
    }

    private void bindValues() {
        timeSlider.setValues(Pc.startMin(this), Pc.endMin(this));
        applyCapUi();
        rebuildBattSlider();
        int hold = Pc.holdPct(this);
        battSlider.setValues(hold, hold);
        updateWindowLabels();
        updateHoldLabel();
        applyModeUi();
        refreshSetup();
    }

    // ---------- hard cap toggle ----------

    /** Sync the cap switch + its explanatory line from the pref, and the
     *  status detail. Programmatic setChecked is safe: the listener ignores
     *  changes that match the stored pref. */
    private void applyCapUi() {
        boolean on = Pc.capEnabled(this);
        int cap = Pc.capPct(this);
        if (capSwitch != null) capSwitch.setChecked(on);
        if (capTitleText != null) capTitleText.setText("Never charge above " + cap + "%");
        if (capLevelText != null) capLevelText.setText(cap + "%");
        if (capLineText != null) {
            capLineText.setText(on
                    ? "Stops charging at " + cap + "% in every mode, even Off."
                    : "Off — the battery may charge to 100%.");
        }
    }

    /** Cap level stepper: save the new level, then the same consequences
     *  as the toggle - the battery slider's ceiling follows the cap, a
     *  stored threshold above the new cap re-clamps, and a strict (fresh)
     *  poke applies the new cap immediately (lowering the cap to the
     *  current level engages bypass in that same moment). */
    private void stepCapLevel(int delta) {
        int cur = Pc.capPct(this);
        int next = Math.max(80, Math.min(95, cur + delta));
        if (next == cur) return;
        Pc.cfg(this).edit().putInt("capPct", next).apply();
        applyCapUi();
        rebuildBattSlider();
        int hold = Pc.holdPct(this);
        battSlider.setValues(hold, hold);
        updateHoldLabel();
        Pc.log(this, "Hard cap level set: " + next + "%");
        setLast("Hard cap level set: " + next + "%");
        startPassService(true);
        refreshStatus(null);
        refreshLog();
    }

    /** Rebuild the battery slider for the current cap state: max 95 with the
     *  cap ON (a stored threshold above 95 reads back clamped to 95 via
     *  Pc.holdPct), max 100 with the cap OFF. */
    private void rebuildBattSlider() {
        if (battSlider == null) return;
        battSlider.configure(30, Pc.capEnabled(this) ? Pc.capPct(this) : 100, 1, 1, false, false);
        battSlider.setSingleMode(true);
    }

    /** Cap toggle = a config change: save the pref FIRST, then poke the
     *  service with the strict (EXTRA_FRESH) re-eval so it bites immediately -
     *  turning the cap ON while plugged at 97% engages bypass in that same
     *  moment (the service's cap block outranks the fresh threshold eval);
     *  turning it OFF hands control straight back to the normal rules. */
    private void onCapToggle(boolean on) {
        Pc.cfg(this).edit().putBoolean("capEnabled", on).apply();
        applyCapUi();
        rebuildBattSlider();
        int hold = Pc.holdPct(this); // re-clamped under the new cap state
        battSlider.setValues(hold, hold);
        updateHoldLabel();
        Pc.log(this, "Hard cap " + Pc.capPct(this) + "%: " + (on ? "ON" : "OFF"));
        setLast("Hard cap " + Pc.capPct(this) + "%: " + (on ? "ON" : "OFF"));
        startPassService(true);
        refreshStatus(null);
        refreshLog();
    }

    // ---------- mode ----------

    private void styleHalf(TextView t, boolean selected) {
        if (selected) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(ACCENT);
            g.setCornerRadius(dp(14));
            t.setBackground(g);
            t.setTextColor(BG);
        } else {
            t.setBackground(new ColorDrawable(0x00000000));
            t.setTextColor(SUB);
        }
    }

    /** Show exactly one mode's screen and style the selector to match. */
    private void applyModeUi() {
        boolean auto = Pc.MODE_AUTO.equals(Pc.mode(this));
        styleHalf(modeAutoText, auto);
        styleHalf(modeOffText, !auto);
        autoContainer.setVisibility(auto ? View.VISIBLE : View.GONE);
        offContainer.setVisibility(auto ? View.GONE : View.VISIBLE);
    }

    /** Mode switch. Pc.setMode() flips "master" together with "mode" in one
     *  pref write, FIRST: for Off the automation gate is already closed before
     *  anything else happens, for Auto the gate opens before the poke.
     *  Entering Off then immediately forces pass_through to 0 (verified) -
     *  Off means off, not "leave it wherever it was". Entering Auto just pokes
     *  the service, which re-evaluates against the sliders at once. */
    private void setMode(String m) {
        if (m.equals(Pc.mode(this))) { applyModeUi(); return; }
        Pc.setMode(this, m);
        if (Pc.MODE_AUTO.equals(m)) {
            Pc.log(this, "Mode: Auto");
            setLast("Mode: Auto");
            applyAutoNow(); // v9: apply the rule HERE, synchronously - no waiting on the service poke
        } else {
            Pc.WriteResult r = Pc.write(this, 0);
            String msg = r.verified(0)
                    ? "Mode: Off - bypass forced OFF VERIFIED readback=0"
                    : "Mode: Off - bypass forced OFF WRITE FAILED readback=" + r.readback
                            + (r.error != null ? " err=" + r.error : "");
            Pc.log(this, msg);
            setLast(msg);
        }
        applyModeUi();
        startPassService(true); // fresh poke: switching into Auto re-evaluates strictly at once
        refreshStatus(null);
        refreshLog();
    }

    /** v9: apply the Auto rules immediately, in the tap handler itself.
     *  Before v9, switching Off -> Auto only POKED the service and waited for
     *  its next evaluation - and the status card only re-read pass_through on
     *  battery-change broadcasts, so both the actual switch and (especially)
     *  the blue card could lag by minutes at a flat battery %. This runs the
     *  same decision the service's fresh evaluation makes (hard cap ->
     *  window -> charge threshold, 25% floor), with a verified write, right
     *  now. The service poke in setMode() still follows for the notification
     *  text and window alarms. Unplugged: nothing to do - the unplug reset
     *  already guarantees bypass is off. */
    private void applyAutoNow() {
        Intent batt = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batt == null) return;
        int lvl = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plugged = batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (lvl < 0 || scale <= 0 || plugged == 0) return;
        int level = Math.round(lvl * 100f / scale);
        int desired;
        String why;
        if (Pc.capEnabled(this) && level >= Pc.capPct(this)) {
            desired = 1;
            why = "hard cap: never charge above " + Pc.capPct(this) + "%";
        } else {
            Calendar cal = Calendar.getInstance();
            int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            if (!Pc.inActiveWindow(this, nowMin)) {
                desired = 0;
                why = "outside window " + Pc.fmtTime(Pc.startMin(this)) + "-" + Pc.fmtTime(Pc.endMin(this));
            } else if (level >= Pc.holdPct(this)) {
                desired = 1;
                why = "at/above " + Pc.holdPct(this) + "% - not charging";
            } else {
                desired = 0;
                why = "below " + Pc.holdPct(this) + "% - charging";
            }
        }
        if (desired == 1 && level < 25) {
            Pc.log(this, "Refused ON at " + level + "% (< 25% floor)");
            return;
        }
        Integer cur = Pc.read(this);
        if (cur != null && cur == desired) return; // already there - the service poke refreshes the notification
        Pc.WriteResult r = Pc.write(this, desired);
        String msg = r.verified(desired)
                ? "Mode: Auto - bypass " + (desired == 1 ? "ON" : "OFF") + " (" + why + ", batt " + level + "%) VERIFIED readback=" + r.readback
                : "Mode: Auto - bypass write " + desired + " WRITE FAILED readback=" + r.readback
                        + (r.error != null ? " err=" + r.error : "");
        Pc.log(this, msg);
        setLast(msg);
    }

    private void updateWindowLabels() {
        int s = Pc.startMin(this), e = Pc.endMin(this);
        winOnText.setText("ON " + Pc.fmtTime(s));
        winOffText.setText("OFF " + Pc.fmtTime(e));
        int len = (e - s + 1440) % 1440;
        if (len == 0) len = 1440;
        int h = len / 60, mn = len % 60;
        winLenText.setText(mn == 0 ? (h + "h window") : (h + "h " + mn + "m window"));
    }

    private void updateHoldLabel() {
        int v = Pc.holdPct(this);
        holdText.setText(v + "%");
        if (thresholdLines != null) {
            thresholdLines.setText("Below " + v + "% — phone charges"
                    + "\nAbove " + v + "% — phone doesn't charge");
        }
    }

    // ---------- setup check (v10) ----------

    /** Samsung's own charge limiter state ("protect_battery"), best effort:
     *  Samsung exposes it as a system/global setting on many models. Null
     *  when unreadable - the checklist then just says it couldn't read it. */
    private Integer readSamsungBatteryProtection() {
        try {
            int v = Settings.System.getInt(getContentResolver(), "protect_battery", -999);
            if (v != -999) return v;
        } catch (Exception ignored) {}
        try {
            int v = Settings.Global.getInt(getContentResolver(), "protect_battery", -999);
            if (v != -999) return v;
        } catch (Exception ignored) {}
        return null;
    }

    private void recordSetupWrite(boolean works) {
        Pc.cfg(this).edit()
                .putString("setupWriteState", works ? "pass" : "fail")
                .putString("setupWriteExec", Pc.executorName(this))
                .apply();
    }

    private void toggleExecutor() {
        boolean toShizuku = !Pc.useShizuku(this);
        Pc.setUseShizuku(this, toShizuku);
        Pc.log(this, "Write path set to " + (toShizuku ? "Shizuku" : "Direct"));
        setLast("Write path: " + (toShizuku ? "Shizuku" : "Direct"));
        refreshSetup();
        startPassService(true);
        refreshLog();
    }

    /** Recompute the setup checklist + verdict from live state. The write
     *  test result itself is stored (by testWrite via recordSetupWrite) -
     *  it is never run automatically, because it flips the setting twice. */
    private void refreshSetup() {
        if (setupVerdictText == null) return;
        StringBuilder sb = new StringBuilder();
        boolean warn = false;
        Integer cur = Pc.read(this);
        sb.append(cur != null
                ? "✓ pass_through switch: present (now " + cur + ")"
                : "– pass_through: not set yet - Run check will try to create it").append('\n');
        if (Pc.useShizuku(this)) {
            sb.append("– Modify system settings: not needed in Shizuku mode").append('\n');
            if (!ShizukuExec.binderAlive()) {
                sb.append("✗ Shizuku: not running - open the Shizuku app and start it").append('\n');
                warn = true;
            } else if (!ShizukuExec.granted()) {
                sb.append("! Shizuku: running, permission not granted - tap 'Shizuku permission'").append('\n');
                warn = true;
            } else {
                sb.append("✓ Shizuku: running, permission granted").append('\n');
            }
        } else {
            boolean cw = Settings.System.canWrite(this);
            sb.append(cw
                    ? "✓ Modify system settings: granted"
                    : "! Modify system settings: not granted - if the write test fails, grant it with the button in the Log card").append('\n');
        }
        boolean bgOk = true;
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            bgOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        sb.append(bgOk
                ? "✓ Background run: allowed"
                : "! Background run: not exempt - tap 'Allow background run' in the Log card").append('\n');
        if (!bgOk) warn = true;
        Intent batt = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = batt != null ? batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) : 0;
        sb.append(plugged == BatteryManager.BATTERY_PLUGGED_AC
                ? "✓ Wall charger connected (real bypass needs a 25W+ PPS charger)"
                : plugged == 0 ? "– Not plugged in"
                : "– Plugged via USB/wireless - bypass needs a 25W+ PPS wall charger").append('\n');
        Integer bp = readSamsungBatteryProtection();
        if (bp == null) {
            sb.append("– Samsung Battery Protection: couldn't read its state").append('\n');
        } else if (bp == 1) {
            sb.append("! Samsung Battery Protection is ON - it limits charging too; two limiters can conflict, consider turning it off").append('\n');
            warn = true;
        } else {
            sb.append("✓ Samsung Battery Protection: off").append('\n');
        }
        String ws = Pc.cfg(this).getString("setupWriteState", null);
        String wexec = Pc.cfg(this).getString("setupWriteExec", null);
        boolean passCurrent = "pass".equals(ws) && Pc.executorName(this).equals(wexec);
        if ("pass".equals(ws) && !passCurrent) {
            sb.append("! Write test: passed with " + wexec + " - run it again for the " + Pc.executorName(this) + " path").append('\n');
            warn = true;
        } else if ("pass".equals(ws)) {
            sb.append("✓ Write test: passed").append('\n');
        } else if ("fail".equals(ws)) {
            sb.append("✗ Write test: FAILED - the setting could not be changed").append('\n');
        } else {
            sb.append("– Write test: not run yet").append('\n');
        }
        String verdict;
        if (passCurrent) {
            verdict = "READY - this phone can be controlled (" + Pc.executorName(this) + " write path).";
        } else if ("fail".equals(ws) && Pc.executorName(this).equals(wexec)) {
            verdict = Pc.useShizuku(this)
                    ? "NOT READY - writes failed even via Shizuku. This phone may not support bypass control."
                    : "NOT READY - the direct write failed. If Shizuku is installed and running, tap 'Use Shizuku' and run the check again.";
        } else {
            verdict = "NOT CHECKED - tap Run check to test this phone.";
        }
        setupVerdictText.setText(verdict);
        setupLinesText.setText(passCurrent && !warn ? "All checks passed." : sb.toString().trim());
        if (setupExecBtn != null) setupExecBtn.setText(Pc.useShizuku(this) ? "Use Direct" : "Use Shizuku");
    }

    // ---------- actions ----------

    private void startPassService() { startPassService(false); }

    /** @param fresh true when this poke follows a CONFIG CHANGE (slider drag
     *  end, mode switch): the service then re-evaluates strictly against the
     *  current settings with no hold band (see PassService.EXTRA_FRESH). */
    private void startPassService(boolean fresh) {
        Intent svc = new Intent(this, PassService.class);
        if (fresh) svc.putExtra(PassService.EXTRA_FRESH, true);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
    }

    private void testWrite() {
        // Change-based and exception-aware: v1 reported "put 1 OK" when pass_through
        // was already 1 (set earlier via ADB) even though the write itself threw.
        // A pass now requires an OBSERVED change (start -> opposite -> start) with
        // no exception in either write.
        Integer start = Pc.read(this);
        if (start == null) {
            // No baseline value yet: write 0 first, then proceed from its readback.
            Pc.WriteResult base = Pc.write(this, 0);
            start = Pc.read(this);
            if (start == null) {
                String bmsg = "TEST WRITE: no baseline (pass_through unset; write 0 readback=" + base.readback + ")"
                        + ((base.error != null) ? " err=" + base.error : "")
                        + " => WRITE PATH BROKEN (grant Modify system settings / ADB permission)";
                Pc.log(this, bmsg);
                setLast(bmsg);
                recordSetupWrite(false);
                refreshStatus(null);
                refreshLog();
                refreshSetup();
                return;
            }
        }
        int s = start;
        int flip = (s == 1) ? 0 : 1;
        Pc.WriteResult rFlip = Pc.write(this, flip);
        boolean flipOk = rFlip.error == null && rFlip.readback != null
                && rFlip.readback == flip && rFlip.readback != s;
        Pc.WriteResult rBack = Pc.write(this, s);
        boolean backOk = rBack.error == null && rBack.readback != null && rBack.readback == s;
        boolean works = flipOk && backOk;
        String msg = "TEST WRITE: start=" + s
                + " flip->" + flip + " readback=" + rFlip.readback + (flipOk ? " CHANGED OK" : " FAIL")
                + ((rFlip.error != null) ? " errFlip=" + rFlip.error : "")
                + "; restore->" + s + " readback=" + rBack.readback + (backOk ? " OK" : " FAIL")
                + ((rBack.error != null) ? " errBack=" + rBack.error : "")
                + (works ? " => WRITE PATH WORKS" : " => WRITE PATH BROKEN (grant Modify system settings / ADB permission)");
        Pc.log(this, msg);
        setLast(msg);
        recordSetupWrite(works);
        refreshStatus(null);
        refreshLog();
        refreshSetup();
    }

    private void setLast(String s) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        Pc.cfg(this).edit().putString("lastAction", ts + "  " + s).apply();
    }

    // ---------- status ----------

    /** The status card IS the indicator: BLUE card = bypass ON, BLACK card =
     *  bypass OFF. The only text on it is the battery % and BYPASS ON/OFF. */
    private void updateStatusCard(Integer cur) {
        boolean on = cur != null && cur == 1;
        if (statusBg != null) statusBg.setColor(on ? STATUS_BLUE : STATUS_BLACK);
        bypassStateText.setText(cur == null ? "BYPASS ?" : (on ? "BYPASS ON" : "BYPASS OFF"));
    }

    private void refreshStatus(Intent batt) {
        if (batt == null) batt = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int pct = -1;
        if (batt != null) {
            int lvl = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            pct = (lvl >= 0 && scale > 0) ? Math.round(lvl * 100f / scale) : -1;
        }
        battPctText.setText(pct >= 0 ? (pct + "%") : "--%");
        updateStatusCard(Pc.read(this));
    }

    private void refreshLog() {
        if (logText == null) return;
        String full = Pc.getLog(this);
        String[] lines = full.split("\n");
        int from = Math.max(0, lines.length - 15);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (i > from) sb.append('\n');
            sb.append(lines[i]);
        }
        logText.setText(sb.toString());
    }
}

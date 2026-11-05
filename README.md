# Bypass Control

<img src="assets/icon.png" width="96" alt="Bypass Control icon">

**Your Samsung phone already knows how to run directly off the charger. Samsung just hid the switch inside a gaming menu. Bypass Control gives it back — on your schedule, at your battery level.**

Free and open source. No root, ever. No ads, no accounts, no tracking — an ~80 KB app that does exactly one job.

**About "no Shizuku required" — read this first, because it needs one honest paragraph.** This app writes a system setting, and Android doesn't hand that ability to just any app. It has **two write paths**. The *Direct* path works on many phones (including our test phone) with no helper at all — but whether it works is decided by your phone's firmware, and the only way to know is to run the app's built-in write test. If your firmware rejects direct writes, the fallback is *Shizuku mode*, which writes through the Shizuku app instead. Either way there is a one-time setup (an ADB install command, and a couple of grants) — the full, finalized **[Setup Guide](#setup-guide)** below is the exact procedure, step by step. We'd rather tell you this up front than print "no Shizuku required" on the tin and let you find out from an error message.

## Why your battery wants this

A lithium battery ages fastest in exactly the situation most of us put it in every day: **plugged in for hours, sitting at 100%.** High voltage, heat, and an endless trickle of micro charge cycles quietly eat capacity while the phone does nothing.

The fix is bypass (pass-through) charging: once the battery reaches a level you choose, the phone stops charging it and runs off the wall charger instead. The battery just rests — no heat, no micro-cycles, no sitting at full voltage.

Most modern Galaxy phones can already do this in hardware. What they don't give you is a usable switch:

- Samsung's own **Pause USB PD charging** is buried in Game Booster and only works while a game is in the foreground.
- Samsung's **Battery Protection** is a fixed-preset limiter, not a bypass you can schedule.
- The third-party tools that unlock it are heavy tweaker suites with paid tiers, or scripts that need Shizuku + Termux and have to be re-armed after every reboot.

Bypass Control is the missing middle: **set a time window and a percentage, and forget it exists.**

## What it does

- **One window, one percentage.** Inside your chosen hours, the phone charges up to your level — then bypass switches on and it runs off the charger. Below the level, it charges. That's the whole rule, stated on screen in plain words: *"Below 88% — phone charges. Above 88% — phone doesn't charge."*
- **A hard cap you control.** Never charge above a level you set (80–95%, default 95%) — in every mode, even Off. Your ceiling, not a preset.
- **Auto | Off, nothing else.** One selector. Off is a real off: bypass is forced off and kept off.
- **Control it from the notification.** The persistent notification shows the live state and carries a Turn Off / Turn On button — no need to open the app.
- **It watches itself.** If the battery rises or drains while bypass should be holding (a weak charger, firmware dropping bypass with the screen off), you get a warning notification, not a silent surprise.
- **A setup check that speaks plainly.** First run answers the only question that matters — *"can this phone do this, and by which path?"* — with a checklist and a READY / NOT READY verdict, instead of a mysterious failure weeks later.

## Who it's for

- Desk workers whose phone lives on a charger all day
- Anyone in a hot climate, where charging heat is brutal on batteries
- Phones that sit plugged in as hotspots, monitors, dash/nav units or second screens
- Battery-health nerds who plan to keep their phone for years

## How it compares

| | Bypass Control | Samsung Pause USB PD | Samsung Battery Protection | Tweaker suites / scripts |
|---|---|---|---|---|
| Works in any app, screen off or on | ✅ (firmware permitting) | ❌ game in foreground only | ✅ | varies |
| Your own percentage | ✅ any level | ❌ | presets only | ✅ |
| Daily schedule | ✅ | ❌ | ❌ | some |
| Independent hard cap | ✅ 80–95% | ❌ | it's the whole feature | some |
| Root | never | — | — | sometimes |
| Helper apps | none on the Direct path; Shizuku only if your firmware rejects direct writes | — | — | often Shizuku/Termux, re-armed per reboot |
| Price | free, open source | built in | built in | freemium / paid tiers |
| Size / scope | ~80 KB, one job | — | — | hundreds of unrelated tweaks |

## Compatibility — the honest box

- **Developed and tested on a Galaxy Z Fold 8 (SM-F9710), Direct path.** Other Samsung models that expose the same `pass_through` switch (Galaxy S22 and newer flagships are the likely candidates) should work by one path or the other — the setup check exists precisely because support varies by model and firmware, and it will tell you READY or NOT READY on your device.
- **You need a real charger:** true bypass needs a **25 W+ USB PD charger with PPS**, fast charging enabled in Samsung's charging settings, and battery above 20% — that's Samsung's hardware requirement, not ours. A PC USB port won't do it.
- **One-time setup involves a computer (or Shizuku).** The app deliberately targets an old Android API level — that's what makes the Direct write path possible at all (see *Why targetSdk 22?* below) — and Android 14+ refuses to tap-install such apps. The Setup Guide walks through it; it takes a few minutes, once.

# Setup Guide

This is the complete procedure, in order. Total time: about 5 minutes, plus the Shizuku detour *only if* your phone needs it.

## Before you start

- A **25 W+ USB PD PPS wall charger** (Samsung's "Super Fast Charging" class) and a decent cable.
- Your phone charged above **20%**, with **Fast charging** turned on (Settings → Battery → Charging settings).
- A computer with **ADB** (Android platform tools) for the install step — or, if you already run Shizuku, an installer app that works through it.
- Optional but recommended: turn **off** Samsung's Battery Protection limiter while you evaluate this app — two limiters acting at once make each other's behaviour hard to read. The app's setup check will warn you if it finds it on.

## Step 1 — Install the APK (the old-SDK step)

1. Download `BypassControl-v10.apk` from [Releases](https://github.com/JDD700/bypass-control/releases).
2. On the phone: enable **Developer options** (tap Build number 7 times) and **USB debugging**, connect the phone, and accept the debugging prompt on the phone.
3. On the computer, in the folder with the APK:

   ```
   adb install -r --bypass-low-target-sdk-block BypassControl-v10.apk
   ```

   - On Windows PowerShell, that's `.\adb install -r --bypass-low-target-sdk-block "C:\path\to\BypassControl-v10.apk"`.
   - What the flag means: the app intentionally targets API level 22 (an old Android version) — that's what allows the Direct write path (details below). Since Android 14, the installer blocks apps targeting very old versions as a safety default; this flag is you, the device owner, overriding that default for this one install. `-r` lets the same command update the app later.
   - Expected result: `Success`.
4. First launch shows a one-time **"This app was built for an older version of Android"** dialog. Tap **OK**. That dialog is the visible price of the write path — every app using this technique shows it.

## Step 2 — Run the setup check

1. Open **Bypass Control**. The second card is **SETUP CHECK**.
2. Tap **Run check**. This runs the write test: it flips the bypass setting to the opposite value, reads it back, flips it back, and reads it back again. It passes only if both changes are *observed* — not just attempted. (It runs only when you tap it, never automatically, because it touches the setting.)
3. Read the verdict:
   - **READY (Direct write path)** — you're done with setup plumbing; go to Step 4.
   - **NOT READY — the direct write failed** — your firmware rejects direct writes. Do Step 3's remaining grants once (they're cheap), run the check again; if it still fails, go to **Step 3b: the Shizuku fallback**.
   - The checklist also shows the state of each grant and warns about a conflicting Battery Protection limiter, a missing background-run exemption, and charger type. Fix what's flagged; the card collapses to "All checks passed" when nothing is outstanding.

## Step 3 — The grants

These make the app reliable rather than merely functional:

1. **Modify system settings** — in the app's Log card (bottom), tap **Modify system settings** and allow it on the Android page that opens.
2. **WRITE_SECURE_SETTINGS** — one ADB command (a blank/silent result means success):

   ```
   adb shell pm grant com.pooh.passcontrol android.permission.WRITE_SECURE_SETTINGS
   ```

3. **Background run** — in the Log card, tap **Allow background run** and exempt the app. Samsung's battery management is aggressive; without this the service can be put to sleep. (Also leave the app out of the "deep sleeping apps" list if your phone has one.)

Then run the check once more — you're aiming for READY.

## Step 3b — The Shizuku fallback (only if the Direct path failed)

If your firmware won't accept direct writes, let Shizuku do them: it runs a small server with shell identity, and the system always accepts writes from the shell. Bypass Control already contains everything needed on its side.

1. Install **Shizuku** (from the Play Store or its GitHub) and open it.
2. Start the Shizuku server, using either of its offered methods:
   - **Wireless debugging** (no computer): follow Shizuku's in-app pairing steps; or
   - **ADB** (computer already connected from Step 1): run the command Shizuku shows on its home screen, of the form:
     ```
     adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
     ```
   Shizuku's home screen should now say it's running.
3. Back in **Bypass Control → SETUP CHECK**: tap **Shizuku permission** and accept the Shizuku dialog asking whether Bypass Control may use it.
4. Tap **Use Shizuku** to switch the write path, then **Run check** again. The test now writes through Shizuku and is verified by readback exactly like the Direct path.
5. Aiming for: **READY (Shizuku write path).**

Two things to know about Shizuku mode:

- **After every phone reboot, Shizuku must be started again** (its step 2) — that's how Shizuku works for every app that uses it. Until then, Bypass Control can't write, and its warnings will say so. The Direct path has no such requirement, which is why it's the default.
- Shizuku mode is the newer of the two paths and has had less device testing. If the setup check passes in Shizuku mode on your phone, we'd genuinely like to hear your model in an issue — it helps the next person.

## Step 4 — Set your rules

- **Mode:** leave it on **Auto**.
- **Bypass window:** drag the two thumbs on the 24-hour slider to the hours you want bypass available (e.g. ON 08:00 / OFF 23:00). Overnight windows work — ON 22:00, OFF 07:00 wraps past midnight. Outside the window the phone always charges normally.
- **Battery:** one slider, one number. Below it the phone charges; at or above it, bypass holds it there. A good starting point is 80–90%.
- **Hard cap:** the "Never charge above" toggle (default ON) with its level stepper (80–95%, default 95%). This outranks everything — even Off mode — because it's a ceiling, not a schedule.

## Step 5 — Confirm it's actually working

1. Plug into the PPS wall charger, inside your window, with the battery near or above your set percentage.
2. The top card turns **blue** and reads **BYPASS ON** — black means off; those are the only two things that card ever shows.
3. In the Log card you should see a line like `Set pass_through=1 (... batt 88%) VERIFIED readback=1`.
4. Leave it for 10–15 minutes of normal use: the percentage should sit still (±1% is gauge noise). That's bypass doing its job.

## Daily use

- **Blue card = running off the charger. Black = charging or off.** That's the entire status language.
- The **persistent notification** shows the state and has a **Turn Off / Turn On (Auto)** button for quick control without opening the app.
- **Unplugging always resets bypass off** — the phone can never be left in a weird state by a forgotten setting.
- **Warnings are notifications, not log lines:** if the percentage rises while bypass should be holding (firmware dropped it, e.g. screen off), or drains while it's on (charger can't keep up), you get a high-priority alert saying which.
- Below **25%**, bypass will never switch on — a hard safety floor.

## Troubleshooting

| Symptom | Likely cause & fix |
|---|---|
| Write test fails, Direct path | Do the Step 3 grants, re-run; still failing → Step 3b (Shizuku). |
| Write test fails even in Shizuku mode | Shizuku not started, or permission not granted — check the SETUP CHECK lines; they name which. If both are fine, your model may not expose `pass_through` at all: please file an issue with your model + One UI version. |
| Bypass ON, but % keeps rising | Not really bypassing: charger isn't PPS / under 25 W, or Fast charging is off in Samsung settings. The app will also post a warning. |
| Bypass ON, but % drains | The charger can't cover the phone's current draw (weak brick, bad cable, heavy load, heat throttling). Try the original Samsung-class PPS charger. |
| Works with screen on, drops off with screen off | Some firmware drops bypass on blank screen; the app's 5-minute plugged-in watchdog re-asserts it and logs `Re-asserted bypass (was reset - possibly screen-off)`. If that line repeats constantly on your model, tell us in an issue. |
| App seems dead after a while | Samsung put it to sleep: re-check the background-run exemption (Step 3.3) and remove it from deep-sleeping apps. In Shizuku mode, also check Shizuku is still running (reboot stops it). |
| Percentage "stuck" just below your number | Normal: it charges to your level, holds, and only tops up when it drifts below. The number is a threshold, not a target it hovers above. |

## Uninstall / put everything back

1. In the app, switch the mode to **Off** (this forces bypass off, verified).
2. Uninstall normally. If you want belt-and-braces, before uninstalling run:
   ```
   adb shell settings put system pass_through 0
   ```
3. In Shizuku mode, you can also revoke Bypass Control's permission inside the Shizuku app at any time.

## Build from source

Needs JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0) via `JAVA_HOME` / `ANDROID_SDK_ROOT`, or dropped under `tools/`:

```
./build-manual.sh
```

The script fetches the Shizuku libraries from Maven Central on first run and produces a signed APK (with a locally generated key if you don't provide `debug.keystore` — fine for your own builds; official releases use the maintainer's private key, which is not in this repo).

## How it works

Samsung devices with bypass support expose a system setting, `pass_through` (1 = run off the charger, 0 = charge normally). Bypass Control automates that one setting:

- **Event-driven, not polling.** It reacts to battery-change events, checks itself every 5 minutes only while plugged in, holds a wakelock only for the milliseconds a write takes, and uses no network or location. Its own battery footprint is effectively zero.
- **Every write is verified** by reading the setting back, and logged. A failed write raises a notification.
- **Safety rails:** bypass is never switched on below 25%, unplugging always resets it off, and the hard cap outranks every other rule.
- **Why targetSdk 22?** Android's SettingsProvider rejects writes to vendor system keys from apps targeting modern API levels — even with every permission granted (we verified this the hard way: same code, same grants, target 34 → every write thrown back; target 22 → works). Apps targeting API ≤ 22 fall under a legacy path where the write is permitted; several long-standing Samsung tweakers rely on the same fact. The trade-offs are the ADB install step and no Play Store — this app is distributed here, on GitHub. And it's exactly why the Shizuku fallback exists: on firmware where even the legacy path is closed, the shell identity Shizuku provides is the other door.

## Disclaimer

Use at your own risk. Bypass charging is a vendor feature of your phone; this app only automates its switch. Battery outcomes depend on your charger, device and firmware. Not affiliated with Samsung. "Galaxy" and "Samsung" are trademarks of Samsung Electronics.

## License

MIT — see [LICENSE](LICENSE).

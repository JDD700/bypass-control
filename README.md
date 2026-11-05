# Bypass Control

<img src="assets/icon.png" width="96" alt="Bypass Control icon">

**Your Samsung phone already knows how to run directly off the charger. Samsung just hid the switch inside a gaming menu. Bypass Control gives it back — on your schedule, at your battery level.**

Free and open source. No root. No Shizuku required. No ads, no accounts, no tracking — an ~80 KB app that does exactly one job.

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
- **A setup check that speaks plainly.** First run answers the only question that matters — *"can this phone do this?"* — with a checklist and a READY / NOT READY verdict, instead of a mysterious failure weeks later.
- **Two write paths.** Direct writing works on most supported phones with zero helpers and survives reboots. If your firmware rejects it, an optional **Shizuku mode** writes with shell identity instead. (Shizuku mode is new and not yet widely device-tested — the setup check will tell you honestly whether it works on yours.)

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
| Root / Shizuku / Termux | **not required** | — | — | often required |
| Price | free, open source | built in | built in | freemium / paid tiers |
| Size / scope | ~80 KB, one job | — | — | hundreds of unrelated tweaks |

## Compatibility — the honest box

- **Developed and tested on a Galaxy Z Fold 8 (SM-F9710).** Other Samsung models that expose the same `pass_through` switch (Galaxy S22 and newer flagships are the likely candidates) should work — the setup check exists precisely because model/firmware support varies, and it will tell you READY or NOT READY on your device.
- **You need a real charger:** true bypass needs a **25 W+ USB PD charger with PPS**, fast charging enabled, and battery above 20% — that's Samsung's hardware requirement, not ours. A PC USB port won't do it.
- **Installing on Android 14+ needs one ADB command** (below). This is because the app intentionally targets an old Android API level — see *Why targetSdk 22?* — and Android blocks tap-installing such apps. It's a one-time, 30-second step.

## Install

1. Download `BypassControl-v10.apk` from [Releases](https://github.com/JDD700/bypass-control/releases).
2. Install it with ADB (USB debugging on, phone connected):

   ```
   adb install -r --bypass-low-target-sdk-block BypassControl-v10.apk
   ```

   (Replace with the actual file path.) Tap **OK** on the one-time "built for an older version of Android" dialog.
3. Open the app, tap **Run check** in the Setup Check card, and grant what's asked.
4. Set your window and percentage. Done — the blue card means bypass is on, black means it's off.

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
- **Why targetSdk 22?** Android's SettingsProvider rejects writes to vendor system keys from apps targeting modern API levels — even with every permission granted (we verified this the hard way). Apps targeting API ≤ 22 use a legacy path where the write is permitted, which is also how several long-standing Samsung tweakers work. The trade-off is the ADB install step above and no Play Store — this app is distributed here, on GitHub.

## Disclaimer

Use at your own risk. Bypass charging is a vendor feature of your phone; this app only automates its switch. Battery outcomes depend on your charger, device and firmware. Not affiliated with Samsung. "Galaxy" and "Samsung" are trademarks of Samsung Electronics.

## License

MIT — see [LICENSE](LICENSE).

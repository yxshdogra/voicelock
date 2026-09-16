# voicelock — Milestone 0 spike

A throwaway harness to answer ONE question before anything else is built: can
Porcupine keyword spotting detect a user's custom phrase reliably, always-on,
screen off, in a pocket, without eating the battery?

Done when, on a real device, a custom phrase said 20 times a day scores
**≥90% true-positive, <1 false-positive per hour, <5% battery per hour**, and the
result holds for an Indian-English speaker.

## Setup

1. Free Picovoice account at https://console.picovoice.ai — copy the AccessKey.
2. `local.properties` (gitignored):
   ```
   sdk.dir=/Users/<you>/Library/Android/sdk
   PICOVOICE_ACCESS_KEY=<paste>
   ```
3. `./gradlew :app:installDebug` with a device on `adb devices`.

## M0a — pipeline check (built-in keyword)

With only an AccessKey, the service listens for the built-in word **"porcupine"**.
Start listening, say it, watch *Detections* tick. This proves mic → service →
Porcupine → log end to end.

## M0b — the real test (custom phrase)

In the Picovoice console, train a wake word from your chosen phrase (Android,
English). Download the `.ppn` and save it as
`app/src/main/assets/keywords/custom.ppn` (gitignored). Rebuild. The service
prefers it automatically.

## Scoring

The instrument is `spike-log.jsonl` in the app's files dir; the UI only mirrors
it. Pull it off the device:

```
adb shell run-as com.houseoftech.voicelock cat files/spike-log.jsonl > spike-log.jsonl
```

Rows are `detect`, `false_positive` (you pressed the button), `battery`
(every 5 min, with `charging`), and `service` start/stop/error. Compute:

- true-positive rate = detections you caused / phrases you said
- false positives per hour = `false_positive` rows / hours the service ran
- battery per hour = drop in `pct` between samples while `charging=false`

## Milestones 1-3 — built, awaiting device checks

While M0 waits on the AccessKey and a phone, the engine-independent mechanics
are in and unit-tested (`./gradlew testDebugUnitTest`, 9 tests). Everything is
driven by a `Trigger`, so each mechanic is exercised from the debug panel with
no audio at all.

**The emulator cannot be started from the agent's shell** (Hypervisor/JIT is
blocked in that context). Start `pocketpdf_16k` from Android Studio's Device
Manager, wait for `adb devices` to show `device`, then:

```
./gradlew :app:installDebug
```

Then in the app, in order:

| Step | Do | Pass when |
|---|---|---|
| grants | Grant mic+notifications; **Allow overlay**; **Enable Device Admin** | both status lines flip to granted/active |
| M1 | Start listening; clap twice, ~0.5 s apart, near the Mac's mic | a `clap` row and a `double_clap` trigger row appear in the log; a single bang does not |
| M2 (Mode A, factory AVD) | press **Lock** | screen turns off; on wake, the dark VoiceLock overlay covers the launcher saying it *is* the lock screen; **Unlock** removes it |
| M2 (Mode B) | set a PIN in emulator Settings, press **Lock** | screen off; on wake the **PIN prompt comes first**; the overlay appears after, saying the PIN still protects the phone |
| M3 | press **Find phone** (or clap twice) | alarm volume maxes, tone loops, `logcat` shows vibration, log shows `no flash unit; torch strobe skipped`; any tap / **Found it** / 60 s stops it and the volume is restored |

Real-device re-checks still owed after this: the overlay and Device Admin under
Android's Restricted Settings gate for sideloaded APKs, and OEM battery killers
(Xiaomi/Vivo/Oppo). Those are Milestone 4.

This project has no backend, no login, and no branding on purpose. Those are
later milestones; nothing here should be reused without deliberate intent.

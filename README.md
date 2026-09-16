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

## Milestones 1-3 — built and device-checked (2026-09-16, Samsung S21 FE)

While M0 waits on the AccessKey, the engine-independent mechanics are in,
unit-tested (`./gradlew testDebugUnitTest`, 9 tests) **and verified on real
hardware** — Samsung SM-G990B2, Android 16 / SDK 36, Mode A (no lock screen).
Everything is driven by a `Trigger`, so each mechanic is exercised from the
debug panel with no audio at all.

Device result: **M1–M3 mechanics all PASS.** Foreground service survives
screen-off (a battery sample was logged while the phone dozed); `lockNow()` +
the `NO_KEYGUARD` overlay work; find-phone rings through DND with torch strobe
and vibration and restores the alarm volume on stop, timeout, or clap. The clap
→ trigger → find-phone chain fires from a real double-clap. Two tuning defects
were found and deferred to M4 (see below). Full log and screenshots were pulled
per step; captured clap dB-over-floor spanned 12.1–37.1 (real double-claps
cluster 12–17).

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

### M4 findings from the device run

- **Single clap can false-trigger a double-clap (reproduced twice). STILL OPEN —
  needs on-device tuning data.** One physical clap crosses the 12 dB threshold
  twice inside the 300–800 ms window (acoustic tail / room reverb); the second
  spike sits right at ~12.4 dB. This is not fixable blind: the reverb slap-back
  is acoustically a genuine soft double-clap to the current features, and every
  cheap guard (raise `spikeThresholdDb`, narrow `doubleClapMaxMs`, require a
  quiet gap) also rejects some *real* fast/loud double-claps. The right fix
  (energy/spectral matching of the pair) needs measured inter-clap profiles from
  the device tuning session, alongside the M0 accuracy run.
- **The find-phone alarm self-triggers the clap detector. FIXED.** While ringing,
  the mic heard its own alarm and emitted extra `double_clap` rows. The mic loop
  now skips both detectors while `dispatcher.isFindPhoneActive`
  (`ListenService.audioLoop`), so nothing self-triggers during playback; the flag
  is `@Volatile` and stays true through the 60 s timeout self-stop, not just user
  stops.

Also owed at M4: overlay + Device Admin under Android's Restricted Settings gate
for sideloaded APKs, and OEM battery killers (Xiaomi/Vivo/Oppo). Platform note:
on this Samsung, `dpm remove-active-admin` silently no-ops (rc=255) — admin must
be deactivated via Settings before the package will uninstall.

This project has no backend, no login, and no branding on purpose. Those are
later milestones; nothing here should be reused without deliberate intent.

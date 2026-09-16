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

This project has no backend, no login, and no lock screen on purpose. Those are
later milestones; nothing here should be reused without deliberate intent.

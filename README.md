# voicelock

Always-on voice phrase detection that locks the phone, plus a double-clap
"find my phone" alarm. The open question Milestone 0 exists to answer: can the
wake engine detect a user's custom phrase reliably, always-on, screen off, in a
pocket, without eating the battery?

Done when, on a real device, a custom phrase said 20 times a day scores
**≥90% true-positive, <1 false-positive per hour, <5% battery per hour**, and the
result holds for an Indian-English speaker.

## Wake engine: Vosk (Apache-2.0)

The engine sits behind the `KeywordEngine` interface, so swapping it is a
one-line factory change in `ListenService.startListening` and nothing downstream
(`Trigger.KeywordDetected`, Milestones 1-3) is affected.

**Vosk is the engine**, using `vosk-model-small-en-in-0.4` — Apache-2.0, 36 MB,
on-device, and **Indian-English**, which the M0 bar explicitly requires. It runs
as a *restricted-grammar phrase spotter*: the grammar is
`["<phrase>", "[unk]"]`, which collapses the language model to a two-way
decision. That is both cheaper than full transcription and more accurate for a
fixed phrase, and it means **there is no model to train and no per-account key —
the wake phrase is just a string** (`VoskEngine.DEFAULT_PHRASE`).

Two engines were rejected, both for licensing:
- **Picovoice Porcupine** — best quality, but the commercial licence is ~$6k/yr.
  Still present behind the seam as a paid fallback (`PorcupineEngine`).
- **openWakeWord** — Apache-2.0 *code*, but every distributed model binary
  (including the mel/embedding backbone) is **CC BY-NC-SA 4.0, non-commercial**,
  and its precomputed negative-features dataset is NC-SA too. Not shippable in a
  paid app without a clean-room rebuild and legal review.

## Setup

1. Download `vosk-model-small-en-in-0.4` from https://alphacephei.com/vosk/models
   and unzip it to `app/src/main/assets/model-en-in/` (gitignored — 36 MB; the
   dir should contain `am/`, `conf/`, `graph/`, `ivector/`).
2. `local.properties` (gitignored):
   ```
   sdk.dir=/Users/<you>/Library/Android/sdk
   ```
3. `./gradlew :app:installDebug` with a device on `adb devices`.

Without the model the service still runs the mic loop and the clap detector —
which is all Milestones 1-3 need — and logs that it is doing so.

## M0a — pipeline check

Start listening and say the phrase (`"unlock my phone"` by default). *Detections*
ticks and the log gets a `detect` row carrying the recognised **text**, proving
mic → service → Vosk → log end to end.

## M0b — the real test (your phrase)

Change `VoskEngine.DEFAULT_PHRASE` to the phrase you want and rebuild. Every word
must exist in the model vocabulary. No training, no console, no key.

## Scoring

The instrument is `spike-log.jsonl` in the app's files dir; the UI only
mirrors it. Rows are `attempt` (you pressed "About to say it", before
speaking — the denominator for true-positive rate), `detect` (with the
recognised `text`), `heard` (an utterance Vosk recognised that did NOT
match — the near-miss denominator), `false_positive` (you pressed the
button), `clap`, `envelope` (onset energy curves, for clap tuning),
`battery` (every 5 min, with `charging`), and `service` start/stop/error.

Protocol:

1. Start listening.
2. For each of ~20 utterances: tap **About to say it**, then say the phrase.
3. Tap **That one was a FALSE positive** whenever it fires unprompted.
4. Leave the phone unplugged for at least 2 hours with the service running.
5. Pull the log:
   ```
   adb shell run-as com.houseoftech.voicelock cat files/spike-log.jsonl > spike-log.jsonl
   ```
   or `scripts/pull-log.sh [output-path]`.
6. Score it:
   ```
   python3 scripts/score.py spike-log.jsonl
   ```

`score.py` pairs each `attempt` with the next `detect` within a window
(default 6 s) to compute true-positive rate, counts unpaired `detect` rows as
false positives, sums `service` start/stop sessions for uptime, and sums
`battery` drops between consecutive samples with `charging=false`. It prints
a report against the bar (**>=90% true-positive, <1 false-positive/hour,
<5% battery/hour**) and exits `0` on overall PASS, `1` on FAIL, `2` if any
metric can't be computed (insufficient data).

## Milestones 1-3 — built and device-checked (2026-09-16, Samsung S21 FE)

The engine-independent mechanics are in, unit-tested
(`./gradlew testDebugUnitTest`, 25 tests) **and verified on real hardware** — Samsung SM-G990B2, Android 16 / SDK 36, Mode A (no lock screen).
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

### Clap-detector findings from the device runs — both FIXED

- **Speech fired the clap detector (alarm went off while talking). FIXED.**
  Spike dB alone could not separate them: talking produced onsets at 12.6-17.8 dB
  while genuine claps measured 12-17 dB in one session and 34-36 dB in another,
  because dB-over-floor drifts with room noise. Two measured rules fixed it:
  1. **A clap's peak IS its onset frame.** An impulse injects all its energy at
     once, so energy can only fall afterwards. Captured envelopes showed speech
     *rising* to 1.2x, 1.4x, even 3.2x after onset and then falling, which a
     decay-only test accepted. A candidate is now rejected if any frame in the
     window exceeds the onset, and must fall to <= half the onset within it.
  2. **An absolute `minSpikeRms` floor.** Measured: speech onsets land at
     rms 136-929, real claps at 3584-7321. Absolute RMS is used deliberately
     rather than more dB-over-floor, for the drift reason above.
  Verified: 58 recognised utterances over 6.5 h produced zero false claps, while
  a real clap still rings.

  Four `ClapDetectorTest` cases replay **envelope curves captured on the device**
  rather than synthetic impulses. This matters: an idealised one-frame `bang()`
  fixture is exactly what let two earlier fixes pass their tests and fail on the
  phone (a real clap spans several 32 ms frames once mic AGC and room reverb are
  involved).

- **The find-phone alarm self-triggered the clap detector. FIXED.** While ringing,
  the mic heard its own alarm and emitted extra `double_clap` rows. The mic loop
  now skips **the clap detector only** while `dispatcher.isFindPhoneActive`
  (`ListenService.audioLoop`); the flag is `@Volatile` and stays true through the
  60 s timeout self-stop, not just user stops. It deliberately does NOT gate the
  keyword engine — doing so meant one stray clap silently killed voice unlock for
  up to 60 s, and swallowed most of an M0 trial.

Also owed at M4: overlay + Device Admin under Android's Restricted Settings gate
for sideloaded APKs, and OEM battery killers (Xiaomi/Vivo/Oppo). Platform note:
on this Samsung, `dpm remove-active-admin` silently no-ops (rc=255) — admin must
be deactivated via Settings before the package will uninstall.

This project has no backend, no login, and no branding on purpose. Those are
later milestones; nothing here should be reused without deliberate intent.

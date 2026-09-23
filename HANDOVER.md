# VoiceLock - Handover

> Handed over by Yash Dogra to Ayush Jain on 2026-09-23. Master index: house-of-tech-admin/ESTATE_HANDOVER.md

## For Ayush (plain language): what this is, whether it is live, the 3-5 things only you can do next

VoiceLock is an unfinished technical prototype, not a shipped product. It is an Android app testing
whether a phone can reliably lock itself when you say a custom phrase out loud (and ring itself on a
double-clap), running entirely on the device with no server, no login, and no cloud account of any
kind. Nothing about it is live: it has never been signed, never uploaded to Play Console, and the
core research question ("does phrase detection actually work well enough, all day, with the screen
off?") has not been answered yet — only the tooling to measure it is finished.

Because there is no backend and no third-party account, there is almost nothing owner-gated here. The
only things only you can do:

1. **Decide whether this project continues.** It is the earliest-stage repo in the estate (pre-Milestone-0
   trial). If it is not a priority, say so explicitly so a new engineer does not spend time on it by default.
2. **Make sure the GitHub repo is actually transferred to your account (`ayush2491`) today**, not just
   listed for transfer. It currently lives solely under Yash's personal GitHub account with Yash as the only
   collaborator (verify with `gh api repos/ayush2491/voicelock` after the transfer step in the estate plan).
   If this step is missed, this code becomes unreachable the moment Yash's access ends.
3. **No files or keys need to be delivered to you for this repo.** There is no keystore, no `.env`, no
   Firebase config, and no API key in use — the local Picovoice-key placeholder in `local.properties` has
   never held a real value.
4. **If you decide to continue it**, the next engineer needs the House-of-Tech Android SDK setup and to
   download a public speech model (see Build and deploy below) — no accounts, no requests to you.

## What it is

An Android app (`com.houseoftech.voicelock`) that listens continuously for a spoken wake phrase and
locks the phone, plus a double-clap "find my phone" alarm. The repo is organized around **Milestone 0**:
proving the wake-phrase engine can hit ≥90% true-positive, <1 false-positive/hour, and <5% battery/hour
for an Indian-English speaker. Milestones 1-3 (the lock/find-phone mechanics) are built and were verified
on a real device on 2026-09-16/17; Milestone 0's own real-world trial has not been run yet. See
[README.md](./README.md) for the full engine rationale and test protocol.

## Where it runs

| component | where | identifier/URL |
|---|---|---|
| Source | GitHub | `https://github.com/ayush2491/voicelock` (private; being transferred to `ayush2491` as part of today's handover — unverified whether the transfer has completed) |
| App | Android device only, sideloaded via adb during development | `com.houseoftech.voicelock` |
| Backend / cloud | none | n/a — no `.firebaserc`, no Cloud Run/Functions, no hosting, no third-party accounts |
| Play Console | not created | n/a |

## Current state

- Latest commit: `38d15fc` "m0: make the accuracy/battery bar scoreable", on `main`, 2026-09-17. Single
  branch (`main`), working tree clean, fully in sync with `origin/main` — no unpushed or stashed work.
- `versionCode 1`, `versionName "0.0.1-spike"` (`app/build.gradle.kts`) — the app has never been signed
  or built for release; there is no keystore and no `signingConfig` block anywhere in the project.
- No open PRs, no other branches to evaluate or prune.
- Milestones 1-3 (lock mechanics, overlay, Device Admin, find-phone alarm, clap detector) are built and
  were device-verified on a Samsung SM-G990B2 (Android 16 / SDK 36) — see the README's "Milestones 1-3"
  section for the full pass/fail table and the two clap-detector bugs that were found and fixed.
- Milestone 0's actual scoring trial (20 utterances/day against the accuracy/battery bar) has **not**
  been run — only the harness to run and score it (`scripts/score.py`, `scripts/pull-log.sh`) is finished.
- Milestone 4 (Restricted Settings handling for sideloaded overlay/Device Admin permissions, OEM battery-killer
  testing on Xiaomi/Vivo/Oppo) has not been started.

## Build and deploy

There is no deploy — this only ever runs as a debug build on a physical device or emulator. From
[README.md](./README.md) `## Setup`:

1. Download `vosk-model-small-en-in-0.4` from https://alphacephei.com/vosk/models and unzip it to
   `app/src/main/assets/model-en-in/` (gitignored, ~36 MB, public Apache-2.0 download — not unique work).
2. Create `local.properties` (gitignored) with `sdk.dir=<your Android SDK path>`.
3. With a device on `adb devices`: `./gradlew :app:installDebug`.

Unit tests: `./gradlew testDebugUnitTest`.

Scoring an M0 trial run (README `## Scoring`):
```
adb shell run-as com.houseoftech.voicelock cat files/spike-log.jsonl > spike-log.jsonl
# or: scripts/pull-log.sh [output-path]
python3 scripts/score.py spike-log.jsonl
```

Traps:
- **Skip step 1 and the app still builds and runs** — it just silently never detects the wake phrase,
  because the mic loop and clap detector work with or without the model (README: "logs that it is doing
  so"). Easy to mistake for a real bug.
- **The emulator cannot be started from an automated shell** (Hypervisor/JIT is blocked in that
  context, per README). Start the AVD manually from Android Studio's Device Manager first, wait for
  `adb devices` to show `device`, then run the install command.
- On Samsung devices, `adb shell dpm remove-active-admin` silently no-ops — Device Admin must be
  deactivated from Settings before the app can be uninstalled (README, end of "Milestones 1-3").

## Secrets and config

| name | where it lives | notes |
|---|---|---|
| `local.properties` (`sdk.dir`, optional `PICOVOICE_ACCESS_KEY`) | Gitignored, machine-local only; currently only on Yash's laptop | Not part of the encrypted secrets archive for this handover — there is no live value to deliver. A new engineer creates their own with their own SDK path. |
| Picovoice `PICOVOICE_ACCESS_KEY` | Not set anywhere; no `console.picovoice.ai` account exists for this project | Only needed if the dormant `PorcupineEngine` fallback is ever activated (see README — commercial license, ~$6k/yr). |
| Vosk model (`app/src/main/assets/model-en-in/`) | Gitignored; downloaded locally per Setup step 1 | Public Apache-2.0 artifact, not a secret; re-downloadable by anyone. |
| Android signing keystore | Does not exist | No release build has ever been produced; this is new work if the project ships, not a handed-over asset. |

## Open items

1. **Confirm the GitHub repo transfer to `ayush2491` completed** (owner: Ayush). Verify with
   `gh api repos/ayush2491/voicelock` and check collaborators; this repo has no organization fallback,
   so a missed transfer means the code becomes unreachable once Yash's access ends.
2. **Decide whether VoiceLock continues past Milestone 0** (owner: Ayush). It is not referenced by any
   owner-console or production system, so this is a pure product-priority call with no urgency.
3. **Run the Milestone 0 real-world trial** and check the result against the bar (≥90% TP, <1 FP/hr,
   <5% battery/hr) using the protocol in [README.md](./README.md) (owner: next engineer).
4. **Start Milestone 4**: Restricted Settings handling for sideloaded overlay/Device Admin permissions,
   and OEM battery-killer testing on Xiaomi/Vivo/Oppo (owner: next engineer).
5. **If the Porcupine fallback is ever activated**, decide who owns the `console.picovoice.ai` account
   and the ~$6k/yr license before wiring in a real `PICOVOICE_ACCESS_KEY` (owner: Ayush, low priority —
   Vosk is the active engine and already meets the licensing bar).

## Gotchas

- This is the only repo in the estate with no backend, no CI, no signing, and no third-party account —
  do not assume the estate-wide deploy/secret patterns (Secret Manager, `gcloud run deploy`, reviewer
  bypass, etc.) apply here; none of them do.
- No `CLAUDE.md` or other handoff doc exists in this repo besides the README — the README doubles as
  the project's plan, architecture note, and test log.
- The `KeywordEngine` interface exists specifically so the engine (currently Vosk) can be swapped with a
  one-line change in `ListenService.startListening` — the dormant `PorcupineEngine` behind it is not dead
  code, it is a deliberate paid-fallback seam.
- Wake-phrase grammar is a restricted two-way decision (`["<phrase>", "[unk]"]`), not open transcription;
  changing `VoskEngine.DEFAULT_PHRASE` requires every word to already exist in the model vocabulary.

## History

- [README.md](./README.md) — engine selection rationale, setup, M0 test protocol and scoring, and the
  full Milestones 1-3 device-test write-up (2026-09-16/17), including the two clap-detector defects found
  and fixed.
- No separate `DEPLOYED.md`, `CLAUDE.md`, or `docs/` directory exists in this repo.
- Master index for the full estate handover: `house-of-tech-admin/ESTATE_HANDOVER.md`.

#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-spike-log.jsonl}"
adb shell run-as com.houseoftech.voicelock cat files/spike-log.jsonl > "$OUT"
wc -l < "$OUT"

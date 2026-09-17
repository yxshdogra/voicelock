#!/usr/bin/env python3
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from score import score  # noqa: E402

FIXTURE = os.path.join(os.path.dirname(__file__), "fixture.jsonl")


def load(path):
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


class TestScore(unittest.TestCase):
    def test_fixture_scores_as_expected(self):
        rows = load(FIXTURE)
        result = score(rows, window_s=6.0)

        self.assertAlmostEqual(result["tp_rate"], 0.9, places=6)
        self.assertEqual(result["fp_count"], 2)
        self.assertAlmostEqual(result["fp_per_hour"], 2 / 3, places=6)
        self.assertAlmostEqual(result["battery_per_hour"], 3.0, places=6)
        self.assertTrue(result["overall_pass"])

    def test_zero_attempts_gives_none_tp_rate(self):
        rows = [
            {"t": 1000, "kind": "service", "event": "start"},
            {"t": 2000, "kind": "detect", "kw": 0},
            {"t": 5000, "kind": "service", "event": "stop"},
        ]
        result = score(rows, window_s=6.0)

        self.assertIsNone(result["tp_rate"])
        self.assertIsNone(result["pass_tp"])
        self.assertIsNone(result["overall_pass"])


if __name__ == "__main__":
    unittest.main()

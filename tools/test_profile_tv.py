import unittest
from profile_tv import parse_summary


class RenderingSummaryTests(unittest.TestCase):
    def test_uses_process_counters_without_adding_window_duplicates(self):
        output = """Total frames rendered: 320
Janky frames: 8 (2.50%)
50th percentile: 8ms
95th percentile: 14ms
HISTOGRAM: 8ms=200
Window: main
Total frames rendered: 320
Janky frames: 8 (2.50%)
95th percentile: 99ms
"""
        result = parse_summary(output)
        self.assertEqual(result["frames"], 320)
        self.assertEqual(result["janky_percent"], 2.5)
        self.assertEqual(result["p95_ms"], 14)
        self.assertTrue(result["sample_at_least_300_frames"])

    def test_empty_sample_is_not_reported_as_smooth(self):
        with self.assertRaises(ValueError):
            parse_summary("Total frames rendered: 0\nJanky frames: 0 (0.00%)")

    def test_missing_process_is_an_error(self):
        with self.assertRaises(ValueError):
            parse_summary("No process found")


if __name__ == "__main__":
    unittest.main()

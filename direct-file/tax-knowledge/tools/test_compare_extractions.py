import json
import tempfile
import unittest
from pathlib import Path

from compare_extractions import compare


class CompareExtractionsTest(unittest.TestCase):
    def test_compare_marks_low_similarity_page_for_review(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            primary = root / "primary"
            secondary = root / "secondary"
            primary.mkdir()
            secondary.mkdir()
            (primary / "p334.txt").write_text(
                "----- IRS_SOURCE_PAGE 1 -----\nSchedule C business income standard mileage.",
                encoding="utf-8",
            )
            (secondary / "p334.txt").write_text(
                "----- IRS_SOURCE_PAGE 1 -----\nCompletely unrelated extraction text.",
                encoding="utf-8",
            )

            args = type(
                "Args",
                (),
                {
                    "primary_text_dir": primary,
                    "secondary_text_dir": secondary,
                    "primary_name": "pdfbox",
                    "secondary_name": "vision",
                    "min_jaccard": 0.85,
                    "min_length_ratio": 0.75,
                },
            )()
            result = compare(args)

            self.assertEqual(result["summary"]["documents_compared"], 1)
            self.assertEqual(result["summary"]["pages_needing_review"], 1)
            json.dumps(result)


if __name__ == "__main__":
    unittest.main()

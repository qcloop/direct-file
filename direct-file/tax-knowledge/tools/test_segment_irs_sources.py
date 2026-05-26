import json
import tempfile
import unittest
from pathlib import Path

from segment_irs_sources import parse_pages, segment_manifest


class SegmentIrsSourcesTest(unittest.TestCase):
    def test_parse_page_markers(self):
        pages = parse_pages(
            """
----- IRS_SOURCE_PAGE 1 -----

Publication title

First page text.

----- IRS_SOURCE_PAGE 2 -----

Second page text.
"""
        )

        self.assertEqual([page.number for page in pages], [1, 2])
        self.assertIn("First page text.", pages[0].text)

    def test_segment_manifest_writes_chunk_yaml(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root / "manifest.json"
            text_dir = root / "text"
            output_dir = root / "sources"
            text_dir.mkdir()
            manifest.write_text(
                json.dumps(
                    {
                        "entries": [
                            {
                                "filename": "p334.pdf",
                                "product_id": "p334",
                                "description": "2025 Publ 334 (PDF)",
                                "url": "https://www.irs.gov/pub/irs-pdf/p334.pdf",
                                "sha256": "abc123",
                                "local_path": "downloads/p334.pdf",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            (text_dir / "p334.txt").write_text(
                "----- IRS_SOURCE_PAGE 1 -----\n\nChapter 1.\n\nBusiness income text.", encoding="utf-8"
            )

            args = type(
                "Args",
                (),
                {
                    "manifest": manifest,
                    "text_dir": text_dir,
                    "output_dir": output_dir,
                    "tax_year": 2025,
                    "max_words": 50,
                    "require_downloaded": True,
                    "require_text": True,
                },
            )()
            generated = segment_manifest(args)

            self.assertEqual(len(generated), 1)
            output = generated[0].read_text(encoding="utf-8")
            self.assertIn("source_id: \"irs_p334_2025\"", output)
            self.assertIn("chunk_id: \"irs_p334_2025_chunk_0001\"", output)
            self.assertIn("Business income text.", output)


if __name__ == "__main__":
    unittest.main()

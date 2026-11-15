import hashlib
import tempfile
import unittest
from pathlib import Path

from ingest_source_observations import extract_irs_html, ingest


class IngestObservationsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.snapshots = self.root / "snapshots"
        self.snapshots.mkdir()
        self.source = {"source_id": "notice", "filename": "notice.html",
                       "url": "https://www.irs.gov/forms-pubs/notice",
                       "document_format": "html", "document_status": "final"}
        self.data = b'<nav>Navigation</nav><main><h1>Correction</h1><p>The PDF will not be revised.</p></main><footer>Footer</footer>'
        self.digest = hashlib.sha256(self.data).hexdigest()
        (self.snapshots / (self.digest + "-notice.html")).write_bytes(self.data)
        self.catalog = {"tax_year": 2026, "review_status": "draft", "sources": [self.source]}
        self.baseline = {"tax_year": 2026, "entries": []}
        self.report = {"tax_year": 2026, "errors": [], "observations": [
            {"source_id": "notice", "url": self.source["url"], "sha256": self.digest}]}

    def run_ingest(self):
        return ingest(self.root, self.catalog, self.baseline, self.report, self.snapshots)

    def test_notice_retains_correction_without_navigation(self):
        entry, = self.run_ingest()
        self.assertEqual(entry["sha256"], self.digest)
        self.assertEqual((self.root / entry["local_path"]).read_bytes(), self.data)
        text = (self.root / "corpora/irs-2026/text/notice.txt").read_text()
        self.assertIn("IRS_SOURCE_PAGE 1", text)
        self.assertIn("PDF will not be revised", text)
        self.assertNotIn("Navigation", text)
        self.assertNotIn("Footer", text)

    def test_changed_pin_requires_revision_review(self):
        self.baseline["entries"] = [{"source_id": "notice", "sha256": "old", "url": self.source["url"]}]
        with self.assertRaisesRegex(ValueError, "revision review"):
            self.run_ingest()
        self.assertFalse((self.root / "corpora").exists())

    def test_bad_hash_and_draft_rejected_before_writes(self):
        snapshot = self.snapshots / (self.digest + "-notice.html")
        snapshot.write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.run_ingest()
        snapshot.write_bytes(self.data)
        self.source["document_status"] = "draft"
        with self.assertRaisesRegex(ValueError, "Draft/unverified"):
            self.run_ingest()
        self.assertFalse((self.root / "corpora").exists())

    def test_no_partial_ingestion_when_batch_has_invalid_html(self):
        second = {**self.source, "source_id": "other", "filename": "other.html"}
        self.catalog["sources"].append(second)
        data = b"<body>No main content</body>"
        digest = hashlib.sha256(data).hexdigest()
        (self.snapshots / (digest + "-other.html")).write_bytes(data)
        self.report["observations"].append({"source_id": "other", "url": second["url"], "sha256": digest})
        with self.assertRaisesRegex(ValueError, "main content"):
            self.run_ingest()
        self.assertFalse((self.root / "corpora").exists())

    def test_unsafe_filename_and_year_mismatch_rejected(self):
        self.source["filename"] = "../notice.html"
        with self.assertRaisesRegex(ValueError, "unsafe"):
            self.run_ingest()
        self.report["tax_year"] = 2025
        with self.assertRaisesRegex(ValueError, "income year"):
            self.run_ingest()

    def test_extraction_keeps_inline_spacing_and_omits_scripts(self):
        text = extract_irs_html(b'<main><p>Tax <b>year</b> 2026 &amp; later.</p><script>secret()</script></main>')
        self.assertIn("Tax year 2026 & later.", text)
        self.assertNotIn("secret", text)


if __name__ == "__main__":
    unittest.main()

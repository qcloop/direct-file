import unittest
from argparse import Namespace

from download_irs_pdfs import matches_filters, parse_entries


FIXTURE = """
<html>
  <body>
    <table>
      <tr>
        <th>Name</th><th>Date</th><th>Size</th><th>Description</th>
      </tr>
      <tr>
        <td><a href="/pub/irs-pdf/p334.pdf">p334.pdf</a></td>
        <td>2026-02-12 10:10:10</td>
        <td>2.00 MB</td>
        <td>2025 Publ 334 (PDF)</td>
      </tr>
      <tr>
        <td><a href="/pub/irs-pdf/f1040.pdf">f1040.pdf</a></td>
        <td>2026-01-02 10:10:10</td>
        <td>200 KB</td>
        <td>2025 Form 1040 (PDF)</td>
      </tr>
    </table>
    <a href="/downloads/irs-pdf?page=1">Next</a>
  </body>
</html>
"""


class DownloadIrsPdfsTest(unittest.TestCase):
    def test_parse_directory_table_entries(self):
        entries, anchors = parse_entries(FIXTURE, "https://www.irs.gov/downloads/irs-pdf")

        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0].filename, "p334.pdf")
        self.assertEqual(entries[0].url, "https://www.irs.gov/pub/irs-pdf/p334.pdf")
        self.assertEqual(entries[0].product_kind, "publication")
        self.assertEqual(entries[0].revision, "2025")
        self.assertEqual(entries[0].revision_year, 2025)
        self.assertEqual(entries[0].description, "2025 Publ 334 (PDF)")
        self.assertEqual(entries[1].product_kind, "form")
        self.assertEqual(anchors[-1].href, "https://www.irs.gov/downloads/irs-pdf?page=1")

    def test_carry_forward_products_can_bypass_revision_filter(self):
        entries, _ = parse_entries(
            """
            <table>
              <tr>
                <td><a href="/pub/irs-pdf/p538.pdf">p538.pdf</a></td>
                <td>2024-01-01 10:10:10</td>
                <td>1.00 MB</td>
                <td>2022 Publ 538 (PDF)</td>
              </tr>
            </table>
            """,
            "https://www.irs.gov/downloads/irs-pdf",
        )
        args = Namespace(kind="publication", revision_year=2025, filename_regex=None)

        self.assertFalse(matches_filters(entries[0], args, {"p538"}))
        self.assertTrue(matches_filters(entries[0], args, {"p538"}, {"p538"}))


if __name__ == "__main__":
    unittest.main()

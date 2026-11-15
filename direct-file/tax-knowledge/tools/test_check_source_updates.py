import hashlib
import unittest
from check_source_updates import check


class CheckSourceUpdatesTest(unittest.TestCase):
    def setUp(self):
        self.catalog = {'tax_year': 2026, 'sources': [{'source_id': 'source', 'url': 'https://www.irs.gov/source.pdf', 'filename': 'source.pdf'}]}
        self.data = b'%PDF-official source'
        self.baseline = {'entries': [{'source_id': 'source', 'sha256': hashlib.sha256(self.data).hexdigest()}]}

    def test_unchanged_source_does_not_request_review(self):
        result = check(self.catalog, self.baseline, fetcher=lambda *_: self.data)
        self.assertEqual(result['changes'], [])
        self.assertEqual(result['errors'], [])

    def test_changed_source_requests_review_without_mutating_baseline(self):
        result = check(self.catalog, self.baseline, fetcher=lambda *_: b'%PDF-new revision')
        self.assertEqual(result['changes'][0]['kind'], 'changed')
        self.assertEqual(self.baseline['entries'][0]['sha256'], hashlib.sha256(self.data).hexdigest())

    def test_html_error_page_cannot_become_a_pdf_snapshot(self):
        result = check(self.catalog, self.baseline, fetcher=lambda *_: b'<html>Not available</html>')
        self.assertTrue(result['errors'])
        self.assertEqual(result['changes'], [])

    def test_discovery_page_changes_flag_new_announcements_even_when_pdfs_are_unchanged(self):
        self.catalog['watch_urls'] = ['https://www.irs.gov/irb']
        self.baseline['watch_pages'] = [{'url': 'https://www.irs.gov/irb', 'sha256': 'old-listing'}]
        result = check(self.catalog, self.baseline, fetcher=lambda url, _: b'new bulletin listing' if url.endswith('/irb') else self.data)
        self.assertEqual(result['changes'][0]['kind'], 'discovery_page_changed')


if __name__ == '__main__':
    unittest.main()

import argparse
import hashlib
import tempfile
import unittest
from pathlib import Path

import yaml
from validate_artifact_provenance import validate


class ValidateArtifactProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.sources = self.root / 'sources/irs/2026'
        self.sources.mkdir(parents=True)
        self.rules = self.root / 'rules/federal/2026'
        self.rules.mkdir(parents=True)
        self.text = 'An official source paragraph.'
        self.digest = hashlib.sha256(self.text.encode()).hexdigest()
        self.ref = {'source_id': 'official', 'chunk_id': 'official_chunk_1', 'pages': [2], 'text_sha256': self.digest}
        self.chunk = {**self.ref, 'text': self.text}
        self.write_source()

    def write_source(self, year=2026):
        (self.sources / 'source.yaml').write_text(yaml.safe_dump({'source_id': 'official', 'tax_year': year, 'chunks': [self.chunk]}))

    def result(self, status='approved', refs=None):
        doc = {'rule_id': 'rule', 'status': status, 'tax_year': 2026, 'source_ids': ['official']}
        if refs is not None:
            doc['source_refs'] = refs
        (self.rules / 'rule.yaml').write_text(yaml.safe_dump(doc))
        return validate(argparse.Namespace(root=self.root, sources_dir=self.root / 'sources/irs', tax_year=2026))

    def test_published_as_well_as_approved_requires_exact_refs(self):
        for status in ('published', 'approved', 'reviewed', 'compiled'):
            with self.subTest(status=status):
                self.assertIn('require source_refs', self.result(status)['errors'][0]['message'])

    def test_approved_artifact_with_exact_source_passes(self):
        self.assertEqual(self.result(refs=[self.ref])['errors'], [])

    def test_changed_text_is_rejected_even_when_declared_hash_is_unchanged(self):
        self.chunk['text'] = 'A changed legal rule.'
        self.write_source()
        self.assertIn('text hash', self.result(refs=[self.ref])['errors'][0]['message'])

    def test_wrong_source_owner_pages_and_hash_are_rejected(self):
        for key, value, message in [('source_id', 'other', 'does not own'), ('pages', [99], 'pages'), ('text_sha256', 'bad', 'text hash')]:
            with self.subTest(key=key):
                bad = {**self.ref, key: value}
                self.assertTrue(any(message in error['message'] for error in self.result(refs=[bad])['errors']))

    def test_unknown_chunk_is_not_accepted_when_source_index_is_empty(self):
        (self.sources / 'source.yaml').unlink()
        self.assertIn('chunk_id not found', self.result(refs=[self.ref])['errors'][0]['message'])

    def test_carry_forward_requires_a_reason(self):
        self.write_source(year=2025)
        self.assertIn('carry_forward_reason', self.result(refs=[self.ref])['errors'][0]['message'])
        self.assertEqual(self.result(refs=[{**self.ref, 'carry_forward_reason': 'Reviewed unchanged definition for 2026.'}])['errors'], [])

    def test_draft_refs_are_validated_and_uncited_drafts_warn(self):
        self.assertTrue(self.result(status='draft')['warnings'])
        self.assertTrue(self.result(status='draft', refs=[{**self.ref, 'text_sha256': 'wrong'}])['errors'])

    def test_year_filter_does_not_promote_or_validate_another_year(self):
        other = self.root / 'rules/federal/2025'
        other.mkdir()
        (other / 'bad.yaml').write_text('status: approved\n')
        self.assertEqual(self.result(refs=[self.ref])['files_checked'], 1)


if __name__ == '__main__':
    unittest.main()

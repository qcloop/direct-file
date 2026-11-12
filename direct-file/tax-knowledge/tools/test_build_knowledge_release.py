import hashlib
import tempfile
import unittest
from pathlib import Path

import yaml
from build_knowledge_release import build


class BuildKnowledgeReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'tax-knowledge'
        text = 'Reviewed source paragraph.'
        ref = {'source_id': 'official', 'chunk_id': 'official_1', 'pages': [1], 'text_sha256': hashlib.sha256(text.encode()).hexdigest()}
        self.write('sources/irs/2026/source.yaml', {'source_id': 'official', 'tax_year': 2026, 'chunks': [{**ref, 'text': text}]})
        for family in ('rules', 'questions', 'evidence', 'conflicts', 'scenarios'):
            self.write(f'{family}/federal/2026/artifact.yaml', {'tax_year': 2026, 'status': 'draft', 'source_refs': [ref]})
        self.write('sources/federal/citations.yaml', {'citations': {}})

    def write(self, name, doc):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(yaml.safe_dump(doc))

    def test_build_is_deterministic_and_never_grants_human_approval(self):
        first = build(self.root, 2026)
        second = build(self.root, 2026)
        self.assertEqual(first, second)
        self.assertEqual(first['status'], 'draft')
        self.assertEqual(first['human_approvals'], [])

    def test_changed_artifact_changes_the_release_identity(self):
        first = build(self.root, 2026)['release_id']
        path = self.root / 'questions/federal/2026/artifact.yaml'
        path.write_text(path.read_text() + '\nnew_wording: Changed interview text\n')
        self.assertNotEqual(first, build(self.root, 2026)['release_id'])

    def test_missing_artifact_family_cannot_be_packaged(self):
        (self.root / 'conflicts/federal/2026/artifact.yaml').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing conflicts'):
            build(self.root, 2026)

    def test_changed_raw_source_cannot_be_packaged_under_an_old_hash(self):
        path = self.root / 'corpora/irs-2026/downloads/source.pdf'
        path.parent.mkdir(parents=True)
        path.write_bytes(b'%PDF-new source')
        self.write('corpora/irs-2026/source-manifest.json', {})
        import json
        (self.root / 'corpora/irs-2026/source-manifest.json').write_text(json.dumps({'entries': [
            {'source_id': 'official', 'local_path': 'corpora/irs-2026/downloads/source.pdf', 'sha256': 'oldhash'}]}))
        with self.assertRaisesRegex(ValueError, 'changed source snapshot'):
            build(self.root, 2026)


if __name__ == '__main__':
    unittest.main()

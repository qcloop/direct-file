import tempfile
import unittest
from pathlib import Path

from validate_artifact_provenance import validate


class ValidateArtifactProvenanceTest(unittest.TestCase):
    def test_reviewed_artifact_requires_source_refs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rules = root / "rules" / "federal" / "2025"
            rules.mkdir(parents=True)
            (rules / "rule.yaml").write_text(
                """
rule_id: bad_rule
status: approved
source_ids:
  - irs_p334_2025
""",
                encoding="utf-8",
            )

            args = type("Args", (), {"root": root, "sources_dir": None})()
            result = validate(args)

            self.assertEqual(len(result["errors"]), 1)
            self.assertIn("source_refs", result["errors"][0]["message"])

    def test_approved_artifact_with_known_chunk_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source_dir = root / "sources"
            source_dir.mkdir()
            (source_dir / "irs_p334_2025.yaml").write_text(
                """
chunks:
  - chunk_id: "irs_p334_2025_chunk_0001"
""",
                encoding="utf-8",
            )
            rules = root / "rules" / "federal" / "2025"
            rules.mkdir(parents=True)
            (rules / "rule.yaml").write_text(
                """
rule_id: good_rule
status: approved
source_refs:
  - source_id: irs_p334_2025
    chunk_id: irs_p334_2025_chunk_0001
    pages:
      - 5
    text_sha256: abc123
""",
                encoding="utf-8",
            )

            args = type("Args", (), {"root": root, "sources_dir": source_dir})()
            result = validate(args)

            self.assertEqual(result["errors"], [])


if __name__ == "__main__":
    unittest.main()

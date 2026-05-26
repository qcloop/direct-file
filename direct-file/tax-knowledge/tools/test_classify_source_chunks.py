import tempfile
import unittest
from pathlib import Path

from classify_source_chunks import classify, parse_rules, parse_source_chunks


SOURCE_FIXTURE = """
source_id: "irs_p334_2025"
product_id: "p334"
tax_year: 2025
source_url: "https://www.irs.gov/pub/irs-pdf/p334.pdf"
pdf_sha256: "abc123"
chunks:
  - chunk_id: "irs_p334_2025_chunk_0001"
    source_id: "irs_p334_2025"
    product_id: "p334"
    pages:
      - 10
      - 11
    heading: "Business Income"
    text: |
      Schedule C self-employment business income and standard mileage rules.
    text_sha256: "text123"
    source_url: "https://www.irs.gov/pub/irs-pdf/p334.pdf"
    pdf_sha256: "abc123"
"""


RULES_FIXTURE = """
rules:
  - topic_id: self_employment
    owner: small_business_tax_specialist
    terms:
      - schedule c
      - self-employment
      - standard mileage
    artifact_targets:
      - factgraph
      - interview
      - scenario
"""


class ClassifySourceChunksTest(unittest.TestCase):
    def test_parse_source_chunks(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "irs_p334_2025.yaml"
            path.write_text(SOURCE_FIXTURE, encoding="utf-8")

            chunks = parse_source_chunks(path)

            self.assertEqual(len(chunks), 1)
            self.assertEqual(chunks[0].chunk_id, "irs_p334_2025_chunk_0001")
            self.assertEqual(chunks[0].pages, [10, 11])
            self.assertIn("standard mileage", chunks[0].text)

    def test_classify_outputs_relevance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sources_dir = root / "sources"
            sources_dir.mkdir()
            (sources_dir / "irs_p334_2025.yaml").write_text(SOURCE_FIXTURE, encoding="utf-8")
            rules = root / "rules.yaml"
            rules.write_text(RULES_FIXTURE, encoding="utf-8")

            args = type(
                "Args",
                (),
                {
                    "sources_dir": sources_dir,
                    "rules": rules,
                    "min_score": 2,
                },
            )()
            results = classify(args)

            self.assertEqual(len(parse_rules(rules)), 1)
            self.assertEqual(len(results), 1)
            self.assertEqual(results[0]["topic_ids"], ["self_employment"])
            self.assertIn("factgraph", results[0]["artifact_targets"])
            self.assertEqual(results[0]["source_ref"]["chunk_id"], "irs_p334_2025_chunk_0001")


if __name__ == "__main__":
    unittest.main()

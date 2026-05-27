import pathlib
import tempfile
import unittest

from generate_factgraph_work_items import generate_work_items, parse_args


class GenerateFactgraphWorkItemsTest(unittest.TestCase):
    def test_generates_source_backed_work_item(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            rules_dir = root / "rules"
            rules_dir.mkdir()
            (rules_dir / "standard-mileage.yaml").write_text(
                """
rule_id: se_standard_mileage_vehicle_deduction_ty2025
tax_year: 2025
jurisdiction: federal
status: draft
rule_type: calculation
topic_id: self_employment
source_ids:
  - irs_publication_463_2025
inputs:
  - /seVehicleBusinessMiles
  - /standardMileageRate
output: /seVehicleDeduction
logic:
  summary: Business miles multiplied by the 2025 standard mileage rate.
  expression: round(/seVehicleBusinessMiles * /standardMileageRate)
scenario_ids:
  - se_driver_standard_mileage_basic
""".strip()
                + "\n",
                encoding="utf-8",
            )
            sources_dir = root / "sources"
            sources_dir.mkdir()
            (sources_dir / "irs_p463_2025.yaml").write_text(
                """
source_id: "irs_p463_2025"
product_id: "p463"
source_url: "https://www.irs.gov/pub/irs-pdf/p463.pdf"
chunks:
  - chunk_id: "irs_p463_2025_chunk_0001"
    source_id: "irs_p463_2025"
    product_id: "p463"
    pages:
      - 1
    text_sha256: "abc"
    text: |
      Standard mileage rate information for business miles.
""".strip()
                + "\n",
                encoding="utf-8",
            )
            relevance = root / "source-relevance.yaml"
            relevance.write_text(
                """
tax_year: 2025
jurisdiction: "federal"
relevance:
  - relevance_id: "rel_self_employment_1"
    source_ref:
      source_id: "irs_p463_2025"
      chunk_id: "irs_p463_2025_chunk_0001"
      pages:
        - 1
      text_sha256: "abc"
    topic_ids:
      - "self_employment"
    artifact_targets:
      - "factgraph"
    confidence: "low"
    matched_terms:
      - "standard mileage"
    owner: "small_business_tax_specialist"
    status: "draft"
""".strip()
                + "\n",
                encoding="utf-8",
            )

            args = parse_args(
                [
                    "--tax-year",
                    "2025",
                    "--rules-dir",
                    str(rules_dir),
                    "--relevance",
                    str(relevance),
                    "--sources-dir",
                    str(sources_dir),
                    "--output",
                    str(root / "out.yaml"),
                ]
            )
            document = generate_work_items(args)

        self.assertEqual(len(document["artifacts"]), 1)
        artifact = document["artifacts"][0]
        self.assertEqual(artifact["topic_id"], "self_employment")
        self.assertEqual(artifact["source_refs"][0]["chunk_id"], "irs_p463_2025_chunk_0001")
        self.assertEqual(artifact["source_refs"][0]["text_sha256"], "abc")
        self.assertEqual(artifact["fact_paths"]["output"], "/seVehicleDeduction")


if __name__ == "__main__":
    unittest.main()

# IRS 2026 Source Expansion Pipeline Run

Run date: 2026-11-12. Status: draft; human tax/content/engineering approvals pending.
Input: the [computer-agent discovery report](irs-2026-ui-scout-20261112.md).
Scope: source acquisition and impact review for calendar-income-year 2026 planning.

## Results

| Stage | Result |
| --- | --- |
| Acquisition | 19 additions: 10 PDFs and nine IRS HTML notices; zero retrieval errors |
| Existing pins | All 13 previously cataloged documents unchanged |
| Corpus | 32 pinned documents: 21 IRS PDFs, nine IRS notices, two US Code snapshots |
| PDF extraction | PDFBox primary; independent pypdf comparison across 10 additions / 92 pages |
| Extraction QA | Zero pages below Jaccard 0.85 or length-ratio 0.75; rendered first pages and 1099 form pages visually inspected |
| Segmentation | 106 added chunks; 353 chunks across 27 source YAML files in the combined citation index |
| Classification | 411 draft topic classifications; these include applicability-review inputs |
| Impact review | 12 exact-citation items in the source-expansion change register |
| Fact graph generation | Zero new work items for the self-employment topic; no new explicit calculation rule models were authored |
| Provenance | Zero errors and warnings for the 2026 artifacts |
| Python tests | 34 passed |
| Service build | `mvn -o clean package`: 87 tests passed, static analysis and packaging passed |
| HTTP MCP smoke | Health UP; protocol initialization, 14 tools, 2026 session/interview default, calculations/citations, and versioned preview export passed |
| Review ZIP | All artifact hashes and 32 raw snapshots verified; repeated packaging byte-identical |

The core additions are Schedule C/SE forms, Publications 1099 and 509, and current
1099-K/NEC/MISC forms and instructions. A follow-up acquisition also captured the
current Form 8829 instructions. Notices cover the Publication 1099 transition,
1040-ES SALT and mailing addresses, 8995/8995-A QBI corrections, mileage, W-2 layout,
dependent-care benefits, and 8829 corrections. Draft forms/instructions discovered
in the scout remain watch inputs, outside this final-source corpus.

## Findings that affect the next authoring pass

- The SALT correction independently qualifies an unchanged 1040-ES PDF. Both now
  have source identities and hashes; the notice links to the corrected source.
- December 2026 information-return revisions are printed in the downloaded PDFs.
  The cover explains first use for calendar-year 2026 reporting, filed starting in
  January 2027. The forms have new cash-tip, occupation-code, and overtime fields.
  AcroForm names were inventoried; field-to-fact mappings remain unapproved.
- Publication 1099 is a new source identity for tax year 2026 / processing year
  2027. Payer reporting thresholds must remain separate from taxable-income rules.
- Both QBI correction pages expressly concern 2025 instructions. Review citations
  retain their actual applicability and an explicit carry-forward review reason.
- The 8829 notice calls the instructions 2026, but the linked current PDF is
  visibly printed 2025. The PDF is retained as a 2025 review input, and the
  applicability discrepancy is unresolved.
- The W-2 page-layout notice says it has no operational impact. The separate
  dependent-care notice corrects the 2026 exclusion to $7,500. Neither expands the
  planner's supported deduction/credit scope automatically.
- The earlier mileage HTML reminder does not supersede the later July-effective
  bulletin already represented in the planner. Publication 509 is a calendar of
  due dates, which can concern other income years; it must not choose session year.

No new calculation parameters or executable XML were derived in this run. The
newly acquired sources have `source_review_required` or carry-forward review
dispositions. The active calculation-source subset remains the preceding reviewed
draft set. See [impact-review items](../changes/federal/2026/source-expansion-20261112.yaml).

## Retained artifacts

- [Catalog](../corpora/irs-2026/source-catalog.json) and [complete source manifest](../corpora/irs-2026/source-manifest.json).
- [Acquisition observations](../corpora/irs-2026/ingestion-observations-20261112.json), including three discovery-page hash changes reviewed in the scout and this follow-up.
- [Exact additions subset](../corpora/irs-2026/additions-manifest-20261112.json), [extraction comparison](../corpora/irs-2026/additions-extraction-comparison.json), and [AcroForm inventory](../corpora/irs-2026/form-field-inventory.json).
- [Relevance output](../relevance/federal/2026/source-relevance.yaml) and [fact graph generation result](../factgraph/federal/2026/generated-factgraph-work-items.yaml).
- [Review packet](../reviews/federal/2026/README.md) and [draft release manifest](../releases/federal/2026/manifest.json).

Raw downloads/text stay gitignored. The review ZIP includes the 32 exact raw
snapshots and their hashes. New sources were verified as current-listing products
with printed revisions; hidden printer-proof metadata containing the word “Draft”
was not mistaken for a visible draft watermark or draft-listing provenance.

## Repeatable commands

From `direct-file/`, use a Python environment with `tools/requirements.txt`.
Independent QA additionally used pypdf 7.0.1 with fontTools, and PDFium/Pillow for
rendering because Poppler was unavailable locally. These are extraction/layout
checks, not tax interpretation approval.

```bash
python3 tax-knowledge/tools/check_source_updates.py \
  --catalog tax-knowledge/corpora/irs-2026/source-catalog.json \
  --baseline tax-knowledge/corpora/irs-2026/source-manifest.json \
  --output /tmp/irs-2026-observations.json \
  --snapshot-dir /tmp/irs-2026-snapshots
python3 tax-knowledge/tools/ingest_source_observations.py \
  --root tax-knowledge \
  --catalog tax-knowledge/corpora/irs-2026/source-catalog.json \
  --observations /tmp/irs-2026-observations.json \
  --snapshot-dir /tmp/irs-2026-snapshots
```

The monitor exits 2 for discoveries/changes and 1 for errors. Review those results
before ingestion; the ingester refuses changed pinned bytes. HTML extraction is
part of ingestion. For PDFBox, run from `direct-file/utils/pdf-to-yaml/`:

```bash
mvn -o compile exec:java \
  -Dexec.mainClass=gov.irs.directfile.pdftoyaml.PdfToSourceText \
  -Dexec.args="../../tax-knowledge/corpora/irs-2026/downloads ../../tax-knowledge/corpora/irs-2026/text"
```

Return to `direct-file/` for the remaining steps. The additions subset allows
segmentation without rewriting older pinned chunk files.

```bash
python3 tax-knowledge/tools/segment_irs_sources.py \
  --tax-year 2026 \
  --manifest tax-knowledge/corpora/irs-2026/additions-manifest-20261112.json \
  --text-dir tax-knowledge/corpora/irs-2026/text \
  --output-dir tax-knowledge/sources/irs/2026 --require-downloaded --require-text
python3 tax-knowledge/tools/classify_source_chunks.py \
  --tax-year 2026 --sources-dir tax-knowledge/sources/irs/2026 \
  --rules tax-knowledge/relevance/federal/2026/classification-rules.yaml \
  --output tax-knowledge/relevance/federal/2026/source-relevance.yaml
python3 tax-knowledge/tools/generate_factgraph_work_items.py \
  --tax-year 2026 --topic self_employment \
  --rules-dir tax-knowledge/rules/federal/2026 \
  --relevance tax-knowledge/relevance/federal/2026/source-relevance.yaml \
  --sources-dir tax-knowledge/sources/irs/2026 \
  --runtime-resource-map tax-knowledge/runtime-resources/direct-file-resource-map.yaml \
  --output tax-knowledge/factgraph/federal/2026/generated-factgraph-work-items.yaml
python3 -m unittest discover -s tax-knowledge/tools
python3 tax-knowledge/tools/validate_artifact_provenance.py \
  --root tax-knowledge --tax-year 2026 \
  --sources-dir tax-knowledge/sources/irs/2026 --warnings-are-errors
python3 tax-knowledge/tools/build_knowledge_release.py \
  --root tax-knowledge --tax-year 2026 \
  --output tax-knowledge/releases/federal/2026/manifest.json \
  --package /tmp/federal-2026-review.zip
```

Build `tax-advisor` with tests and static analysis before packaging, and smoke-test
health plus the real Streamable HTTP MCP initialization and tool calls with the
matching service jar and knowledge directory. Human approval/promotion follows
the existing review packet; this run does not grant or request that approval.

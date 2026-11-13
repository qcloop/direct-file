# Federal 2026 planning source inventory

This corpus supports self-employment and ordinary-income **planning during income year 2026**.
It is not a filing or MeF release. Captured November 12, 2026; human tax review is pending.

`source-catalog.json` records each authority's actual revision year and disposition.
`source-manifest.json` pins 21 IRS PDFs, nine IRS HTML notices, two official US Code
HTML snapshots, and the hashes of four IRS discovery pages. `pdf-manifest.json`,
`active-pdf-manifest.json`, `html-manifest.json`, and `statute-manifest.json` are
ingestion subsets. The two US Code snapshots contain
law through Public Law 119-124 (October 15, 2026).

The current Schedule SE instructions and Publication 505 are 2026 revisions. Schedule C
instructions, Publications 334/463, and Forms 8995/8959 instructions downloaded here
remain 2025 revisions. Their carry-forward or replacement decisions need section-level
review; they are not relabeled as 2026 authorities. No draft IRS form or instructions
are used as authoritative runtime sources.

The active citation chunks are under `sources/irs/2026/`. PDFBox is the primary
extractor; important bracket and QBI tables were rendered and checked visually.
Control characters from PDF fonts are normalized before chunk hashing. The minimum
QBI deduction and Additional Medicare thresholds also have exact official statutory
citations. HTML sources use logical page 1; `document_format` distinguishes them from PDFs.

Raw downloads and extracted text are ignored by git. The review ZIP produced by the
release packager includes the pinned raw snapshots and verifies their byte hashes.
Keep that ZIP in release artifact storage before replacing local downloads.

From `direct-file/`, check for changes without mutating this baseline:

```bash
python3 tax-knowledge/tools/check_source_updates.py \
  --catalog tax-knowledge/corpora/irs-2026/source-catalog.json \
  --baseline tax-knowledge/corpora/irs-2026/source-manifest.json \
  --output /tmp/irs-2026-observation.json \
  --snapshot-dir /tmp/irs-2026-observed-sources
```

Exit 0 means unchanged, 2 requests review, and 1 reports a retrieval error. Listing
changes are discovery signals, not new tax-law authorities. Review new announcements,
corrections, effective dates, and affected artifacts before updating any baseline.
The repository's source-monitor workflow runs this weekly and on demand after it is
merged into the default branch; it cannot approve, commit, or deploy tax changes.

## November 12 source expansion

The [pipeline run](../../runs/irs-2026-source-expansion-20261112.md) records acquisition
of 19 additional sources: 10 PDFs and nine correction/transition notices. The exact
subset is `additions-manifest-20261112.json`; the acquisition observations are retained
separately in `ingestion-observations-20261112.json`. All 13 previously pinned source
documents were unchanged. New documents remain review inputs; the active calculation
source subset and graph parameters were not expanded automatically.

The additions include 2026 Schedule C/SE, Publication 1099, Publication 509, and current
1099-K/NEC/MISC forms/instructions. `additions-extraction-comparison.json` compares PDFBox
with pypdf across all 92 PDF pages, with zero threshold flags. `form-field-inventory.json`
records AcroForm names for mapping review, not approved fact mappings.

Corrections are independent HTML sources with logical page 1 and explicit
`corrects_source_ids` where the corrected source is known. The SALT correction says
2026 Form 1040-ES will not be revised; its PDF alone cannot capture the correction.
The 8995/8995-A notices explicitly concern 2025 instructions. The 8829 notice calls
its instructions 2026, but the linked PDF is printed 2025; that discrepancy remains
an applicability-review issue. The earlier mileage HTML reminder does not supersede
the later July-effective bulletin already used by the planner.

# Federal 2026 planning source inventory

This corpus supports self-employment and ordinary-income **planning during income year 2026**.
It is not a filing or MeF release. Captured November 12, 2026; human tax review is pending.

`source-catalog.json` records each authority's actual revision year and disposition.
`source-manifest.json` pins 11 IRS PDFs, two official US Code HTML snapshots, and the
hashes of four IRS discovery pages. `pdf-manifest.json`, `active-pdf-manifest.json`,
and `statute-manifest.json` are ingestion subsets. The two US Code snapshots contain
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

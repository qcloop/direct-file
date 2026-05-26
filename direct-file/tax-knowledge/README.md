# Tax Knowledge Prototype

This directory contains prototype authoring artifacts for an AI-assisted tax preparation backend.

The goal is to turn authoritative tax sources plus human tax expertise into versioned, reviewable, executable knowledge packages. Runtime services should consume only approved artifacts.

## Artifact Families

- `corpora/`: source corpus manifests and ingestion plans.
- `tools/`: deterministic ingestion utilities for agency source material.
- `schemas/`: YAML schemas for source, topic, question, evidence, conflict, rule, and scenario artifacts.
- `workflows/`: agent and human review workflows for producing artifacts.
- `sources/`: chunked source citations generated from authoritative documents.
- `relevance/`: chunk-level relevance classifications.
- `topics/`: tax topic definitions and branch metadata.
- `rules/`: draft and approved tax rule models.
- `factgraph/`: source-backed fact graph work items and generated draft metadata.
- `questions/`: relevance-gated question definitions.
- `evidence/`: mappings from documents/imports to topic signals and candidate facts.
- `conflicts/`: contradiction and review-item rules.
- `scenarios/`: synthetic taxpayer fact bundles and expected outcomes.
- `reviews/`: human review packets and approval records.

## Runtime Boundary

These files are authoring artifacts. They should not directly change a tax return.

Backend services should compile approved artifacts into:

- topic applicability rules
- question planner inputs
- document evidence signal maps
- conflict detection rules
- fact graph rule drafts or generated fact graph XML
- scenario regression tests

Only facts with explicit taxpayer confirmation or an approved system source should be written into the deterministic fact graph.

## Prototype Scope

The initial seed is federal tax year 2025 individual income tax, centered on Form 1040 and Schedule C/self-employment. The IRS corpus entry points are the official Forms, Instructions and Publications listing, the IRS static PDF directory, and the TY2025 Modernized e-File 1040 schema/business-rules page.

## IRS PDF Ingestion

Use `tools/download_irs_pdfs.py` to discover and optionally download IRS PDFs into a tax-year corpus:

```bash
python3 tax-knowledge/tools/download_irs_pdfs.py \
  --tax-year 2025 \
  --product-list tax-knowledge/corpora/irs-2025/publication-products.txt \
  --output-dir tax-knowledge/corpora/irs-2025/downloads \
  --manifest tax-knowledge/corpora/irs-2025/pdf-manifest.json
```

The command above writes an inventory manifest only. Add `--download` to fetch PDFs and record SHA-256 hashes. Raw PDFs are ignored by git; the manifest is the reviewable handoff to source segmentation and artifact generation.

After downloading PDFs, extract page text with the PDFBox utility:

```bash
cd utils/pdf-to-yaml
./mvnw exec:java \
  -Dexec.mainClass=gov.irs.directfile.pdftoyaml.PdfToSourceText \
  -Dexec.args="../../tax-knowledge/corpora/irs-2025/downloads ../../tax-knowledge/corpora/irs-2025/text"
```

Then segment the extracted page text into citation chunks:

```bash
python3 tax-knowledge/tools/segment_irs_sources.py \
  --tax-year 2025 \
  --manifest tax-knowledge/corpora/irs-2025/pdf-manifest.json \
  --text-dir tax-knowledge/corpora/irs-2025/text \
  --output-dir tax-knowledge/sources/irs/2025 \
  --require-downloaded \
  --require-text
```

The resulting source chunks are the citation layer used by agents and reviewers when drafting topics, rules, interview questions, evidence maps, and conflict rules.

Optional extraction sanity check:

```bash
python3 tax-knowledge/tools/compare_extractions.py \
  --primary-text-dir tax-knowledge/corpora/irs-2025/text \
  --secondary-text-dir tax-knowledge/corpora/irs-2025/vision-text \
  --output tax-knowledge/corpora/irs-2025/extraction-comparison.json
```

The secondary directory can be produced by a vision model or OCR engine as long as it uses the same `IRS_SOURCE_PAGE` page markers.

Classify source chunks by relevance:

```bash
python3 tax-knowledge/tools/classify_source_chunks.py \
  --tax-year 2025 \
  --sources-dir tax-knowledge/sources/irs/2025 \
  --rules tax-knowledge/relevance/federal/2025/classification-rules.yaml \
  --output tax-knowledge/relevance/federal/2025/source-relevance.yaml
```

Before any artifact moves beyond draft, validate exact source provenance:

```bash
python3 tax-knowledge/tools/validate_artifact_provenance.py \
  --root tax-knowledge \
  --sources-dir tax-knowledge/sources/irs/2025
```

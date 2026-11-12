# Federal 2026 planning release review

Status: **draft — human approvals pending**. No tax-specialist, content-owner, or engineering
approval is asserted by this packet. The repository pipeline requires “Tax expert approves
interpretation” and tax/content/engineering approval before runtime release.

This release covers planning for income earned during calendar year 2026, including
2026 estimated payments whose last regular installment falls in January 2027. It does
not authorize 2026 e-filing, change MeF/export mappings, or expand the filing backend.

## Review materials

- [Change register](../../../changes/federal/2026/2025-to-2026.yaml): changes, effective dates, exact source references, implementation targets, and scenario coverage.
- [Source inventory](../../../corpora/irs-2026/README.md): document revisions, hashes, current versus older products, and update monitoring.
- [Parameters](../../../rules/federal/2026/): ordinary income, self-employment, and estimated-tax constants.
- [Interview](../../../questions/federal/2026/gateway-questions.yaml), [evidence maps](../../../evidence/federal/2026/document-signals.yaml), and [conflicts](../../../conflicts/federal/2026/document-answer-conflicts.yaml).
- [Scenarios](../../../scenarios/federal/2026/) and executable golden fixtures in `tax-advisor/src/test/resources/evals/*2026.json`.
- [Release manifest](../../../releases/federal/2026/manifest.json): immutable artifact and executable XML hashes. Its status remains draft until actual human approval.

## Tax and content decisions requiring approval

1. The two 2026 mileage rates and the July 1 boundary are represented with separate actual
   mileage inputs. Before July, observed mileage is projected into both six-month periods.
   After July, the completed first-period total is retained and the second period is
   projected from its own observed pace. Monthly projections are estimates and assume even
   activity within their observation periods. Verify the interview wording and assumptions.
2. The eligible active-business QBI minimum is modeled from section 199A(i). An unknown
   material-participation answer stays unknown; it can produce `needs_facts`. Verify the
   eligibility question, $1,000 boundary, and $400 minimum, including its interaction with
   the ordinary income limit. Only a single consolidated business is modeled.
3. MFS uses its own $201,775 QBI threshold and $75,000 prior-year AGI safe-harbor threshold.
   The 110% safe harbor applies strictly above its AGI threshold. The safe-harbor correction
   also affects 2024/2025 planning.
4. The inherited 2025 standard deductions are corrected to $15,750 single/MFS and $31,500
   MFJ using Rev. Proc. 2025-32. Review that baseline correction alongside 2026.
5. The ordinary-income model still omits credits, capital-gains/qualified-dividend rates,
   itemized deductions, NIIT, AMT, and special personal deductions (including qualified tips,
   overtime, senior, car-loan, and non-itemizer charitable deductions). QBI above the simple-
   method threshold remains an upper bound: Form 8995-A wage/property/SSTB limits and the
   expanded phase-in calculations are not implemented. Estimates involving these items need
   appropriate review; this release does not claim complete Form 1040 coverage.
6. Older downloaded 2025 publications/instructions are inventory and carry-forward review
   inputs. They are not automatically authoritative for changed 2026 provisions. Confirm
   each section's applicability and replacements before extending runtime coverage.

## Engineering acceptance

The server clock determines the default income year in a configurable named zone. An
existing session keeps its year across New Year. Explicit other years remain available
when supported; an unsupported current year fails instead of falling back. Interview,
evidence, and conflicts are scoped together by year/jurisdiction. Evidence with an explicit
conflicting income year is rejected, regardless of its receipt date.

Default runtime policy admits `approved`, legacy `published`, and `compiled` artifacts.
The `preview` profile explicitly permits drafts for review and labels the 2026 outputs.
Tax parameters cannot be overwritten through `set_fact`. Loaded parameters and citations
are immutable until restart. Sessions and exports carry the hash of knowledge plus XML;
retained sessions from another version must be recreated rather than reinterpreted.

The provenance validator checks source ownership, chunk identity, cited pages, and actual
text hashes. Source snapshots are also byte-hashed before packaging. The source monitor
checks known documents and IRS discovery listings; it cannot approve or deploy changes.

## Validation and promotion

From `direct-file/`, use a Python environment with `tax-knowledge/tools/requirements.txt`:

```bash
python3 -m unittest discover -s tax-knowledge/tools
python3 tax-knowledge/tools/validate_artifact_provenance.py \
  --root tax-knowledge --tax-year 2026 \
  --sources-dir tax-knowledge/sources/irs/2026 --warnings-are-errors
python3 tax-knowledge/tools/build_knowledge_release.py \
  --root tax-knowledge --tax-year 2026 \
  --output tax-knowledge/releases/federal/2026/manifest.json \
  --package /tmp/federal-2026-review.zip
```

Build `tax-advisor` with tests and static analysis, and smoke-test both `/actuator/health`
and the real `/mcp` handshake/tool calls. The review ZIP includes exact raw IRS and US Code
snapshots; retain it in release artifact storage.

After the actual owners approve, record their names, roles, date, and reviewed content
hash in the release record. Update the status of each approved root artifact and each
reviewed nested evidence/conflict item; approving only the containing file does not
approve a nested draft. Repeat validation and packaging after these status changes,
record approval for the final content identity, and run without the preview profile.
The packager always produces a draft candidate and never invents approvals.

Deployment uses the matching service build and the packaged knowledge directory via
`DF_TAX_KNOWLEDGE_ROOT`. For rollback, restore that build and directory together; sessions
from a different version must be recreated. The legacy 2025 corpus still contains invalid
PDF control characters in some generated YAML; the 2026 validator is scoped to the clean
2026 corpus. Re-segment and re-review legacy chunks before seeking a global provenance pass.

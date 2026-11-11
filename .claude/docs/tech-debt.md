# Tech debt & known limitations

In-repo, versioned tracker of known gaps and follow-ups, so the next agent (or human) sees them
without relying on out-of-repo memory. Keep entries short; link to code where useful. When an item
is resolved, delete it (git history keeps the record).

## Build / platform

- **submit/ and status/ can't be built here.** They depend on proprietary MeF SDK jars
  (`gov.irs.mef.*`, `gov.irs.a2a.mef.*`) absent from this public repo. Their compile failures are
  pre-existing, not regressions. Boot 4 test-slice migration for `status` is also still pending
  (same pattern already applied to `backend`).
- **state-api `org.testcontainers:junit-jupiter` is declared but unused** — candidate for removal
  (the Testcontainers BOM is pinned in the parent pom regardless).

## tax-advisor — tax modeling (MVP scope)

- **QBI is the simple method only (Form 8995).** Above the filing-status threshold, Form 8995-A
  (SSTB / W-2-wage / UBIA limits) governs; the tool returns an upper bound and flags it. Not modeled:
  the 8995-A limits, the phase-in range between threshold and threshold+range, and the 2026 OBBBA
  changes (expanded phase-in, $400 minimum-deduction floor).
- **QBI base omits SE health-insurance and SE-retirement deductions** (not modeled), so QBI is a
  slight overestimate when those apply. QBI here = net profit − deductible ½ SE tax only.
- **2026 QBI thresholds are `provisional`** (Rev. Proc. 2025-32) — confirm against the final 2026
  Form 8995.
- **Filing status supports single / mfj / mfs only.** HoH and QSS aren't first-class; for the
  provisions modeled so far they share the "single" thresholds (correct for §3101(b)(2) Additional
  Medicare; verify per-provision before relying on it for new features).
- **Single consolidated Schedule C** (one SE business). Multi-business is additive (Collection +
  re-rolled sums).
- **Annualization is straight-line** (12 ÷ months) — seasonal income will differ.
- **Income tax is an ordinary-rate model only.** Total tax is now derived (standard deduction + the
  seven ordinary brackets + QBI + SE tax + Additional Medicare via `project_total_tax`), but it omits
  tax credits (EITC, CTC, education…), capital-gains/qualified-dividend preferential rates, the NIIT,
  the AMT, and itemized deductions; it always assumes the standard deduction. Income besides the
  Schedule C is a single agent-supplied `/planning/otherTaxableIncome` (e.g. W-2 box 1), not a modeled
  W-2 with its own withholding. So `projectedCurrentYearTax` is a planning estimate, not a 1040 line 22/24.
- **2026 ordinary brackets + standard deduction are `provisional`** (Rev. Proc. 2025-32, post-OBBBA) —
  confirm against the final 2026 figures. 2024/2025 are final (Rev. Procs. 2023-34 / 2024-40), but the
  2025 standard deduction here is the Rev. Proc. baseline; verify any OBBBA mid-year adjustment.

## Harness / process (from the harness-engineering adoption)

- **Eval harness is a first increment** — golden scenarios under `tax-advisor` cover the calc
  tools; expand coverage (more scenarios, edge cases, adversarial inputs) and consider a full-run
  multi-step library.
- **Doc-gardening is manual.** The drift gate (`ToolDocumentationDriftTest`) catches the README tool
  list; broader staleness (architecture.md, this file) could be swept on a schedule.
- **All 14 MCP tools now use the `@McpTool` path** and publish a conforming `outputSchema` +
  `structuredContent` — except `plan_questions` (`generateOutputSchema = false`), whose open
  interview-planning `PlanResult` has nested records with business-significant null fields
  (e.g. `CandidateFact.sourceField` drives `withEvidence()`); giving it a strict schema would
  require coercing those nulls and changing behavior. If a strict schema for it is ever wanted,
  build an output-only DTO that projects `PlanResult` with non-null strings rather than mutating
  the shared records. The service is now on spring-ai 2.0.2 GA, but this approach has
  not been re-evaluated against the GA schema generator (its null-field handling may have improved,
  allowing simpler typed returns).

# Tech debt & known limitations

In-repo, versioned tracker of known gaps and follow-ups, so the next agent (or human) sees them
without relying on out-of-repo memory. Keep entries short; link to code where useful. When an item
is resolved, delete it (git history keeps the record).

## Build / platform

- **submit/ and status/ can't be built here.** They depend on proprietary MeF SDK jars
  (`gov.irs.mef.*`, `gov.irs.a2a.mef.*`) absent from this public repo. Their compile failures are
  pre-existing, not regressions. Boot 4 test-slice migration for `status` is also still pending
  (same pattern already applied to `backend`).
- **spring-ai is on `2.0.0-M8` (a milestone, pre-GA).** Chosen because it's the Spring Boot 4 line
  and the first with the MCP Streamable HTTP transport. Revisit and pin to `2.0.0` GA when released.
- **state-api `org.testcontainers:junit-jupiter` is declared but unused** — candidate for removal
  (the Testcontainers BOM is pinned in the parent pom regardless).

## df-plan-service — tax modeling (MVP scope)

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
- **`projectedCurrentYearTax` and `taxableIncomeBeforeQBIDeduction` are agent-supplied** — the graph
  does not model the full 1040, so neither the Additional Medicare surtax nor the QBI deduction
  flows automatically into a total-tax figure. A true total-tax derivation would need the full graph.

## Harness / process (from the harness-engineering adoption)

- **Eval harness is a first increment** — golden scenarios under `df-plan-service` cover the calc
  tools; expand coverage (more scenarios, edge cases, adversarial inputs) and consider a full-run
  multi-step library.
- **Doc-gardening is manual.** The drift gate (`ToolDocumentationDriftTest`) catches the README tool
  list; broader staleness (architecture.md, this file) could be swept on a schedule.
- **Only `create_session` publishes an MCP `outputSchema`/`structuredContent`** (it uses the
  `@McpTool` path). The other 11 tools use `@Tool` + `MethodToolCallbackProvider`, which emits no
  output schema in spring-ai 2.0.0-M8. If clients want structured output across the board, migrate
  the rest to `@McpTool` with typed result records — but watch the schema-gen bugs (no `@JsonProperty`
  renames; avoid nullable/optional fields, or they're marked required and fail validation). Revisit
  at spring-ai 2.0.0 GA, where the generator may improve.

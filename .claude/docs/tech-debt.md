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
  the 8995-A limits and the expanded 2026 phase-in range. The eligible 2026 $400 minimum is now
  modeled with an explicit material-participation answer; unknown eligibility stays unknown.
- **QBI base omits SE health-insurance and SE-retirement deductions** (not modeled), so QBI is a
  slight overestimate when those apply. QBI here = net profit − deductible ½ SE tax only.
- **Filing status supports single / mfj / mfs only.** HoH and QSS are rejected; their standard
  deductions and ordinary brackets must not be approximated by single.
- **Single consolidated Schedule C** (one SE business). Multi-business is additive (Collection +
  re-rolled sums).
- **Annualization is straight-line** (12 ÷ months) — seasonal income will differ.
- **Income tax is an ordinary-rate model only.** Total tax is now derived (standard deduction + the
  seven ordinary brackets + QBI + SE tax + Additional Medicare via `project_total_tax`), but it omits
  tax credits (EITC, CTC, education…), capital-gains/qualified-dividend preferential rates, the NIIT,
  the AMT, and itemized deductions; it always assumes the standard deduction. Income besides the
  Schedule C is a single agent-supplied `/planning/otherTaxableIncome` (e.g. W-2 box 1), not a modeled
  W-2 with its own withholding. So `projectedCurrentYearTax` is a planning estimate, not a 1040 line 22/24.
- **2026 release awaits human tax/content/engineering review.** Its values are verified against
  pinned final sources, but artifacts remain draft and require the explicit `preview` profile. See
  `direct-file/tax-knowledge/reviews/federal/2026/README.md`. The default profile rejects drafts.
- **Legacy 2025 source YAML contains PDF control artifacts.** Structural provenance validation
  exposes invalid YAML in some old generated chunks. Re-segment and review those sources before
  seeking a global provenance pass; the 2026 corpus normalizes controls before hashing.
- **Special deductions are not modeled.** Qualified tips, overtime, senior/car-loan deductions,
  and the 2026 non-itemizer charitable deduction remain outside the ordinary-income model.
- **Future income years need reviewed releases.** The clock advances automatically, but a year
  without complete released parameters is refused; it never falls back to stale law.

## tax-advisor — session state

- **The MCP transport session is still per-instance.** Redis shares planning sessions, not Spring AI's
  `Mcp-Session-Id` state, so multi-instance deployments still need session-pinned routing. spring-ai
  2.0.2 ships a stateless server mode (`McpServerStatelessAutoConfiguration`) that would remove this;
  not evaluated.
- **One store round trip per fact read.** A tool that reads a dozen facts loads the session from Redis a
  dozen times (and rebuilds the graph each time, as before). Fine locally; if latency to a remote Redis
  matters, load the snapshot once per tool call and read every fact from it.
- **The in-memory store never evicts.** Only the Redis store has an idle timeout; a long-running
  single instance on the default profile still accumulates sessions until restart.
- **AWS readiness is partial.** TLS/auth via the URL works with Spring's stock support; cluster-mode
  endpoints need extra config, IAM-token auth is not implemented, and nothing here has been run against
  ElastiCache/MemoryDB. On a Redis shared with other apps, its eviction policy can drop sessions before
  the idle timeout (behaves as an early expiry).
- **Redis tests need a Redis.** `RedisSessionStoreTest` skips when `REDIS_URL` is unreachable; CI should
  provide one (the repo's compose file already defines a `redis` service) or the store goes untested.

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

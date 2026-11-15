# Decision log

Short, append-only record of non-obvious engineering decisions and *why* — the rationale that would
otherwise live in a chat thread or someone's head (and so be invisible to agents reading the repo).
Newest last. Each entry: the decision, the reason, and the alternative rejected.

## Spring Boot 4.1 migration

- **Jackson 2 `ObjectMapper` via an explicit bean in tax-advisor.** Boot 4 defaults JSON to
  Jackson 3 (`tools.jackson`) and no longer auto-configures a Jackson 2 mapper. tax-advisor must
  stay Jackson 2 because shared `data-models` `FactTypeWithItem` holds a Jackson 2 `JsonNode`.
  *Rejected:* migrating the service to Jackson 3 (would force changing the shared record).
- **`jcl-over-slf4j` instead of the real commons-logging.** Spring Framework 7 dropped `spring-jcl`;
  the real Apache commons-logging writes to stdout on discovery, which corrupts the stdio MCP
  transport. `jcl-over-slf4j` provides the API but routes through SLF4J/Logback.
- **`spring-boot-starter-web` → `-webmvc`** repo-wide (Boot 4 canonical name); test slices moved to
  per-tech modules (`spring-boot-data-jpa-test`, `spring-boot-webmvc-test`).
- **Testcontainers BOM imported explicitly** in the parent pom (Boot 4 stopped managing it), pinned
  to the 1.x line because 2.0 dropped the `junit-jupiter` artifact state-api references.
- **Moved off pre-release Spring versions to GA:** Boot `4.1.0-RC1` → `4.1.2`, spring-ai `2.0.0-M8` →
  `2.0.2`, springdoc `3.0.3` → `3.2.0` (built against Boot 4.1.2). The parent pom's `mockito.version` /
  `byte-buddy.version` overrides were dropped: Boot 4.1.2 manages Mockito 5.23.0 + ByteBuddy 1.18.15,
  and the old ByteBuddy 1.17.7 pin had become a downgrade. *Rejected:* Boot 4.2 / spring-ai 2.1
  (release candidates only at the time).

## tax-advisor MCP transport

- **Reactive WebFlux + MCP Streamable HTTP (`/mcp`, Netty).** Single endpoint (spec 2025-03-26+),
  session via `Mcp-Session-Id`. *Rejected:* the older WebMVC HTTP+SSE two-endpoint transport.
  Required spring-ai ≥ 2.0.0-M8.
- **`data-models` de-opinionated:** depends on lightweight `org.springframework:spring-web` (for
  `HttpStatus`) + explicit `jackson-databind`, NOT `spring-boot-starter-web`. A web starter on a
  library forces every consumer into SERVLET mode and broke the reactive `/mcp` router. Enforced by
  `WebStackFreeConstraintTest`.
- **All MCP tools use the `@McpTool` annotation path with `generateOutputSchema=true`** so the server
  publishes a real MCP `outputSchema` + matching `structuredContent` per tool. *Why:* in spring-ai
  2.0.0-M8 the `@Tool`/`MethodToolCallbackProvider` path emits **no** output schema regardless of
  return type (verified) — `@McpTool` is the only way. Once every tool moved, the empty
  `McpServerConfig` `MethodToolCallbackProvider` bean was deleted; the annotation scanner registers
  all 12. Conventions that dodge the known schema-gen bugs
  ([spring-ai#4825](https://github.com/spring-projects/spring-ai/issues/4825),
  [#4487](https://github.com/spring-projects/spring-ai/issues/4487)): no `@JsonProperty` (schema
  names == serialized names), heterogeneous/nullable fact values typed `Object` (permissive schema),
  and descriptive strings always non-null (empty when absent). `plan_questions` is the deliberate
  exception (`generateOutputSchema=false`) — its open `PlanResult` carries business-significant null
  fields. *Trade-off:* tool outputs became camelCase + schema-backed (were snake_case `Map`); shared
  `ReadResult`/`ExplainResult` gained null-safe compact constructors.

## tax-advisor tax modeling

- **Tax constants are YAML data; calculations are declarative fact-graph XML.** Adding a year or
  tweaking a constant is data-only, no code change. Persister type codes are validated before
  reaching the engine; writes are atomic (rollback) so one bad `set_fact` can't poison a session.
- **Total tax is DERIVED, not agent-supplied.** Previously `/planning/projectedCurrentYearTax` and
  `/planning/taxableIncomeBeforeQBIDeduction` were `Writable` facts the LLM had to estimate; the
  service then presented that guess as authoritative (a real chat transcript showed a hallucinated
  $5,534 "balance due" that didn't equal its own breakdown, and an uncapped QBI deduction because the
  agent passed taxable income too high). Now an ordinary income-tax engine (`tax-plan/selfEmployment.xml`
  + `income-tax-parameters.yaml`) derives net profit → AGI → taxable income → bracket tax, and
  `projectedCurrentYearTax = income tax + SE tax + Additional Medicare`. A new `project_total_tax` tool
  exposes the whole ladder so the agent calls it instead of doing 1040 math by hand. *Scope:* ordinary
  income only (no credits, capital-gains/qualified-dividend rates, NIIT, AMT, or itemized deductions);
  income besides the Schedule C enters via `/planning/otherTaxableIncome`. Optional income inputs are
  zero-defaulted at session creation so the chain computes from just the Schedule C figures. *Rejected:*
  keeping the agent guess with only a provenance label (cheaper, but still launders a hallucination).
- **Standard deduction and the seven ordinary brackets are status-scoped YAML** (`income-tax-parameters.yaml`,
  one file per year). Brackets modeled as six injected ceilings + seven rates, tax summed per bracket
  (not a compressed marginal-difference formula) so `explain`/`cite` read naturally. 2026 figures are
  `provisional` (Rev. Proc. 2025-32); 2024/2025 are final.
- **Filing-status-dependent constants are status-scoped YAML rows** (a `filing_status` field);
  `createSession(year, status)` injects only the matching row. *Rejected:* modeling filing status as
  an enum fact in the graph (more XML + an enum-options wiring), and hard-coding a status→bracket map
  in Java (different provisions group statuses differently, e.g. Additional Medicare vs QBI).

## tax-advisor session state

- **Session state sits behind a `SessionStore`; Redis is an opt-in implementation, in-memory stays the
  default.** *Why:* sessions held in one JVM's map meant a second instance could not see them and a
  restart lost them. The client web app already runs Redis for its conversational state, so the shared
  store reuses that endpoint (`REDIS_URL`). *This narrows an earlier stance:* the service was designed to
  persist no taxpayer data (FTI surface). That still holds in the default profile; with the `redis`
  profile, taxpayer-entered figures live in Redis for up to the idle timeout (60m sliding). Encryption in
  transit / at rest and access control are the store's configuration, and whoever turns the profile on
  owns that. *Rejected:* making Redis the only store (the stdio transport, tests, and single-instance use
  should not need infrastructure), and client-held state (changes every tool's contract and payload).
- **Optimistic compare-and-set instead of a lock.** The old `synchronized (session)` only excluded
  writers in the same JVM. Each update now reads a versioned snapshot and replaces it only if unchanged
  (a single-key Lua script in Redis; `computeIfPresent` in memory), retrying on conflict. *Rejected:* a
  distributed lock (more moving parts, and lock expiry introduces its own failure mode) for what is
  almost always one sequential conversation.
- **The whole session is one value, not one key per fact.** The fact graph is rebuilt from all facts on
  every read anyway, so per-fact keys would add round trips without saving work, and a single value
  makes the version check trivially atomic and cluster-safe. *Trade-off:* a session is rewritten whole
  on each write (a few KB).
- **Session JSON goes through `StringRedisTemplate` + this service's Jackson 2 mapper**, not Spring Data
  Redis value serializers — `FactTypeWithItem` carries a Jackson 2 `JsonNode` (CLAUDE.md invariant 3).
- **Redis timeouts are 2s** (connect and command). The client default is 60s, which left a tool call
  hanging for a minute when Redis was down (observed); now it fails immediately with a clear error.

## Harness engineering

- **Promote invariants from prose to machine-checked gates.** Architectural/taste rules are enforced
  as structural tests with remediation in the failure message; the knowledge base is kept fresh with
  mechanical checks (tool-inventory drift gate). Context for agents lives in-repo (AGENTS.md map +
  `.claude/docs/`), not in external memory.
- **Self-improvement loop, adapted (not copied) from the "self-improving tax agents" pattern.** The
  loop is: production signal → finding → eval → scoped fix (the fix half is a coding agent against the
  build gates). We do NOT copy the extraction-agent version: tax-advisor is a deterministic
  calculator (no field-accuracy to climb) that persists no taxpayer data (no rich production traces).
  So: (a) a `findings.md` ledger turns recurring client-app/transcript/SME signals into evals + fixes;
  (b) telemetry is **aggregate and PII-free** — a single AOP aspect (`ToolCallTelemetry`) on `@McpTool`
  methods records only `{tool, outcome, errorClass}` to Micrometer + a structured log, never args or
  field values or exception messages, surfacing tool *misuse* (e.g. `needs_facts` / `error` rates) at
  `/actuator/metrics` without storing anything; (c) the eval suite gained adversarial/edge fixtures, an
  affordance-eval class (does `needs_facts`/notes steer the agent right?), and a coverage gate so eval
  breadth can't silently shrink. *Rejected:* per-session or value-level traces (FTI/privacy), and a
  trend-line "score over time" (needs external CI history — the coverage floor is the in-repo stand-in).
  *Chose AOP* over instrumenting all 14 tools by hand: one advice, verified to intercept the MCP
  scanner's invocations (`ToolCallTelemetryTest`). Boot 4 AOP starter is `spring-boot-starter-aspectj`.

## Annual planning-year refresh

- **The server clock determines the default income year.** Users estimate 2026 income throughout
  calendar year 2026; filing season does not shift the default back to 2025. An injected Clock and
  configurable named zone make this deterministic and testable. `create_session` returns the year
  and server date; interviews inherit that session year and reject conflicts. Existing sessions
  keep their year across New Year. *Rejected:* asking an LLM to remember the date or adding a
  separate clock MCP call that a client could omit.
- **Verified values and artifact approval are separate.** Final annual guidance can support
  planning before every year-end form/publication has been revised. The 2026 source inventory
  preserves actual source revision years; draft IRS guidance is not authoritative. Default runtime
  policy accepts released artifacts; the explicit preview profile allows drafts and labels them.
  No generated packet asserts human approvals.
- **Sessions pin loaded knowledge and executable XML content.** Parameters and citations load once;
  a combined digest travels with sessions and exports. Retained sessions from another version must
  be recreated. *Rejected:* letting a file edit or deployment reinterpret an old session silently.
- **2026 mileage has two periods.** The graph combines January–June and July–December rates with
  separate observed-mile inputs and projection factors. Before July it projects the observed pace
  into both halves; afterward it retains the completed first half and projects only the second
  half's observed pace. Unallocated post-change mileage is rejected instead of guessed.
- **The 2026 active-business QBI minimum requires confirmed eligibility.** The graph models the
  $1,000 QBI threshold and $400 minimum; missing material participation remains incomplete.
  Form 8995-A limits and expanded phase-in calculations remain outside the simple-method scope.
- **Refresh automation produces review candidates.** Source monitoring covers pinned documents
  and IRS discovery pages. Structural provenance checks ownership/pages/actual text hashes, and
  deterministic packages retain raw snapshots and graph hashes. Monitoring and packaging cannot
  approve, commit, or deploy tax changes.

## Total-first mileage collection

- **Collect one mileage total and ask one conditional allocation follow-up.** The
  income year's parameters determine dated rate periods. Actual YTD figures before
  the change need no split, even when reported later. A total spanning the change
  returns `needs_facts`; one period answer determines the other by subtraction.
  This supersedes rejecting an unallocated total. Unknown allocations remain pending;
  automatic allocation estimates are not enabled.
- **Persist pending inputs in the session's fact map, atomically.** The total,
  coverage date, calculation mode, and other Schedule C inputs survive the follow-up
  and work with the existing Redis serialization. Clear old period facts in the same
  update so the graph cannot produce a stale deduction/tax result. Resume only for
  the same date and mode; omission of the date preserves its original value.
- **Question wording remains in the knowledge artifacts.** The pending question is
  prioritized by `plan_questions` and returned by the calculation tool. Actual period
  miles remain distinct from future mileage projected by the existing graph factors.

## Unknown mileage allocation: labeled estimate (2026-11-13)

- **Default to a labeled planning estimate for total-only mileage.** The user selected
  this behavior over a range or a blocked interview. This supersedes the default-pending
  policy above. The graph distributes the total by elapsed calendar-day shares from
  January 1 through the coverage date; for a YTD projection it extends the second-period
  pace through year-end. Receipts/other expenses retain monthly annualization.
  This distribution is a product assumption, not an IRS requirement. Seasonal driving
  and midyear business starts require the user to refine with actual period mileage.
- **Estimated allocations never become actual records.** Separate graph outputs and
  result fields expose actual/estimated/projected miles. The assumption is visible in
  tool results, client summaries, and exports. A period answer refines the saved estimate;
  invalid answers leave the prior state intact. Exact-only mode remains available through
  `/planning/requireExactMileageAllocation`; missing splits then remain `needs_facts`.
- **A built jar is not evidence that the running client uses it.** The latest observed
  client error still contained the obsolete split-rejection text. Verify the actual 8090
  runtime after rebuilding. MCP transport recovery is limited to a rejected HTTP 404
  session; timeouts and tool errors are not automatically replayed.
- **Treat an empty 0/0 period pair as unallocated total mileage.** The live local-model
  regression reproduced optional period fields filled with zero despite explicit total-only
  guidance. A supplied total remains primary; the empty pair yields the disclosed estimate
  or exact-only `needs_facts`, never fabricated actual records. Other inconsistent/negative
  period values remain invalid and atomic. Client diagnostics explain how to correct them.

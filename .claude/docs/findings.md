# Findings ledger — production signal → finding → eval → fix

Append-only record of the **self-improvement loop** for tax-advisor, adapted from the OpenAI/Thrive
"self-improving tax agents" pattern (production use → structured findings → tailored evals → scoped,
human-reviewed fix). Newest last.

**Why this exists.** tax-advisor is a deterministic fact-graph calculator, not a learned extraction
agent, so we do **not** copy that loop wholesale (there is no field-extraction accuracy to climb, and we
persist no taxpayer data). What *does* transfer: our "practitioners" are the **client-app team and tax
SMEs**, our production signal is **how the client LLM uses the MCP tools** (and where it misuses them),
and every signal that recurs becomes a **regression eval** plus a scoped fix. The fix half of the loop is
a coding agent (Claude Code / Codex) working against those evals with the build gates as the success
condition — see `operations.md`.

**How to use.** When a signal arrives (a client-app report, a chat transcript, a tax-SME review, a
telemetry anomaly), add an entry. Group recurring signals under one finding. A finding is only actionable
once it has (a) a clear failure statement, (b) at least one eval that fails before the fix, and (c) a
bounded change. Ambiguous or judgment-laden cases (real tax-law questions) route to a human, not the loop
— we never invent tax law (CLAUDE.md invariant 5).

Entry template:

```
## FIND-NNN — <one-line title>
- **Signal:** where it came from (transcript, client report, SME, telemetry) + what was observed.
- **Finding:** the failure, stated precisely. What class is it? (tool-misuse / modeling gap / stale
  constant / schema / UX-affordance / genuinely-ambiguous→human)
- **Eval:** the test(s) that pin it (must fail before the fix).
- **Fix:** the change + commit.
- **Status:** open | shipped | routed-to-human.
```

---

## FIND-001 — create_session structuredContent did not match its MCP outputSchema
- **Signal:** Client-app team reported `create_session` failing MCP output-schema validation when
  `filingStatus` was passed ("structuredContent does not match tool outputSchema").
- **Finding:** Schema/contract class. The `@Tool` path emitted no `outputSchema`; tools that did publish
  one (`@McpTool`) tripped spring-ai schema-gen bugs (null required strings, `@JsonProperty` ignored).
  Inconsistent structured output across the 12 tools.
- **Eval:** `PlanningAgentEvalTest` (golden scenarios run through the typed tool surface, serialized to
  the same shape as `structuredContent`); per-tool conformance verified over the live `/mcp` transport.
- **Fix:** Migrated all tools to the `@McpTool` path with `generateOutputSchema=true`; null-safe compact
  constructors on shared `ReadResult`/`ExplainResult`; `Object` for heterogeneous fact values;
  `plan_questions` documented as the schemaless exception. See `decisions.md` (MCP transport section).
- **Status:** shipped.

## FIND-002 — agent invented the total-tax / balance-due figure
- **Signal:** Chat transcript — the AI presented a "projected balance due at filing" of $5,334 that did
  not equal its own breakdown ($441 income tax + $3,689 SE tax), and later produced a different $4,130.
- **Finding:** Tool-design class (the worst kind: the service *laundered* an LLM guess into a sealed,
  authoritative-looking export). `projectedCurrentYearTax` and `taxableIncomeBeforeQBIDeduction` were
  `Writable` facts the agent had to estimate; QBI was also over-deducted because the agent passed taxable
  income too high, so the (correct) income cap never bound.
- **Eval:** `TotalTaxConsistencyTest` — asserts `projectedTotalTax == incomeTax + seTax + AdditionalMedicare`
  and that QBI is capped, on the transcript scenario. Eval fixtures re-pointed to the derived model.
- **Fix:** Modeled ordinary income tax in the fact graph (standard deduction + 7 brackets), made total tax
  **derived**; added `project_total_tax`; `income-tax-parameters.yaml` for 2024–2026. Commit `7d254e9`.
- **Status:** shipped.

## FIND-003 — agent hand-waved the filing-status comparison
- **Signal:** Chat transcript — asked "would filing status make a difference?", the AI estimated a
  "moderate effect" and a guessed income-tax figure instead of computing it (and missed that MFJ's
  standard deduction zeroes the income tax at that income).
- **Finding:** Tool-misuse + missing-affordance class. No tool computed a cross-status comparison, and
  filing status is fixed at `create_session`, so the agent had no cheap correct path and improvised.
- **Eval:** `CompareFilingStatusesTest` — SE tax constant across statuses; MFJ income tax = 0 at the
  transcript income; needs-facts before income is set.
- **Fix:** `compare_filing_statuses` tool (clones session inputs across single/MFJ/MFS); filing-status
  guidance added to the MCP server `instructions` and `create_session`. Commit `ad74777`.
- **Status:** shipped.

## FIND-004 — agent led with the wrong deduction questions and didn't defer to the server
- **Signal:** Chat transcript — for a delivery driver the AI asked about "gas, maintenance, phone"
  expenses (which the standard mileage rate already covers — double-counting risk) instead of business
  miles, and answered tax questions from its own priors rather than calling the tools.
- **Finding:** Tool-misuse + knowledge-coverage class. The server gave no signal to defer, and the gig
  case (1099-K, platform fees, mileage-vs-actual) was under-modeled in tax-knowledge.
- **Eval:** `TaxKnowledgePlanningIntegrationTest` (1099-K → SE signal → gig detail questions; conflict on
  denied SE income); the deference is steered by the server `instructions` (verified in the handshake).
- **Fix:** Server `instructions` + directive `plan_questions` description; gig tax-knowledge (1099-K
  evidence map, platform-fee/supplies/prior-year questions, anti-double-count help text). Commits
  `5ccd5b6`, `d00126f`.
- **Status:** shipped.

## FIND-005 — web agent supplied a placeholder planning session
- **Signal:** User-visible reference `f9cc7369-84db-4615-890b-81eb225f99ce` matched
  the companion TaxAdvisor app log at 2026-11-13 00:10:10 UTC. Ollama called
  `plan_questions` with `sessionId: "?"`, without a preceding successful session
  creation. The MCP server correctly returned an unknown/expired-session error.
- **Finding:** Client tool-argument/session-orchestration failure. The generic UI
  message suggested retrying an outage, which would not fix the invalid ID. This
  report did not establish a mileage-calculation failure.
- **Eval:** Companion app `Agents/src/provider/__tests__/ollama-sessions.test.ts`
  and `Tools/src/mcp/__tests__/tool-failure.test.ts`: the reported no-session call,
  stream/non-stream restoration, session ambiguity/isolation, and recovery message.
  New regressions fail with the original code and pass with the patch. An AI SDK
  replay into the real local MCP interview succeeded with synthetic inputs.
- **Fix:** Companion app adapter processes responses even without an existing ID;
  omits a placeholder for the optional sessionless interview; resolves placeholders
  only when one session is known in that request; preserves explicit unknown IDs
  and ambiguous contexts. Session failures receive an actionable recovery diagnostic.
  Existing session-creation gating in that app is preserved. No tax-law/graph change.
- **Status:** fixed locally in the companion app; typecheck, lint, 73 tests,
  production build, MCP connectivity, and the real-MCP regression passed.

## FIND-006 — obsolete runtime and empty optional mileage fields blocked total-only estimates
- **Signal:** Companion log reference `5192d4cf-36bd-402d-8162-8f2bdf85fed1`
  contained the obsolete split-rejection message. A synthetic local-model replay then
  reproduced a positive total plus zero/zero optional period placeholders. After
  calculation succeeded, the model sometimes omitted the assumption in its reply.
- **Finding:** Building a newer jar had not replaced the listening JVM. Mandatory
  allocation questions and optional-field placeholders made a total-only flow brittle.
  Disclosure must be visible without depending on model prose.
- **Eval:** `EstimatedMileageIntegrationTest`, exact-only mileage integration tests,
  live MCP schema checks on the actual 8090 endpoint, companion summary/scenario and
  chat-stream tests, and targeted local Ollama behavior eval.
- **Fix:** User-selected labeled even-calendar-day estimate in the graph, distinct
  actual/estimated/projected outputs, partial actual refinement, strict opt-in, atomic
  input replacement, empty-pair handling, and actionable invalid-mileage diagnostics.
  Client retains labels and canonical scenario inputs and appends a missing assumption
  note deterministically. The actual local preview JVM was replaced with the tested jar.
- **Status:** fixed locally; 103 service tests, 34 pipeline tests, 86 companion tests,
  mandatory static/build gates, HTTP and client checks passed. See
  `direct-file/tax-knowledge/runs/irs-2026-mileage-estimate-20261113.md`.

# Unknown-mileage planning estimate validation — 2026-11-13

Status: draft review candidate. No tax-specialist, engineering-owner, or content-owner
approval is asserted. The user selected the labeled planning estimate product behavior.

## Reproduced signals

- Companion app reference `5192d4cf-36bd-402d-8162-8f2bdf85fed1` still came from an
  obsolete running MCP JVM that rejected unallocated 2026 mileage totals.
- A targeted local Ollama regression supplied `businessMiles: "1000"` alongside
  `businessMilesBeforeRateChange: "0"` and `businessMilesAfterRateChange: "0"`, despite
  receiving only a total. The previous validation rejected that empty allocation.
- After calculation succeeded, the model sometimes omitted the assumption in prose.
  Disclosure now also has a deterministic chat-stream safeguard and a summary label.

## Behavior verified

- Default total-only inputs produce `status: ok`, allocation status `estimated`, and
  a server-authored assumption. No required cutoff question is returned.
- The graph distributes observed miles by calendar-day shares from January 1 through
  the coverage date. For a YTD projection, future second-period mileage extends that
  observed pace. Receipts and other expenses retain monthly annualization.
- Separate actual, estimated, and projected period outputs prevent assumptions from
  becoming actual records. The report displays the assumption separately from inputs.
- Empty zero/zero period placeholders provide no allocation of a supplied total;
  they yield the estimate or exact-only pending state. Other invalid allocations fail
  before writes. A partial actual-period answer retains the date, total, receipts,
  expenses, and wages and clears the assumption.
- Exact-only mode still returns `needs_facts`, one authored question, and incomplete
  dependent tax facts. Single-rate years, zero mileage, and pre-cutover actuals work.
- The client uses authoritative projected miles and recorded inputs for scenarios,
  retains the summary label through quarterly calculations, clears it after an actual
  refinement, and appends the note to chat when the model omits it.
- A rejected HTTP 404 MCP transport session reconnects once. Ambiguous failures and
  calculator error results are not replayed; planning sessions are not fabricated.

## Checks

- `mvn -o clean package`: 103 tests passed; SpotBugs, PMD, and Spotless passed.
- Knowledge pipeline unit suite: 34 tests passed. Exact provenance validation: 353
  known chunks, zero errors and warnings. Source snapshots/rates were not repinned.
- Companion app isolated checkout: typecheck, lint, 86 tests, production build passed.
  Concurrent guided-intake/locale changes in `stream-chat.ts` were preserved on apply.
- Live Streamable HTTP smoke on temporary port and actual port 8090: 14 tools,
  output-schema validation, default estimated and strict pending paths, partial
  refinement, citations, and labeled/versioned export passed. An initial HTTP check
  caught a Rational serialized as `{}`; projected miles now use numeric conversion,
  with an added assertion and successful repeat.
- Real companion MCP client and AI SDK response replay: total-only calculation,
  session placeholder restoration, partial refinement, and scenario baseline passed.
- Running web app `POST /api/scenarios` returned HTTP 200 for 2026 total-only mileage;
  the unperturbed scenario matched its baseline (synthetic net profit 46,089).
- Targeted local-model preview eval passed with executed estimated results, no
  invented nonzero period amounts, and disclosed allocation. Other live evals were
  outside this targeted run.

## Limits and release

The even-day assumption is a product estimate, not an IRS allocation rule or mileage
log. Seasonal driving and businesses started midyear need actual-period refinement.
The existing graph supports one rate or two periods starting at a configured month
boundary. The 2026 corpus/release remains draft and runs only under explicit preview
policy. Restarting the local MCP runtime requires a fresh planning session.

The review package is rebuilt against the matching executable jar and source XML;
its manifest records artifact, raw-source, graph, and jar hashes. No publication,
production deployment, or approval was performed.

# Decision log

Short, append-only record of non-obvious engineering decisions and *why* — the rationale that would
otherwise live in a chat thread or someone's head (and so be invisible to agents reading the repo).
Newest last. Each entry: the decision, the reason, and the alternative rejected.

## Spring Boot 4.1 migration

- **Jackson 2 `ObjectMapper` via an explicit bean in df-plan-service.** Boot 4 defaults JSON to
  Jackson 3 (`tools.jackson`) and no longer auto-configures a Jackson 2 mapper. df-plan-service must
  stay Jackson 2 because shared `data-models` `FactTypeWithItem` holds a Jackson 2 `JsonNode`.
  *Rejected:* migrating the service to Jackson 3 (would force changing the shared record).
- **`jcl-over-slf4j` instead of the real commons-logging.** Spring Framework 7 dropped `spring-jcl`;
  the real Apache commons-logging writes to stdout on discovery, which corrupts the stdio MCP
  transport. `jcl-over-slf4j` provides the API but routes through SLF4J/Logback.
- **`spring-boot-starter-web` → `-webmvc`** repo-wide (Boot 4 canonical name); test slices moved to
  per-tech modules (`spring-boot-data-jpa-test`, `spring-boot-webmvc-test`).
- **Testcontainers BOM imported explicitly** in the parent pom (Boot 4 stopped managing it), pinned
  to the 1.x line because 2.0 dropped the `junit-jupiter` artifact state-api references.

## df-plan-service MCP transport

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

## df-plan-service tax modeling

- **Tax constants are YAML data; calculations are declarative fact-graph XML.** Adding a year or
  tweaking a constant is data-only, no code change. Persister type codes are validated before
  reaching the engine; writes are atomic (rollback) so one bad `set_fact` can't poison a session.
- **Filing-status-dependent constants are status-scoped YAML rows** (a `filing_status` field);
  `createSession(year, status)` injects only the matching row. *Rejected:* modeling filing status as
  an enum fact in the graph (more XML + an enum-options wiring), and hard-coding a status→bracket map
  in Java (different provisions group statuses differently, e.g. Additional Medicare vs QBI).

## Harness engineering

- **Promote invariants from prose to machine-checked gates.** Architectural/taste rules are enforced
  as structural tests with remediation in the failure message; the knowledge base is kept fresh with
  mechanical checks (tool-inventory drift gate). Context for agents lives in-repo (AGENTS.md map +
  `.claude/docs/`), not in external memory.

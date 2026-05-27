# df-plan-service

A tax-planning agent backend that extends the Direct File Fact Graph with
self-employment, Schedule SE, and quarterly-estimate facts, and exposes them
through a [Spring AI](https://docs.spring.io/spring-ai/reference/) MCP
(Model Context Protocol) server so an authorized LLM gateway can drive a
conversational tax-planning experience.

## What's here

```
src/main/java/gov/irs/directfile/planservice/
  PlanServiceApplication.java               # Spring Boot main
  config/PlanServiceProperties.java         # @ConfigurationProperties
  config/McpServerConfig.java               # registers @Tool methods with Spring AI's MCP server
  graph/PlanningGraphService.java           # loads combined dictionary, manages sessions
  knowledge/TaxKnowledgeService.java        # loads tax-knowledge artifacts, evaluates planning gates
  mcp/PlanningTools.java                    # @Tool-annotated methods exposed via MCP

src/main/resources/
  application.yaml                          # HTTP+SSE transport (default profile)
  application-stdio.yaml                    # STDIO transport (profile=stdio)
  tax-plan/selfEmployment.xml               # planning fact module

src/test/java/.../QuarterlyEstimateIntegrationTest.java
src/test/java/.../TaxKnowledgePlanningIntegrationTest.java
```

The MCP plumbing — JSON-RPC framing, capabilities negotiation, tool discovery,
JSON Schema generation from method signatures, SSE/STDIO transports — is
provided by `spring-ai-starter-mcp-server-webmvc` (HTTP+SSE) and
`spring-ai-starter-mcp-server` (core + STDIO). Tools are plain
`@Tool`-annotated Java methods on the `PlanningTools` Spring bean.

The planning fact module declaratively encodes:

- TY2024 constants: 12.4% SS, 2.9% Medicare, 92.35% wage multiplier, $168,600
  SS wage base, $0.67/mi standard mileage, $400 SE-tax threshold.
- Schedule C: gross receipts, vehicle deduction (standard mileage method),
  platform fees, supplies, net profit clipped at zero.
- Schedule SE: net earnings (× 92.35%), SS portion (capped against the wage
  base remaining after W-2 wages), Medicare portion, total SE tax,
  deductible half-of-SE adjustment.
- Estimated tax safe harbor per IRC §6654: lesser of (100%/110% prior-year
  tax) or (90% projected current-year tax); next quarterly payment derived
  from remaining balance / remaining quarters.

The agent never computes tax math — all derivations live in
`selfEmployment.xml`. Tools read/write facts and explain dependencies.

The `plan_questions` tool is the first bridge from authored tax-knowledge
artifacts into runtime planning. It loads YAML artifacts from `../tax-knowledge`
by default, or from `DF_TAX_KNOWLEDGE_ROOT` if set. The tool evaluates:

- prior session facts and explicit answer facts
- document evidence, such as `form_w2` or `form_1099_nec`
- profile signals, such as prior-year topics
- question `asks_when` / `skip_when` gates
- conflict rules that require review before continuing

It returns candidate facts that require taxpayer confirmation, review conflicts,
and the next applicable questions. It does not write facts into a return.

## Tools

Spring AI exposes the following tools at the MCP `tools/list` endpoint. Names
match the wire format; argument shapes correspond to the Java parameter names
on `PlanningTools` (camelCase, not snake_case).

| Tool                          | Purpose                                                |
| ----------------------------- | ------------------------------------------------------ |
| `create_session`              | Allocate a new in-memory planning session              |
| `get_fact`                    | Read a fact from a session's graph                     |
| `set_fact`                    | Write a value to a writable fact                       |
| `explain`                     | One-level derivation explanation for a fact            |
| `estimate_quarterly_payment`  | IRC §6654 safe-harbor quarterly recommendation         |
| `plan_questions`              | Plan next interview questions from tax-knowledge YAML  |

## Build prerequisites

This module depends on two things the open-source Direct File repo does not
publish to Maven Central:

1. **`gov.irs:factgraph_3`** — the Scala 3 engine, lives in the standalone
   fact-graph repo ([`../../../fact-graph/`](../../../fact-graph/)) and ships via sbt.
2. **`gov.irs.directfile:loaders`** — the shared XML loader extracted from
   the backend, lives in [`../libs/loaders/`](../libs/loaders/).

### One-time local setup

```bash
# 1. Install sbt + Scala (see ../../ONBOARDING.md). On macOS:
brew install sbt

# 2. Publish the fact graph to your local Maven repo:
cd ../../../fact-graph
sbt publishM2          # writes to ~/.m2/repository/gov/irs/factgraph_3/...

# 3. Install Direct File's BOMs and shared libs:
cd ../boms && ../backend/mvnw install -N
cd ../libs/data-models && ../../backend/mvnw install
cd ../loaders && ../../backend/mvnw install

# 4. Build the plan service:
cd ../df-plan-service && ../backend/mvnw test
```

## Running

### HTTP+SSE transport (default — network-accessible)

```bash
../backend/mvnw spring-boot:run
```

The service listens on port 8090 and publishes two MCP endpoints:

- `GET  /mcp/sse`     — server-sent-events stream (long-lived; LLM gateway opens this first)
- `POST /mcp/message` — JSON-RPC requests from the connected client

### STDIO transport (subprocess of a desktop MCP host)

```bash
../backend/mvnw spring-boot:run -Dspring-boot.run.profiles=stdio
```

With `stdio` active, the Spring Boot web server is disabled, stdout is
reserved for the JSON-RPC stream, and application logs go to
`~/.df-plan-service/df-plan-service.log`. Wire up in e.g. Claude Desktop's
config:

```json
{
  "mcpServers": {
    "df-plan-service": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/df-plan-service/target/df-plan-service-0.0.1-SNAPSHOT.jar",
        "--spring.profiles.active=stdio"
      ]
    }
  }
}
```

### Sanity-checking the HTTP endpoint

The Spring AI MCP server speaks the MCP protocol over SSE, so a raw `curl`
no longer exchanges full request/response pairs the way the previous
JSON-RPC endpoint did. To exercise tools from the command line, use the
official `mcp` CLI (or any MCP client SDK) pointed at the SSE endpoint:

```bash
# With the modelcontextprotocol/inspector or any MCP client:
#   transport: http+sse
#   url:       http://localhost:8090
#   sse path:  /mcp/sse
#   msg path:  /mcp/message
```

End-to-end smoke testing is covered by the integration tests
(`QuarterlyEstimateIntegrationTest`, `TaxKnowledgePlanningIntegrationTest`),
which invoke the same `@Tool` methods that the MCP server exposes.

## Wiring to an LLM

This service is the *MCP server* side. An LLM gateway (Azure OpenAI Gov,
Bedrock GovCloud, on-prem model fronted by Spring AI's `ChatClient`, a
desktop client like Claude Desktop, etc.) acts as the MCP *client*. After
the standard MCP handshake it calls `tools/list`, then `tools/call` to
invoke `create_session` → `set_fact` → `estimate_quarterly_payment` in
response to user turns.

**Important boundaries** (see the architectural notes that produced this
scaffold):

- The model runs server-side behind an authorized egress allowlist.
- Even server-side, prefer passing fact *paths* rather than values where
  the model only needs to reason about completeness.
- This MVP keeps planning sessions in memory only — nothing is persisted
  that would qualify as FTI under §6103. Once a session writes facts into
  a filed return (out of scope here), full FedRAMP-High obligations attach.

## Known limitations (MVP)

- Single-business Schedule C only. Multi-business (per-platform Schedule Cs)
  needs a `Collection` wrapper around the SE facts.
- No Additional Medicare 0.9% surtax (Form 8959).
- No QBI deduction (Section 199A).
- One-level `explain` only — recursive walker is a follow-up.
- `plan_questions` currently evaluates a small declarative condition subset:
  fact equality, evidence presence/attributes, and profile membership.
- TY2025 quarterly deadlines hardcoded inline in `PlanningTools`. Move to a
  year-keyed constant.
- No auth on the MCP endpoints. In production these must sit behind the
  same auth boundary as the rest of Direct File.

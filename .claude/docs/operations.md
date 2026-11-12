# Direct File — operations & build/run guide

> Supplements the repo-root `CLAUDE.md`. All commands assume you're in the Maven tree under
> `direct-file/` (one level below the repo root). See `ONBOARDING.md` for first-time setup.

## Toolchain

- **JDK 26** (e.g. Corretto) to run the build; **Java 25** is the language/target level
  (`java.version` in the parent POM).
- **Maven 3.9.x** (services also ship the `mvnw` wrapper; the README invokes `../backend/mvnw`).
- **Node + npm** for `df-client/` (React/Vite).
- **Docker / Docker Compose** for the full local stack (Postgres, Redis, LocalStack, etc.).
- The fact graph (`gov.irs:factgraph_3`) is built and published from its **own repo** into
  `~/.m2`; it is a prerequisite, not built here.

## Build order (important)

First-party artifacts resolve from local `~/.m2`, so install in dependency order:

1. **Fact graph** — published from the standalone fact-graph repo (`make publish`) →
   `gov.irs:factgraph_3:3.1.0-SNAPSHOT`.
2. **Parent POM** — after any edit to it, reinstall before building dependents:
   ```bash
   mvn -N install -f direct-file/boms/irs-spring-boot-starter-parent/pom.xml
   ```
3. **Shared libs** — `libs/data-models`, `libs/loaders`, `libs/starters/*`:
   ```bash
   mvn -q install -f direct-file/libs/data-models/pom.xml   # etc.
   ```
4. **The service** you're working on:
   ```bash
   cd direct-file/tax-advisor && mvn clean install
   ```

> If a build can't find `gov.irs:factgraph_3`, `gov.irs.directfile:loaders`,
> `gov.irs.directfile:data-models`, or `irs-spring-boot-starter-*`, the local install step
> above was missed.

## The build gates (static analysis + format)

Bound to `compile`/`package` via the parent POM:
- **SpotBugs** (effort Max) + **findsecbugs**
- **PMD** (ruleset in `config/`; PMD pinned to 7.21.0 for JDK-26 class parsing)
- **Spotless** — Palantir Java format, `removeUnusedImports`, import order, sorted POMs,
  YAML/JSON formatting. Runs `check` at `package`.

Fast inner loop (skip the gates while iterating), then run the real build before declaring done:
```bash
mvn test-compile -Dspotbugs.skip=true -Dpmd.skip=true -Dspotless.check.skip=true
mvn spotless:apply        # auto-format instead of hand-fixing
mvn clean package         # the authoritative build (tests + all gates)
```

## Running tax-advisor

Default (reactive WebFlux/Netty, Streamable HTTP on `:8090`):
```bash
java -jar direct-file/tax-advisor/target/tax-advisor-0.0.1-SNAPSHOT.jar
# or: cd direct-file/tax-advisor && ../backend/mvnw spring-boot:run
```
- `DF_TAX_KNOWLEDGE_ROOT` (default `../tax-knowledge`) points at the YAML knowledge root.
- Health: `GET /actuator/health` (also `/health/liveness`, `/health/readiness`). Only `health`
  is web-exposed by default; add `management.endpoints.web.exposure.include` to expose more.

Verify the MCP endpoint (Streamable HTTP — `initialize` returns the session id header):
```bash
# 1) initialize — note the Mcp-Session-Id response header
curl -i -X POST http://localhost:8090/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
# 2) reuse the id for notifications/initialized, then tools/list, tools/call …
curl -X POST http://localhost:8090/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: <id>' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

Shared session store (several instances, sessions survive restarts):
```bash
REDIS_URL=redis://localhost:6379 java -jar .../tax-advisor-0.0.1-SNAPSHOT.jar --spring.profiles.active=redis
```
- `REDIS_URL` defaults to `redis://localhost:6379` — the same endpoint the client web app uses. Keys are
  namespaced `tax-advisor:session:<id>` and expire after `tax-advisor.session-store.idle-timeout` (60m,
  sliding), so they do not collide with or outlive the web app's own keys.
- Without the profile the store is in-memory, Redis is never contacted, and its health indicator is off.
  With it, an unreachable Redis shows as `DOWN` in `/actuator/health` and tool calls fail within 2s.
- Each instance still owns its MCP transport sessions: put instances behind a load balancer that pins
  `Mcp-Session-Id` to the instance that issued it.
- For AWS (ElastiCache / MemoryDB): use a `rediss://` URL with credentials for TLS + auth. The store
  uses only single-key commands and scripts, so it is compatible with cluster mode, but a
  cluster-mode-enabled endpoint needs Spring's cluster settings (`spring.data.redis.cluster.nodes`)
  rather than just the URL. IAM-token auth (short-lived tokens) is not wired up.
- `RedisSessionStoreTest` runs against `REDIS_URL` and is **skipped** when no Redis answers, so a green
  build without Redis running has not exercised the Redis store.

stdio transport (subprocess of a desktop MCP host):
```bash
java -jar .../tax-advisor-0.0.1-SNAPSHOT.jar --spring.profiles.active=stdio
```
- No HTTP listener; JSON-RPC over stdin/stdout. Application logs go to
  `~/.tax-advisor/tax-advisor.log` (stdout stays JSON-RPC-only). Wire into Claude
  Desktop/Cursor via a `command: java` + `args: [-jar, …, --spring.profiles.active=stdio]` entry.

## Full local stack (Docker Compose)

```bash
docker compose build
docker compose up -d
```
Access the app via the CSP simulator at `http://localhost:5000` (`DF_CSPSIM_PORT`).

Default host ports (overridable by env var; see the root `README.md` table):

| Service | Port (env) |
|---------|-----------|
| backend / api | 8080 (`DF_API_PORT`) |
| status | 8082 (`DF_STATUS_PORT`) |
| submit | 8083 (`DF_SUBMIT_PORT`) |
| df-client (frontend) | 3000 (`DF_FE_PORT`) |
| screener | 3500 (`DF_SCREENER_PORT`) |
| CSP simulator | 5000 (`DF_CSPSIM_PORT`) |
| external-service simulator | 5001 (`DF_EXTSVCSIM_PORT`) |
| backend DB | 5432 (`DF_DB_PORT`) · state-api DB 5433 · submit/status DB 32768 |
| Prometheus / Grafana | 9090 / 3030 |
| tax-advisor | 8090 (standalone; not wired into the default compose file) |

Infra containers: Postgres (per service), Redis, LocalStack (SQS/SNS/KMS), WireMock, OTel
collector, Prometheus, Grafana.

## Buildability caveats

- `submit/` and `status/` need proprietary **MeF SDK jars** absent from this repo — they won't
  fully build locally. Don't attribute their failures to your changes.
- When you change the **parent POM**, the only services you can fully validate here are
  `backend`, `state-api`, `email-service`, and `tax-advisor` (+ the `libs`). Build all of
  them, not just your target, because the parent is shared.
- Spring Boot `4.1.2` and `spring-ai 2.0.2` are GA and resolve from Maven Central — no milestone
  repository is needed.

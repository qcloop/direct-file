package com.realworld.tax.advisor.mcp;

import java.time.Clock;
import java.time.LocalDate;

import com.realworld.tax.advisor.graph.PersisterTypes;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.PlanningGraphService.ReadResult;
import com.realworld.tax.advisor.graph.SessionId;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import static com.realworld.tax.advisor.mcp.ToolSupport.SESSION_ID_DESCRIPTION;

/**
 * MCP tools that open a planning session and read or write its facts directly:
 * {@code create_session}, {@code get_fact}, {@code set_fact}.
 *
 * <p>See the package documentation for the wire-format conventions every tool result follows.
 */
@Component
public class SessionTools {

    private final PlanningGraphService graph;

    private final ToolSupport support;

    private final Clock clock;

    public SessionTools(PlanningGraphService graph, ToolSupport support, Clock clock) {
        this.graph = graph;
        this.support = support;
        this.clock = clock;
    }

    @McpTool(
            name = "create_session",
            description = "Create a new in-memory planning session for a tax year and return its id. "
                    + "The session is seeded with that year's indexed tax constants (standard mileage "
                    + "rate, Social Security wage base) from tax-knowledge; if a year has no published "
                    + "constants the call fails rather than silently using another year's rates. All "
                    + "subsequent tool calls require this id. Sessions are not persisted and live only "
                    + "for the duration of the server process — if a later call reports an "
                    + "unknown/expired session, call this again and re-enter the facts.",
            generateOutputSchema = true)
    public CreateSessionResult createSession(
            @McpToolParam(
                            description = "Income year to plan for. Omit to use the server's current calendar year "
                                    + "(2026 income is planned throughout 2026, even though the return is filed later). "
                                    + "Supply a year only when the taxpayer explicitly requests another year.",
                            required = false)
                    String taxYear,
            @McpToolParam(
                            description = "Filing status: single | mfj | mfs. Defaults to single. Drives"
                                    + " filing-status thresholds (standard deduction, income-tax brackets, QBI"
                                    + " and Additional Medicare thresholds). Self-employment tax does NOT depend"
                                    + " on it. For a quick set-aside estimate it is fine to default to single and"
                                    + " tell the taxpayer you assumed it; status is fixed for the life of the"
                                    + " session, so to change it (or to compare) create a new session or call"
                                    + " compare_filing_statuses — do not estimate the difference by hand."
                                    + " Head-of-household and qualifying surviving spouse are not supported by"
                                    + " this ordinary-income planning model; do not substitute single for them.",
                            required = false)
                    String filingStatus) {
        // Sample once: a call straddling midnight must not return a date from a different default year.
        LocalDate planningDate = LocalDate.now(clock);
        int year = parseTaxYear(taxYear, planningDate.getYear());
        SessionId session = graph.createSession(year, filingStatus);
        return new CreateSessionResult(
                session.value(),
                year,
                graph.filingStatusOf(session),
                support.provisionalWarning(year),
                planningDate.toString(),
                clock.getZone().getId(),
                graph.knowledgeVersionOf(session));
    }

    /**
     * Structured result of {@code create_session}. Returned as a typed record (via the {@code @McpTool}
     * path with {@code generateOutputSchema=true}) so the server publishes an accurate MCP
     * {@code outputSchema} and matching {@code structuredContent} that strict clients can validate.
     *
     * <p>Two deliberate choices avoid spring-ai's known schema-generation pitfalls: no
     * {@code @JsonProperty} renames (the schema generator ignores them, which would desync schema
     * field names from the serialized content), so the wire names are the record component names; and
     * {@code provisionalWarning} is always present (empty string when the year is finalized) rather
     * than nullable, since generators tend to mark every property required.
     */
    public record CreateSessionResult(
            String sessionId,
            int taxYear,
            String filingStatus,
            String provisionalWarning,
            String planningDate,
            String planningTimeZone,
            String knowledgeVersion) {}

    @McpTool(
            name = "get_fact",
            description = "Read a single fact from a planning session's fact graph. Returns the "
                    + "current value if computable, or a 'not yet computable' note listing why.",
            generateOutputSchema = true)
    public ReadResult getFact(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(description = "Fact path, e.g. /seTax or /planning/safeHarborTarget.") String path) {
        SessionId session = SessionId.of(sessionId);
        return graph.readFact(session, path);
    }

    @McpTool(
            generateOutputSchema = true,
            name = "set_fact",
            description = "Write a value to a writable fact in a planning session. Specify the "
                    + "type via 'type' (short alias: dollar/int/boolean/string/day/enum/ein/tin) "
                    + "or 'typeCode' (full Scala class name). Optionally pass 'source' to record "
                    + "where the value came from as stated by the taxpayer. Returns whether the "
                    + "write was accepted by the fact graph's validation pass.")
    public PlanningGraphService.WriteResult setFact(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(description = "Fact path, e.g. /planning/priorYearTotalTax.") String path,
            @McpToolParam(
                            description = "Short type alias: dollar | int | boolean | string | day | enum | ein | tin. "
                                    + "Either 'type' or 'typeCode' must be supplied.",
                            required = false)
                    String type,
            @McpToolParam(
                            description = "Full Scala persister type code, used when 'type' is not one of the aliases.",
                            required = false)
                    String typeCode,
            @McpToolParam(
                            description = "Value to write, as a string: e.g. \"608\" (dollar), "
                                    + "\"2025-04-15\" (day), \"true\" (boolean).")
                    String value,
            @McpToolParam(
                            description = "Optional free-text provenance as stated by the taxpayer, e.g. "
                                    + "\"2024 1099-NEC box 1, Uber\". Recorded for the export report and "
                                    + "shown there as self-reported; it is not verified and never enters "
                                    + "the tax math.",
                            required = false)
                    String source) {
        SessionId session = SessionId.of(sessionId);

        String resolvedTypeCode = PersisterTypes.resolve(type, typeCode);
        PlanningGraphService.WriteResult result =
                graph.writeFact(session, path, resolvedTypeCode, PersisterTypes.shape(resolvedTypeCode, value));
        graph.setSourceNote(session, path, source);
        return result;
    }

    private static int parseTaxYear(String taxYear, int currentYear) {
        return ToolSupport.isBlank(taxYear) ? currentYear : Integer.parseInt(taxYear.trim());
    }
}

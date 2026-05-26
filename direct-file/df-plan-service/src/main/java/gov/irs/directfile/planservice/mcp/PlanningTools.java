package gov.irs.directfile.planservice.mcp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import gov.irs.directfile.planservice.graph.PlanningGraphService;
import gov.irs.directfile.planservice.graph.PlanningGraphService.ReadResult;
import gov.irs.directfile.planservice.knowledge.TaxKnowledgeService;

/**
 * Spring AI MCP tools exposed by df-plan-service.
 *
 * <p>Each {@code @Tool}-annotated method is auto-registered with the MCP server via
 * {@link gov.irs.directfile.planservice.config.McpServerConfig#planningToolCallbacks}.
 * Spring AI introspects the method signature to produce the JSON Schema advertised
 * in {@code tools/list}, and routes incoming {@code tools/call} invocations here.
 *
 * <p>Wire-format note: parameter names in the generated JSON Schema follow the Java
 * camelCase identifiers ({@code sessionId}, {@code asOfDate}). This is a deliberate
 * break from the hand-rolled JSON-RPC server's snake_case shape.
 */
@Component
public class PlanningTools {

    private static final String WRAPPER_PREFIX = "gov.irs.factgraph.persisters.";
    private static final String DOLLAR_WRAPPER = WRAPPER_PREFIX + "DollarWrapper";
    private static final String INT_WRAPPER = WRAPPER_PREFIX + "IntWrapper";

    private static final Map<String, String> TYPE_ALIASES = Map.of(
            "dollar", DOLLAR_WRAPPER,
            "int", INT_WRAPPER,
            "boolean", WRAPPER_PREFIX + "BooleanWrapper",
            "string", WRAPPER_PREFIX + "StringWrapper",
            "day", WRAPPER_PREFIX + "DayWrapper",
            "enum", WRAPPER_PREFIX + "EnumWrapper",
            "ein", WRAPPER_PREFIX + "EinWrapper",
            "tin", WRAPPER_PREFIX + "TinWrapper");

    /** Federal quarterly estimated-tax deadlines for TY2025 payments. */
    private static final List<Deadline> DEADLINES_2025 = List.of(
            new Deadline("Q1", LocalDate.of(2025, 4, 15)),
            new Deadline("Q2", LocalDate.of(2025, 6, 16)), // 6/15 is a Sunday
            new Deadline("Q3", LocalDate.of(2025, 9, 15)),
            new Deadline("Q4", LocalDate.of(2026, 1, 15)));

    private static final List<RequiredFact> REQUIRED_FACTS = List.of(
            new RequiredFact(
                    "/planning/priorYearTotalTax",
                    "Total federal tax from your prior-year return (Form 1040, Line 24)."),
            new RequiredFact(
                    "/planning/priorYearAGI",
                    "Adjusted gross income from your prior-year return (Form 1040, Line 11)."),
            new RequiredFact(
                    "/planning/projectedCurrentYearTax",
                    "Your best estimate of total federal tax (income tax + SE tax) for this year."),
            new RequiredFact(
                    "/planning/ytdWithholding",
                    "Federal income tax already withheld by employers or platforms so far this year."),
            new RequiredFact(
                    "/planning/ytdEstimatedPaymentsMade",
                    "Quarterly estimated tax payments you've already made this year."));

    private final PlanningGraphService graph;
    private final TaxKnowledgeService taxKnowledge;

    public PlanningTools(PlanningGraphService graph, TaxKnowledgeService taxKnowledge) {
        this.graph = graph;
        this.taxKnowledge = taxKnowledge;
    }

    @Tool(
            name = "create_session",
            description = "Create a new in-memory planning session and return its id. "
                    + "All subsequent tool calls require this id. Sessions are not persisted "
                    + "and live only for the duration of the server process.")
    public Map<String, Object> createSession() {
        return Map.of("sessionId", graph.createSession());
    }

    @Tool(
            name = "get_fact",
            description = "Read a single fact from a planning session's fact graph. Returns the "
                    + "current value if computable, or a 'not yet computable' note listing why.")
    public ReadResult getFact(
            @ToolParam(description = "Planning session id from create_session.") String sessionId,
            @ToolParam(description = "Fact path, e.g. /seTax or /planning/safeHarborTarget.") String path) {
        return graph.readFact(sessionId, path);
    }

    @Tool(
            name = "set_fact",
            description = "Write a value to a writable fact in a planning session. Specify the "
                    + "type via 'type' (short alias: dollar/int/boolean/string/day/enum/ein/tin) "
                    + "or 'typeCode' (full Scala class name). Returns whether the write was "
                    + "accepted by the fact graph's validation pass.")
    public PlanningGraphService.WriteResult setFact(
            @ToolParam(description = "Planning session id from create_session.") String sessionId,
            @ToolParam(description = "Fact path, e.g. /planning/priorYearTotalTax.") String path,
            @ToolParam(
                            description = "Short type alias: dollar | int | boolean | string | day | enum | ein | tin. "
                                    + "Either 'type' or 'typeCode' must be supplied.",
                            required = false)
                    String type,
            @ToolParam(
                            description = "Full Scala persister type code, used when 'type' is not one of the aliases.",
                            required = false)
                    String typeCode,
            @ToolParam(description = "Value matching the declared type.") Object value) {

        String resolvedTypeCode = typeCode;
        if (resolvedTypeCode == null || resolvedTypeCode.isBlank()) {
            if (type == null) {
                throw new IllegalArgumentException("Must supply 'type' or 'typeCode'.");
            }
            resolvedTypeCode = TYPE_ALIASES.get(type.toLowerCase());
            if (resolvedTypeCode == null) {
                throw new IllegalArgumentException("Unknown type alias: " + type);
            }
        }

        // Dollar values arrive as numbers; the persister wants them as strings.
        Object shaped = value;
        if (DOLLAR_WRAPPER.equals(resolvedTypeCode) && value instanceof Number n) {
            shaped = n.toString();
        }

        return graph.writeFact(sessionId, path, resolvedTypeCode, shaped);
    }

    @Tool(
            name = "explain",
            description = "Explain how a fact's value was derived: returns the fact's name, "
                    + "description, computed value, and the values of every fact it directly "
                    + "depends on. Useful for grounding 'why is my number X?' answers.")
    public PlanningGraphService.ExplainResult explain(
            @ToolParam(description = "Planning session id from create_session.") String sessionId,
            @ToolParam(description = "Fact path to explain.") String path) {
        return graph.explain(sessionId, path);
    }

    @Tool(
            name = "estimate_quarterly_payment",
            description = "Compute the next federal estimated tax payment for a self-employed "
                    + "taxpayer using IRC §6654 safe-harbor logic (lesser of 100%/110% prior-year "
                    + "tax or 90% projected current-year tax). Returns the recommended payment, "
                    + "next deadline, and a derivation chain. If required facts are missing, "
                    + "returns a 'needs_facts' response so the agent can gather them.")
    public Map<String, Object> estimateQuarterlyPayment(
            @ToolParam(description = "Planning session id from create_session.") String sessionId,
            @ToolParam(description = "ISO date (YYYY-MM-DD). Required because the fact graph has no clock.")
                    String asOfDate) {

        LocalDate asOf = LocalDate.parse(asOfDate);

        // 1. Check that required writable facts are populated.
        List<Map<String, String>> missing = new ArrayList<>();
        for (RequiredFact rf : REQUIRED_FACTS) {
            ReadResult r = graph.readFact(sessionId, rf.path);
            if (!r.complete()) {
                missing.add(Map.of("path", rf.path, "prompt", rf.prompt));
            }
        }
        if (!missing.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "needs_facts");
            out.put("missing_facts", missing);
            out.put("hint", "Ask the user for these values, write each via set_fact, then call this tool again.");
            return out;
        }

        // 2. Resolve the next deadline and compute remaining quarters.
        Deadline next = DEADLINES_2025.stream()
                .filter(d -> !d.due.isBefore(asOf))
                .findFirst()
                .orElse(DEADLINES_2025.get(DEADLINES_2025.size() - 1));
        long remainingQuarters =
                DEADLINES_2025.stream().filter(d -> !d.due.isBefore(asOf)).count();
        if (remainingQuarters == 0) {
            remainingQuarters = 1; // past last deadline — recommend Q4 catch-up payment
        }

        graph.writeFact(sessionId, "/planning/remainingQuarters", INT_WRAPPER, remainingQuarters);

        // 3. Read the derived recommendation + supporting facts.
        ReadResult suggested = graph.readFact(sessionId, "/planning/nextQuarterlyPaymentSuggested");
        ReadResult safeHarbor = graph.readFact(sessionId, "/planning/safeHarborTarget");
        ReadResult ytdApplied = graph.readFact(sessionId, "/planning/ytdPaymentsApplied");
        ReadResult remainingDue = graph.readFact(sessionId, "/planning/remainingPaymentDue");
        ReadResult seTax = graph.readFact(sessionId, "/seTax");
        ReadResult highIncomeRule = graph.readFact(sessionId, "/planning/highIncomeSafeHarborApplies");

        Map<String, Object> derivation = new LinkedHashMap<>();
        derivation.put("self_employment_tax", seTax.value());
        derivation.put(
                "safe_harbor_rule",
                Boolean.TRUE.equals(highIncomeRule.value())
                        ? "110% of prior-year tax (AGI > $150,000)"
                        : "100% of prior-year tax");
        derivation.put("safe_harbor_target", safeHarbor.value());
        derivation.put("ytd_payments_already_applied", ytdApplied.value());
        derivation.put("remaining_due_through_year_end", remainingDue.value());
        derivation.put("remaining_quarters", remainingQuarters);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("recommended_payment", suggested.value());
        out.put("next_deadline", next.due.toString());
        out.put("quarter", next.quarter);
        out.put("derivation", derivation);
        out.put("explanation_tree", graph.explain(sessionId, "/planning/nextQuarterlyPaymentSuggested"));
        out.put("payment_url", "https://www.irs.gov/payments/direct-pay");
        return out;
    }

    @Tool(
            name = "plan_questions",
            description = "Plan relevant tax interview questions from tax-knowledge artifacts. "
                    + "Inputs may include current session facts, document evidence, prior-year "
                    + "profile data, and previous answers. Returns candidate facts that require "
                    + "confirmation, review conflicts, and the next applicable questions.")
    public Object planQuestions(
            @ToolParam(
                            description =
                                    "Optional planning session id. If supplied, the session's facts are merged in.",
                            required = false)
                    String sessionId,
            @ToolParam(description = "Tax year to plan for. Defaults to 2025.", required = false) Integer taxYear,
            @ToolParam(description = "Jurisdiction. Defaults to 'federal'.", required = false) String jurisdiction,
            @ToolParam(
                            description = "Optional fact map. Values may be raw values or {value, status}; "
                                    + "status defaults to confirmed.",
                            required = false)
                    Map<String, Object> facts,
            @ToolParam(
                            description = "Document evidence. Items may be strings like 'form_w2' or objects with "
                                    + "documentType plus arbitrary fields.",
                            required = false)
                    List<Object> evidence,
            @ToolParam(description = "Taxpayer profile signals, such as prior_year_topics.", required = false)
                    Map<String, Object> profile,
            @ToolParam(description = "Include questions already answered in this session.", required = false)
                    Boolean includeAnswered,
            @ToolParam(description = "Maximum applicable questions to return.", required = false) Integer limit) {

        Map<String, Object> mergedFacts = new LinkedHashMap<>();
        if (sessionId != null && !sessionId.isBlank()) {
            graph.sessionFacts(sessionId).forEach((path, value) -> mergedFacts.put(path, confirmed(value)));
        }
        if (facts != null) {
            mergedFacts.putAll(facts);
        }

        return taxKnowledge.plan(new TaxKnowledgeService.PlanRequest(
                taxYear == null ? 2025 : taxYear,
                jurisdiction == null ? "federal" : jurisdiction,
                mergedFacts,
                evidenceStates(evidence),
                profile == null ? Map.of() : profile,
                Boolean.TRUE.equals(includeAnswered),
                limit));
    }

    private static Map<String, Object> confirmed(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        out.put("status", "confirmed");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<TaxKnowledgeService.EvidenceState> evidenceStates(List<Object> evidence) {
        if (evidence == null) {
            return List.of();
        }
        List<TaxKnowledgeService.EvidenceState> states = new ArrayList<>();
        for (Object item : evidence) {
            if (item instanceof String documentType) {
                states.add(new TaxKnowledgeService.EvidenceState(documentType, Map.of()));
            } else if (item instanceof Map<?, ?> map) {
                Map<String, Object> attributes = new LinkedHashMap<>((Map<String, Object>) map);
                String documentType = (String) attributes.remove("document_type");
                if (documentType == null) {
                    documentType = (String) attributes.remove("documentType");
                }
                if (documentType != null) {
                    states.add(new TaxKnowledgeService.EvidenceState(documentType, attributes));
                }
            }
        }
        return states;
    }

    private record Deadline(String quarter, LocalDate due) {}

    private record RequiredFact(String path, String prompt) {}
}

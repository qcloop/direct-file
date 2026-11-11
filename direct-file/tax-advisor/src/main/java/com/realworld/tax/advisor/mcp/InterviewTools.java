package com.realworld.tax.advisor.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService.EvidenceState;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tool that plans the taxpayer interview from the tax-knowledge artifacts: {@code plan_questions}.
 *
 * <p>See the package documentation for the wire-format conventions every tool result follows.
 */
@Component
public class InterviewTools {

    /** Key for the status field returned in the plan_questions confirmed-fact wrapper. */
    private static final String STATUS_KEY = "status";

    private final PlanningGraphService graph;

    private final TaxKnowledgeService taxKnowledge;

    public InterviewTools(PlanningGraphService graph, TaxKnowledgeService taxKnowledge) {
        this.graph = graph;
        this.taxKnowledge = taxKnowledge;
    }

    // plan_questions deliberately does NOT publish an outputSchema (generateOutputSchema = false). Its
    // PlanResult is an open, evolving interview-planning structure whose nested records carry
    // business-logic-significant null fields (e.g. CandidateFact.sourceField, whose null-ness drives
    // withEvidence()). A generated schema marks every string required, so a null would make
    // structuredContent fail validation; and coercing those nulls to "" would change behavior. Omitting
    // the schema means no strict validation (and no validation failure) — the correct trade-off for an
    // open structure. Every other tool publishes a strict, conforming schema.
    @McpTool(
            generateOutputSchema = false,
            name = "plan_questions",
            description = "Plan the next tax interview questions from IRS-backed tax-knowledge artifacts. "
                    + "Call this BEFORE asking the taxpayer any interview question, and ask what it "
                    + "returns in the order returned — do not invent your own questions; the sequencing "
                    + "encodes tax rules a general model gets wrong (e.g. it asks a driver for business "
                    + "miles, not gas/maintenance receipts, which the standard mileage rate already covers). "
                    + "Inputs may include current session facts, document evidence, prior-year profile "
                    + "data, and previous answers. Returns candidate facts that require confirmation, "
                    + "review conflicts, and the next applicable questions.")
    public TaxKnowledgeService.PlanResult planQuestions(
            @McpToolParam(
                            description =
                                    "Optional planning session id. If supplied, the session's facts are merged in.",
                            required = false)
                    String sessionId,
            @McpToolParam(description = "Tax year to plan for. Defaults to 2025.", required = false) Integer taxYear,
            @McpToolParam(description = "Jurisdiction. Defaults to 'federal'.", required = false) String jurisdiction,
            @McpToolParam(
                            description = "Optional fact map. Values may be raw values or {value, status}; "
                                    + "status defaults to confirmed.",
                            required = false)
                    Map<String, Object> facts,
            @McpToolParam(
                            description = "Document evidence. Items may be strings like 'form_w2' or objects with "
                                    + "documentType plus arbitrary fields.",
                            required = false)
                    List<Object> evidence,
            @McpToolParam(description = "Taxpayer profile signals, such as prior_year_topics.", required = false)
                    Map<String, Object> profile,
            @McpToolParam(description = "Include questions already answered in this session.", required = false)
                    Boolean includeAnswered,
            @McpToolParam(description = "Maximum applicable questions to return.", required = false) Integer limit) {

        return taxKnowledge.plan(new TaxKnowledgeService.PlanRequest(
                taxYear == null ? 2025 : taxYear,
                jurisdiction == null ? "federal" : jurisdiction,
                sessionAndSuppliedFacts(sessionId, facts),
                evidenceStates(evidence),
                profile == null ? Map.of() : profile,
                Boolean.TRUE.equals(includeAnswered),
                limit));
    }

    /**
     * The facts to plan against: the session's own facts (when a session is given), each treated as
     * confirmed, overlaid by any facts the caller supplied directly.
     */
    private Map<String, Object> sessionAndSuppliedFacts(String sessionId, Map<String, Object> suppliedFacts) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (!ToolSupport.isBlank(sessionId)) {
            graph.sessionFacts(SessionId.of(sessionId)).forEach((path, value) -> merged.put(path, confirmed(value)));
        }
        if (suppliedFacts != null) {
            merged.putAll(suppliedFacts);
        }
        return merged;
    }

    private static Map<String, Object> confirmed(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        out.put(STATUS_KEY, "confirmed");
        return out;
    }

    /** Evidence items are either a bare document type ("form_w2") or an object describing one. */
    private static List<EvidenceState> evidenceStates(List<Object> evidence) {
        if (evidence == null) {
            return List.of();
        }
        List<EvidenceState> states = new ArrayList<>();
        for (Object item : evidence) {
            if (item instanceof String documentType) {
                states.add(new EvidenceState(documentType, Map.of()));
            } else if (item instanceof Map<?, ?> described) {
                describedEvidence(described).ifPresent(states::add);
            }
        }
        return states;
    }

    /**
     * An evidence object names its document type under {@code document_type} (or {@code documentType});
     * every other field is an attribute of that document. An object naming no type is ignored.
     */
    @SuppressWarnings("unchecked")
    private static Optional<EvidenceState> describedEvidence(Map<?, ?> described) {
        Map<String, Object> attributes = new LinkedHashMap<>((Map<String, Object>) described);
        String documentType = (String) attributes.remove("document_type");
        if (documentType == null) {
            documentType = (String) attributes.remove("documentType");
        }
        return documentType == null ? Optional.empty() : Optional.of(new EvidenceState(documentType, attributes));
    }
}

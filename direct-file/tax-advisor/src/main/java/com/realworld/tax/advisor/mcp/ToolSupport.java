package com.realworld.tax.advisor.mcp;

import com.realworld.tax.advisor.graph.PersisterTypes;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService;
import org.springframework.stereotype.Component;

/**
 * What the MCP tool classes share: reading and writing a session's facts by path, the
 * provisional-constants warning every result carries, and the handful of input/result conventions
 * (blank means zero, result strings are never null) that keep the tools' wire format uniform.
 */
@Component
class ToolSupport {

    /** Shared description for the {@code sessionId} tool parameter (returned by {@code create_session}). */
    static final String SESSION_ID_DESCRIPTION = "Planning session id from create_session.";

    /** {@code status} of a tool result that computed normally. */
    static final String STATUS_OK = "ok";

    /** {@code status} of a tool result asking the agent to collect or compute facts first. */
    static final String STATUS_NEEDS_FACTS = "needs_facts";

    private final PlanningGraphService graph;
    private final TaxKnowledgeService taxKnowledge;

    ToolSupport(PlanningGraphService graph, TaxKnowledgeService taxKnowledge) {
        this.graph = graph;
        this.taxKnowledge = taxKnowledge;
    }

    Object value(SessionId sessionId, String path) {
        return graph.readFact(sessionId, path).value();
    }

    boolean isComplete(SessionId sessionId, String path) {
        return graph.readFact(sessionId, path).complete();
    }

    void writeDollar(SessionId sessionId, String path, String amount) {
        graph.writeFact(sessionId, path, PersisterTypes.DOLLAR, dollarOrZero(amount));
    }

    /** Optional inputs are zero-defaulted at session creation; only overwrite when a value is supplied. */
    void writeDollarIfSupplied(SessionId sessionId, String path, String amount) {
        if (!isBlank(amount)) {
            writeDollar(sessionId, path, amount);
        }
    }

    /** The year's provisional-constants warning, or empty when the year is finalized. */
    String provisionalWarning(int taxYear) {
        return nullToEmpty(taxKnowledge.provisionalWarning(taxYear));
    }

    /** Dollar inputs are written as strings (DollarWrapper reads a string); blank means $0. */
    static String dollarOrZero(String v) {
        return isBlank(v) ? "0" : v.trim();
    }

    /** Tool result strings must be non-null for MCP output-schema conformance (required: string). */
    static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}

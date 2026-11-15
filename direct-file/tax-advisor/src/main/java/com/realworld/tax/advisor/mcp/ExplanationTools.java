package com.realworld.tax.advisor.mcp;

import java.util.List;

import com.realworld.tax.advisor.citation.CitationService;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.PlanningGraphService.ReadResult;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService.Citation;
import com.realworld.tax.advisor.report.PlanReportService;
import com.realworld.tax.advisor.report.PlanReportService.PlanReport;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import static com.realworld.tax.advisor.mcp.ToolSupport.SESSION_ID_DESCRIPTION;
import static com.realworld.tax.advisor.mcp.ToolSupport.STATUS_OK;

/**
 * MCP tools that justify a figure rather than compute one: {@code explain} (how a fact was derived),
 * {@code cite} (the legal authority behind it) and {@code export_plan} (the sealed, taxpayer-held report).
 *
 * <p>See the package documentation for the wire-format conventions every tool result follows.
 */
@Component
public class ExplanationTools {

    private final PlanningGraphService graph;

    private final CitationService citationService;

    private final PlanReportService reportService;

    public ExplanationTools(
            PlanningGraphService graph, CitationService citationService, PlanReportService reportService) {
        this.graph = graph;
        this.citationService = citationService;
        this.reportService = reportService;
    }

    @McpTool(
            name = "explain",
            description = "Explain how a fact's value was derived: returns the fact's name, "
                    + "description, computed value, and the values of every fact it directly "
                    + "depends on. Useful for grounding 'why is my number X?' answers.",
            generateOutputSchema = true)
    public PlanningGraphService.ExplainResult explain(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(description = "Fact path to explain.") String path) {
        SessionId session = SessionId.of(sessionId);
        return graph.explain(session, path);
    }

    @McpTool(
            generateOutputSchema = true,
            name = "cite",
            description = "Explain the legal basis for a fact: returns the authorities (IRC sections, "
                    + "IRS forms/instructions/publications) behind the fact's value, each with a formal "
                    + "citation, an official link, and a friendly plain-language explanation. Use to "
                    + "answer 'why / on what basis is this number what it is?'. Authorities are derived "
                    + "from the fact's actual computation, so a result cites every statutory constant it "
                    + "depends on.")
    public CiteResult cite(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(
                            description =
                                    "Fact path to explain the basis for, e.g. /seTax or /planning/safeHarborTarget.")
                    String path) {
        SessionId session = SessionId.of(sessionId);
        int taxYear = graph.taxYearOf(session);
        ReadResult value = graph.readFact(session, path);

        List<Authority> authorities = citationService.citationsForFact(taxYear, path).stream()
                .map(Authority::from)
                .toList();
        String note = authorities.isEmpty()
                ? "No statutory authority is attached to this value — it is either a raw input you provided"
                        + " or pure arithmetic with no tax-law constant behind it."
                : "";
        return new CiteResult(STATUS_OK, path, value.value(), authorities, note);
    }

    /** One legal authority behind a fact (a resolved citation), all strings non-null. */
    public record Authority(
            String sourceId, String authority, String citation, String title, String url, String plainLanguage) {
        static Authority from(Citation c) {
            return new Authority(
                    ToolSupport.nullToEmpty(c.sourceId()),
                    ToolSupport.nullToEmpty(c.authority()),
                    ToolSupport.nullToEmpty(c.citation()),
                    ToolSupport.nullToEmpty(c.title()),
                    ToolSupport.nullToEmpty(c.url()),
                    ToolSupport.nullToEmpty(c.plainLanguage()));
        }
    }

    /** Structured result of {@code cite}. */
    public record CiteResult(String status, String path, Object value, List<Authority> authorities, String note) {}

    @McpTool(
            name = "export_plan",
            description = "Generate a self-contained, printable planning summary for the session and "
                    + "return it as Markdown sealed with a SHA-256 content hash. The summary lists the "
                    + "taxpayer's self-reported inputs, the year's statutory parameters with their IRS "
                    + "sources, and each computed result with its one-level derivation. By default it also "
                    + "cites the legal authority behind each result and appends a plain-language 'Sources' "
                    + "section; pass includeCitations=false to omit citations. The service stores nothing — "
                    + "this artifact is the taxpayer's own record to save or print; hand the Markdown to the "
                    + "user verbatim. Use after the relevant figures have been computed.",
            generateOutputSchema = true)
    public ExportPlanResult exportPlan(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(
                            description = "Include legal citations and a plain-language Sources section. "
                                    + "Defaults to true.",
                            required = false)
                    Boolean includeCitations) {
        SessionId session = SessionId.of(sessionId);
        PlanReport report = reportService.generate(session, includeCitations == null || includeCitations);
        return new ExportPlanResult(
                STATUS_OK,
                report.taxYear(),
                report.generatedAt().toString(),
                "SHA-256",
                report.sha256(),
                report.markdown(),
                "This report is not stored by the service. Give the reportMarkdown to the taxpayer to keep;"
                        + " the sha256 lets them later confirm the document was not altered.");
    }

    /** Structured result of {@code export_plan} (all fields non-null for output-schema conformance). */
    public record ExportPlanResult(
            String status,
            int taxYear,
            String generatedAt,
            String hashAlgorithm,
            String sha256,
            String reportMarkdown,
            String note) {}
}

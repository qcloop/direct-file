package gov.irs.directfile.planservice.mcp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import gov.irs.directfile.planservice.citation.CitationService;
import gov.irs.directfile.planservice.graph.PlanningGraphService;
import gov.irs.directfile.planservice.graph.PlanningGraphService.ReadResult;
import gov.irs.directfile.planservice.knowledge.TaxKnowledgeService;
import gov.irs.directfile.planservice.knowledge.TaxKnowledgeService.Citation;
import gov.irs.directfile.planservice.report.PlanReportService;
import gov.irs.directfile.planservice.report.PlanReportService.PlanReport;

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
    private static final String RATIONAL_WRAPPER = WRAPPER_PREFIX + "RationalWrapper";

    private static final Map<String, String> TYPE_ALIASES = Map.of(
            "dollar", DOLLAR_WRAPPER,
            "int", INT_WRAPPER,
            "boolean", WRAPPER_PREFIX + "BooleanWrapper",
            "string", WRAPPER_PREFIX + "StringWrapper",
            "day", WRAPPER_PREFIX + "DayWrapper",
            "enum", WRAPPER_PREFIX + "EnumWrapper",
            "ein", WRAPPER_PREFIX + "EinWrapper",
            "tin", WRAPPER_PREFIX + "TinWrapper");

    /** Shared description for the {@code sessionId} tool parameter (returned by {@code create_session}). */
    private static final String SESSION_ID_DESCRIPTION = "Planning session id from create_session.";

    /** Key for the status field returned by the tool responses. */
    private static final String STATUS_KEY = "status";

    /** Key for the free-text note field returned by several tool responses. */
    private static final String NOTE_KEY = "note";

    /** Self-employment tax fact path, read by several tools. */
    private static final String SE_TAX_PATH = "/seTax";

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
    private final PlanReportService reportService;
    private final CitationService citationService;

    public PlanningTools(
            PlanningGraphService graph,
            TaxKnowledgeService taxKnowledge,
            PlanReportService reportService,
            CitationService citationService) {
        this.graph = graph;
        this.taxKnowledge = taxKnowledge;
        this.reportService = reportService;
        this.citationService = citationService;
    }

    @Tool(
            name = "create_session",
            description = "Create a new in-memory planning session for a tax year and return its id. "
                    + "The session is seeded with that year's indexed tax constants (standard mileage "
                    + "rate, Social Security wage base) from tax-knowledge; if a year has no published "
                    + "constants the call fails rather than silently using another year's rates. All "
                    + "subsequent tool calls require this id. Sessions are not persisted and live only "
                    + "for the duration of the server process — if a later call reports an "
                    + "unknown/expired session, call this again and re-enter the facts.")
    public Map<String, Object> createSession(
            @ToolParam(
                            description = "Tax year to plan for, e.g. \"2025\". Defaults to the current calendar year.",
                            required = false)
                    String taxYear) {
        int year = (taxYear == null || taxYear.isBlank())
                ? java.time.Year.now().getValue()
                : Integer.parseInt(taxYear.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", graph.createSession(year));
        out.put("taxYear", year);
        addProvisionalWarning(out, year);
        return out;
    }

    /**
     * Attach a {@code provisional_warning} to a tool response when the session's tax year carries
     * provisional (draft, unverified) constants, so the agent never presents those numbers as final.
     */
    private void addProvisionalWarning(Map<String, Object> out, int taxYear) {
        String warning = taxKnowledge.provisionalWarning(taxYear);
        if (warning != null) {
            out.put("provisional_warning", warning);
        }
    }

    @Tool(
            name = "get_fact",
            description = "Read a single fact from a planning session's fact graph. Returns the "
                    + "current value if computable, or a 'not yet computable' note listing why.")
    public ReadResult getFact(
            @ToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @ToolParam(description = "Fact path, e.g. /seTax or /planning/safeHarborTarget.") String path) {
        return graph.readFact(sessionId, path);
    }

    @Tool(
            name = "set_fact",
            description = "Write a value to a writable fact in a planning session. Specify the "
                    + "type via 'type' (short alias: dollar/int/boolean/string/day/enum/ein/tin) "
                    + "or 'typeCode' (full Scala class name). Optionally pass 'source' to record "
                    + "where the value came from as stated by the taxpayer. Returns whether the "
                    + "write was accepted by the fact graph's validation pass.")
    public PlanningGraphService.WriteResult setFact(
            @ToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
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
            @ToolParam(
                            description = "Value to write, as a string: e.g. \"608\" (dollar), "
                                    + "\"2025-04-15\" (day), \"true\" (boolean).")
                    String value,
            @ToolParam(
                            description = "Optional free-text provenance as stated by the taxpayer, e.g. "
                                    + "\"2024 1099-NEC box 1, Uber\". Recorded for the export report and "
                                    + "shown there as self-reported; it is not verified and never enters "
                                    + "the tax math.",
                            required = false)
                    String source) {

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

        // The MCP client sends the value as a string; convert it to the JSON shape each persister
        // wrapper expects. DollarWrapper and the other string-backed wrappers read a string as-is;
        // IntWrapper expects a JSON number and BooleanWrapper a JSON boolean.
        Object shaped = value;
        if (INT_WRAPPER.equals(resolvedTypeCode)) {
            shaped = Long.parseLong(value.trim());
        } else if ((WRAPPER_PREFIX + "BooleanWrapper").equals(resolvedTypeCode)) {
            shaped = Boolean.parseBoolean(value.trim());
        }

        PlanningGraphService.WriteResult result = graph.writeFact(sessionId, path, resolvedTypeCode, shaped);
        graph.setSourceNote(sessionId, path, source);
        return result;
    }

    @Tool(
            name = "calculate_se_tax",
            description = "Compute self-employment tax (Schedule SE) for a planning session from gross "
                    + "receipts and business expenses. Returns net profit, net earnings (92.35% of "
                    + "profit), the Social Security portion (capped at the annual wage base and reduced "
                    + "by any W-2 Social Security wages), the Medicare portion, total SE tax, and the "
                    + "deductible half. Use this to build the projected current-year tax that "
                    + "estimate_quarterly_payment needs.")
    public Map<String, Object> calculateSeTax(
            @ToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @ToolParam(description = "Gross self-employment receipts (Schedule C line 1), as a string, e.g. \"24000\".")
                    String grossReceipts,
            @ToolParam(
                            description =
                                    "Business miles driven (standard-mileage method), as a whole number. Default 0.",
                            required = false)
                    String businessMiles,
            @ToolParam(description = "Platform/commission fees withheld, as a string. Default 0.", required = false)
                    String platformFees,
            @ToolParam(description = "Supplies and other business expenses, as a string. Default 0.", required = false)
                    String suppliesAndOther,
            @ToolParam(
                            description = "Social Security wages already reported on W-2s this year, as a string. "
                                    + "Default 0.",
                            required = false)
                    String socialSecurityWagesFromW2) {

        // Populate the Schedule C / SE inputs; the fact graph derives the rest. These are full-year
        // actuals, so the annualization factor is 1/1 (no projection) — see project_net_profit for the
        // year-to-date path.
        graph.writeFact(sessionId, "/seGrossReceipts", DOLLAR_WRAPPER, dollarOrZero(grossReceipts));
        graph.writeFact(sessionId, "/seVehicleBusinessMiles", INT_WRAPPER, longOrZero(businessMiles));
        graph.writeFact(sessionId, "/sePlatformFees", DOLLAR_WRAPPER, dollarOrZero(platformFees));
        graph.writeFact(sessionId, "/seSuppliesAndOther", DOLLAR_WRAPPER, dollarOrZero(suppliesAndOther));
        graph.writeFact(
                sessionId, "/seSocialSecurityWagesFromW2", DOLLAR_WRAPPER, dollarOrZero(socialSecurityWagesFromW2));
        graph.writeFact(sessionId, "/seAnnualizationFactor", RATIONAL_WRAPPER, Map.of("n", 1, "d", 1));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(STATUS_KEY, "ok");
        // Surface the tax year and the rate actually used, so a wrong-year session (e.g. planning
        // 2026 on a 2025 session) is visible rather than silently using stale constants.
        out.put("tax_year", graph.taxYearOf(sessionId));
        out.put("standard_mileage_rate", graph.readDecimal(sessionId, "/standardMileageRate"));
        out.put("net_profit", graph.readFact(sessionId, "/seNetProfit").value());
        out.put(
                "net_earnings_from_se",
                graph.readFact(sessionId, "/seNetEarnings").value());
        out.put(
                "social_security_portion",
                graph.readFact(sessionId, "/seSocialSecurityTax").value());
        out.put("medicare_portion", graph.readFact(sessionId, "/seMedicareTax").value());
        out.put("self_employment_tax", graph.readFact(sessionId, SE_TAX_PATH).value());
        out.put(
                "deductible_half",
                graph.readFact(sessionId, "/deductibleHalfOfSETax").value());
        out.put("explanation_tree", graph.explain(sessionId, SE_TAX_PATH));
        addProvisionalWarning(out, graph.taxYearOf(sessionId));
        return out;
    }

    @Tool(
            name = "project_net_profit",
            description = "Project full-year self-employment net profit (and SE tax) from year-to-date "
                    + "raw numbers. Give the receipts, miles, fees, and supplies accumulated so far this "
                    + "year plus the asOfDate; the tool annualizes them straight-line (× 12 ÷ months of "
                    + "data) and runs the same Schedule C/SE math. Returns projected gross receipts, net "
                    + "profit, net earnings, and SE tax with a derivation chain. Assumes income is even "
                    + "across the year — for seasonal income or a known full-year figure, use "
                    + "calculate_se_tax with full-year numbers instead.")
    public Map<String, Object> projectNetProfit(
            @ToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @ToolParam(
                            description = "ISO date (YYYY-MM-DD) the year-to-date figures run through. Required "
                                    + "because the fact graph has no clock; it sets how many months to annualize from.")
                    String asOfDate,
            @ToolParam(description = "Year-to-date gross self-employment receipts, as a string, e.g. \"21000\".")
                    String ytdGrossReceipts,
            @ToolParam(
                            description = "Year-to-date business miles driven, as a whole number. Default 0.",
                            required = false)
                    String ytdBusinessMiles,
            @ToolParam(description = "Year-to-date platform/commission fees, as a string. Default 0.", required = false)
                    String ytdPlatformFees,
            @ToolParam(
                            description = "Year-to-date supplies and other business expenses, as a string. Default 0.",
                            required = false)
                    String ytdSuppliesAndOther,
            @ToolParam(
                            description = "Social Security wages already reported on W-2s this year, as a string. "
                                    + "Not annualized. Default 0.",
                            required = false)
                    String socialSecurityWagesFromW2) {

        int taxYear = graph.taxYearOf(sessionId);
        int monthsElapsed = monthsElapsedInTaxYear(taxYear, LocalDate.parse(asOfDate));

        // Write the year-to-date figures into the same Schedule C inputs, then set the annualization
        // factor so the graph projects them to a full year (12 / months of data).
        graph.writeFact(sessionId, "/seGrossReceipts", DOLLAR_WRAPPER, dollarOrZero(ytdGrossReceipts));
        graph.writeFact(sessionId, "/seVehicleBusinessMiles", INT_WRAPPER, longOrZero(ytdBusinessMiles));
        graph.writeFact(sessionId, "/sePlatformFees", DOLLAR_WRAPPER, dollarOrZero(ytdPlatformFees));
        graph.writeFact(sessionId, "/seSuppliesAndOther", DOLLAR_WRAPPER, dollarOrZero(ytdSuppliesAndOther));
        graph.writeFact(
                sessionId, "/seSocialSecurityWagesFromW2", DOLLAR_WRAPPER, dollarOrZero(socialSecurityWagesFromW2));
        graph.writeFact(sessionId, "/seMonthsElapsed", INT_WRAPPER, (long) monthsElapsed);
        graph.writeFact(sessionId, "/seAnnualizationFactor", RATIONAL_WRAPPER, Map.of("n", 12, "d", monthsElapsed));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(STATUS_KEY, "ok");
        out.put("tax_year", taxYear);
        out.put("standard_mileage_rate", graph.readDecimal(sessionId, "/standardMileageRate"));
        out.put("months_elapsed", monthsElapsed);
        out.put("annualization_factor", "12/" + monthsElapsed);
        out.put("ytd_gross_receipts", dollarOrZero(ytdGrossReceipts));
        out.put(
                "projected_gross_receipts",
                graph.readFact(sessionId, "/seEffectiveGrossReceipts").value());
        out.put(
                "projected_net_profit",
                graph.readFact(sessionId, "/seNetProfit").value());
        out.put(
                "projected_net_earnings_from_se",
                graph.readFact(sessionId, "/seNetEarnings").value());
        out.put(
                "projected_self_employment_tax",
                graph.readFact(sessionId, SE_TAX_PATH).value());
        out.put(
                "deductible_half",
                graph.readFact(sessionId, "/deductibleHalfOfSETax").value());
        out.put(
                NOTE_KEY,
                "Projected figures annualize your year-to-date numbers assuming income is even across "
                        + "the year (straight-line). Seasonal income will differ. This projects "
                        + "self-employment tax only; it does not include income tax.");
        out.put("explanation_tree", graph.explain(sessionId, "/seNetProfit"));
        addProvisionalWarning(out, taxYear);
        return out;
    }

    @Tool(
            name = "explain",
            description = "Explain how a fact's value was derived: returns the fact's name, "
                    + "description, computed value, and the values of every fact it directly "
                    + "depends on. Useful for grounding 'why is my number X?' answers.")
    public PlanningGraphService.ExplainResult explain(
            @ToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @ToolParam(description = "Fact path to explain.") String path) {
        return graph.explain(sessionId, path);
    }

    @Tool(
            name = "cite",
            description = "Explain the legal basis for a fact: returns the authorities (IRC sections, "
                    + "IRS forms/instructions/publications) behind the fact's value, each with a formal "
                    + "citation, an official link, and a friendly plain-language explanation. Use to "
                    + "answer 'why / on what basis is this number what it is?'. Authorities are derived "
                    + "from the fact's actual computation, so a result cites every statutory constant it "
                    + "depends on.")
    public Map<String, Object> cite(
            @ToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @ToolParam(description = "Fact path to explain the basis for, e.g. /seTax or /planning/safeHarborTarget.")
                    String path) {
        int taxYear = graph.taxYearOf(sessionId);
        ReadResult value = graph.readFact(sessionId, path);
        List<Citation> found = citationService.citationsForFact(taxYear, path);

        List<Map<String, Object>> authorities = new ArrayList<>();
        for (Citation c : found) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("source_id", c.sourceId());
            a.put("authority", c.authority());
            a.put("citation", c.citation());
            a.put("title", c.title());
            a.put("url", c.url());
            a.put("plain_language", c.plainLanguage());
            authorities.add(a);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(STATUS_KEY, "ok");
        out.put("path", path);
        out.put("value", value.value());
        out.put("authorities", authorities);
        if (authorities.isEmpty()) {
            out.put(
                    NOTE_KEY,
                    "No statutory authority is attached to this value — it is either a raw input you "
                            + "provided or pure arithmetic with no tax-law constant behind it.");
        }
        return out;
    }

    @Tool(
            name = "export_plan",
            description = "Generate a self-contained, printable planning summary for the session and "
                    + "return it as Markdown sealed with a SHA-256 content hash. The summary lists the "
                    + "taxpayer's self-reported inputs, the year's statutory parameters with their IRS "
                    + "sources, and each computed result with its one-level derivation. By default it also "
                    + "cites the legal authority behind each result and appends a plain-language 'Sources' "
                    + "section; pass includeCitations=false to omit citations. The service stores nothing — "
                    + "this artifact is the taxpayer's own record to save or print; hand the Markdown to the "
                    + "user verbatim. Use after the relevant figures have been computed.")
    public Map<String, Object> exportPlan(
            @ToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @ToolParam(
                            description = "Include legal citations and a plain-language Sources section. "
                                    + "Defaults to true.",
                            required = false)
                    Boolean includeCitations) {
        PlanReport report = reportService.generate(sessionId, includeCitations == null || includeCitations);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(STATUS_KEY, "ok");
        out.put("tax_year", report.taxYear());
        out.put("generated_at", report.generatedAt());
        out.put("hash_algorithm", "SHA-256");
        out.put("sha256", report.sha256());
        out.put("report_markdown", report.markdown());
        out.put(
                NOTE_KEY,
                "This report is not stored by the service. Give the report_markdown to the taxpayer to "
                        + "keep; the sha256 lets them later confirm the document was not altered.");
        return out;
    }

    @Tool(
            name = "estimate_quarterly_payment",
            description = "Compute the next federal estimated tax payment for a self-employed "
                    + "taxpayer using IRC §6654 safe-harbor logic (lesser of 100%/110% prior-year "
                    + "tax or 90% projected current-year tax). Returns the recommended payment, "
                    + "next deadline, and a derivation chain. If required facts are missing, "
                    + "returns a 'needs_facts' response so the agent can gather them.")
    public Map<String, Object> estimateQuarterlyPayment(
            @ToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
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
            out.put(STATUS_KEY, "needs_facts");
            out.put("missing_facts", missing);
            out.put("hint", "Ask the user for these values, write each via set_fact, then call this tool again.");
            return out;
        }

        // 2. Resolve the next deadline and remaining quarters for the session's tax year
        // (asOf only determines which of that year's deadlines are still ahead).
        List<Deadline> deadlines = deadlinesForTaxYear(graph.taxYearOf(sessionId));
        Deadline next = deadlines.stream()
                .filter(d -> !d.due.isBefore(asOf))
                .findFirst()
                .orElse(deadlines.get(deadlines.size() - 1));
        long remainingQuarters =
                deadlines.stream().filter(d -> !d.due.isBefore(asOf)).count();
        if (remainingQuarters == 0) {
            remainingQuarters = 1; // past the final (Jan 15) deadline — recommend a catch-up payment
        }

        graph.writeFact(sessionId, "/planning/remainingQuarters", INT_WRAPPER, remainingQuarters);

        // 3. Read the derived recommendation + supporting facts.
        ReadResult suggested = graph.readFact(sessionId, "/planning/nextQuarterlyPaymentSuggested");
        ReadResult safeHarbor = graph.readFact(sessionId, "/planning/safeHarborTarget");
        ReadResult ytdApplied = graph.readFact(sessionId, "/planning/ytdPaymentsApplied");
        ReadResult remainingDue = graph.readFact(sessionId, "/planning/remainingPaymentDue");
        ReadResult seTax = graph.readFact(sessionId, SE_TAX_PATH);
        ReadResult highIncomeRule = graph.readFact(sessionId, "/planning/highIncomeSafeHarborApplies");
        ReadResult projectedTax = graph.readFact(sessionId, "/planning/projectedCurrentYearTax");
        ReadResult balanceDueAtFiling = graph.readFact(sessionId, "/planning/projectedBalanceDueAtFiling");

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
        out.put(STATUS_KEY, "ok");
        out.put("recommended_payment", suggested.value());
        out.put("next_deadline", next.due.toString());
        out.put("quarter", next.quarter);
        // Keep the two questions distinct: the safe-harbor recommendation ("least to prepay to
        // avoid the penalty") is not the same as what's actually owed for the year.
        out.put("projected_total_tax", projectedTax.value());
        out.put("projected_balance_due_at_filing", balanceDueAtFiling.value());
        out.put(
                NOTE_KEY,
                "recommended_payment is the safe-harbor minimum that avoids the IRC §6654 "
                        + "underpayment penalty; it does NOT settle the year's tax. "
                        + "projected_balance_due_at_filing is what would still be owed at filing if no "
                        + "further payments are made beyond those already counted (negative = refund).");
        out.put("derivation", derivation);
        out.put("explanation_tree", graph.explain(sessionId, "/planning/nextQuarterlyPaymentSuggested"));
        out.put("payment_url", "https://www.irs.gov/payments/direct-pay");
        addProvisionalWarning(out, graph.taxYearOf(sessionId));
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
        out.put(STATUS_KEY, "confirmed");
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

    /**
     * Federal individual estimated-tax deadlines (IRC §6654) for {@code taxYear}: the Apr 15 / Jun 15 /
     * Sep 15 installments in that year and the Jan 15 installment in the next. Derived from the tax year
     * (which the session is created with) rather than hard-coded, and pushed to the next Monday when the
     * 15th lands on a weekend (federal-holiday shifts, e.g. Emancipation Day, are not modeled — treat
     * these as planning dates, not authoritative filing dates).
     */
    private static List<Deadline> deadlinesForTaxYear(int taxYear) {
        return List.of(
                new Deadline("Q1", weekdayOnOrAfter(LocalDate.of(taxYear, 4, 15))),
                new Deadline("Q2", weekdayOnOrAfter(LocalDate.of(taxYear, 6, 15))),
                new Deadline("Q3", weekdayOnOrAfter(LocalDate.of(taxYear, 9, 15))),
                new Deadline("Q4", weekdayOnOrAfter(LocalDate.of(taxYear + 1, 1, 15))));
    }

    /**
     * Months of {@code taxYear} elapsed as of {@code asOf} (1–12), used as the annualization basis.
     * The date must fall <em>within</em> the session's tax year. A date outside it is rejected rather
     * than clamped: clamping a later-year date to 12 months would silently turn the projection into a
     * no-op (factor 12/12 = 1) and return a full-year-sized number to a year-to-date user — a wrong
     * headline figure with no failure signal. For full-year actuals, use {@code calculate_se_tax}.
     */
    private static int monthsElapsedInTaxYear(int taxYear, LocalDate asOf) {
        if (asOf.getYear() != taxYear) {
            throw new IllegalArgumentException("asOfDate " + asOf + " is not within tax year " + taxYear
                    + ". project_net_profit annualizes year-to-date figures inside the planning year, so the "
                    + "date must fall in " + taxYear + ". Create the session for the year you are planning, or "
                    + "use calculate_se_tax if you already have full-year figures.");
        }
        return asOf.getMonthValue();
    }

    /** Push a date to the following Monday if it falls on a weekend. */
    private static LocalDate weekdayOnOrAfter(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case SATURDAY -> d.plusDays(2);
            case SUNDAY -> d.plusDays(1);
            default -> d;
        };
    }

    /** Dollar inputs are written as strings (DollarWrapper reads a string); blank means $0. */
    private static String dollarOrZero(String v) {
        return (v == null || v.isBlank()) ? "0" : v.trim();
    }

    /** Int inputs (e.g. miles) are written as a JSON number; blank means 0. */
    private static long longOrZero(String v) {
        return (v == null || v.isBlank()) ? 0L : Long.parseLong(v.trim());
    }

    private record Deadline(String quarter, LocalDate due) {}

    private record RequiredFact(String path, String prompt) {}
}

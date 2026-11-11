package com.realworld.tax.advisor.mcp;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.realworld.tax.advisor.citation.CitationService;
import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.FilingStatus;
import com.realworld.tax.advisor.graph.PersisterTypes;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.PlanningGraphService.ReadResult;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService.Citation;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService.EvidenceState;
import com.realworld.tax.advisor.report.PlanReportService;
import com.realworld.tax.advisor.report.PlanReportService.PlanReport;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI MCP tools exposed by tax-advisor.
 *
 * <p>Every method is annotated with {@code @McpTool} and is auto-registered with the MCP server by
 * Spring AI's annotation scanner. {@code generateOutputSchema = true} makes the server publish an MCP
 * {@code outputSchema} for each tool and return matching {@code structuredContent}; tools therefore
 * return typed records (not {@code Map}). plan_questions is the one exception
 * ({@code generateOutputSchema = false}) — its result is an open interview-planning structure whose
 * nested records carry business-significant null fields, so it is left without a strict schema.
 *
 * <p>Wire-format note: both the input parameter names and the output record field names are the Java
 * camelCase identifiers ({@code sessionId}, {@code filingStatus}). No {@code @JsonProperty} renames —
 * Spring AI's schema generator ignores them, which would desync the schema from the serialized
 * content. Result records keep all descriptive strings non-null (empty when absent) and type
 * heterogeneous/nullable fact values as {@code Object} so the generated schema validates.
 */
@Component
public class PlanningTools {

    /** Shared description for the {@code sessionId} tool parameter (returned by {@code create_session}). */
    private static final String SESSION_ID_DESCRIPTION = "Planning session id from create_session.";

    /** {@code status} of a tool result that computed normally. */
    private static final String STATUS_OK = "ok";

    /** {@code status} of a tool result asking the agent to collect or compute facts first. */
    private static final String STATUS_NEEDS_FACTS = "needs_facts";

    /** Key for the status field returned in the plan_questions confirmed-fact wrapper. */
    private static final String STATUS_KEY = "status";

    /** Taxpayer-supplied facts estimate_quarterly_payment cannot run without, each with the prompt to ask. */
    private static final List<MissingFact> REQUIRED_FACTS = List.of(
            new MissingFact(
                    FactPaths.PRIOR_YEAR_TOTAL_TAX,
                    "Total federal tax from your prior-year return (Form 1040, Line 24)."),
            new MissingFact(
                    FactPaths.PRIOR_YEAR_AGI,
                    "Adjusted gross income from your prior-year return (Form 1040, Line 11)."),
            new MissingFact(
                    FactPaths.YTD_WITHHOLDING,
                    "Federal income tax already withheld by employers or platforms so far this year."),
            new MissingFact(
                    FactPaths.YTD_ESTIMATED_PAYMENTS_MADE,
                    "Quarterly estimated tax payments you've already made this year."));

    // Projected current-year total tax is no longer an agent-supplied required fact — it is derived
    // (income tax + SE tax + Additional Medicare). But it only computes once the Schedule C inputs are
    // set, so the quarterly tool checks it is complete and, if not, points the agent to run the income
    // projection first rather than silently treating a missing total as zero.
    private static final MissingFact PROJECTED_TAX_PREREQUISITE = new MissingFact(
            FactPaths.PROJECTED_TOTAL_TAX,
            "Run project_total_tax (or project_net_profit / calculate_se_tax) first so the projected"
                    + " current-year total tax can be computed from your income — it is no longer entered by hand.");

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
                            description = "Tax year to plan for, e.g. \"2025\". Defaults to the current calendar year.",
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
                                    + " Head-of-household and qualifying surviving spouse use the 'single'"
                                    + " thresholds for these provisions.",
                            required = false)
                    String filingStatus) {
        int year = parseTaxYear(taxYear);
        String sessionId = graph.createSession(year, filingStatus);
        return new CreateSessionResult(sessionId, year, graph.filingStatusOf(sessionId), provisionalWarning(year));
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
    public record CreateSessionResult(String sessionId, int taxYear, String filingStatus, String provisionalWarning) {}

    @McpTool(
            name = "get_fact",
            description = "Read a single fact from a planning session's fact graph. Returns the "
                    + "current value if computable, or a 'not yet computable' note listing why.",
            generateOutputSchema = true)
    public ReadResult getFact(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(description = "Fact path, e.g. /seTax or /planning/safeHarborTarget.") String path) {
        return graph.readFact(sessionId, path);
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

        String resolvedTypeCode = PersisterTypes.resolve(type, typeCode);
        PlanningGraphService.WriteResult result =
                graph.writeFact(sessionId, path, resolvedTypeCode, PersisterTypes.shape(resolvedTypeCode, value));
        graph.setSourceNote(sessionId, path, source);
        return result;
    }

    @McpTool(
            generateOutputSchema = true,
            name = "calculate_se_tax",
            description = "Compute self-employment tax (Schedule SE) for a planning session from gross "
                    + "receipts and business expenses. Returns net profit, net earnings (92.35% of "
                    + "profit), the Social Security portion (capped at the annual wage base and reduced "
                    + "by any W-2 Social Security wages), the Medicare portion, total SE tax, and the "
                    + "deductible half. Use this to build the projected current-year tax that "
                    + "estimate_quarterly_payment needs.")
    public SeTaxResult calculateSeTax(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(
                            description =
                                    "Gross self-employment receipts (Schedule C line 1), as a string, e.g. \"24000\".")
                    String grossReceipts,
            @McpToolParam(
                            description =
                                    "Business miles driven (standard-mileage method), as a whole number. Default 0.",
                            required = false)
                    String businessMiles,
            @McpToolParam(description = "Platform/commission fees withheld, as a string. Default 0.", required = false)
                    String platformFees,
            @McpToolParam(
                            description = "Supplies and other business expenses, as a string. Default 0.",
                            required = false)
                    String suppliesAndOther,
            @McpToolParam(
                            description = "Social Security wages already reported on W-2s this year, as a string. "
                                    + "Default 0.",
                            required = false)
                    String socialSecurityWagesFromW2) {

        // Populate the Schedule C / SE inputs; the fact graph derives the rest. These are full-year
        // actuals, so the annualization factor is 1/1 (no projection) — see project_net_profit for the
        // year-to-date path.
        writeScheduleCInputs(
                sessionId,
                new ScheduleCInputs(
                        grossReceipts, businessMiles, platformFees, suppliesAndOther, socialSecurityWagesFromW2));
        graph.writeFact(
                sessionId, FactPaths.SE_ANNUALIZATION_FACTOR, PersisterTypes.RATIONAL, PersisterTypes.rational(1, 1));

        // Surface the tax year and the rate actually used, so a wrong-year session (e.g. planning
        // 2026 on a 2025 session) is visible rather than silently using stale constants.
        int year = graph.taxYearOf(sessionId);
        return new SeTaxResult(
                STATUS_OK,
                year,
                graph.readDecimal(sessionId, FactPaths.STANDARD_MILEAGE_RATE),
                value(sessionId, FactPaths.SE_NET_PROFIT),
                value(sessionId, FactPaths.SE_NET_EARNINGS),
                value(sessionId, FactPaths.SE_SOCIAL_SECURITY_TAX),
                value(sessionId, FactPaths.SE_MEDICARE_TAX),
                value(sessionId, FactPaths.SE_TAX),
                value(sessionId, FactPaths.DEDUCTIBLE_HALF_OF_SE_TAX),
                graph.explain(sessionId, FactPaths.SE_TAX),
                provisionalWarning(year));
    }

    /**
     * Structured result of {@code calculate_se_tax}. Fact values are typed {@code Object} (they may be
     * a dollar number or null when incomplete; {@code Object} maps to a permissive schema), strings are
     * non-null, and the explanation tree is the null-safe {@link PlanningGraphService.ExplainResult}.
     */
    public record SeTaxResult(
            String status,
            int taxYear,
            Object standardMileageRate,
            Object netProfit,
            Object netEarningsFromSe,
            Object socialSecurityPortion,
            Object medicarePortion,
            Object selfEmploymentTax,
            Object deductibleHalf,
            PlanningGraphService.ExplainResult explanationTree,
            String provisionalWarning) {}

    @McpTool(
            generateOutputSchema = true,
            name = "calculate_additional_medicare",
            description = "Compute the 0.9% Additional Medicare Tax (Form 8959) for a planning session. "
                    + "Run calculate_se_tax or project_net_profit first so the session has self-employment "
                    + "net earnings; this tool then adds W-2 Medicare wages (box 5) and applies the session's "
                    + "filing-status threshold ($200K single / $250K MFJ / $125K MFS, set at create_session). "
                    + "The surtax hits combined Medicare wages + SE income above the threshold — wages are "
                    + "counted first, reducing the threshold left for SE income. Returns the wage portion, "
                    + "the self-employment portion, and the total (Form 8959 Line 18).")
    public AdditionalMedicareResult calculateAdditionalMedicare(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(
                            description = "Medicare wages already reported on W-2 box 5 (wages subject to "
                                    + "Medicare), as a string. Default 0 for a purely self-employed taxpayer.",
                            required = false)
                    String medicareWagesFromW2) {
        writeDollar(sessionId, FactPaths.MEDICARE_WAGES_FROM_W2, medicareWagesFromW2);

        // The SE portion needs net earnings from a prior calculate_se_tax / project_net_profit call.
        // If they aren't populated yet, surface a note rather than silently returning a null SE portion.
        ReadResult netEarnings = graph.readFact(sessionId, FactPaths.SE_NET_EARNINGS);
        String note = netEarnings.complete()
                ? ""
                : "Self-employment net earnings aren't set yet — run calculate_se_tax or project_net_profit"
                        + " first for the SE portion. The wage portion (if any) is still computed.";
        int year = graph.taxYearOf(sessionId);
        return new AdditionalMedicareResult(
                STATUS_OK,
                year,
                graph.filingStatusOf(sessionId),
                value(sessionId, FactPaths.ADDITIONAL_MEDICARE_THRESHOLD),
                value(sessionId, FactPaths.MEDICARE_WAGES_FROM_W2),
                netEarnings.value(),
                value(sessionId, FactPaths.SE_INCOME_SUBJECT_TO_ADDITIONAL_MEDICARE),
                value(sessionId, FactPaths.ADDITIONAL_MEDICARE_TAX_ON_SE),
                value(sessionId, FactPaths.WAGES_SUBJECT_TO_ADDITIONAL_MEDICARE),
                value(sessionId, FactPaths.ADDITIONAL_MEDICARE_TAX_ON_WAGES),
                value(sessionId, FactPaths.ADDITIONAL_MEDICARE_TAX),
                graph.explain(sessionId, FactPaths.ADDITIONAL_MEDICARE_TAX),
                note,
                provisionalWarning(year));
    }

    /** Structured result of {@code calculate_additional_medicare} (Form 8959). */
    public record AdditionalMedicareResult(
            String status,
            int taxYear,
            String filingStatus,
            Object additionalMedicareThreshold,
            Object medicareWagesFromW2,
            Object netEarningsFromSe,
            Object seIncomeOverThreshold,
            Object additionalMedicareTaxOnSe,
            Object wagesOverThreshold,
            Object additionalMedicareTaxOnWages,
            Object additionalMedicareTax,
            PlanningGraphService.ExplainResult explanationTree,
            String note,
            String provisionalWarning) {}

    @McpTool(
            generateOutputSchema = true,
            name = "estimate_qbi_deduction",
            description = "Estimate the 20% Qualified Business Income (QBI) deduction (Form 8995, IRC 199A) "
                    + "for a planning session. Run calculate_se_tax or project_net_profit first so the session "
                    + "has self-employment net profit; for a sole proprietor, QBI is that net profit minus the "
                    + "deductible half of SE tax. The deduction is the lesser of 20% of QBI and 20% of (taxable "
                    + "income minus net capital gains). Taxable income before QBI is now DERIVED by the graph "
                    + "(AGI minus the standard deduction) — you no longer pass it in, so the income cap binds on "
                    + "the computed figure rather than an estimate. This is the SIMPLE Form 8995 method, valid "
                    + "when taxable income is at or below the filing-status threshold; above it, Form 8995-A "
                    + "wage/property limits apply and the result is only an upper bound (flagged in the response).")
    public QbiResult estimateQbiDeduction(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(
                            description = "Net capital gains, including qualified dividends, as a string. "
                                    + "Excluded from the income cap. Optional; defaults to 0 (set at "
                                    + "create_session). Pass a value only to override.",
                            required = false)
                    String netCapitalGains) {
        // Taxable income before QBI is derived (AGI − standard deduction), so this tool no longer writes
        // it. Net capital gains is zero-defaulted at session creation; only overwrite when supplied.
        writeDollarIfSupplied(sessionId, FactPaths.NET_CAPITAL_GAINS, netCapitalGains);

        ReadResult netProfit = graph.readFact(sessionId, FactPaths.SE_NET_PROFIT);
        String note = netProfit.complete()
                ? ""
                : "Self-employment net profit isn't set yet — run calculate_se_tax or project_net_profit"
                        + " first so QBI can be computed from it.";
        ReadResult above = graph.readFact(sessionId, FactPaths.QBI_ABOVE_THRESHOLD);
        String aboveWarning = Boolean.TRUE.equals(above.value())
                ? "Taxable income is above the filing-status threshold for the simple method, so Form 8995-A"
                        + " (specified-service-business, W-2-wage, and qualified-property limits) governs. The"
                        + " qbiDeduction shown is an UPPER BOUND and may be reduced or eliminated."
                : "";
        int year = graph.taxYearOf(sessionId);
        return new QbiResult(
                STATUS_OK,
                year,
                graph.filingStatusOf(sessionId),
                value(sessionId, FactPaths.QUALIFIED_BUSINESS_INCOME),
                value(sessionId, FactPaths.QBI_COMPONENT),
                value(sessionId, FactPaths.QBI_INCOME_LIMIT),
                value(sessionId, FactPaths.QBI_DEDUCTION),
                value(sessionId, FactPaths.QBI_THRESHOLD),
                above.value(),
                aboveWarning,
                graph.explain(sessionId, FactPaths.QBI_DEDUCTION),
                note,
                provisionalWarning(year));
    }

    /** Structured result of {@code estimate_qbi_deduction} (Form 8995). */
    public record QbiResult(
            String status,
            int taxYear,
            String filingStatus,
            Object qualifiedBusinessIncome,
            Object qbiComponent,
            Object qbiIncomeLimit,
            Object qbiDeduction,
            Object qbiThreshold,
            Object aboveThreshold,
            String aboveThresholdWarning,
            PlanningGraphService.ExplainResult explanationTree,
            String note,
            String provisionalWarning) {}

    @McpTool(
            generateOutputSchema = true,
            name = "project_total_tax",
            description = "Project full-year total federal tax for a self-employed planning session: the "
                    + "whole ladder from net profit to AGI to taxable income to income tax, plus SE tax and "
                    + "the Additional Medicare surtax. Run calculate_se_tax or project_net_profit first so the "
                    + "session has self-employment net profit. Income tax is computed in-graph from the year's "
                    + "standard deduction, ordinary brackets, and the QBI deduction — you do NOT estimate any "
                    + "of it. Call this instead of working out total tax yourself; it is also exactly what feeds "
                    + "the projected balance due in estimate_quarterly_payment. Scope: ordinary income only (no "
                    + "credits, capital-gains/qualified-dividend rates, NIIT, or AMT); income besides this "
                    + "Schedule C enters via otherOrdinaryIncome.")
    public TotalTaxResult projectTotalTax(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(
                            description = "Ordinary income other than this self-employment (e.g. W-2 box 1 wages, "
                                    + "interest), as a string. Optional; defaults to 0. Pass a value for a taxpayer "
                                    + "who also has a W-2 or other ordinary income.",
                            required = false)
                    String otherOrdinaryIncome,
            @McpToolParam(
                            description = "Net capital gains incl. qualified dividends, as a string. Optional; "
                                    + "defaults to 0. Excluded from the QBI income cap.",
                            required = false)
                    String netCapitalGains) {
        // Both optional inputs are zero-defaulted at session creation; only overwrite when supplied.
        writeDollarIfSupplied(sessionId, FactPaths.OTHER_TAXABLE_INCOME, otherOrdinaryIncome);
        writeDollarIfSupplied(sessionId, FactPaths.NET_CAPITAL_GAINS, netCapitalGains);

        ReadResult netProfit = graph.readFact(sessionId, FactPaths.SE_NET_PROFIT);
        String incomplete = netProfit.complete()
                ? ""
                : "Self-employment net profit isn't set yet — run calculate_se_tax or project_net_profit"
                        + " first so income tax and total tax can be computed. ";
        String note = incomplete
                + "projectedTotalTax is income tax + SE tax + Additional Medicare surtax. It excludes credits,"
                + " capital-gains/qualified-dividend rates, the NIIT, and AMT, so a return with those will"
                + " differ. Income tax is on taxable income after the standard and QBI deductions.";
        int year = graph.taxYearOf(sessionId);
        return new TotalTaxResult(
                STATUS_OK,
                year,
                graph.filingStatusOf(sessionId),
                value(sessionId, FactPaths.SE_NET_PROFIT),
                value(sessionId, FactPaths.OTHER_TAXABLE_INCOME),
                value(sessionId, FactPaths.DEDUCTIBLE_HALF_OF_SE_TAX),
                value(sessionId, FactPaths.PROJECTED_AGI),
                value(sessionId, FactPaths.STANDARD_DEDUCTION),
                value(sessionId, FactPaths.QBI_DEDUCTION),
                value(sessionId, FactPaths.TAXABLE_INCOME),
                value(sessionId, FactPaths.PROJECTED_INCOME_TAX),
                value(sessionId, FactPaths.SE_TAX),
                value(sessionId, FactPaths.ADDITIONAL_MEDICARE_TAX),
                value(sessionId, FactPaths.PROJECTED_TOTAL_TAX),
                note,
                graph.explain(sessionId, FactPaths.PROJECTED_TOTAL_TAX),
                provisionalWarning(year));
    }

    /** Structured result of {@code project_total_tax}: the full net-profit-to-total-tax ladder. */
    public record TotalTaxResult(
            String status,
            int taxYear,
            String filingStatus,
            Object netProfit,
            Object otherOrdinaryIncome,
            Object deductibleHalfOfSeTax,
            Object adjustedGrossIncome,
            Object standardDeduction,
            Object qbiDeduction,
            Object taxableIncome,
            Object incomeTax,
            Object selfEmploymentTax,
            Object additionalMedicareTax,
            Object projectedTotalTax,
            String note,
            PlanningGraphService.ExplainResult explanationTree,
            String provisionalWarning) {}

    @McpTool(
            generateOutputSchema = true,
            name = "compare_filing_statuses",
            description = "Project this session's total tax under each filing status (single, married filing "
                    + "jointly, married filing separately) on identical income, in one call. Run "
                    + "calculate_se_tax or project_net_profit first so there is income to compare. Use this to "
                    + "answer 'would my filing status change what I owe?' with COMPUTED numbers rather than "
                    + "estimating: self-employment tax is the same under every status (it is based on business "
                    + "profit), so only income tax differs — through the standard deduction and bracket widths — "
                    + "plus the Additional Medicare surtax threshold, which is lower for MFS. A session's filing "
                    + "status is fixed at create_session, so this spins up throwaway sessions to compare; to "
                    + "actually switch, create a new session with the chosen status.")
    public CompareFilingStatusesResult compareFilingStatuses(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId) {
        int year = graph.taxYearOf(sessionId);
        if (!isComplete(sessionId, FactPaths.SE_NET_PROFIT)) {
            return new CompareFilingStatusesResult(
                    STATUS_NEEDS_FACTS,
                    year,
                    List.of(),
                    "Run calculate_se_tax or project_net_profit first so there is income to compare across"
                            + " filing statuses.",
                    provisionalWarning(year));
        }
        List<FilingStatusProjection> projections = Arrays.stream(FilingStatus.values())
                .map(status -> projectUnder(sessionId, status))
                .toList();
        return new CompareFilingStatusesResult(
                STATUS_OK,
                year,
                projections,
                "Self-employment tax (Social Security + Medicare) is identical across filing statuses; only"
                        + " income tax differs, through the standard deduction and bracket widths (the Additional"
                        + " Medicare surtax threshold also differs — lower for MFS). Married filing separately is"
                        + " rarely beneficial. These are estimates on identical inputs, not a recommendation of"
                        + " which status to file.",
                provisionalWarning(year));
    }

    /**
     * Project the session's income under {@code status} in a throwaway session (a session's filing
     * status is fixed at creation), which is discarded so it never leaks into the in-memory map.
     */
    private FilingStatusProjection projectUnder(String sessionId, FilingStatus status) {
        String sid = graph.cloneSessionWithFilingStatus(sessionId, status);
        try {
            return new FilingStatusProjection(
                    status.code(),
                    value(sid, FactPaths.STANDARD_DEDUCTION),
                    value(sid, FactPaths.TAXABLE_INCOME),
                    value(sid, FactPaths.PROJECTED_INCOME_TAX),
                    value(sid, FactPaths.SE_TAX),
                    value(sid, FactPaths.ADDITIONAL_MEDICARE_TAX),
                    value(sid, FactPaths.PROJECTED_TOTAL_TAX));
        } finally {
            graph.discardSession(sid);
        }
    }

    /** One filing status's projection within a {@code compare_filing_statuses} result. */
    public record FilingStatusProjection(
            String filingStatus,
            Object standardDeduction,
            Object taxableIncome,
            Object incomeTax,
            Object selfEmploymentTax,
            Object additionalMedicareTax,
            Object projectedTotalTax) {}

    /** Structured result of {@code compare_filing_statuses}: one projection per filing status. */
    public record CompareFilingStatusesResult(
            String status,
            int taxYear,
            List<FilingStatusProjection> projections,
            String note,
            String provisionalWarning) {}

    @McpTool(
            generateOutputSchema = true,
            name = "project_net_profit",
            description = "Project full-year self-employment net profit (and SE tax) from year-to-date "
                    + "raw numbers. Give the receipts, miles, fees, and supplies accumulated so far this "
                    + "year plus the asOfDate; the tool annualizes them straight-line (× 12 ÷ months of "
                    + "data) and runs the same Schedule C/SE math. Returns projected gross receipts, net "
                    + "profit, net earnings, and SE tax with a derivation chain. Assumes income is even "
                    + "across the year — for seasonal income or a known full-year figure, use "
                    + "calculate_se_tax with full-year numbers instead.")
    public ProjectNetProfitResult projectNetProfit(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(
                            description = "ISO date (YYYY-MM-DD) the year-to-date figures run through. Required "
                                    + "because the fact graph has no clock; it sets how many months to annualize from.")
                    String asOfDate,
            @McpToolParam(description = "Year-to-date gross self-employment receipts, as a string, e.g. \"21000\".")
                    String ytdGrossReceipts,
            @McpToolParam(
                            description = "Year-to-date business miles driven, as a whole number. Default 0.",
                            required = false)
                    String ytdBusinessMiles,
            @McpToolParam(
                            description = "Year-to-date platform/commission fees, as a string. Default 0.",
                            required = false)
                    String ytdPlatformFees,
            @McpToolParam(
                            description = "Year-to-date supplies and other business expenses, as a string. Default 0.",
                            required = false)
                    String ytdSuppliesAndOther,
            @McpToolParam(
                            description = "Social Security wages already reported on W-2s this year, as a string. "
                                    + "Not annualized. Default 0.",
                            required = false)
                    String socialSecurityWagesFromW2) {

        int taxYear = graph.taxYearOf(sessionId);
        int monthsElapsed = monthsElapsedInTaxYear(taxYear, LocalDate.parse(asOfDate));

        // Write the year-to-date figures into the same Schedule C inputs, then set the annualization
        // factor so the graph projects them to a full year (12 / months of data).
        writeScheduleCInputs(
                sessionId,
                new ScheduleCInputs(
                        ytdGrossReceipts,
                        ytdBusinessMiles,
                        ytdPlatformFees,
                        ytdSuppliesAndOther,
                        socialSecurityWagesFromW2));
        graph.writeFact(sessionId, FactPaths.SE_MONTHS_ELAPSED, PersisterTypes.INT, (long) monthsElapsed);
        graph.writeFact(
                sessionId,
                FactPaths.SE_ANNUALIZATION_FACTOR,
                PersisterTypes.RATIONAL,
                PersisterTypes.rational(12, monthsElapsed));

        return new ProjectNetProfitResult(
                STATUS_OK,
                taxYear,
                graph.readDecimal(sessionId, FactPaths.STANDARD_MILEAGE_RATE),
                monthsElapsed,
                "12/" + monthsElapsed,
                dollarOrZero(ytdGrossReceipts),
                value(sessionId, FactPaths.SE_EFFECTIVE_GROSS_RECEIPTS),
                value(sessionId, FactPaths.SE_NET_PROFIT),
                value(sessionId, FactPaths.SE_NET_EARNINGS),
                value(sessionId, FactPaths.SE_TAX),
                value(sessionId, FactPaths.DEDUCTIBLE_HALF_OF_SE_TAX),
                "Projected figures annualize your year-to-date numbers assuming income is even across"
                        + " the year (straight-line). Seasonal income will differ. This projects"
                        + " self-employment tax only; it does not include income tax.",
                graph.explain(sessionId, FactPaths.SE_NET_PROFIT),
                provisionalWarning(taxYear));
    }

    /** Structured result of {@code project_net_profit} (year-to-date projection). */
    public record ProjectNetProfitResult(
            String status,
            int taxYear,
            Object standardMileageRate,
            int monthsElapsed,
            String annualizationFactor,
            String ytdGrossReceipts,
            Object projectedGrossReceipts,
            Object projectedNetProfit,
            Object projectedNetEarningsFromSe,
            Object projectedSelfEmploymentTax,
            Object deductibleHalf,
            String note,
            PlanningGraphService.ExplainResult explanationTree,
            String provisionalWarning) {}

    @McpTool(
            name = "explain",
            description = "Explain how a fact's value was derived: returns the fact's name, "
                    + "description, computed value, and the values of every fact it directly "
                    + "depends on. Useful for grounding 'why is my number X?' answers.",
            generateOutputSchema = true)
    public PlanningGraphService.ExplainResult explain(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(description = "Fact path to explain.") String path) {
        return graph.explain(sessionId, path);
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
        int taxYear = graph.taxYearOf(sessionId);
        ReadResult value = graph.readFact(sessionId, path);

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
                    nullToEmpty(c.sourceId()),
                    nullToEmpty(c.authority()),
                    nullToEmpty(c.citation()),
                    nullToEmpty(c.title()),
                    nullToEmpty(c.url()),
                    nullToEmpty(c.plainLanguage()));
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
        PlanReport report = reportService.generate(sessionId, includeCitations == null || includeCitations);
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

    @McpTool(
            generateOutputSchema = true,
            name = "estimate_quarterly_payment",
            description = "Compute the next federal estimated tax payment for a self-employed "
                    + "taxpayer using IRC §6654 safe-harbor logic (lesser of 100%/110% prior-year "
                    + "tax or 90% projected current-year tax). Returns the recommended payment, "
                    + "next deadline, and a derivation chain. If required facts are missing, "
                    + "returns a 'needs_facts' response so the agent can gather them.")
    public QuarterlyPaymentResult estimateQuarterlyPayment(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(description = "ISO date (YYYY-MM-DD). Required because the fact graph has no clock.")
                    String asOfDate) {

        LocalDate asOf = LocalDate.parse(asOfDate);

        List<MissingFact> missing = missingQuarterlyFacts(sessionId);
        if (!missing.isEmpty()) {
            return QuarterlyPaymentResult.needsFacts(missing);
        }

        // Resolve the next deadline and remaining quarters for the session's tax year
        // (asOf only determines which of that year's deadlines are still ahead).
        int year = graph.taxYearOf(sessionId);
        EstimatedTaxSchedule schedule = EstimatedTaxSchedule.forTaxYear(year);
        EstimatedTaxSchedule.Deadline next = schedule.nextOnOrAfter(asOf);
        long remainingQuarters = schedule.remainingQuarters(asOf);
        graph.writeFact(sessionId, FactPaths.REMAINING_QUARTERS, PersisterTypes.INT, remainingQuarters);

        return new QuarterlyPaymentResult(
                STATUS_OK,
                List.of(),
                "",
                value(sessionId, FactPaths.NEXT_QUARTERLY_PAYMENT_SUGGESTED),
                next.due().toString(),
                next.quarter(),
                value(sessionId, FactPaths.PROJECTED_TOTAL_TAX),
                value(sessionId, FactPaths.PROJECTED_BALANCE_DUE_AT_FILING),
                "recommendedPayment is the safe-harbor minimum that avoids the IRC §6654 underpayment"
                        + " penalty; it does NOT settle the year's tax. projectedBalanceDueAtFiling is what"
                        + " would still be owed at filing if no further payments are made beyond those already"
                        + " counted (negative = refund).",
                safeHarborDerivation(sessionId, remainingQuarters),
                graph.explain(sessionId, FactPaths.NEXT_QUARTERLY_PAYMENT_SUGGESTED),
                "https://www.irs.gov/payments/direct-pay",
                provisionalWarning(year));
    }

    /**
     * The facts still standing between the session and a quarterly estimate: taxpayer-supplied inputs
     * not yet written, plus the derived projected total tax, which only resolves once the income
     * inputs are present — if it is still incomplete, that is surfaced as a prerequisite to run first.
     */
    private List<MissingFact> missingQuarterlyFacts(String sessionId) {
        List<MissingFact> missing = new ArrayList<>();
        for (MissingFact required : REQUIRED_FACTS) {
            if (!isComplete(sessionId, required.path())) {
                missing.add(required);
            }
        }
        if (!isComplete(sessionId, PROJECTED_TAX_PREREQUISITE.path())) {
            missing.add(PROJECTED_TAX_PREREQUISITE);
        }
        return missing;
    }

    /**
     * How the recommendation was reached. Keeps the two questions distinct: the safe-harbor
     * recommendation ("least to prepay to avoid the penalty") is not the same as what's actually owed
     * for the year. A free-form map, surfaced under the permissive {@code derivation} Object field.
     */
    private Map<String, Object> safeHarborDerivation(String sessionId, long remainingQuarters) {
        boolean highIncomeRule = Boolean.TRUE.equals(value(sessionId, FactPaths.HIGH_INCOME_SAFE_HARBOR_APPLIES));
        Map<String, Object> derivation = new LinkedHashMap<>();
        derivation.put("selfEmploymentTax", value(sessionId, FactPaths.SE_TAX));
        derivation.put(
                "safeHarborRule",
                highIncomeRule ? "110% of prior-year tax (AGI > $150,000)" : "100% of prior-year tax");
        derivation.put("safeHarborTarget", value(sessionId, FactPaths.SAFE_HARBOR_TARGET));
        derivation.put("ytdPaymentsAlreadyApplied", value(sessionId, FactPaths.YTD_PAYMENTS_APPLIED));
        derivation.put("remainingDueThroughYearEnd", value(sessionId, FactPaths.REMAINING_PAYMENT_DUE));
        derivation.put("remainingQuarters", remainingQuarters);
        return derivation;
    }

    /** A required fact the agent must collect before estimate_quarterly_payment can run. */
    public record MissingFact(String path, String prompt) {}

    /**
     * Structured result of {@code estimate_quarterly_payment}. Two shapes share one record: with facts
     * missing, {@code status="needs_facts"}, {@code missingFacts} populated and the rest empty/null;
     * otherwise {@code status="ok"} with the recommendation. {@code derivation}/{@code explanationTree}
     * are typed {@code Object} (permissive schema) because they are absent in the needs-facts shape.
     */
    public record QuarterlyPaymentResult(
            String status,
            List<MissingFact> missingFacts,
            String hint,
            Object recommendedPayment,
            String nextDeadline,
            String quarter,
            Object projectedTotalTax,
            Object projectedBalanceDueAtFiling,
            String note,
            Object derivation,
            Object explanationTree,
            String paymentUrl,
            String provisionalWarning) {

        /** The needs-facts shape: what is missing and how to proceed, with every result field empty. */
        static QuarterlyPaymentResult needsFacts(List<MissingFact> missing) {
            return new QuarterlyPaymentResult(
                    STATUS_NEEDS_FACTS,
                    missing,
                    "Ask the user for these values, write each via set_fact, then call this tool again.",
                    null,
                    "",
                    "",
                    null,
                    null,
                    "",
                    null,
                    null,
                    "",
                    "");
        }
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
        if (!isBlank(sessionId)) {
            graph.sessionFacts(sessionId).forEach((path, value) -> merged.put(path, confirmed(value)));
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

    /**
     * Write the Schedule C / Schedule SE inputs; the fact graph derives the rest. Blank optional
     * inputs are written as zero.
     */
    private void writeScheduleCInputs(String sessionId, ScheduleCInputs inputs) {
        writeDollar(sessionId, FactPaths.SE_GROSS_RECEIPTS, inputs.grossReceipts());
        graph.writeFact(
                sessionId, FactPaths.SE_VEHICLE_BUSINESS_MILES, PersisterTypes.INT, longOrZero(inputs.businessMiles()));
        writeDollar(sessionId, FactPaths.SE_PLATFORM_FEES, inputs.platformFees());
        writeDollar(sessionId, FactPaths.SE_SUPPLIES_AND_OTHER, inputs.suppliesAndOther());
        writeDollar(sessionId, FactPaths.SE_SOCIAL_SECURITY_WAGES_FROM_W2, inputs.socialSecurityWagesFromW2());
    }

    private void writeDollar(String sessionId, String path, String amount) {
        graph.writeFact(sessionId, path, PersisterTypes.DOLLAR, dollarOrZero(amount));
    }

    /** Optional inputs are zero-defaulted at session creation; only overwrite when a value is supplied. */
    private void writeDollarIfSupplied(String sessionId, String path, String amount) {
        if (!isBlank(amount)) {
            writeDollar(sessionId, path, amount);
        }
    }

    private Object value(String sessionId, String path) {
        return graph.readFact(sessionId, path).value();
    }

    private boolean isComplete(String sessionId, String path) {
        return graph.readFact(sessionId, path).complete();
    }

    /** The year's provisional-constants warning, or empty when the year is finalized. */
    private String provisionalWarning(int taxYear) {
        return nullToEmpty(taxKnowledge.provisionalWarning(taxYear));
    }

    private static int parseTaxYear(String taxYear) {
        return isBlank(taxYear) ? Year.now().getValue() : Integer.parseInt(taxYear.trim());
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

    /** Dollar inputs are written as strings (DollarWrapper reads a string); blank means $0. */
    private static String dollarOrZero(String v) {
        return isBlank(v) ? "0" : v.trim();
    }

    /** Tool result strings must be non-null for MCP output-schema conformance (required: string). */
    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    /** Int inputs (e.g. miles) are written as a JSON number; blank means 0. */
    private static long longOrZero(String v) {
        return isBlank(v) ? 0L : Long.parseLong(v.trim());
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    /** The Schedule C / Schedule SE inputs shared by calculate_se_tax and project_net_profit, as received. */
    private record ScheduleCInputs(
            String grossReceipts,
            String businessMiles,
            String platformFees,
            String suppliesAndOther,
            String socialSecurityWagesFromW2) {}
}

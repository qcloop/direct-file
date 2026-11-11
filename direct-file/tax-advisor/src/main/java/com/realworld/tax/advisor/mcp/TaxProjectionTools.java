package com.realworld.tax.advisor.mcp;

import java.util.Arrays;
import java.util.List;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.FilingStatus;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.PlanningGraphService.ReadResult;
import com.realworld.tax.advisor.graph.SessionId;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import static com.realworld.tax.advisor.mcp.ToolSupport.SESSION_ID_DESCRIPTION;
import static com.realworld.tax.advisor.mcp.ToolSupport.STATUS_NEEDS_FACTS;
import static com.realworld.tax.advisor.mcp.ToolSupport.STATUS_OK;

/**
 * MCP tools that carry self-employment income up the rest of the return:
 * {@code calculate_additional_medicare} (Form 8959), {@code estimate_qbi_deduction} (Form 8995),
 * {@code project_total_tax} (the full net-profit-to-total-tax ladder) and
 * {@code compare_filing_statuses}. Each expects the session to already hold Schedule C income.
 *
 * <p>See the package documentation for the wire-format conventions every tool result follows.
 */
@Component
public class TaxProjectionTools {

    private final PlanningGraphService graph;

    private final ToolSupport support;

    public TaxProjectionTools(PlanningGraphService graph, ToolSupport support) {
        this.graph = graph;
        this.support = support;
    }

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
        SessionId session = SessionId.of(sessionId);
        support.writeDollar(session, FactPaths.MEDICARE_WAGES_FROM_W2, medicareWagesFromW2);

        // The SE portion needs net earnings from a prior calculate_se_tax / project_net_profit call.
        // If they aren't populated yet, surface a note rather than silently returning a null SE portion.
        ReadResult netEarnings = graph.readFact(session, FactPaths.SE_NET_EARNINGS);
        String note = netEarnings.complete()
                ? ""
                : "Self-employment net earnings aren't set yet — run calculate_se_tax or project_net_profit"
                        + " first for the SE portion. The wage portion (if any) is still computed.";
        int year = graph.taxYearOf(session);
        return new AdditionalMedicareResult(
                STATUS_OK,
                year,
                graph.filingStatusOf(session),
                support.value(session, FactPaths.ADDITIONAL_MEDICARE_THRESHOLD),
                support.value(session, FactPaths.MEDICARE_WAGES_FROM_W2),
                netEarnings.value(),
                support.value(session, FactPaths.SE_INCOME_SUBJECT_TO_ADDITIONAL_MEDICARE),
                support.value(session, FactPaths.ADDITIONAL_MEDICARE_TAX_ON_SE),
                support.value(session, FactPaths.WAGES_SUBJECT_TO_ADDITIONAL_MEDICARE),
                support.value(session, FactPaths.ADDITIONAL_MEDICARE_TAX_ON_WAGES),
                support.value(session, FactPaths.ADDITIONAL_MEDICARE_TAX),
                graph.explain(session, FactPaths.ADDITIONAL_MEDICARE_TAX),
                note,
                support.provisionalWarning(year));
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
        SessionId session = SessionId.of(sessionId);
        // Taxable income before QBI is derived (AGI − standard deduction), so this tool no longer writes
        // it. Net capital gains is zero-defaulted at session creation; only overwrite when supplied.
        support.writeDollarIfSupplied(session, FactPaths.NET_CAPITAL_GAINS, netCapitalGains);

        ReadResult netProfit = graph.readFact(session, FactPaths.SE_NET_PROFIT);
        String note = netProfit.complete()
                ? ""
                : "Self-employment net profit isn't set yet — run calculate_se_tax or project_net_profit"
                        + " first so QBI can be computed from it.";
        ReadResult above = graph.readFact(session, FactPaths.QBI_ABOVE_THRESHOLD);
        String aboveWarning = Boolean.TRUE.equals(above.value())
                ? "Taxable income is above the filing-status threshold for the simple method, so Form 8995-A"
                        + " (specified-service-business, W-2-wage, and qualified-property limits) governs. The"
                        + " qbiDeduction shown is an UPPER BOUND and may be reduced or eliminated."
                : "";
        int year = graph.taxYearOf(session);
        return new QbiResult(
                STATUS_OK,
                year,
                graph.filingStatusOf(session),
                support.value(session, FactPaths.QUALIFIED_BUSINESS_INCOME),
                support.value(session, FactPaths.QBI_COMPONENT),
                support.value(session, FactPaths.QBI_INCOME_LIMIT),
                support.value(session, FactPaths.QBI_DEDUCTION),
                support.value(session, FactPaths.QBI_THRESHOLD),
                above.value(),
                aboveWarning,
                graph.explain(session, FactPaths.QBI_DEDUCTION),
                note,
                support.provisionalWarning(year));
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
        SessionId session = SessionId.of(sessionId);
        // Both optional inputs are zero-defaulted at session creation; only overwrite when supplied.
        support.writeDollarIfSupplied(session, FactPaths.OTHER_TAXABLE_INCOME, otherOrdinaryIncome);
        support.writeDollarIfSupplied(session, FactPaths.NET_CAPITAL_GAINS, netCapitalGains);

        ReadResult netProfit = graph.readFact(session, FactPaths.SE_NET_PROFIT);
        String incomplete = netProfit.complete()
                ? ""
                : "Self-employment net profit isn't set yet — run calculate_se_tax or project_net_profit"
                        + " first so income tax and total tax can be computed. ";
        String note = incomplete
                + "projectedTotalTax is income tax + SE tax + Additional Medicare surtax. It excludes credits,"
                + " capital-gains/qualified-dividend rates, the NIIT, and AMT, so a return with those will"
                + " differ. Income tax is on taxable income after the standard and QBI deductions.";
        int year = graph.taxYearOf(session);
        return new TotalTaxResult(
                STATUS_OK,
                year,
                graph.filingStatusOf(session),
                support.value(session, FactPaths.SE_NET_PROFIT),
                support.value(session, FactPaths.OTHER_TAXABLE_INCOME),
                support.value(session, FactPaths.DEDUCTIBLE_HALF_OF_SE_TAX),
                support.value(session, FactPaths.PROJECTED_AGI),
                support.value(session, FactPaths.STANDARD_DEDUCTION),
                support.value(session, FactPaths.QBI_DEDUCTION),
                support.value(session, FactPaths.TAXABLE_INCOME),
                support.value(session, FactPaths.PROJECTED_INCOME_TAX),
                support.value(session, FactPaths.SE_TAX),
                support.value(session, FactPaths.ADDITIONAL_MEDICARE_TAX),
                support.value(session, FactPaths.PROJECTED_TOTAL_TAX),
                note,
                graph.explain(session, FactPaths.PROJECTED_TOTAL_TAX),
                support.provisionalWarning(year));
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
        SessionId session = SessionId.of(sessionId);
        int year = graph.taxYearOf(session);
        if (!support.isComplete(session, FactPaths.SE_NET_PROFIT)) {
            return new CompareFilingStatusesResult(
                    STATUS_NEEDS_FACTS,
                    year,
                    List.of(),
                    "Run calculate_se_tax or project_net_profit first so there is income to compare across"
                            + " filing statuses.",
                    support.provisionalWarning(year));
        }
        List<FilingStatusProjection> projections = Arrays.stream(FilingStatus.values())
                .map(status -> projectUnder(session, status))
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
                support.provisionalWarning(year));
    }

    /**
     * Project the session's income under {@code status} in a throwaway session (a session's filing
     * status is fixed at creation), which is discarded so it never leaks into the in-memory map.
     */
    private FilingStatusProjection projectUnder(SessionId session, FilingStatus status) {
        SessionId sid = graph.cloneSessionWithFilingStatus(session, status);
        try {
            return new FilingStatusProjection(
                    status.code(),
                    support.value(sid, FactPaths.STANDARD_DEDUCTION),
                    support.value(sid, FactPaths.TAXABLE_INCOME),
                    support.value(sid, FactPaths.PROJECTED_INCOME_TAX),
                    support.value(sid, FactPaths.SE_TAX),
                    support.value(sid, FactPaths.ADDITIONAL_MEDICARE_TAX),
                    support.value(sid, FactPaths.PROJECTED_TOTAL_TAX));
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
}

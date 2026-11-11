package com.realworld.tax.advisor.mcp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PersisterTypes;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import static com.realworld.tax.advisor.mcp.ToolSupport.SESSION_ID_DESCRIPTION;
import static com.realworld.tax.advisor.mcp.ToolSupport.STATUS_NEEDS_FACTS;
import static com.realworld.tax.advisor.mcp.ToolSupport.STATUS_OK;

/**
 * MCP tool for the next federal estimated tax payment under the IRC §6654 safe harbor:
 * {@code estimate_quarterly_payment}.
 *
 * <p>See the package documentation for the wire-format conventions every tool result follows.
 */
@Component
public class QuarterlyPaymentTools {

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

    private final ToolSupport support;

    public QuarterlyPaymentTools(PlanningGraphService graph, ToolSupport support) {
        this.graph = graph;
        this.support = support;
    }

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
        SessionId session = SessionId.of(sessionId);

        LocalDate asOf = LocalDate.parse(asOfDate);

        List<MissingFact> missing = missingQuarterlyFacts(session);
        if (!missing.isEmpty()) {
            return QuarterlyPaymentResult.needsFacts(missing);
        }

        // Resolve the next deadline and remaining quarters for the session's tax year
        // (asOf only determines which of that year's deadlines are still ahead).
        int year = graph.taxYearOf(session);
        EstimatedTaxSchedule schedule = EstimatedTaxSchedule.forTaxYear(year);
        EstimatedTaxSchedule.Deadline next = schedule.nextOnOrAfter(asOf);
        long remainingQuarters = schedule.remainingQuarters(asOf);
        graph.writeFact(session, FactPaths.REMAINING_QUARTERS, PersisterTypes.INT, remainingQuarters);

        return new QuarterlyPaymentResult(
                STATUS_OK,
                List.of(),
                "",
                support.value(session, FactPaths.NEXT_QUARTERLY_PAYMENT_SUGGESTED),
                next.due().toString(),
                next.quarter(),
                support.value(session, FactPaths.PROJECTED_TOTAL_TAX),
                support.value(session, FactPaths.PROJECTED_BALANCE_DUE_AT_FILING),
                "recommendedPayment is the safe-harbor minimum that avoids the IRC §6654 underpayment"
                        + " penalty; it does NOT settle the year's tax. projectedBalanceDueAtFiling is what"
                        + " would still be owed at filing if no further payments are made beyond those already"
                        + " counted (negative = refund).",
                safeHarborDerivation(session, remainingQuarters),
                graph.explain(session, FactPaths.NEXT_QUARTERLY_PAYMENT_SUGGESTED),
                "https://www.irs.gov/payments/direct-pay",
                support.provisionalWarning(year));
    }

    /**
     * The facts still standing between the session and a quarterly estimate: taxpayer-supplied inputs
     * not yet written, plus the derived projected total tax, which only resolves once the income
     * inputs are present — if it is still incomplete, that is surfaced as a prerequisite to run first.
     */
    private List<MissingFact> missingQuarterlyFacts(SessionId session) {
        List<MissingFact> missing = new ArrayList<>();
        for (MissingFact required : REQUIRED_FACTS) {
            if (!support.isComplete(session, required.path())) {
                missing.add(required);
            }
        }
        if (!support.isComplete(session, PROJECTED_TAX_PREREQUISITE.path())) {
            missing.add(PROJECTED_TAX_PREREQUISITE);
        }
        return missing;
    }

    /**
     * How the recommendation was reached. Keeps the two questions distinct: the safe-harbor
     * recommendation ("least to prepay to avoid the penalty") is not the same as what's actually owed
     * for the year. A free-form map, surfaced under the permissive {@code derivation} Object field.
     */
    private Map<String, Object> safeHarborDerivation(SessionId session, long remainingQuarters) {
        boolean highIncomeRule = Boolean.TRUE.equals(support.value(session, FactPaths.HIGH_INCOME_SAFE_HARBOR_APPLIES));
        Map<String, Object> derivation = new LinkedHashMap<>();
        derivation.put("selfEmploymentTax", support.value(session, FactPaths.SE_TAX));
        derivation.put(
                "safeHarborRule",
                highIncomeRule ? "110% of prior-year tax (AGI > $150,000)" : "100% of prior-year tax");
        derivation.put("safeHarborTarget", support.value(session, FactPaths.SAFE_HARBOR_TARGET));
        derivation.put("ytdPaymentsAlreadyApplied", support.value(session, FactPaths.YTD_PAYMENTS_APPLIED));
        derivation.put("remainingDueThroughYearEnd", support.value(session, FactPaths.REMAINING_PAYMENT_DUE));
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
}

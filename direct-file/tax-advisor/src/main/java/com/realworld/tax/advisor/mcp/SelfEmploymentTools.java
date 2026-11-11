package com.realworld.tax.advisor.mcp;

import java.time.LocalDate;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PersisterTypes;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import static com.realworld.tax.advisor.mcp.ToolSupport.SESSION_ID_DESCRIPTION;
import static com.realworld.tax.advisor.mcp.ToolSupport.STATUS_OK;

/**
 * MCP tools that turn Schedule C figures into net profit and self-employment tax (Schedule SE):
 * {@code calculate_se_tax} for full-year actuals and {@code project_net_profit} for annualized
 * year-to-date figures. Both write the same Schedule C inputs and let the fact graph derive the rest.
 *
 * <p>See the package documentation for the wire-format conventions every tool result follows.
 */
@Component
public class SelfEmploymentTools {

    private final PlanningGraphService graph;

    private final ToolSupport support;

    public SelfEmploymentTools(PlanningGraphService graph, ToolSupport support) {
        this.graph = graph;
        this.support = support;
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
        SessionId session = SessionId.of(sessionId);

        // Populate the Schedule C / SE inputs; the fact graph derives the rest. These are full-year
        // actuals, so the annualization factor is 1/1 (no projection) — see project_net_profit for the
        // year-to-date path.
        writeScheduleCInputs(
                session,
                new ScheduleCInputs(
                        grossReceipts, businessMiles, platformFees, suppliesAndOther, socialSecurityWagesFromW2));
        graph.writeFact(
                session, FactPaths.SE_ANNUALIZATION_FACTOR, PersisterTypes.RATIONAL, PersisterTypes.rational(1, 1));

        // Surface the tax year and the rate actually used, so a wrong-year session (e.g. planning
        // 2026 on a 2025 session) is visible rather than silently using stale constants.
        int year = graph.taxYearOf(session);
        return new SeTaxResult(
                STATUS_OK,
                year,
                graph.readDecimal(session, FactPaths.STANDARD_MILEAGE_RATE),
                support.value(session, FactPaths.SE_NET_PROFIT),
                support.value(session, FactPaths.SE_NET_EARNINGS),
                support.value(session, FactPaths.SE_SOCIAL_SECURITY_TAX),
                support.value(session, FactPaths.SE_MEDICARE_TAX),
                support.value(session, FactPaths.SE_TAX),
                support.value(session, FactPaths.DEDUCTIBLE_HALF_OF_SE_TAX),
                graph.explain(session, FactPaths.SE_TAX),
                support.provisionalWarning(year));
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
        SessionId session = SessionId.of(sessionId);

        int taxYear = graph.taxYearOf(session);
        int monthsElapsed = monthsElapsedInTaxYear(taxYear, LocalDate.parse(asOfDate));

        // Write the year-to-date figures into the same Schedule C inputs, then set the annualization
        // factor so the graph projects them to a full year (12 / months of data).
        writeScheduleCInputs(
                session,
                new ScheduleCInputs(
                        ytdGrossReceipts,
                        ytdBusinessMiles,
                        ytdPlatformFees,
                        ytdSuppliesAndOther,
                        socialSecurityWagesFromW2));
        graph.writeFact(session, FactPaths.SE_MONTHS_ELAPSED, PersisterTypes.INT, (long) monthsElapsed);
        graph.writeFact(
                session,
                FactPaths.SE_ANNUALIZATION_FACTOR,
                PersisterTypes.RATIONAL,
                PersisterTypes.rational(12, monthsElapsed));

        return new ProjectNetProfitResult(
                STATUS_OK,
                taxYear,
                graph.readDecimal(session, FactPaths.STANDARD_MILEAGE_RATE),
                monthsElapsed,
                "12/" + monthsElapsed,
                ToolSupport.dollarOrZero(ytdGrossReceipts),
                support.value(session, FactPaths.SE_EFFECTIVE_GROSS_RECEIPTS),
                support.value(session, FactPaths.SE_NET_PROFIT),
                support.value(session, FactPaths.SE_NET_EARNINGS),
                support.value(session, FactPaths.SE_TAX),
                support.value(session, FactPaths.DEDUCTIBLE_HALF_OF_SE_TAX),
                "Projected figures annualize your year-to-date numbers assuming income is even across"
                        + " the year (straight-line). Seasonal income will differ. This projects"
                        + " self-employment tax only; it does not include income tax.",
                graph.explain(session, FactPaths.SE_NET_PROFIT),
                support.provisionalWarning(taxYear));
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

    /**
     * Write the Schedule C / Schedule SE inputs; the fact graph derives the rest. Blank optional
     * inputs are written as zero.
     */
    private void writeScheduleCInputs(SessionId session, ScheduleCInputs inputs) {
        support.writeDollar(session, FactPaths.SE_GROSS_RECEIPTS, inputs.grossReceipts());
        graph.writeFact(
                session, FactPaths.SE_VEHICLE_BUSINESS_MILES, PersisterTypes.INT, longOrZero(inputs.businessMiles()));
        support.writeDollar(session, FactPaths.SE_PLATFORM_FEES, inputs.platformFees());
        support.writeDollar(session, FactPaths.SE_SUPPLIES_AND_OTHER, inputs.suppliesAndOther());
        support.writeDollar(session, FactPaths.SE_SOCIAL_SECURITY_WAGES_FROM_W2, inputs.socialSecurityWagesFromW2());
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

    /** Int inputs (e.g. miles) are written as a JSON number; blank means 0. */
    private static long longOrZero(String v) {
        return ToolSupport.isBlank(v) ? 0L : Long.parseLong(v.trim());
    }

    /** The Schedule C / Schedule SE inputs shared by calculate_se_tax and project_net_profit, as received. */
    private record ScheduleCInputs(
            String grossReceipts,
            String businessMiles,
            String platformFees,
            String suppliesAndOther,
            String socialSecurityWagesFromW2) {}
}

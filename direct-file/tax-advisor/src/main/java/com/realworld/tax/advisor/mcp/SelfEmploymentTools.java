package com.realworld.tax.advisor.mcp;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import static com.realworld.tax.advisor.mcp.ToolSupport.SESSION_ID_DESCRIPTION;
import static com.realworld.tax.advisor.mcp.ToolSupport.STATUS_NEEDS_FACTS;
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
    private final Clock clock;
    private final MileageService mileageService;

    public SelfEmploymentTools(
            PlanningGraphService graph, ToolSupport support, Clock clock, MileageService mileageService) {
        this.graph = graph;
        this.support = support;
        this.clock = clock;
        this.mileageService = mileageService;
    }

    @McpTool(
            generateOutputSchema = true,
            name = "calculate_se_tax",
            description =
                    "Compute self-employment tax (Schedule SE) for a planning session from gross "
                            + "receipts and business expenses. Returns net profit, net earnings (92.35% of "
                            + "profit), the Social Security portion (capped at the annual wage base and reduced "
                            + "by any W-2 Social Security wages), the Medicare portion, total SE tax, and the "
                            + "deductible half. Use this to build the projected current-year tax that "
                            + "estimate_quarterly_payment needs. For an unknown mileage split, supply only businessMiles; omit both optional period fields. The server returns a labeled planning estimate.")
    public SeTaxResult calculateSeTax(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(
                            description =
                                    "Gross self-employment receipts (Schedule C line 1), as a string, e.g. \"24000\". Omit only when resuming an existing mileage estimate or pending follow-up.",
                            required = false)
                    String grossReceipts,
            @McpToolParam(
                            description =
                                    "Total full-year business miles as a whole number. Supply this first. If rate-period "
                                            + "allocation is unknown, returns an explicitly labeled planning estimate assuming even daily mileage. Default 0 for a new calculation; omit to retain during a mileage refinement or pending follow-up.",
                            required = false)
                    String businessMiles,
            @McpToolParam(
                            description =
                                    "Platform/commission fees withheld, as a string. Default 0 for a new calculation; omit to retain during a mileage refinement or pending follow-up.",
                            required = false)
                    String platformFees,
            @McpToolParam(
                            description =
                                    "Supplies and other business expenses, as a string. Default 0 for a new calculation; omit to retain during a mileage refinement or pending follow-up.",
                            required = false)
                    String suppliesAndOther,
            @McpToolParam(
                            description =
                                    "Social Security wages already reported on W-2s this year, as a string. "
                                            + "Default 0 for a new calculation; omit to retain during a mileage refinement or pending follow-up.",
                            required = false)
                    String socialSecurityWagesFromW2,
            @McpToolParam(
                            description =
                                    "Full-year business miles before the returned mileageRateChangeDate. "
                                            + "Omit if unknown; never use 0 as an unknown placeholder. With a supplied or retained total, the remaining period is derived by subtraction.",
                            required = false)
                    String businessMilesBeforeRateChange,
            @McpToolParam(
                            description = "Full-year business miles from the returned mileageRateChangeDate onward. "
                                    + "Omit if unknown. Enter 0 only if the actual period mileage is known to be zero.",
                            required = false)
                    String businessMilesAfterRateChange) {
        SessionId session = SessionId.of(sessionId);
        int year = graph.taxYearOf(session);
        MileageService.Allocation mileage = mileageService.apply(
                session,
                LocalDate.of(year, 12, 31),
                false,
                businessMiles,
                businessMilesBeforeRateChange,
                businessMilesAfterRateChange,
                new MileageService.Amounts(grossReceipts, platformFees, suppliesAndOther, socialSecurityWagesFromW2));

        return new SeTaxResult(
                mileage.pending() ? STATUS_NEEDS_FACTS : STATUS_OK,
                year,
                graph.readDecimal(session, FactPaths.STANDARD_MILEAGE_RATE),
                secondMileageRate(session),
                mileageChangeDate(session),
                value(session, mileage, FactPaths.SE_NET_PROFIT),
                value(session, mileage, FactPaths.SE_NET_EARNINGS),
                value(session, mileage, FactPaths.SE_SOCIAL_SECURITY_TAX),
                value(session, mileage, FactPaths.SE_MEDICARE_TAX),
                value(session, mileage, FactPaths.SE_TAX),
                value(session, mileage, FactPaths.DEDUCTIBLE_HALF_OF_SE_TAX),
                graph.explain(session, FactPaths.SE_TAX),
                support.provisionalWarning(year),
                mileage.total(),
                allocationStatus(mileage),
                mileageService.periods(session, mileage),
                mileage.pending() ? mileageService.followUps(session, false) : List.of(),
                mileage.pending() ? pendingNote() : mileageService.allocationNote(session),
                mileageService.allocationNote(session),
                mileageService.recordedInputs(session));
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
            Object standardMileageRateAfterChange,
            String mileageRateChangeDate,
            Object netProfit,
            Object netEarningsFromSe,
            Object socialSecurityPortion,
            Object medicarePortion,
            Object selfEmploymentTax,
            Object deductibleHalf,
            PlanningGraphService.ExplainResult explanationTree,
            String provisionalWarning,
            Object businessMiles,
            String mileageAllocationStatus,
            List<MileageService.RatePeriod> mileagePeriods,
            List<MileageService.FollowUp> followUpQuestions,
            String note,
            String mileageAllocationNote,
            MileageService.RecordedInputs recordedInputs) {}

    @McpTool(
            generateOutputSchema = true,
            name = "project_net_profit",
            description = "Project full-year self-employment net profit (and SE tax) from year-to-date "
                    + "raw numbers. Give the receipts, miles, fees, and supplies accumulated so far this "
                    + "year plus the asOfDate; the tool annualizes them straight-line (× 12 ÷ months of "
                    + "data) for income and other expenses; estimated mileage uses calendar days. Unknown rate-period splits return a labeled even-daily planning assumption. Returns projected gross receipts, net "
                    + "profit, net earnings, and SE tax with a derivation chain. Assumes income is even "
                    + "across the year — for seasonal income or a known full-year figure, use "
                    + "calculate_se_tax with full-year numbers instead.")
    public ProjectNetProfitResult projectNetProfit(
            @McpToolParam(description = SESSION_ID_DESCRIPTION) String sessionId,
            @McpToolParam(
                            description =
                                    "ISO date (YYYY-MM-DD) the year-to-date figures run through. Omit to use "
                                            + "the existing mileage calculation's date, or the server's current date. Must fall within the session's income year.",
                            required = false)
                    String asOfDate,
            @McpToolParam(
                            description =
                                    "Year-to-date gross self-employment receipts, as a string, e.g. \"21000\". Omit only when resuming an existing mileage estimate or pending follow-up.",
                            required = false)
                    String ytdGrossReceipts,
            @McpToolParam(
                            description =
                                    "Total actual year-to-date business miles, as a whole number. Supply this first; "
                                            + "uses a labeled even-daily-mileage planning estimate if the rate-period split is unknown. Default 0 for a new calculation; omit to retain during a mileage refinement or pending follow-up.",
                            required = false)
                    String ytdBusinessMiles,
            @McpToolParam(
                            description =
                                    "Year-to-date platform/commission fees, as a string. Default 0 for a new calculation; omit to retain during a mileage refinement or pending follow-up.",
                            required = false)
                    String ytdPlatformFees,
            @McpToolParam(
                            description =
                                    "Year-to-date supplies and other business expenses, as a string. Default 0 for a new calculation; omit to retain during a mileage refinement or pending follow-up.",
                            required = false)
                    String ytdSuppliesAndOther,
            @McpToolParam(
                            description =
                                    "Social Security wages already reported on W-2s this year, as a string. "
                                            + "Not annualized. Default 0 for a new calculation; omit to retain during a mileage refinement or pending follow-up.",
                            required = false)
                    String socialSecurityWagesFromW2,
            @McpToolParam(
                            description =
                                    "Actual year-to-date business miles before the returned mileageRateChangeDate. "
                                            + "Omit if unknown; never use 0 as an unknown placeholder. With a supplied or retained total, the remaining period is derived by subtraction.",
                            required = false)
                    String ytdBusinessMilesBeforeRateChange,
            @McpToolParam(
                            description =
                                    "Actual year-to-date business miles from the returned mileageRateChangeDate onward. "
                                            + "With a total, the other period can be omitted. Omit if unknown. Enter 0 only if the actual period mileage is known to be zero.",
                            required = false)
                    String ytdBusinessMilesAfterRateChange) {
        SessionId session = SessionId.of(sessionId);

        int taxYear = graph.taxYearOf(session);
        LocalDate asOf = mileageService.projectionDate(session, asOfDate, LocalDate.now(clock));
        int monthsElapsed = monthsElapsedInTaxYear(taxYear, asOf);
        MileageService.Allocation mileage = mileageService.apply(
                session,
                asOf,
                true,
                ytdBusinessMiles,
                ytdBusinessMilesBeforeRateChange,
                ytdBusinessMilesAfterRateChange,
                new MileageService.Amounts(
                        ytdGrossReceipts, ytdPlatformFees, ytdSuppliesAndOther, socialSecurityWagesFromW2));

        return new ProjectNetProfitResult(
                mileage.pending() ? STATUS_NEEDS_FACTS : STATUS_OK,
                taxYear,
                graph.readDecimal(session, FactPaths.STANDARD_MILEAGE_RATE),
                secondMileageRate(session),
                mileageChangeDate(session),
                monthsElapsed,
                "12/" + monthsElapsed,
                support.value(session, FactPaths.SE_GROSS_RECEIPTS).toString(),
                support.value(session, FactPaths.SE_EFFECTIVE_GROSS_RECEIPTS),
                value(session, mileage, FactPaths.SE_NET_PROFIT),
                value(session, mileage, FactPaths.SE_NET_EARNINGS),
                value(session, mileage, FactPaths.SE_TAX),
                value(session, mileage, FactPaths.DEDUCTIBLE_HALF_OF_SE_TAX),
                mileage.pending()
                        ? pendingNote()
                        : mileageService.allocationNote(session)
                                + " Projected figures annualize your year-to-date numbers assuming income is even across"
                                + " the year (straight-line). Seasonal income will differ. This projects"
                                + " self-employment tax only; it does not include income tax. "
                                + mileageProjectionNote(session),
                graph.explain(session, FactPaths.SE_NET_PROFIT),
                support.provisionalWarning(taxYear),
                mileage.total(),
                allocationStatus(mileage),
                mileageService.periods(session, mileage),
                mileage.pending() ? mileageService.followUps(session, true) : List.of(),
                asOf.toString(),
                mileageService.allocationNote(session),
                mileage.pending() ? null : graph.readDecimal(session, FactPaths.SE_PROJECTED_MILES),
                mileageService.recordedInputs(session));
    }

    /** Structured result of {@code project_net_profit} (year-to-date projection). */
    public record ProjectNetProfitResult(
            String status,
            int taxYear,
            Object standardMileageRate,
            Object standardMileageRateAfterChange,
            String mileageRateChangeDate,
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
            String provisionalWarning,
            Object businessMiles,
            String mileageAllocationStatus,
            List<MileageService.RatePeriod> mileagePeriods,
            List<MileageService.FollowUp> followUpQuestions,
            String asOfDate,
            String mileageAllocationNote,
            Object projectedBusinessMiles,
            MileageService.RecordedInputs recordedInputs) {}

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

    private Object value(SessionId session, MileageService.Allocation mileage, String path) {
        return mileage.pending() ? null : support.value(session, path);
    }

    private static String allocationStatus(MileageService.Allocation mileage) {
        return mileage.pending() ? "pending" : mileage.estimated() ? "estimated" : "actual";
    }

    private static String pendingNote() {
        return "Your total mileage and other inputs are saved. Answer the follow-up with one period's actual miles; "
                + "the remainder is calculated by subtraction. If you do not know the allocation, leave it pending. "
                + "No mileage deduction or dependent tax estimate is available until the allocation is supplied.";
    }

    private String mileageChangeDate(SessionId session) {
        LocalDate change = mileageService.changeDate(session);
        return change == null ? "" : change.toString();
    }

    private Object secondMileageRate(SessionId session) {
        return mileageService.changeDate(session) == null
                ? null
                : graph.readDecimal(session, FactPaths.STANDARD_MILEAGE_RATE_AFTER_CHANGE);
    }

    private String mileageProjectionNote(SessionId session) {
        return mileageService.changeDate(session) == null
                ? ""
                : "Actual miles are retained by rate period. Future mileage is projected from observed pace: "
                        + "before the change, the first-period pace is used for both periods; after the change, "
                        + "only the remaining second-period mileage is projected. Future miles are estimates, not actuals.";
    }
}

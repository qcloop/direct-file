package com.realworld.tax.advisor.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Scope screening for one Schedule C. All amounts and income limits remain graph-derived. */
@Component
public class QbiEligibilityTools {
    private static final String SOURCE = "https://www.irs.gov/newsroom/qualified-business-income-deduction";
    private final PlanningGraphService graph;
    private final TaxProjectionTools projections;

    public QbiEligibilityTools(PlanningGraphService graph, TaxProjectionTools projections) {
        this.graph = graph;
        this.projections = projections;
    }

    public enum BusinessType {
        SOLE_PROPRIETOR,
        OTHER_BUSINESS,
        UNSURE
    }

    public enum DomesticBusiness {
        US_BUSINESS,
        OUTSIDE_US,
        UNSURE
    }

    public enum SpecialCases {
        NONE_APPLY,
        SOME_APPLY,
        UNSURE
    }

    public enum Participation {
        PARTICIPATES,
        DOES_NOT_PARTICIPATE,
        UNSURE
    }

    @McpTool(
            generateOutputSchema = true,
            name = "check_qbi_eligibility",
            description = "Check the supported single-Schedule-C QBI planning scope after calculate_se_tax and "
                    + "project_total_tax have recorded all income. Returns missing questions, a supported deduction, "
                    + "or an explicit unsupported reason. Never infer the answers from a 1099. This check does not "
                    + "approve complex cases or change general tax eligibility. Above-threshold results are not "
                    + "confirmed. Pass all answers again on each check; no taxpayer eligibility is guessed.")
    public EligibilityResult checkQbiEligibility(
            @McpToolParam(description = "Planning session ID") String sessionId,
            @McpToolParam(
                            description =
                                    "sole_proprietor: one self-employed business reported on Schedule C, "
                                            + "including a single-member LLC taxed that way; OTHER_BUSINESS or UNSURE otherwise.",
                            required = false)
                    BusinessType businessType,
            @McpToolParam(
                            description = "us_business: all this work is a regular, for-profit US trade or business; "
                                    + "outside_us or UNSURE otherwise.",
                            required = false)
                    DomesticBusiness domesticBusiness,
            @McpToolParam(
                            description = "none_apply only after taxpayer confirms no current/prior business losses, "
                                    + "other businesses/rental/K-1/cooperative income, investment gains/qualified dividends, other "
                                    + "income beyond supplied gigs/W-2s, SE health-insurance/retirement or qualified-tip deductions, "
                                    + "or substantially the same services for an employer left within three years. Otherwise "
                                    + "some_apply or UNSURE. Those cases exceed this calculator's supported scope.",
                            required = false)
                    SpecialCases specialCases,
            @McpToolParam(
                            description =
                                    "Explicit confirmation under IRC 469(h), needed only when the graph requires "
                                            + "material participation for the active-business minimum. Not a general QBI eligibility test.",
                            required = false)
                    Participation participation) {
        SessionId session = SessionId.of(sessionId);
        if (!graph.readFact(session, FactPaths.SE_NET_PROFIT).complete()) {
            return result("needs_facts", "unconfirmed", "income_needed", List.of("gig_income"), null);
        }
        // Scope exclusions are not a finding that a taxpayer cannot claim QBI on their return.
        if (businessType == BusinessType.OTHER_BUSINESS) return unsupported("business_type");
        if (domesticBusiness == DomesticBusiness.OUTSIDE_US) return unsupported("business_location");
        if (specialCases == SpecialCases.SOME_APPLY) return unsupported("special_cases");
        if (businessType == BusinessType.UNSURE
                || domesticBusiness == DomesticBusiness.UNSURE
                || specialCases == SpecialCases.UNSURE) return unsupported("answers_uncertain");
        var above = graph.readFact(session, FactPaths.QBI_ABOVE_THRESHOLD);
        if (!above.complete()) throw new IllegalStateException("Record all income before checking QBI eligibility");
        if (Boolean.TRUE.equals(above.value())) return unsupported("above_threshold");
        List<String> missing = new ArrayList<>();
        if (businessType == null) missing.add("qbi_business_type");
        if (domesticBusiness == null) missing.add("qbi_us_business");
        if (specialCases == null) missing.add("qbi_special_cases");
        if (!missing.isEmpty()) return result("needs_facts", "unconfirmed", "answers_needed", missing, null);
        if (participation == Participation.PARTICIPATES || participation == Participation.DOES_NOT_PARTICIPATE) {
            projections.estimateQbiDeduction(sessionId, null, participation == Participation.PARTICIPATES);
        }
        var deduction = graph.readFact(session, FactPaths.QBI_DEDUCTION);
        if (!deduction.complete()) {
            if (participation == Participation.UNSURE) return unsupported("participation_uncertain");
            return result("needs_facts", "unconfirmed", "participation_needed", List.of("qbi_participation"), null);
        }
        String eligibility = ((BigDecimal) deduction.value()).signum() == 0 ? "no_deduction" : "eligible";
        return result("ok", eligibility, "simple_method", List.of(), deduction.value());
    }

    private EligibilityResult unsupported(String reason) {
        return result("unsupported", "unsupported", reason, List.of(), null);
    }

    private EligibilityResult result(
            String status, String eligibility, String reason, List<String> missing, Object deduction) {
        return new EligibilityResult(status, eligibility, reason, missing, deduction, SOURCE);
    }

    public record EligibilityResult(
            String status,
            String eligibility,
            String reason,
            List<String> missingFields,
            Object deduction,
            String sourceUrl) {}
}

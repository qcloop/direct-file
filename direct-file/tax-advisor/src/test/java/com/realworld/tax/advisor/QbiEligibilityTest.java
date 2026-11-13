package com.realworld.tax.advisor;

import java.math.BigDecimal;

import com.realworld.tax.advisor.mcp.QbiEligibilityTools;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import com.realworld.tax.advisor.mcp.TaxProjectionTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.realworld.tax.advisor.mcp.QbiEligibilityTools.BusinessType.*;
import static com.realworld.tax.advisor.mcp.QbiEligibilityTools.DomesticBusiness.*;
import static com.realworld.tax.advisor.mcp.QbiEligibilityTools.Participation.*;
import static com.realworld.tax.advisor.mcp.QbiEligibilityTools.SpecialCases.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "tax-advisor.allow-draft-artifacts=true")
class QbiEligibilityTest {
    @Autowired
    SessionTools sessions;

    @Autowired
    SelfEmploymentTools se;

    @Autowired
    TaxProjectionTools projections;

    @Autowired
    QbiEligibilityTools eligibility;

    private String session(String year, String gross, String other) {
        String sid = sessions.createSession(year, "single").sessionId();
        se.calculateSeTax(sid, gross, "0", null, null, "0", "0", "0");
        projections.projectTotalTax(sid, other, null);
        return sid;
    }

    @Test
    void asksForMissingScopeAnswersAndDoesNotAssumeEligibility() {
        String sid = session("2026", "60000", "0");
        var result = eligibility.checkQbiEligibility(sid, null, null, null, null);
        assertThat(result.status()).isEqualTo("needs_facts");
        assertThat(result.missingFields()).containsExactly("qbi_business_type", "qbi_us_business", "qbi_special_cases");
        assertThat(result.deduction()).isNull();
    }

    @Test
    void usesTheGraphDeductionAndDoesNotRequireParticipationForGeneralQbi() {
        String sid = session("2025", "60000", "0");
        var result = eligibility.checkQbiEligibility(sid, SOLE_PROPRIETOR, US_BUSINESS, NONE_APPLY, null);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.eligibility()).isEqualTo("eligible");
        assertThat((BigDecimal) result.deduction()).isEqualByComparingTo("8002");
    }

    @Test
    void rejectsOutOfScopeAndUncertainCasesWithoutClaimingNoDeduction() {
        String sid = session("2026", "60000", "0");
        assertThat(eligibility
                        .checkQbiEligibility(sid, OTHER_BUSINESS, US_BUSINESS, NONE_APPLY, null)
                        .reason())
                .isEqualTo("business_type");
        assertThat(eligibility
                        .checkQbiEligibility(sid, SOLE_PROPRIETOR, OUTSIDE_US, NONE_APPLY, null)
                        .reason())
                .isEqualTo("business_location");
        var special = eligibility.checkQbiEligibility(sid, SOLE_PROPRIETOR, US_BUSINESS, SOME_APPLY, null);
        assertThat(special.status()).isEqualTo("unsupported");
        assertThat(special.deduction()).isNull();
        assertThat(eligibility
                        .checkQbiEligibility(
                                sid, SOLE_PROPRIETOR, US_BUSINESS, QbiEligibilityTools.SpecialCases.UNSURE, null)
                        .reason())
                .isEqualTo("answers_uncertain");
        String high = session("2026", "60000", "200000");
        assertThat(eligibility
                        .checkQbiEligibility(high, SOLE_PROPRIETOR, US_BUSINESS, NONE_APPLY, null)
                        .reason())
                .isEqualTo("above_threshold");
    }

    @Test
    void minimumRequiresExplicitParticipationAndUnknownStaysUnknown() {
        String sid = session("2026", "1100", "0");
        assertThat(eligibility
                        .checkQbiEligibility(sid, SOLE_PROPRIETOR, US_BUSINESS, NONE_APPLY, null)
                        .missingFields())
                .containsExactly("qbi_participation");
        assertThat(eligibility
                        .checkQbiEligibility(
                                sid, SOLE_PROPRIETOR, US_BUSINESS, NONE_APPLY, QbiEligibilityTools.Participation.UNSURE)
                        .reason())
                .isEqualTo("participation_uncertain");
        assertThat((BigDecimal) eligibility
                        .checkQbiEligibility(sid, SOLE_PROPRIETOR, US_BUSINESS, NONE_APPLY, PARTICIPATES)
                        .deduction())
                .isEqualByComparingTo("400");
        var noMinimum =
                eligibility.checkQbiEligibility(sid, SOLE_PROPRIETOR, US_BUSINESS, NONE_APPLY, DOES_NOT_PARTICIPATE);
        assertThat(noMinimum.eligibility()).isEqualTo("no_deduction");
        assertThat((BigDecimal) noMinimum.deduction()).isZero();
    }

    @Test
    void zeroIncomeProducesZeroDeduction() {
        String sid = session("2026", "0", "0");
        var result = eligibility.checkQbiEligibility(sid, SOLE_PROPRIETOR, US_BUSINESS, NONE_APPLY, null);
        assertThat(result.eligibility()).isEqualTo("no_deduction");
        assertThat((BigDecimal) result.deduction()).isZero();
    }
}

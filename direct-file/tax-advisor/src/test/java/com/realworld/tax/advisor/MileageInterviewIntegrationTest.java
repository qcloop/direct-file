package com.realworld.tax.advisor;

import java.math.BigDecimal;
import java.util.Map;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService;
import com.realworld.tax.advisor.mcp.InterviewTools;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import com.realworld.tax.advisor.mcp.TaxProjectionTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Total-first collection must preserve actuals and withhold dependent results until allocated. */
@SpringBootTest
class MileageInterviewIntegrationTest {
    @Autowired
    SessionTools sessions;

    @Autowired
    SelfEmploymentTools se;

    @Autowired
    InterviewTools interview;

    @Autowired
    PlanningGraphService graph;

    @Autowired
    TaxProjectionTools projections;

    private String session() {
        String id = sessions.createSession("2026", null).sessionId();
        sessions.setFact(id, FactPaths.SE_MILEAGE_REQUIRE_EXACT, "boolean", null, "true", null);
        return id;
    }

    @Test
    void interviewStartsWithOneTotalAndOnlyShowsTheFollowUpWhenPending() {
        String id = session();
        var facts = Map.<String, Object>of("/hasSelfEmploymentIncome", true, "/usesVehicleForBusiness", true);
        var initial = interview.planQuestions(id, null, null, facts, null, null, false, null);
        assertThat(initial.questions())
                .extracting(TaxKnowledgeService.PlannedQuestion::questionId)
                .contains("se_vehicle_business_miles")
                .doesNotContain(
                        "se_vehicle_business_miles_before_rate_change", "se_vehicle_business_miles_after_rate_change");
        se.calculateSeTax(id, "50000", "12000", "0", "0", "0", null, null);
        var pending = interview.planQuestions(id, null, null, facts, null, null, false, 1);
        assertThat(pending.questions())
                .extracting(TaxKnowledgeService.PlannedQuestion::questionId)
                .containsExactly("se_vehicle_business_miles_before_rate_change");
        se.calculateSeTax(id, null, null, null, null, null, "5000", null);
        var completed = interview.planQuestions(id, null, null, facts, null, null, false, null);
        assertThat(completed.questions())
                .extracting(TaxKnowledgeService.PlannedQuestion::questionId)
                .doesNotContain(
                        "se_vehicle_business_miles",
                        "se_vehicle_business_miles_before_rate_change",
                        "se_vehicle_business_miles_after_rate_change");
    }

    @Test
    void figuresBeforeTheChangeNeedNoFollowUpEvenWhenReportedLaterInTheYear() {
        String id = session();
        var result = se.projectNetProfit(id, "2026-05-31", "20000", "5000", "0", "0", "0", null, null);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.followUpQuestions()).isEmpty();
        assertThat(result.mileageAllocationStatus()).isEqualTo("actual");
        assertThat(result.mileagePeriods().getFirst().actualBusinessMiles()).isEqualTo(5000L);
        assertThat(result.mileagePeriods().getLast().actualBusinessMiles()).isEqualTo(0L);
        assertThat(result.note()).contains("Future miles are estimates");
        assertThat(result.mileagePeriods().getFirst().endDate()).isEqualTo("2026-06-30");
    }

    @Test
    void projectionFollowUpRetainsTheCoverageDateAndOtherInputs() {
        String id = session();
        var pending = se.projectNetProfit(id, "2026-08-31", "32000", "8000", "1000", "0", "0", null, null);
        assertThat(pending.status()).isEqualTo("needs_facts");
        assertThat(pending.projectedNetProfit()).isNull();
        assertThat(pending.followUpQuestions().getFirst().argumentName()).isEqualTo("ytdBusinessMilesBeforeRateChange");
        var completed = se.projectNetProfit(id, null, null, null, null, null, null, "6000", null);
        assertThat(completed.asOfDate()).isEqualTo("2026-08-31");
        assertThat(completed.monthsElapsed()).isEqualTo(8);
        assertThat(completed.ytdGrossReceipts()).isEqualTo("32000.00");
        assertThat((BigDecimal) completed.projectedNetProfit()).isEqualByComparingTo("37590");
        assertThat(completed.mileagePeriods().getLast().actualBusinessMiles()).isEqualTo(2000L);
    }

    @Test
    void callerMayProvideTotalAndEitherPeriodTogether() {
        String id = session();
        var result = se.calculateSeTax(id, "50000", "12000", "0", "0", "0", null, "7000");
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.followUpQuestions()).isEmpty();
        assertThat(result.mileagePeriods().getFirst().actualBusinessMiles()).isEqualTo(5000L);
        assertThat((BigDecimal) result.netProfit()).isEqualByComparingTo("41055");
    }

    @Test
    void zeroMilesAndSingleRateYearsNeedNoAllocation() {
        var zero = se.calculateSeTax(session(), "50000", "0", "0", "0", "0", null, null);
        assertThat(zero.status()).isEqualTo("ok");
        assertThat(zero.followUpQuestions()).isEmpty();
        String historical = sessions.createSession("2025", null).sessionId();
        var single = se.calculateSeTax(historical, "50000", "12000", "0", "0", "0", null, null);
        assertThat(single.status()).isEqualTo("ok");
        assertThat(single.mileagePeriods()).hasSize(1);
        assertThat(single.followUpQuestions()).isEmpty();
    }

    @Test
    void explicitZeroBeforeChangeAllocatesAllMilesToTheLaterPeriod() {
        var result = se.calculateSeTax(session(), "50000", "10000", "0", "0", "0", "0", null);
        assertThat(result.status()).isEqualTo("ok");
        assertThat((BigDecimal) result.netProfit()).isEqualByComparingTo("42400");
    }

    @Test
    void anUnresolvedReplacementInvalidatesEarlierTaxResults() {
        String id = session();
        se.calculateSeTax(id, "50000", "10000", "0", "0", "0", "5000", null);
        assertThat(graph.readFact(SessionId.of(id), FactPaths.SE_TAX).complete())
                .isTrue();
        var pending = se.calculateSeTax(id, "60000", "12000", "0", "0", "0", null, null);
        assertThat(pending.status()).isEqualTo("needs_facts");
        assertThat(pending.note()).contains("leave it pending");
        assertThat(graph.readFact(SessionId.of(id), FactPaths.SE_NET_PROFIT).complete())
                .isFalse();
        assertThat(graph.readFact(SessionId.of(id), FactPaths.SE_TAX).complete())
                .isFalse();
        assertThat(projections.projectTotalTax(id, null, null).status()).isEqualTo("needs_facts");
    }

    @Test
    void invalidAllocationAndMalformedAmountsDoNotPartiallyOverwriteThePendingRequest() {
        String id = session();
        se.calculateSeTax(id, "50000", "10000", "1000", "0", "0", null, null);
        var saved = graph.sessionFacts(SessionId.of(id));
        assertThatThrownBy(() -> se.calculateSeTax(id, null, null, null, null, null, "10001", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed");
        assertThat(graph.sessionFacts(SessionId.of(id))).isEqualTo(saved);
        assertThatThrownBy(() -> se.calculateSeTax(id, "not-money", "10000", "0", "0", "0", "5000", null))
                .isInstanceOf(RuntimeException.class);
        assertThat(graph.sessionFacts(SessionId.of(id))).isEqualTo(saved);
    }

    @Test
    void pendingDataCannotBeReusedForAnotherDateOrCalculationMode() {
        String id = session();
        se.projectNetProfit(id, "2026-08-31", "32000", "8000", "0", "0", "0", null, null);
        assertThatThrownBy(() -> se.projectNetProfit(id, "2026-09-30", null, null, null, null, null, "6000", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> se.calculateSeTax(id, null, null, null, null, null, "6000", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(graph.sessionFacts(SessionId.of(id)).get(FactPaths.SE_MILEAGE_AS_OF))
                .isEqualTo("2026-08-31");
    }
}

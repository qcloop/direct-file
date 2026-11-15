package com.realworld.tax.advisor;

import java.math.BigDecimal;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import com.realworld.tax.advisor.report.PlanReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A total-only planning estimate must be computable, disclosed, and replaceable by actuals. */
@SpringBootTest
class EstimatedMileageIntegrationTest {
    @Autowired
    SessionTools sessions;

    @Autowired
    SelfEmploymentTools se;

    @Autowired
    PlanningGraphService graph;

    @Autowired
    PlanReportService reports;

    private String session() {
        return sessions.createSession("2026", null).sessionId();
    }

    @Test
    void totalOnlyFullYearMileageProducesALabeledEstimateWithoutInventedActualRecords() {
        String id = session();
        var result = se.calculateSeTax(id, "50000", "3650", "0", "0", "0", null, null);
        // Ten miles per calendar day: 1,810 before July, 1,840 from July. Round once:
        // 1,810 * .725 + 1,840 * .76 = 2,710.65 -> 2,711.
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.mileageAllocationStatus()).isEqualTo("estimated");
        assertThat(result.followUpQuestions()).isEmpty();
        assertThat(result.mileageAllocationNote()).contains("evenly", "2026-01-01", "2026-12-31");
        assertThat((BigDecimal) result.netProfit()).isEqualByComparingTo("47289");
        assertThat(result.mileagePeriods())
                .allSatisfy(period -> assertThat(period.actualBusinessMiles()).isNull());
        assertThat((BigDecimal) result.mileagePeriods().getFirst().estimatedBusinessMiles())
                .isEqualByComparingTo("1810");
        assertThat((BigDecimal) result.mileagePeriods().getLast().estimatedBusinessMiles())
                .isEqualByComparingTo("1840");
        assertThat(graph.readFact(SessionId.of(id), FactPaths.SE_MILES_BEFORE_CHANGE)
                        .complete())
                .isFalse();
        assertThat(graph.readFact(SessionId.of(id), FactPaths.SE_MILES_AFTER_CHANGE)
                        .complete())
                .isFalse();
        assertThat(reports.generate(SessionId.of(id)).markdown()).contains("Mileage allocation is estimated");
    }

    @Test
    void emptyPeriodPlaceholdersDoNotRejectOrFabricateAnAllocationOfTheTotal() {
        String id = session();
        var result = se.calculateSeTax(id, "50000", "3650", "0", "0", "0", "0", "0");
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.mileageAllocationStatus()).isEqualTo("estimated");
        assertThat((BigDecimal) result.netProfit()).isEqualByComparingTo("47289");
        assertThat(result.mileagePeriods())
                .allSatisfy(p -> assertThat(p.actualBusinessMiles()).isNull());
        sessions.setFact(id, FactPaths.SE_MILEAGE_REQUIRE_EXACT, "boolean", null, "true", null);
        var exact = se.calculateSeTax(id, null, null, null, null, null, "0", "0");
        assertThat(exact.status()).isEqualTo("needs_facts");
        assertThat(exact.selfEmploymentTax()).isNull();
    }

    @Test
    void totalOnlyYtdMileageUsesTheCoveredDateAndProjectsFutureMileageSeparately() {
        String id = session();
        var result = se.projectNetProfit(id, "2026-08-31", "32000", "2430", "0", "0", "0", null, null);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.mileageAllocationStatus()).isEqualTo("estimated");
        assertThat(result.mileageAllocationNote()).contains("2026-08-31");
        assertThat((BigDecimal) result.projectedBusinessMiles()).isEqualByComparingTo("3650");
        assertThat((BigDecimal) result.recordedInputs().grossReceipts()).isEqualByComparingTo("32000");
        assertThat((BigDecimal) result.mileagePeriods().getFirst().estimatedBusinessMiles())
                .isEqualByComparingTo("1810");
        assertThat((BigDecimal) result.mileagePeriods().getLast().estimatedBusinessMiles())
                .isEqualByComparingTo("620");
        assertThat((BigDecimal) result.projectedNetProfit()).isEqualByComparingTo("45289");
        assertThat(result.followUpQuestions()).isEmpty();
    }

    @Test
    void actualPeriodAnswerRefinesTheEstimateAndClearsItsLabelWithoutRepeatingInputs() {
        String id = session();
        se.calculateSeTax(id, "50000", "10000", "1000", "0", "0", null, null);
        var refined = se.calculateSeTax(id, null, null, null, null, null, "10000", null);
        assertThat(refined.mileageAllocationStatus()).isEqualTo("actual");
        assertThat(refined.mileageAllocationNote()).isEmpty();
        assertThat((BigDecimal) refined.netProfit()).isEqualByComparingTo("41750");
        assertThat(refined.mileagePeriods())
                .allSatisfy(
                        period -> assertThat(period.estimatedBusinessMiles()).isNull());
        assertThat(reports.generate(SessionId.of(id)).markdown()).doesNotContain("Mileage allocation is estimated");
    }

    @Test
    void exactOnlyPreferenceWithholdsDependentResultsUntilActualAllocationIsKnown() {
        String id = session();
        se.calculateSeTax(id, "50000", "10000", "0", "0", "0", null, null);
        sessions.setFact(id, FactPaths.SE_MILEAGE_REQUIRE_EXACT, "boolean", null, "true", null);
        var exact = se.calculateSeTax(id, null, null, null, null, null, null, null);
        assertThat(exact.status()).isEqualTo("needs_facts");
        assertThat(exact.selfEmploymentTax()).isNull();
        assertThat(exact.followUpQuestions()).hasSize(1);
        assertThat(graph.readFact(SessionId.of(id), FactPaths.SE_TAX).complete())
                .isFalse();
    }

    @Test
    void preChangeAndZeroMileageRemainActualWithNoAllocationAssumption() {
        var early = se.projectNetProfit(session(), "2026-05-31", "20000", "5000", "0", "0", "0", null, null);
        assertThat(early.mileageAllocationStatus()).isEqualTo("actual");
        assertThat(early.mileageAllocationNote()).isEmpty();
        var zero = se.calculateSeTax(session(), "50000", "0", "0", "0", "0", null, null);
        assertThat(zero.mileageAllocationStatus()).isEqualTo("actual");
        assertThat(zero.mileageAllocationNote()).isEmpty();
    }

    @Test
    void invalidRefinementCannotReplaceTheSavedEstimate() {
        String id = session();
        se.calculateSeTax(id, "50000", "10000", "0", "0", "0", null, null);
        var saved = graph.sessionFacts(SessionId.of(id));
        assertThatThrownBy(() -> se.calculateSeTax(id, null, null, null, null, null, "10001", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(graph.sessionFacts(SessionId.of(id))).isEqualTo(saved);
    }
}

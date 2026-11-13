package com.realworld.tax.advisor;

import java.math.BigDecimal;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.graph.SessionSnapshot;
import com.realworld.tax.advisor.graph.SessionStore;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import com.realworld.tax.advisor.mcp.TaxProjectionTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independently calculated boundaries from the pinned final IRS 2026 sources. */
@SpringBootTest
class TaxYear2026IntegrationTest {
    @Autowired
    SessionTools sessions;

    @Autowired
    SelfEmploymentTools selfEmployment;

    @Autowired
    TaxProjectionTools projections;

    @Autowired
    PlanningGraphService graph;

    @Autowired
    SessionStore store;

    @Test
    void retainedSessionsCannotBeReinterpretedByAnotherKnowledgeRelease() {
        SessionId id = SessionId.of(session());
        var current = store.find(id).orElseThrow();
        var snapshot = current.snapshot();
        store.replace(
                id,
                current,
                new SessionSnapshot(
                        snapshot.taxYear(),
                        snapshot.filingStatus(),
                        snapshot.facts(),
                        snapshot.sourceNotes(),
                        "another-release"));
        assertThatThrownBy(() -> graph.readFact(id, FactPaths.STANDARD_DEDUCTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different tax-knowledge release");
        graph.discardSession(id);
    }

    private String session() {
        String id = sessions.createSession("2026", null).sessionId();
        sessions.setFact(id, FactPaths.SE_MILEAGE_REQUIRE_EXACT, "boolean", null, "true", null);
        return id;
    }

    private BigDecimal value(String id, String path) {
        return (BigDecimal) graph.readFact(SessionId.of(id), path).value();
    }

    @Test
    void appliesBothMileageRatesToFullYearActualsAndCitesTheirBoundaries() {
        String id = session();
        var result = selfEmployment.calculateSeTax(id, "50000", null, "0", "0", "0", "5000", "5000");
        // 5,000 x .725 + 5,000 x .76 = 7,425, not 7,250 or 7,600.
        assertThat(value(id, "/seVehicleDeduction")).isEqualByComparingTo("7425");
        assertThat(result.mileageRateChangeDate()).isEqualTo("2026-07-01");
        assertThat((BigDecimal) result.standardMileageRateAfterChange()).isEqualByComparingTo("0.76");
        assertThat((BigDecimal) result.netProfit()).isEqualByComparingTo("42575");
    }

    @Test
    void retainsUnallocatedMileageAndAsksOneFollowUpWithoutReturningTax() {
        String id = session();
        var pending = selfEmployment.calculateSeTax(id, "50000", "10000", "0", "0", "0", null, null);
        assertThat(pending.status()).isEqualTo("needs_facts");
        assertThat(pending.businessMiles()).isEqualTo(10000L);
        assertThat(pending.selfEmploymentTax()).isNull();
        assertThat(pending.followUpQuestions()).hasSize(1);
        assertThat(pending.followUpQuestions().getFirst().argumentName()).isEqualTo("businessMilesBeforeRateChange");
        assertThat(graph.readFact(SessionId.of(id), FactPaths.SE_GROSS_RECEIPTS).complete())
                .isTrue();
        assertThat(graph.readFact(SessionId.of(id), FactPaths.SE_TAX).complete())
                .isFalse();
        var completed = selfEmployment.calculateSeTax(id, null, null, null, null, null, "4000", null);
        assertThat(completed.status()).isEqualTo("ok");
        assertThat(value(id, "/seVehicleDeduction")).isEqualByComparingTo("7460");
        assertThatThrownBy(() -> selfEmployment.calculateSeTax(id, "50000", "10000", "0", "0", "0", "4000", "5000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal");
        assertThatThrownBy(() -> selfEmployment.calculateSeTax(id, "50000", null, "0", "0", "0", "-1", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonnegative");
    }

    @Test
    void projectsTheTwoPeriodsFromTheirOwnObservedMileagePaces() {
        String id = session();
        // March: 1,000 observed miles project to 2,000 in each half: 1,450 + 1,520 = 2,970.
        selfEmployment.projectNetProfit(id, "2026-03-31", "12000", "1000", "0", "0", "0", null, null);
        assertThat(value(id, "/seVehicleDeduction")).isEqualByComparingTo("2970");
        // August: first half is complete (6,000), two observed second-half months (2,000)
        // project to 6,000 second-half miles: 4,350 + 4,560 = 8,910.
        selfEmployment.projectNetProfit(id, "2026-08-31", "32000", null, "0", "0", "0", "6000", "2000");
        assertThat(value(id, "/seVehicleDeduction")).isEqualByComparingTo("8910");
        // Switching back to actuals must reset both period factors.
        selfEmployment.calculateSeTax(id, "50000", null, "0", "0", "0", "6000", "2000");
        assertThat(value(id, "/seVehicleDeduction")).isEqualByComparingTo("5870");
    }

    @Test
    void requiresPeriodAllocationStartingOnTheChangeDate() {
        String id = session();
        selfEmployment.projectNetProfit(id, "2026-06-30", "12000", "1000", "0", "0", "0", null, null);
        assertThat(value(id, "/seVehicleDeduction")).isEqualByComparingTo("1485");
        assertThat(selfEmployment
                        .projectNetProfit(id, "2026-07-01", "12000", "1000", "0", "0", "0", null, null)
                        .status())
                .isEqualTo("needs_facts");
        assertThatThrownBy(() ->
                        selfEmployment.projectNetProfit(id, "2026-06-30", "12000", null, "0", "0", "0", "1000", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be positive");
    }

    @Test
    void qbiMinimumRequiresIncomeThresholdAndConfirmedMaterialParticipation() {
        String below = session();
        selfEmployment.calculateSeTax(below, "1075", "0", "0", "0", "0", null, null);
        assertThat(value(below, FactPaths.QUALIFIED_BUSINESS_INCOME)).isEqualByComparingTo("999");
        assertThat((BigDecimal)
                        projections.estimateQbiDeduction(below, null, null).qbiDeduction())
                .isEqualByComparingTo("0");

        String at = session();
        selfEmployment.calculateSeTax(at, "1076", "0", "0", "0", "0", null, null);
        assertThatThrownBy(() ->
                        sessions.setFact(at, FactPaths.QBI_MATERIAL_PARTICIPATION, "boolean", null, "unknown", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown answer unset");
        assertThat(value(at, FactPaths.QUALIFIED_BUSINESS_INCOME)).isEqualByComparingTo("1000");
        var unknown = projections.estimateQbiDeduction(at, null, null);
        assertThat(unknown.status()).isEqualTo("needs_facts");
        assertThat(unknown.qbiDeduction()).isNull();
        assertThat(unknown.note()).contains("material participation");
        assertThat(projections.projectTotalTax(at, null, null).status()).isEqualTo("needs_facts");
        assertThat((BigDecimal) projections.estimateQbiDeduction(at, null, true).qbiDeduction())
                .isEqualByComparingTo("400");
        assertThat((BigDecimal)
                        projections.estimateQbiDeduction(at, null, false).qbiDeduction())
                .isEqualByComparingTo("0");
    }

    @Test
    void injectsDistinctMfsQbiAndSafeHarborThresholdsAndCorrected2025Deduction() {
        String mfs = sessions.createSession("2026", "mfs").sessionId();
        assertThat(value(mfs, FactPaths.QBI_THRESHOLD)).isEqualByComparingTo("201775");
        assertThat(value(mfs, "/highIncomeSafeHarborThreshold")).isEqualByComparingTo("75000");
        sessions.setFact(mfs, FactPaths.PRIOR_YEAR_AGI, "dollar", null, "75000", null);
        assertThat(graph.readFact(SessionId.of(mfs), FactPaths.HIGH_INCOME_SAFE_HARBOR_APPLIES)
                        .value())
                .isEqualTo(false);
        sessions.setFact(mfs, FactPaths.PRIOR_YEAR_AGI, "dollar", null, "75001", null);
        assertThat(graph.readFact(SessionId.of(mfs), FactPaths.HIGH_INCOME_SAFE_HARBOR_APPLIES)
                        .value())
                .isEqualTo(true);
        String historical = sessions.createSession("2025", "mfj").sessionId();
        assertThat(value(historical, FactPaths.STANDARD_DEDUCTION)).isEqualByComparingTo("31500");
    }
}

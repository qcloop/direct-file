package com.realworld.tax.advisor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import com.realworld.tax.advisor.report.PlanReportService;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.realworld.tax.advisor.PropertySupport.*;
import static dev.hegel.Generators.*;
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

    @HegelTest(testCases = 1000)
    void generatedTotalOnlyMileageUsesCalendarDaySharesWithoutActualRecords(TestCase tc) {
        int miles = tc.draw(integers().min(1), "totalMiles");
        LocalDate date = tc.draw(dates().min(LocalDate.of(2026, 7, 1)).max(LocalDate.of(2026, 12, 31)), "asOf");
        try (var session = PropertySupport.session(graph, 2026, filingStatus(tc))) {
            var result = se.projectNetProfit(
                    session.id(), date.toString(), "50000", Integer.toString(miles), "0", "0", "0", null, null);
            assertThat(result.mileageAllocationStatus()).isEqualTo("estimated");
            assertThat(result.mileagePeriods())
                    .allSatisfy(p -> assertThat(p.actualBusinessMiles()).isNull());
            assertThat(graph.readFact(session.sessionId(), FactPaths.SE_MILES_BEFORE_CHANGE)
                            .complete())
                    .isFalse();
            assertThat(graph.readFact(session.sessionId(), FactPaths.SE_MILES_AFTER_CHANGE)
                            .complete())
                    .isFalse();
            // Mileage projection follows calendar days, independently of receipt annualization by month.
            BigDecimal expected = BigDecimal.valueOf(miles)
                    .multiply(BigDecimal.valueOf(365))
                    .divide(BigDecimal.valueOf(date.getDayOfYear()), 10, RoundingMode.HALF_UP);
            assertThat((BigDecimal) result.projectedBusinessMiles()).isEqualByComparingTo(expected);
        }
    }

    @HegelTest(testCases = 1000)
    void aSinglePeriodRefinementAgreesWithCompleteActualInput(TestCase tc) {
        int total = tc.draw(integers().min(1), "totalMiles");
        int before = tc.draw(integers().min(0).max(total), "beforeMiles");
        boolean answerFirst = tc.draw(booleans(), "answerFirstPeriod");
        boolean projection = tc.draw(booleans(), "projection");
        String gross = dollars(tc, "gross").toPlainString();
        String fees = dollars(tc, "fees").toPlainString();
        String status = filingStatus(tc);
        try (var refined = PropertySupport.session(graph, 2026, status);
                var fresh = PropertySupport.session(graph, 2026, status)) {
            String b = Integer.toString(before);
            String a = Integer.toString(total - before);
            if (projection) {
                se.projectNetProfit(
                        refined.id(), "2026-08-31", gross, Integer.toString(total), fees, "0", "0", null, null);
                se.projectNetProfit(
                        refined.id(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        answerFirst ? b : null,
                        answerFirst ? null : a);
                se.projectNetProfit(fresh.id(), "2026-08-31", gross, Integer.toString(total), fees, "0", "0", b, a);
            } else {
                se.calculateSeTax(refined.id(), gross, Integer.toString(total), fees, "0", "0", null, null);
                se.calculateSeTax(
                        refined.id(), null, null, null, null, null, answerFirst ? b : null, answerFirst ? null : a);
                se.calculateSeTax(fresh.id(), gross, Integer.toString(total), fees, "0", "0", b, a);
            }
            for (String path : new String[] {
                FactPaths.SE_NET_PROFIT,
                FactPaths.SE_TAX,
                FactPaths.SE_MILES_BEFORE_CHANGE,
                FactPaths.SE_MILES_AFTER_CHANGE,
                FactPaths.SE_GROSS_RECEIPTS,
                FactPaths.SE_PLATFORM_FEES
            }) {
                assertThat(graph.readFact(refined.sessionId(), path).value())
                        .as(path)
                        .isEqualTo(graph.readFact(fresh.sessionId(), path).value());
            }
        }
    }

    @HegelTest(testCases = 1000)
    void invalidRefinementLeavesTheWholeSavedEstimateUnchanged(TestCase tc) {
        int total = tc.draw(integers().min(1), "totalMiles");
        String invalid = tc.draw(
                sampledFrom("-1", "0.5", "9223372036854775808", Long.toString((long) total + 1)), "invalidPeriod");
        boolean projection = tc.draw(booleans(), "projection");
        try (var session = PropertySupport.session(graph, 2026, filingStatus(tc))) {
            if (projection)
                se.projectNetProfit(
                        session.id(), "2026-08-31", "50000", Integer.toString(total), "0", "0", "0", null, null);
            else se.calculateSeTax(session.id(), "50000", Integer.toString(total), "0", "0", "0", null, null);
            var saved = graph.sessionFacts(session.sessionId());
            if (projection)
                assertThatThrownBy(() ->
                                se.projectNetProfit(session.id(), null, null, null, null, null, null, invalid, null))
                        .isInstanceOf(IllegalArgumentException.class);
            else
                assertThatThrownBy(() -> se.calculateSeTax(session.id(), null, null, null, null, null, invalid, null))
                        .isInstanceOf(IllegalArgumentException.class);
            assertThat(graph.sessionFacts(session.sessionId())).isEqualTo(saved);
        }
    }

    @HegelTest(testCases = 1000)
    void replacingActualsClearsStaleAllocationAndExactOnlyWithholdsTax(TestCase tc) {
        int total = tc.draw(integers().min(1), "totalMiles");
        int before = tc.draw(integers().min(0).max(total), "beforeMiles");
        boolean exact = tc.draw(booleans(), "requireExact");
        try (var session = PropertySupport.session(graph, 2026, filingStatus(tc))) {
            se.projectNetProfit(
                    session.id(),
                    "2026-08-31",
                    "50000",
                    Integer.toString(total),
                    "0",
                    "0",
                    "0",
                    Integer.toString(before),
                    Integer.toString(total - before));
            sessions.setFact(
                    session.id(), FactPaths.SE_MILEAGE_REQUIRE_EXACT, "boolean", null, Boolean.toString(exact), null);
            var replaced = se.calculateSeTax(session.id(), "50000", Integer.toString(total), "0", "0", "0", null, null);
            assertThat(replaced.mileageAllocationStatus()).isEqualTo(exact ? "pending" : "estimated");
            assertThat(graph.readFact(session.sessionId(), FactPaths.SE_MILES_BEFORE_CHANGE)
                            .complete())
                    .isFalse();
            assertThat(graph.readFact(session.sessionId(), FactPaths.SE_MILES_AFTER_CHANGE)
                            .complete())
                    .isFalse();
            if (exact) assertThat(replaced.selfEmploymentTax()).isNull();
        }
    }

    @HegelTest(testCases = 1000)
    void actualPeriodDeductionAppliesEachReleasedRate(TestCase tc) {
        int total = tc.draw(integers().min(0), "totalMiles");
        int before = tc.draw(integers().min(0).max(total), "beforeMiles");
        try (var session = PropertySupport.session(graph, 2026, filingStatus(tc))) {
            se.calculateSeTax(
                    session.id(),
                    "50000",
                    Integer.toString(total),
                    "0",
                    "0",
                    "0",
                    Integer.toString(before),
                    Integer.toString(total - before));
            BigDecimal first = money(BigDecimal.valueOf(before).multiply(new BigDecimal("0.725")));
            BigDecimal second = money(BigDecimal.valueOf(total - before).multiply(new BigDecimal("0.76")));
            assertThat(graph.readDecimal(session.sessionId(), "/seVehicleDeduction"))
                    .isEqualByComparingTo(rounded(first.add(second)));
        }
    }
}

package com.realworld.tax.advisor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.realworld.tax.advisor.PropertySupport.*;
import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Projected net profit: the annualization path scales year-to-date raw numbers to a full year in
 * the fact graph (× 12 ÷ months of data), then runs the same Schedule C/SE math. The direct path
 * (full-year actuals) must be unchanged — it just uses an annualization factor of 1.
 */
@SpringBootTest
class ProjectNetProfitTest {

    @Autowired
    PlanningGraphService graph;

    @Autowired
    SessionTools sessionTools;

    @Autowired
    SelfEmploymentTools selfEmploymentTools;

    @HegelTest(testCases = 1000)
    void annualizesYearToDateFiguresToAFullYear(TestCase tc) {
        int year = tc.draw(sampledFrom(2024, 2025), "singleRateYear");
        LocalDate date = tc.draw(dates().min(LocalDate.of(year, 1, 1)).max(LocalDate.of(year, 12, 31)), "asOf");
        BigDecimal gross = dollars(tc, "gross");
        BigDecimal fees = dollars(tc, "fees");
        BigDecimal supplies = dollars(tc, "supplies");
        BigDecimal miles = BigDecimal.valueOf(tc.draw(integers().min(0), "miles"));
        BigDecimal rate = new BigDecimal(year == 2024 ? "0.67" : "0.70");
        BigDecimal month = BigDecimal.valueOf(date.getMonthValue());
        try (var session = session(graph, year, filingStatus(tc))) {
            var out = selfEmploymentTools.projectNetProfit(
                    session.id(),
                    date.toString(),
                    gross.toPlainString(),
                    miles.toPlainString(),
                    fees.toPlainString(),
                    supplies.toPlainString(),
                    "0",
                    null,
                    null);
            BigDecimal receipts =
                    rounded(gross.multiply(BigDecimal.valueOf(12)).divide(month, 2, RoundingMode.HALF_EVEN));
            BigDecimal expenses = rounded(
                            fees.multiply(BigDecimal.valueOf(12)).divide(month, 2, RoundingMode.HALF_EVEN))
                    .add(rounded(supplies.multiply(BigDecimal.valueOf(12)).divide(month, 2, RoundingMode.HALF_EVEN)))
                    .add(rounded(miles.multiply(rate)
                            .multiply(BigDecimal.valueOf(12))
                            .divide(month, 2, RoundingMode.HALF_EVEN)));
            assertThat((BigDecimal) out.projectedNetProfit())
                    .isEqualByComparingTo(receipts.subtract(expenses).max(BigDecimal.ZERO));
            assertThat((BigDecimal) out.projectedGrossReceipts()).isEqualByComparingTo(receipts);
            assertThat(out.monthsElapsed()).isEqualTo(date.getMonthValue());
        }
    }

    @HegelTest(testCases = 1000)
    void fullYearActualsAgreeWithDecemberProjection(TestCase tc) {
        int year = year(tc);
        String status = filingStatus(tc);
        String gross = dollars(tc, "gross").toPlainString();
        String fees = dollars(tc, "fees").toPlainString();
        String supplies = dollars(tc, "supplies").toPlainString();
        String wages = dollars(tc, "wages").toPlainString();
        String miles = tc.draw(integers().min(0), "miles").toString();
        try (var actual = session(graph, year, status);
                var projected = session(graph, year, status)) {
            var a = selfEmploymentTools.calculateSeTax(actual.id(), gross, miles, fees, supplies, wages, null, null);
            var p = selfEmploymentTools.projectNetProfit(
                    projected.id(), year + "-12-31", gross, miles, fees, supplies, wages, null, null);
            assertThat((BigDecimal) p.projectedNetProfit()).isEqualByComparingTo((BigDecimal) a.netProfit());
            assertThat((BigDecimal) p.projectedSelfEmploymentTax())
                    .isEqualByComparingTo((BigDecimal) a.selfEmploymentTax());
            assertThat((BigDecimal) p.deductibleHalf()).isEqualByComparingTo((BigDecimal) a.deductibleHalf());
        }
    }

    @Test
    void halfCentMileageRateIsExactNotRoundedToCents() {
        // The 2026 rate is 72.5¢/mile. Modeled as a Rational it is exact: 10,000 mi × $0.725 = $7,250.
        // A cents-precision Dollar would have stored 0.72 and given $7,200 — silently understating the
        // deduction (the user-reported gap). calculate_se_tax writes a 1/1 factor, so no annualization.
        SessionId s2026 = graph.createSession(2026);
        selfEmploymentTools.calculateSeTax(s2026.value(), "50000", null, "0", "0", "0", "10000", "0");

        assertThat((BigDecimal) graph.readFact(s2026, "/seVehicleDeduction").value())
                .isEqualByComparingTo("7250");
    }

    @Test
    void flagsProvisionalConstantsForADraftYearButNotAFinalizedOne() {
        // Constants are verified, but the release is still a draft pending human tax review.
        String s2026 = sessionTools.createSession("2026", null).sessionId();
        var out2026 =
                selfEmploymentTools.projectNetProfit(s2026, "2026-07-01", "21000", null, "0", "0", "0", "6000", "1000");
        assertThat(out2026.provisionalWarning())
                .contains("2026")
                .contains("PREVIEW")
                .contains("approval");

        // 2025 is finalized — no warning.
        String s2025 = sessionTools.createSession("2025", null).sessionId();
        var out2025 =
                selfEmploymentTools.projectNetProfit(s2025, "2025-07-01", "21000", "7000", "0", "0", "0", null, null);
        assertThat(out2025.provisionalWarning()).isEmpty();
    }

    @Test
    void createSessionWarnsOnlyForADraftYear() {
        assertThat(sessionTools.createSession("2026", null).provisionalWarning())
                .isNotEmpty();
        assertThat(sessionTools.createSession("2025", null).provisionalWarning())
                .isEmpty();
    }

    @HegelTest(testCases = 1000)
    void rejectsDatesOutsideTheSessionYearWithoutChangingFacts(TestCase tc) {
        int year = year(tc);
        LocalDate date = tc.draw(dates(), "outsideDate");
        if (date.getYear() == year) {
            date = date.plusYears(1);
        }
        String outside = date.toString();
        try (var session = session(graph, year, filingStatus(tc))) {
            var before = graph.sessionFacts(session.sessionId());
            assertThatThrownBy(() -> selfEmploymentTools.projectNetProfit(
                            session.id(), outside, "5000", "0", "0", "0", "0", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not within tax year " + year);
            assertThat(graph.sessionFacts(session.sessionId())).isEqualTo(before);
        }
    }
}

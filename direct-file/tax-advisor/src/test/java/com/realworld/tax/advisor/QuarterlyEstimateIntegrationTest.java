package com.realworld.tax.advisor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.mcp.QuarterlyPaymentTools;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import com.realworld.tax.advisor.mcp.TaxProjectionTools;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.realworld.tax.advisor.PropertySupport.*;
import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.*;

/** Safe harbor is a prepayment target, distinct from total tax and from the signed filing balance. */
@SpringBootTest
class QuarterlyEstimateIntegrationTest {
    @Autowired
    PlanningGraphService graph;

    @Autowired
    SessionTools sessionTools;

    @Autowired
    SelfEmploymentTools selfEmploymentTools;

    @Autowired
    TaxProjectionTools projections;

    @Autowired
    QuarterlyPaymentTools quarterlyPaymentTools;

    @HegelTest(testCases = 1000)
    void recommendationMatchesSafeHarborAcrossIncomeTiersAndDeadlines(TestCase tc) {
        int year = year(tc);
        String status = filingStatus(tc);
        BigDecimal gross = dollars(tc, "profit");
        BigDecimal priorTax = dollars(tc, "priorTax");
        BigDecimal threshold = new BigDecimal(status.equals("mfs") ? "75000" : "150000");
        BigDecimal agi = tc.draw(booleans(), "nearThreshold")
                ? threshold.add(BigDecimal.valueOf(tc.draw(integers().min(-1).max(1), "thresholdCents"), 2))
                : dollars(tc, "priorAgi");
        BigDecimal withheld = dollars(tc, "withheld");
        BigDecimal paid = dollars(tc, "paid");
        LocalDate date = tc.draw(dates().min(LocalDate.of(year, 1, 1)).max(LocalDate.of(year + 1, 2, 1)), "asOf");
        try (var session = session(graph, year, status)) {
            selfEmploymentTools.calculateSeTax(session.id(), gross.toPlainString(), null, null, null, null, null, null);
            projections.estimateQbiDeduction(session.id(), null, true);
            write(session.id(), FactPaths.PRIOR_YEAR_TOTAL_TAX, priorTax);
            write(session.id(), FactPaths.PRIOR_YEAR_AGI, agi);
            write(session.id(), FactPaths.YTD_WITHHOLDING, withheld);
            write(session.id(), FactPaths.YTD_ESTIMATED_PAYMENTS_MADE, paid);
            var result = quarterlyPaymentTools.estimateQuarterlyPayment(session.id(), date.toString());
            List<LocalDate> deadlines = List.of(
                            LocalDate.of(year, 4, 15),
                            LocalDate.of(year, 6, 15),
                            LocalDate.of(year, 9, 15),
                            LocalDate.of(year + 1, 1, 15))
                    .stream()
                    .map(d -> {
                        while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
                        return d;
                    })
                    .toList();
            long remaining = Math.max(
                    1, deadlines.stream().filter(d -> !d.isBefore(date)).count());
            LocalDate next = deadlines.stream()
                    .filter(d -> !d.isBefore(date))
                    .findFirst()
                    .orElse(deadlines.getLast());
            BigDecimal prior =
                    rounded(agi.compareTo(threshold) > 0 ? money(priorTax.multiply(new BigDecimal("1.10"))) : priorTax);
            BigDecimal current =
                    rounded(money(((BigDecimal) result.projectedTotalTax()).multiply(new BigDecimal("0.90"))));
            BigDecimal due =
                    prior.min(current).subtract(withheld).subtract(paid).max(BigDecimal.ZERO);
            BigDecimal expected = rounded(due.divide(BigDecimal.valueOf(remaining), 2, RoundingMode.HALF_EVEN));
            assertThat((BigDecimal) result.recommendedPayment()).isEqualByComparingTo(expected);
            assertThat(result.nextDeadline()).isEqualTo(next.toString());
        }
    }

    @HegelTest(testCases = 1000)
    void repartitioningPaymentsPreservesRecommendationAndBalance(TestCase tc) {
        BigDecimal withheld = dollars(tc, "withheld");
        BigDecimal paid = dollars(tc, "paid");
        try (var session = session(graph, tc)) {
            selfEmploymentTools.calculateSeTax(
                    session.id(), dollars(tc, "profit").toPlainString(), null, null, null, null, null, null);
            projections.estimateQbiDeduction(session.id(), null, true);
            write(session.id(), FactPaths.PRIOR_YEAR_TOTAL_TAX, dollars(tc, "priorTax"));
            write(session.id(), FactPaths.PRIOR_YEAR_AGI, dollars(tc, "priorAgi"));
            write(session.id(), FactPaths.YTD_WITHHOLDING, withheld);
            write(session.id(), FactPaths.YTD_ESTIMATED_PAYMENTS_MADE, paid);
            String date = graph.taxYearOf(session.sessionId()) + "-08-20";
            var first = quarterlyPaymentTools.estimateQuarterlyPayment(session.id(), date);
            write(session.id(), FactPaths.YTD_WITHHOLDING, BigDecimal.ZERO);
            write(session.id(), FactPaths.YTD_ESTIMATED_PAYMENTS_MADE, withheld.add(paid));
            var second = quarterlyPaymentTools.estimateQuarterlyPayment(session.id(), date);
            assertThat((BigDecimal) second.recommendedPayment())
                    .isEqualByComparingTo((BigDecimal) first.recommendedPayment());
            assertThat((BigDecimal) second.projectedBalanceDueAtFiling())
                    .isEqualByComparingTo((BigDecimal) first.projectedBalanceDueAtFiling());
        }
    }

    private void write(String sid, String path, BigDecimal amount) {
        sessionTools.setFact(sid, path, "dollar", null, amount.toPlainString(), null);
    }
}

package com.realworld.tax.advisor;

import java.math.BigDecimal;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PlanningGraphService;
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

/** The simple-method deduction has independent business-income and taxable-income caps. */
@SpringBootTest
class QbiDeductionTest {
    @Autowired
    PlanningGraphService graph;

    @Autowired
    SessionTools sessionTools;

    @Autowired
    SelfEmploymentTools selfEmploymentTools;

    @Autowired
    TaxProjectionTools taxProjectionTools;

    @HegelTest(testCases = 1000)
    void regularDeductionIsTheSmallerRoundedCapAndRetainsOmittedGains(TestCase tc) {
        BigDecimal gross = dollars(tc, "profit");
        BigDecimal other = dollars(tc, "otherIncome");
        BigDecimal gains = dollars(tc, "capitalGains");
        try (var session = session(graph, tc)) {
            var se = selfEmploymentTools.calculateSeTax(
                    session.id(), gross.toPlainString(), null, null, null, null, null, null);
            sessionTools.setFact(
                    session.id(), FactPaths.OTHER_TAXABLE_INCOME, "dollar", null, other.toPlainString(), null);
            var result = taxProjectionTools.estimateQbiDeduction(session.id(), gains.toPlainString(), false);
            BigDecimal qbi =
                    rounded(gross).subtract((BigDecimal) se.deductibleHalf()).max(BigDecimal.ZERO);
            BigDecimal taxable = qbi.add(other)
                    .subtract(graph.readDecimal(session.sessionId(), FactPaths.STANDARD_DEDUCTION))
                    .max(BigDecimal.ZERO);
            BigDecimal component = rounded(money(qbi.multiply(new BigDecimal("0.20"))));
            BigDecimal cap =
                    rounded(money(taxable.subtract(gains).max(BigDecimal.ZERO).multiply(new BigDecimal("0.20"))));
            assertThat((BigDecimal) result.qbiDeduction()).isEqualByComparingTo(component.min(cap));
            assertThat((BigDecimal) result.qualifiedBusinessIncome()).isEqualByComparingTo(qbi);
            assertThat((BigDecimal) taxProjectionTools
                            .estimateQbiDeduction(session.id(), null, null)
                            .qbiDeduction())
                    .isEqualByComparingTo(component.min(cap));
        }
    }

    @HegelTest(testCases = 1000)
    void minimumStaysUnknownUntilParticipationIsConfirmedWhenItCouldIncreaseTheDeduction(TestCase tc) {
        // Supplement the full money domain with the low-income region where the active-business minimum matters.
        BigDecimal gross = tc.draw(booleans(), "lowIncome")
                ? BigDecimal.valueOf(tc.draw(integers().min(0).max(2200), "lowProfit"))
                : dollars(tc, "profit");
        try (var session = session(graph, tc)) {
            selfEmploymentTools.calculateSeTax(session.id(), gross.toPlainString(), null, null, null, null, null, null);
            BigDecimal regular = graph.readDecimal(session.sessionId(), FactPaths.QBI_COMPONENT)
                    .min(graph.readDecimal(session.sessionId(), FactPaths.QBI_INCOME_LIMIT));
            BigDecimal qbi = graph.readDecimal(session.sessionId(), FactPaths.QUALIFIED_BUSINESS_INCOME);
            BigDecimal minimum = graph.readDecimal(session.sessionId(), "/qbiMinimumDeduction");
            BigDecimal minimumIncome = graph.readDecimal(session.sessionId(), "/qbiMinimumIncome");
            boolean couldIncrease = regular.compareTo(minimum) < 0 && qbi.compareTo(minimumIncome) >= 0;
            var unknown = taxProjectionTools.estimateQbiDeduction(session.id(), null, null);
            if (couldIncrease) {
                assertThat(unknown.status()).isEqualTo("needs_facts");
                assertThat(unknown.qbiDeduction()).isNull();
            } else {
                assertThat((BigDecimal) unknown.qbiDeduction()).isEqualByComparingTo(regular);
            }
            assertThat((BigDecimal) taxProjectionTools
                            .estimateQbiDeduction(session.id(), null, false)
                            .qbiDeduction())
                    .isEqualByComparingTo(regular);
            assertThat((BigDecimal) taxProjectionTools
                            .estimateQbiDeduction(session.id(), null, true)
                            .qbiDeduction())
                    .isEqualByComparingTo(couldIncrease ? minimum : regular);
        }
    }

    @HegelTest(testCases = 1000)
    void aboveThresholdResultsAreLabeledAsUpperBounds(TestCase tc) {
        BigDecimal gross = dollars(tc, "profit");
        BigDecimal other = dollars(tc, "otherIncome");
        try (var session = session(graph, tc)) {
            selfEmploymentTools.calculateSeTax(session.id(), gross.toPlainString(), null, null, null, null, null, null);
            sessionTools.setFact(
                    session.id(), FactPaths.OTHER_TAXABLE_INCOME, "dollar", null, other.toPlainString(), null);
            var result = taxProjectionTools.estimateQbiDeduction(session.id(), null, false);
            boolean above = graph.readDecimal(session.sessionId(), "/planning/taxableIncomeBeforeQBIDeduction")
                            .compareTo((BigDecimal) result.qbiThreshold())
                    > 0;
            assertThat(result.aboveThreshold()).isEqualTo(above);
            if (above) assertThat(result.aboveThresholdWarning()).contains("UPPER BOUND", "8995-A");
            else assertThat(result.aboveThresholdWarning()).isEmpty();
        }
    }
}

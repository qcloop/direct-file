package com.realworld.tax.advisor;

import java.math.BigDecimal;

import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.TaxProjectionTools;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.realworld.tax.advisor.PropertySupport.*;
import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.*;

/** Form 8959 counts wages first, then SE earnings against the remaining status-specific threshold. */
@SpringBootTest
class AdditionalMedicareTaxTest {
    @Autowired
    PlanningGraphService graph;

    @Autowired
    SelfEmploymentTools selfEmploymentTools;

    @Autowired
    TaxProjectionTools taxProjectionTools;

    @HegelTest(testCases = 1000)
    void surtaxUsesOneCombinedThresholdAndRoundsEachComponent(TestCase tc) {
        String status = filingStatus(tc);
        BigDecimal threshold = new BigDecimal(
                switch (status) {
                    case "mfj" -> "250000";
                    case "mfs" -> "125000";
                    default -> "200000";
                });
        BigDecimal gross = dollars(tc, "profit");
        BigDecimal wages = tc.draw(booleans(), "nearThreshold")
                ? threshold.add(BigDecimal.valueOf(tc.draw(integers().min(-1).max(1), "thresholdCents"), 2))
                : dollars(tc, "wages");
        try (var session = session(graph, year(tc), status)) {
            selfEmploymentTools.calculateSeTax(session.id(), gross.toPlainString(), null, null, null, null, null, null);
            var result = taxProjectionTools.calculateAdditionalMedicare(session.id(), wages.toPlainString());
            BigDecimal earnings = rounded(money(rounded(gross).multiply(new BigDecimal("0.9235"))));
            BigDecimal seBase = earnings.subtract(threshold.subtract(wages).max(BigDecimal.ZERO))
                    .max(BigDecimal.ZERO);
            BigDecimal wageTax =
                    rounded(money(wages.subtract(threshold).max(BigDecimal.ZERO).multiply(new BigDecimal("0.009"))));
            BigDecimal seTax = rounded(money(seBase.multiply(new BigDecimal("0.009"))));
            assertThat((BigDecimal) result.additionalMedicareThreshold()).isEqualByComparingTo(threshold);
            assertThat((BigDecimal) result.additionalMedicareTaxOnWages()).isEqualByComparingTo(wageTax);
            assertThat((BigDecimal) result.additionalMedicareTaxOnSe()).isEqualByComparingTo(seTax);
            assertThat((BigDecimal) result.additionalMedicareTax()).isEqualByComparingTo(wageTax.add(seTax));
        }
    }

    @HegelTest(testCases = 1000)
    void rejectsUnsupportedFilingStatuses(TestCase tc) {
        String status = "unsupported:" + tc.draw(text(), "status");
        int year = year(tc);
        assertThatThrownBy(() -> graph.createSession(year, status))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filing status");
    }
}

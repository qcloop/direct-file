package gov.irs.directfile.planservice;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import gov.irs.directfile.planservice.graph.PlanningGraphService;
import gov.irs.directfile.planservice.mcp.PlanningTools;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the QBI deduction (Form 8995 simple method, IRC 199A) end-to-end through the MCP tool
 * surface. Numbers are hand-verifiable. Scenario base (single, TY2025): Schedule C net profit
 * $60,000, no expenses -> net earnings 55,410, SE tax 8,478, deductible half 4,239, so
 * QBI = 60,000 - 4,239 = 55,761 and the 20% component = 11,152.
 */
@SpringBootTest
class QbiDeductionTest {

    @Autowired
    PlanningGraphService graph;

    @Autowired
    PlanningTools tools;

    private String sessionWith60kProfit(String filingStatus) {
        String sid = (String) tools.createSession("2025", filingStatus).get("sessionId");
        tools.calculateSeTax(sid, "60000", null, null, null, null);
        return sid;
    }

    @Test
    void deductionIsTwentyPercentOfQbiWhenTheIncomeLimitIsNotBinding() {
        String sid = sessionWith60kProfit("single");
        // Taxable income 70,000 -> income cap = 20% x 70,000 = 14,000, which exceeds the 11,152
        // component, so the component binds.
        Map<String, Object> r = tools.estimateQbiDeduction(sid, "70000", "0");

        assertThat((BigDecimal) r.get("qualified_business_income")).isEqualByComparingTo("55761");
        assertThat((BigDecimal) r.get("qbi_component")).isEqualByComparingTo("11152");
        assertThat((BigDecimal) r.get("qbi_income_limit")).isEqualByComparingTo("14000");
        assertThat((BigDecimal) r.get("qbi_deduction")).isEqualByComparingTo("11152");
        assertThat(r.get("above_threshold")).isEqualTo(false);
        assertThat(r).doesNotContainKey("above_threshold_warning");
    }

    @Test
    void deductionIsCappedAtTwentyPercentOfTaxableIncome() {
        String sid = sessionWith60kProfit("single");
        // Taxable income 40,000 -> cap = 8,000, below the 11,152 component, so the cap binds.
        Map<String, Object> r = tools.estimateQbiDeduction(sid, "40000", "0");

        assertThat((BigDecimal) r.get("qbi_income_limit")).isEqualByComparingTo("8000");
        assertThat((BigDecimal) r.get("qbi_deduction")).isEqualByComparingTo("8000");
    }

    @Test
    void netCapitalGainsReduceTheIncomeCapBase() {
        String sid = sessionWith60kProfit("single");
        // Taxable income 70,000 minus 20,000 net capital gains = 50,000 -> cap = 10,000 < 11,152.
        Map<String, Object> r = tools.estimateQbiDeduction(sid, "70000", "20000");

        assertThat((BigDecimal) r.get("qbi_income_limit")).isEqualByComparingTo("10000");
        assertThat((BigDecimal) r.get("qbi_deduction")).isEqualByComparingTo("10000");
    }

    @Test
    void aboveTheThresholdTheSimpleResultIsFlaggedAsAnUpperBound() {
        String sid = sessionWith60kProfit("single");
        // Single threshold for TY2025 is 197,300; 250,000 is above it.
        Map<String, Object> r = tools.estimateQbiDeduction(sid, "250000", "0");

        assertThat((BigDecimal) r.get("qbi_threshold")).isEqualByComparingTo("197300");
        assertThat(r.get("above_threshold")).isEqualTo(true);
        assertThat(r).containsKey("above_threshold_warning");
    }

    @Test
    void filingStatusSelectsTheInjectedThreshold() {
        assertThat((BigDecimal) graph.readFact(graph.createSession(2025, "single"), "/qbiThreshold")
                        .value())
                .isEqualByComparingTo("197300");
        assertThat((BigDecimal) graph.readFact(graph.createSession(2025, "mfj"), "/qbiThreshold")
                        .value())
                .isEqualByComparingTo("394600");
    }
}

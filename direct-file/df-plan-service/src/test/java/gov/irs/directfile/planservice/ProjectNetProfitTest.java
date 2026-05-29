package gov.irs.directfile.planservice;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import gov.irs.directfile.planservice.graph.PlanningGraphService;
import gov.irs.directfile.planservice.mcp.PlanningTools;

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
    PlanningTools tools;

    @Test
    void annualizesYearToDateFiguresToAFullYear() {
        String sid = (String) tools.createSession("2025").get("sessionId");

        // Seven months of data (through July 1). Numbers chosen to annualize cleanly (× 12/7).
        Map<String, Object> out = tools.projectNetProfit(sid, "2025-07-01", "21000", "7000", "1750", "0", "0");

        assertThat(out).containsEntry("status", "ok");
        assertThat(out).containsEntry("months_elapsed", 7);
        assertThat(out).containsEntry("annualization_factor", "12/7");

        // Output surfaces the tax year and the rate actually used (so a wrong-year session is visible).
        assertThat(out).containsEntry("tax_year", 2025);
        assertThat((BigDecimal) out.get("standard_mileage_rate")).isEqualByComparingTo("0.70");

        // Receipts: 21,000 × 12/7 = 36,000.
        assertThat((BigDecimal) out.get("projected_gross_receipts")).isEqualByComparingTo("36000");

        // Expenses annualized too: vehicle 7,000 mi × $0.70 × 12/7 = $8,400; fees 1,750 × 12/7 = $3,000.
        // Net profit = 36,000 − (8,400 + 3,000 + 0) = 24,600.
        assertThat((BigDecimal) out.get("projected_net_profit")).isEqualByComparingTo("24600");

        // SE tax is computed from the projected profit and is complete.
        assertThat(graph.readFact(sid, "/seTax").complete()).isTrue();
        assertThat((BigDecimal) out.get("projected_self_employment_tax")).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void directFullYearPathIsUnchangedByAnnualization() {
        String sid = (String) tools.createSession("2025").get("sessionId");

        // calculate_se_tax writes an annualization factor of 1/1, so full-year actuals pass through.
        tools.calculateSeTax(sid, "40000", "10000", "3000", "1000", "0");

        // 40,000 − (10,000 mi × $0.70 + 3,000 + 1,000) = 40,000 − 11,000 = 29,000.
        assertThat((BigDecimal) graph.readFact(sid, "/seNetProfit").value()).isEqualByComparingTo("29000");
    }

    @Test
    void refusesToProjectFromBeforeTheTaxYearStarted() {
        String sid = (String) tools.createSession("2025").get("sessionId");

        assertThatThrownBy(() -> tools.projectNetProfit(sid, "2024-11-30", "5000", "0", "0", "0", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2025");
    }

    @Test
    void refusesToProjectFromAfterTheTaxYearEndedInsteadOfSilentlyClampingToAFullYear() {
        // Bug 1 repro: a 2025 session with a 2026 asOfDate must NOT clamp to 12 months (factor 12/12),
        // which would silently return the year-to-date figure unchanged as if it were full-year.
        String sid = (String) tools.createSession("2025").get("sessionId");

        assertThatThrownBy(() -> tools.projectNetProfit(sid, "2026-05-31", "11690", "15000", "0", "0", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not within tax year 2025");
    }
}

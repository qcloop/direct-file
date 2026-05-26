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
 * Exercises a full agentic flow end-to-end through the Spring AI tool surface:
 *
 *   1. Create a planning session via the create_session tool.
 *   2. Initial call to estimate_quarterly_payment returns 'needs_facts'.
 *   3. Agent writes each required fact via set_fact.
 *   4. Second call returns a recommended Q3 payment with a derivation chain.
 *
 * Uses a realistic gig-worker scenario: Uber + DoorDash driver in TY2025,
 * netting ~$32K SE profit, prior-year tax $3,800, AGI $58K (not high-income),
 * has $400 in 1099 withholding YTD, has paid Q1+Q2 already.
 */
@SpringBootTest
class QuarterlyEstimateIntegrationTest {

    @Autowired
    PlanningGraphService graph;

    @Autowired
    PlanningTools tools;

    @Test
    void quarterlyEstimateFlow() {
        String sid = (String) tools.createSession().get("sessionId");

        // 1) Without any facts populated, the tool should ask for what's missing.
        Map<String, Object> before = tools.estimateQuarterlyPayment(sid, "2025-08-20");
        assertThat(before).containsEntry("status", "needs_facts");
        assertThat((Iterable<?>) before.get("missing_facts")).isNotEmpty();

        // 2) Gather facts the way the LLM agent would after a short interview.
        writeDollar(sid, "/planning/priorYearTotalTax", 3800);
        writeDollar(sid, "/planning/priorYearAGI", 58000);
        writeDollar(sid, "/planning/projectedCurrentYearTax", 7200);
        writeDollar(sid, "/planning/ytdWithholding", 400);
        writeDollar(sid, "/planning/ytdEstimatedPaymentsMade", 1800); // Q1 + Q2 already paid

        // 3) Run again — now we should get a real recommendation.
        Map<String, Object> after = tools.estimateQuarterlyPayment(sid, "2025-08-20");
        assertThat(after).containsEntry("status", "ok");
        assertThat(after.get("next_deadline")).isEqualTo("2025-09-15");
        assertThat(after.get("quarter")).isEqualTo("Q3");

        BigDecimal recommended = (BigDecimal) after.get("recommended_payment");
        // Safe harbor = lesser of (100% of $3,800 = $3,800) and (90% of $7,200 = $6,480) -> $3,800.
        // Remaining due = $3,800 - $2,200 (withholding + paid) = $1,600.
        // Split across remaining 2 quarters (Q3 + Q4) -> $800 per quarter.
        assertThat(recommended).isEqualByComparingTo("800");
    }

    private void writeDollar(String sid, String path, int dollars) {
        tools.setFact(sid, path, "dollar", null, dollars);
    }
}

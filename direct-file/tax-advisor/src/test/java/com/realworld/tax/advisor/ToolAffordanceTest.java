package com.realworld.tax.advisor;

import com.realworld.tax.advisor.mcp.QuarterlyPaymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import com.realworld.tax.advisor.mcp.TaxProjectionTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Affordance evals: the tools must STEER a calling LLM toward correct use rather than letting it
 * improvise (the failure mode behind FIND-002/003 in the findings ledger — the agent hand-waving total
 * tax and filing-status comparisons). These assert the self-correcting responses — {@code needs_facts}
 * shapes and prerequisite notes — that point the agent at the right next tool instead of guessing.
 */
@SpringBootTest
class ToolAffordanceTest {

    @Autowired
    SessionTools sessionTools;

    @Autowired
    TaxProjectionTools taxProjectionTools;

    @Autowired
    QuarterlyPaymentTools quarterlyPaymentTools;

    @Test
    void quarterlyAsksForWhatIsMissingAndPointsAtTheIncomeProjection() {
        String s = sessionTools.createSession("2025", "single").sessionId();
        var q = quarterlyPaymentTools.estimateQuarterlyPayment(s, "2025-08-20");

        assertThat(q.status()).isEqualTo("needs_facts");
        assertThat(q.missingFacts())
                .extracting(QuarterlyPaymentTools.MissingFact::path)
                .contains(
                        "/planning/priorYearTotalTax",
                        "/planning/priorYearAGI",
                        "/planning/ytdWithholding",
                        "/planning/ytdEstimatedPaymentsMade",
                        // Total tax is derived, not entered — the prerequisite must steer to the projection.
                        "/planning/projectedCurrentYearTax");
        assertThat(q.missingFacts())
                .filteredOn(f -> f.path().equals("/planning/projectedCurrentYearTax"))
                .allSatisfy(f -> assertThat(f.prompt()).contains("project_total_tax"));
    }

    @Test
    void compareFilingStatusesAsksForIncomeFirst() {
        String s = sessionTools.createSession("2025", "single").sessionId();
        var c = taxProjectionTools.compareFilingStatuses(s);
        assertThat(c.status()).isEqualTo("needs_facts");
        assertThat(c.note()).contains("calculate_se_tax", "project_net_profit");
    }

    @Test
    void totalTaxAndQbiNoteThatIncomeMustBeSetFirst() {
        String s = sessionTools.createSession("2025", "single").sessionId();
        // No SE inputs yet — both tools should say so rather than return a misleading zero silently.
        assertThat(taxProjectionTools.projectTotalTax(s, null, null).note())
                .contains("calculate_se_tax", "project_net_profit");
        assertThat(taxProjectionTools.estimateQbiDeduction(s, null).note())
                .contains("calculate_se_tax", "project_net_profit");
    }
}

package gov.irs.directfile.planservice;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import gov.irs.directfile.planservice.graph.PlanningGraphService;
import gov.irs.directfile.planservice.knowledge.TaxKnowledgeService;
import gov.irs.directfile.planservice.mcp.PlanningTools;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TaxKnowledgePlanningIntegrationTest {

    @Autowired
    PlanningTools tools;

    @Autowired
    PlanningGraphService graph;

    @Test
    void documentEvidenceProducesCandidateFactsAndConflictReviewItems() {
        Object result = tools.planQuestions(
                null,
                null,
                null,
                Map.of("/hasSelfEmploymentIncome", Map.of("value", false, "status", "confirmed")),
                List.<Object>of(Map.of("document_type", "form_1099_nec", "nonemployee_compensation", "12000")),
                null,
                null,
                null);

        TaxKnowledgeService.PlanResult plan = (TaxKnowledgeService.PlanResult) result;

        assertThat(plan.candidateFacts())
                .anySatisfy(candidate -> {
                    assertThat(candidate.path()).isEqualTo("/hasSelfEmploymentIncome");
                    assertThat(candidate.value()).isEqualTo(true);
                    assertThat(candidate.requiresConfirmation()).isTrue();
                })
                .anySatisfy(candidate -> {
                    assertThat(candidate.path()).isEqualTo("/seGrossReceipts");
                    assertThat(candidate.value()).isEqualTo("12000");
                });
        assertThat(plan.conflicts())
                .extracting(TaxKnowledgeService.ReviewConflict::conflictId)
                .contains("no_self_employment_but_1099_nec_present");
        assertThat(plan.questions())
                .extracting(TaxKnowledgeService.PlannedQuestion::questionId)
                .doesNotContain("uses_vehicle_for_business", "se_vehicle_business_miles");
    }

    @Test
    void priorAnswersGateDetailQuestions() {
        Object result = tools.planQuestions(
                null,
                null,
                null,
                Map.of(
                        "/hasSelfEmploymentIncome", Map.of("value", true, "status", "confirmed"),
                        "/usesVehicleForBusiness", Map.of("value", true, "status", "confirmed")),
                null,
                null,
                null,
                null);

        TaxKnowledgeService.PlanResult plan = (TaxKnowledgeService.PlanResult) result;

        assertThat(plan.questions())
                .extracting(TaxKnowledgeService.PlannedQuestion::questionId)
                .contains("se_vehicle_business_miles")
                .doesNotContain("has_self_employment_income", "uses_vehicle_for_business");
    }

    @Test
    void sessionFactsAreTreatedAsConfirmedAnswers() {
        String sessionId = setFactSessionWithSelfEmploymentAnswer(false);

        Object result = tools.planQuestions(
                sessionId, null, null, null, null, Map.of("prior_year_topics", List.of("self_employment")), null, null);

        TaxKnowledgeService.PlanResult plan = (TaxKnowledgeService.PlanResult) result;

        assertThat(plan.conflicts())
                .extracting(TaxKnowledgeService.ReviewConflict::conflictId)
                .contains("prior_year_schedule_c_but_current_year_denied");
        assertThat(plan.questions())
                .extracting(TaxKnowledgeService.PlannedQuestion::questionId)
                .doesNotContain("has_self_employment_income", "uses_vehicle_for_business");
    }

    private String setFactSessionWithSelfEmploymentAnswer(boolean hasSelfEmployment) {
        String sessionId = graph.createSession(2025);
        tools.setFact(
                sessionId, "/hasSelfEmploymentIncome", "boolean", null, Boolean.toString(hasSelfEmployment), null);
        return sessionId;
    }
}

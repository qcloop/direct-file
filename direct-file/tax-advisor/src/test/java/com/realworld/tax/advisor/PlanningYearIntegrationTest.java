package com.realworld.tax.advisor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PersisterTypes;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.mcp.InterviewTools;
import com.realworld.tax.advisor.mcp.QuarterlyPaymentTools;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Planning follows the income calendar year, independent of filing season or model knowledge. */
@SpringBootTest
class PlanningYearIntegrationTest {
    @Autowired
    MutableClock clock;

    @Autowired
    SessionTools sessions;

    @Autowired
    InterviewTools interviews;

    @Autowired
    SelfEmploymentTools selfEmployment;

    @Autowired
    PlanningGraphService graph;

    @Autowired
    QuarterlyPaymentTools quarterly;

    @Test
    void januaryPaymentUsesTheExistingIncomeYearAndCurrentServerDate() {
        String id = sessions.createSession(null, null).sessionId();
        selfEmployment.calculateSeTax(id, "0", "0", "0", "0", "0", null, null);
        for (String path : List.of(
                FactPaths.PRIOR_YEAR_TOTAL_TAX,
                FactPaths.PRIOR_YEAR_AGI,
                FactPaths.YTD_WITHHOLDING,
                FactPaths.YTD_ESTIMATED_PAYMENTS_MADE)) {
            sessions.setFact(id, path, "dollar", null, "0", null);
        }
        clock.set(Instant.parse("2027-01-10T16:00:00Z"));
        var payment = quarterly.estimateQuarterlyPayment(id, null);
        assertThat(payment.status()).isEqualTo("ok");
        assertThat(payment.nextDeadline()).isEqualTo("2027-01-15");
        assertThat(graph.taxYearOf(SessionId.of(id))).isEqualTo(2026);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock testPlanningClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-03-31T16:00:00Z");
        private int reads;

        void set(Instant instant) {
            current = instant;
            reads = 0;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("America/New_York");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            reads++;
            return current;
        }
    }

    @BeforeEach
    void currentDate() {
        clock.set(Instant.parse("2026-03-31T16:00:00Z"));
    }

    @Test
    void defaultsToCurrentIncomeYearAndReturnsTheServerDate() {
        var session = sessions.createSession(null, null);
        assertThat(session.taxYear()).isEqualTo(2026);
        assertThat(session.planningDate()).isEqualTo("2026-03-31");
        assertThat(session.planningTimeZone()).isEqualTo("America/New_York");
        assertThat(session.knowledgeVersion()).matches("[0-9a-f]{64}");
        assertThat(clock.reads).isEqualTo(1);
        var plan = interviews.planQuestions(session.sessionId(), null, null, null, null, null, null, null);
        assertThat(plan.taxYear()).isEqualTo(2026);
        assertThat(plan.artifactStatus()).isEqualTo("preview");
        assertThat(plan.questions()).anySatisfy(q -> assertThat(q.prompt()).contains("income year 2026"));
        assertThat(plan.questions()).noneSatisfy(q -> assertThat(q.prompt()).contains("income year 2025"));
    }

    @Test
    void calendarBoundaryUsesTheConfiguredZoneAndKeepsExistingSessionYears() {
        clock.set(Instant.parse("2026-01-01T04:59:59Z"));
        var old = sessions.createSession(null, null);
        assertThat(old.taxYear()).isEqualTo(2025);
        clock.set(Instant.parse("2026-01-01T05:00:00Z"));
        assertThat(sessions.createSession(null, null).taxYear()).isEqualTo(2026);
        assertThat(interviews
                        .planQuestions(old.sessionId(), null, null, null, null, null, null, null)
                        .taxYear())
                .isEqualTo(2025);
    }

    @Test
    void explicitHistoricalYearIsSupportedAndUnpublishedNextYearNeverFallsBack() {
        assertThat(sessions.createSession("2025", null).taxYear()).isEqualTo(2025);
        var current = sessions.createSession(null, null);
        clock.set(Instant.parse("2027-01-01T05:00:00Z"));
        assertThatThrownBy(() -> sessions.createSession(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2027");
        assertThat(interviews
                        .planQuestions(current.sessionId(), null, null, null, null, null, null, null)
                        .taxYear())
                .isEqualTo(2026);
    }

    @Test
    void standaloneInterviewUsesTheSameClockAndScopesEvidenceAndConflicts() {
        var plan = interviews.planQuestions(
                null,
                null,
                null,
                Map.of("/hasSelfEmploymentIncome", Map.of("value", false, "status", "confirmed")),
                List.of(Map.of("document_type", "form_1099_nec", "tax_year", 2026)),
                null,
                null,
                null);
        assertThat(plan.taxYear()).isEqualTo(2026);
        assertThat(plan.evidenceSignals())
                .allSatisfy(signal -> assertThat(signal.evidenceMapId()).endsWith("2026"));
        assertThat(plan.conflicts())
                .extracting(c -> c.conflictId())
                .containsExactly("no_self_employment_but_1099_nec_present");
    }

    @Test
    void rejectsAConflictingInterviewYearAndPriorYearDocument() {
        var session = sessions.createSession(null, null);
        assertThatThrownBy(
                        () -> interviews.planQuestions(session.sessionId(), 2025, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> interviews.planQuestions(
                        session.sessionId(),
                        null,
                        null,
                        null,
                        List.of(Map.of("document_type", "form_w2", "tax_year", 2025)),
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receipt date");
        assertThatThrownBy(() -> interviews.planQuestions(null, 2026, "CA", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CA");
    }

    @Test
    void projectionDefaultsToServerDateAndReleasedConstantsCannotBeOverwritten() {
        var session = sessions.createSession(null, null);
        var result =
                selfEmployment.projectNetProfit(session.sessionId(), null, "6000", "1000", "0", "0", "0", null, null);
        assertThat(result.taxYear()).isEqualTo(2026);
        assertThat(result.monthsElapsed()).isEqualTo(3);
        assertThatThrownBy(() -> graph.writeFact(
                        SessionId.of(session.sessionId()),
                        FactPaths.STANDARD_MILEAGE_RATE,
                        PersisterTypes.RATIONAL,
                        PersisterTypes.rational(1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be overwritten");
    }
}

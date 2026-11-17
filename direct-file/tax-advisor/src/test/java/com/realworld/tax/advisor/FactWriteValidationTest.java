package com.realworld.tax.advisor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.realworld.tax.advisor.graph.PersisterTypes;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService;
import com.realworld.tax.advisor.mcp.SessionTools;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.realworld.tax.advisor.PropertySupport.*;
import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the set_fact write path against malformed persister type codes — the failure mode
 * behind the {@code upickle.core.Abort: invalid tag for tagged object} error an MCP client hit
 * by passing an invented typeCode (e.g. {@code us.gov.irs.df.plan.FilingStatusPersister}) instead
 * of {@code type="enum"}.
 */
@SpringBootTest
class FactWriteValidationTest {
    @Autowired
    TaxKnowledgeService knowledge;

    @Autowired
    PlanningGraphService graph;

    @Autowired
    SessionTools sessionTools;

    @HegelTest(testCases = 1000)
    void setFactRejectsAnInventedPersisterTypeCodeWithAnActionableMessage(TestCase tc) {
        String invented = "invalid." + tc.draw(text(), "typeCode");
        BigDecimal value = dollars(tc, "savedValue");
        try (var session = session(graph, tc)) {
            SessionId sid = session.sessionId();
            sessionTools.setFact(
                    sid.value(), "/planning/priorYearTotalTax", "dollar", null, value.toPlainString(), null);
            var saved = graph.sessionFacts(sid);

            assertThatThrownBy(() -> sessionTools.setFact(
                            sid.value(), "/planning/priorYearTotalTax", null, invented, "single", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gov.irs.factgraph.persisters.")
                    .hasMessageContaining("enum");

            // The rejected write never touched the graph, so the session is still usable.
            assertThat(graph.sessionFacts(sid)).isEqualTo(saved);
            assertThat((BigDecimal)
                            graph.readFact(sid, "/planning/priorYearTotalTax").value())
                    .isEqualByComparingTo(value);
        }
    }

    @HegelTest(testCases = 1000)
    void aWriteThatFailsGraphAssemblyIsRolledBackAndDoesNotPoisonTheSession(TestCase tc) {
        BigDecimal first = dollars(tc, "savedTax");
        BigDecimal next = dollars(tc, "nextAgi");
        try (var session = session(graph, tc)) {
            SessionId sid = session.sessionId();
            sessionTools.setFact(
                    sid.value(), "/planning/priorYearTotalTax", "dollar", null, first.toPlainString(), null);
            var saved = graph.sessionFacts(sid);

            // A typeCode under the persisters package passes the set_fact guard but is not a real
            // persister, so the fact graph's upickle aborts when it assembles the session.
            assertThatThrownBy(() -> graph.writeFact(
                            sid,
                            "/planning/priorYearAGI",
                            "gov.irs.factgraph.persisters.FilingStatusPersister",
                            "single"))
                    .isInstanceOf(RuntimeException.class);

            // The earlier good fact still reads back — the bad write was rolled back rather than left
            // wedged in the session's fact map (which would break every later graph build).
            assertThat(graph.sessionFacts(sid)).isEqualTo(saved);
            assertThat((BigDecimal)
                            graph.readFact(sid, "/planning/priorYearTotalTax").value())
                    .isEqualByComparingTo(first);

            // And the session still accepts new well-formed writes.
            sessionTools.setFact(sid.value(), "/planning/priorYearAGI", "dollar", null, next.toPlainString(), null);
            assertThat((BigDecimal)
                            graph.readFact(sid, "/planning/priorYearAGI").value())
                    .isEqualByComparingTo(next);
        }
    }

    @HegelTest(testCases = 1000)
    void malformedBatchesCannotPartiallyReplaceOrRemoveFactsOrProvenance(TestCase tc) {
        try (var session = session(graph, tc)) {
            sessionTools.setFact(
                    session.id(),
                    "/planning/priorYearTotalTax",
                    "dollar",
                    null,
                    dollars(tc, "savedTax").toPlainString(),
                    null);
            graph.setSourceNote(session.sessionId(), "/planning/priorYearTotalTax", tc.draw(text(), "sourceNote"));
            var facts = graph.sessionFacts(session.sessionId());
            var notes = graph.sourceNotes(session.sessionId());
            Map<String, PersisterTypes.TypedValue> updates = Map.of(
                    "/planning/priorYearAGI", new PersisterTypes.TypedValue(PersisterTypes.DOLLAR, "1000"),
                    "/seAnnualizationFactor",
                            new PersisterTypes.TypedValue(
                                    PersisterTypes.RATIONAL,
                                    PersisterTypes.rational(tc.draw(integers(), "numerator"), 0)));
            assertThatThrownBy(
                            () -> graph.writeFacts(session.sessionId(), updates, Set.of("/planning/priorYearTotalTax")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(graph.sessionFacts(session.sessionId())).isEqualTo(facts);
            assertThat(graph.sourceNotes(session.sessionId())).isEqualTo(notes);
        }
    }

    @HegelTest(testCases = 1000)
    void releasedParametersCannotBeReplacedOrRemovedThroughEitherWriteApi(TestCase tc) {
        int year = year(tc);
        var parameter = tc.draw(sampledFrom(knowledge.taxParametersForYear(year)), "protectedParameter");
        try (var session = session(graph, year, filingStatus(tc))) {
            var facts = graph.sessionFacts(session.sessionId());
            assertThatThrownBy(() ->
                            graph.writeFact(session.sessionId(), parameter.factPath(), PersisterTypes.DOLLAR, "1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> graph.writeFacts(
                            session.sessionId(),
                            Map.of(parameter.factPath(), new PersisterTypes.TypedValue(PersisterTypes.DOLLAR, "1")),
                            Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> graph.writeFacts(session.sessionId(), Map.of(), Set.of(parameter.factPath())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(graph.sessionFacts(session.sessionId())).isEqualTo(facts);
        }
    }

    @HegelTest(testCases = 1000)
    void malformedTaxpayerValuesCannotReachGraphErrorLogs(TestCase tc) {
        String sentinel = "private:" + tc.draw(text(), "malformedDollar");
        Logger logger = (Logger) LoggerFactory.getLogger(PlanningGraphService.class);
        Level level = logger.getLevel();
        boolean additive = logger.isAdditive();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ERROR);
        logger.setAdditive(false);
        try (var session = session(graph, tc)) {
            assertThatThrownBy(() -> graph.writeFact(
                            session.sessionId(), "/planning/priorYearTotalTax", PersisterTypes.DOLLAR, sentinel))
                    .isInstanceOf(RuntimeException.class);
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(sentinel);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(level);
            logger.setAdditive(additive);
        }
    }
}

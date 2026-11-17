package com.realworld.tax.advisor.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.mcp.QuarterlyPaymentTools;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.realworld.tax.advisor.PropertySupport.*;
import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Check exact event counts through the Spring proxy and privacy at the aspect boundary. */
@SpringBootTest
class ToolCallTelemetryTest {
    @Autowired
    SessionTools sessionTools;

    @Autowired
    SelfEmploymentTools selfEmploymentTools;

    @Autowired
    QuarterlyPaymentTools quarterlyPaymentTools;

    @Autowired
    PlanningGraphService graph;

    @Autowired
    MeterRegistry meters;

    @HegelTest(testCases = 1000)
    void recordsExactlyOneEventForEachProxiedOutcome(TestCase tc) {
        int year = year(tc);
        String status = filingStatus(tc);
        double ok = calls("calculate_se_tax", "ok");
        double needs = calls("estimate_quarterly_payment", "needs_facts");
        double errors = calls("get_fact", "error");
        try (var session = session(graph, year, status);
                var fresh = session(graph, year, status)) {
            selfEmploymentTools.calculateSeTax(
                    session.id(), dollars(tc, "profit").toPlainString(), null, null, null, null, null, null);
            assertThat(quarterlyPaymentTools
                            .estimateQuarterlyPayment(fresh.id(), year + "-08-20")
                            .status())
                    .isEqualTo("needs_facts");
            assertThatThrownBy(() -> sessionTools.getFact("unknown:" + tc.draw(text(), "unknownSession"), "/seTax"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(calls("calculate_se_tax", "ok")).isEqualTo(ok + 1);
            assertThat(calls("estimate_quarterly_payment", "needs_facts")).isEqualTo(needs + 1);
            assertThat(calls("get_fact", "error")).isEqualTo(errors + 1);
        }
    }

    private double calls(String tool, String outcome) {
        var counter = meters.find("taxadvisor.tool.calls")
                .tags("tool", tool, "outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @McpTool(name = "privacy_probe")
    public Probe probe() {
        return null;
    }

    public record Probe(String status, String taxpayerValue) {}

    @HegelTest(testCases = 1000)
    void resultFieldsAndExceptionMessagesCannotReachMetersOrLogs(TestCase tc) throws Throwable {
        String sentinel = "private:" + tc.draw(text(), "taxpayerValue");
        boolean fail = tc.draw(booleans(), "throws");
        boolean unexpected = tc.draw(booleans(), "unexpectedStatus");
        String outcome = tc.draw(sampledFrom("ok", "needs_facts", "unsupported"), "knownStatus");
        Probe result = new Probe(unexpected ? sentinel : outcome, sentinel);
        RuntimeException failure = new IllegalArgumentException(sentinel, new RuntimeException(sentinel));
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        when(point.getArgs()).thenReturn(new Object[] {sentinel});
        if (fail) when(point.proceed()).thenThrow(failure);
        else when(point.proceed()).thenReturn(result);
        var annotation = getClass().getMethod("probe").getAnnotation(McpTool.class);
        Logger logger = (Logger) LoggerFactory.getLogger(ToolCallTelemetry.class);
        Level original = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        var registry = new SimpleMeterRegistry();
        try {
            var telemetry = new ToolCallTelemetry(registry);
            if (fail)
                assertThatThrownBy(() -> telemetry.recordToolCall(point, annotation))
                        .isSameAs(failure);
            else assertThat(telemetry.recordToolCall(point, annotation)).isSameAs(result);
            String expected = fail ? "error" : unexpected ? "ok" : outcome;
            assertThat(registry.get("taxadvisor.tool.calls")
                            .tags("tool", "privacy_probe", "outcome", expected)
                            .counter()
                            .count())
                    .isEqualTo(1);
            assertThat(registry.get("taxadvisor.tool.latency")
                            .tag("tool", "privacy_probe")
                            .timer()
                            .count())
                    .isEqualTo(1);
            for (var meter : registry.getMeters()) {
                assertThat(meter.getId().getTags().toString()).doesNotContain(sentinel);
                var keys = meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey);
                switch (meter.getId().getName()) {
                    case "taxadvisor.tool.calls" -> assertThat(keys).containsOnly("tool", "outcome");
                    case "taxadvisor.tool.errors" -> assertThat(keys).containsOnly("tool", "errorClass");
                    case "taxadvisor.tool.latency" -> assertThat(keys).containsOnly("tool");
                    default -> throw new AssertionError("Unexpected telemetry meter: " + meter.getId());
                }
            }
            assertThat(appender.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(sentinel);
                assertThat(event.getThrowableProxy()).isNull();
            });
            if (fail)
                assertThat(registry.get("taxadvisor.tool.errors")
                                .tag("errorClass", "IllegalArgumentException")
                                .counter()
                                .count())
                        .isEqualTo(1);
            else assertThat(registry.find("taxadvisor.tool.errors").counters()).isEmpty();
        } finally {
            registry.close();
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(original);
        }
    }
}

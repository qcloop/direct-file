package com.realworld.tax.advisor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.mcp.SelfEmploymentTools;
import com.realworld.tax.advisor.mcp.SessionTools;
import com.realworld.tax.advisor.mcp.TaxProjectionTools;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-scenario eval harness for the planning agent (a harness-engineering pattern: turn
 * representative agent runs into repeatable, deterministic evals).
 *
 * <p>Each fixture under {@code src/test/resources/evals/*.json} is a full-run scenario — a filing
 * situation plus a sequence of tool calls, each with expected output fields. The tools are
 * {@code @McpTool} methods returning typed records; the runner invokes them and serializes the
 * record to JSON (the same shape the MCP server publishes as {@code structuredContent}), so the
 * expected fields are the tools' camelCase wire names.
 *
 * <p>Add a scenario by dropping a JSON file in {@code evals/}; add a new step tool by extending the
 * dispatch in {@link #invoke}.
 */
@SpringBootTest
class PlanningAgentEvalTest {

    @Autowired
    PlanningGraphService graph;

    @Autowired
    SessionTools sessionTools;

    @Autowired
    SelfEmploymentTools selfEmploymentTools;

    @Autowired
    TaxProjectionTools taxProjectionTools;

    @Autowired
    ObjectMapper objectMapper;

    @TestFactory
    Stream<DynamicTest> goldenScenarios() throws Exception {
        List<DynamicTest> cases = new ArrayList<>();
        for (Resource fixture : new PathMatchingResourcePatternResolver().getResources("classpath*:/evals/*.json")) {
            JsonNode scenario = objectMapper.readTree(fixture.getInputStream());
            cases.add(DynamicTest.dynamicTest(scenario.get("name").asText(), () -> runScenario(scenario)));
        }
        assertThat(cases).as("no eval fixtures found under evals/").isNotEmpty();
        return cases.stream();
    }

    private void runScenario(JsonNode scenario) {
        String name = scenario.get("name").asText();
        String sessionId = sessionTools
                .createSession(
                        scenario.get("taxYear").asText(),
                        scenario.get("filingStatus").asText())
                .sessionId();

        for (JsonNode step : scenario.get("steps")) {
            String tool = step.get("tool").asText();
            JsonNode args = step.has("args") ? step.get("args") : objectMapper.createObjectNode();
            JsonNode result = objectMapper.valueToTree(invoke(sessionId, tool, args));

            JsonNode expect = step.get("expect");
            expect.fieldNames().forEachRemaining(field -> {
                JsonNode want = expect.get(field);
                JsonNode got = result.get(field);
                assertThat(got)
                        .as("scenario '%s', step '%s', field '%s' missing from result %s", name, tool, field, result)
                        .isNotNull();
                if (want.isNumber() && got.isNumber()) {
                    assertThat(new BigDecimal(got.asText()))
                            .as("scenario '%s', step '%s', field '%s'", name, tool, field)
                            .isEqualByComparingTo(new BigDecimal(want.asText()));
                } else {
                    assertThat(got.asText())
                            .as("scenario '%s', step '%s', field '%s'", name, tool, field)
                            .isEqualTo(want.asText());
                }
            });
        }
    }

    /** Dispatch a fixture step to the typed @McpTool method (tools no longer go through a callback provider). */
    private Object invoke(String sessionId, String tool, JsonNode a) {
        return switch (tool) {
            case "calculate_se_tax" ->
                selfEmploymentTools.calculateSeTax(
                        sessionId,
                        txt(a, "grossReceipts"),
                        txt(a, "businessMiles"),
                        txt(a, "platformFees"),
                        txt(a, "suppliesAndOther"),
                        txt(a, "socialSecurityWagesFromW2"),
                        txt(a, "businessMilesBeforeRateChange"),
                        txt(a, "businessMilesAfterRateChange"));
            case "calculate_additional_medicare" ->
                taxProjectionTools.calculateAdditionalMedicare(sessionId, txt(a, "medicareWagesFromW2"));
            case "estimate_qbi_deduction" ->
                taxProjectionTools.estimateQbiDeduction(
                        sessionId,
                        txt(a, "netCapitalGains"),
                        a.has("materiallyParticipates")
                                ? a.get("materiallyParticipates").asBoolean()
                                : null);
            case "project_total_tax" ->
                taxProjectionTools.projectTotalTax(sessionId, txt(a, "otherOrdinaryIncome"), txt(a, "netCapitalGains"));
            default -> throw new IllegalArgumentException("eval dispatch has no case for tool: " + tool);
        };
    }

    private static String txt(JsonNode a, String key) {
        return a.has(key) ? a.get(key).asText() : null;
    }

    @HegelTest(testCases = 1000)
    void readOnlyToolCallsCannotChangeScenarioResults(TestCase tc) throws Exception {
        List<JsonNode> scenarios = new ArrayList<>();
        for (Resource fixture : new PathMatchingResourcePatternResolver().getResources("classpath*:/evals/*.json")) {
            try (var input = fixture.getInputStream()) {
                scenarios.add(objectMapper.readTree(input));
            }
        }
        JsonNode scenario = tc.draw(sampledFrom(scenarios), "scenario");
        List<String> probes = tc.draw(lists(text()), "readOnlyPaths");
        int year = scenario.get("taxYear").asInt();
        String status = scenario.get("filingStatus").asText();
        try (var baseline = PropertySupport.session(graph, year, status);
                var observed = PropertySupport.session(graph, year, status)) {
            for (JsonNode step : scenario.get("steps")) {
                String tool = step.get("tool").asText();
                JsonNode args = step.has("args") ? step.get("args") : objectMapper.createObjectNode();
                for (String path : probes) {
                    try {
                        sessionTools.getFact(observed.id(), "/unknown/" + path);
                    } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
                        // Malformed paths may be rejected; both accepted and rejected reads must leave state alone.
                    }
                }
                JsonNode expected = objectMapper.valueToTree(invoke(baseline.id(), tool, args));
                JsonNode actual = objectMapper.valueToTree(invoke(observed.id(), tool, args));
                assertThat(actual).as("%s after read-only calls", tool).isEqualTo(expected);
            }
        }
    }
}

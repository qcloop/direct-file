package gov.irs.directfile.planservice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import gov.irs.directfile.planservice.mcp.PlanningTools;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-scenario eval harness for the planning agent (a harness-engineering pattern: turn
 * representative agent runs into repeatable, deterministic evals).
 *
 * <p>Each fixture under {@code src/test/resources/evals/*.json} is a <em>full-run</em> scenario — a
 * filing situation plus a sequence of MCP tool calls, each with expected output fields. The runner
 * drives the <em>real</em> tool surface (the same {@link MethodToolCallbackProvider} the MCP server
 * exposes), marshalling arguments and results as JSON exactly as an LLM agent would, so this is a
 * regression eval of what the agent actually receives — not a white-box unit test of internal
 * methods. Each tool call doubles as a single-step eval (one call → expected fields).
 *
 * <p>Add a scenario by dropping a JSON file in {@code evals/}; no Java changes are needed.
 */
@SpringBootTest
class PlanningAgentEvalTest {

    @Autowired
    PlanningTools planningTools;

    @Autowired
    ObjectMapper objectMapper;

    @TestFactory
    Stream<DynamicTest> goldenScenarios() throws Exception {
        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        for (ToolCallback cb : MethodToolCallbackProvider.builder()
                .toolObjects(planningTools)
                .build()
                .getToolCallbacks()) {
            tools.put(cb.getToolDefinition().name(), cb);
        }

        List<DynamicTest> cases = new ArrayList<>();
        for (Resource fixture : new PathMatchingResourcePatternResolver().getResources("classpath*:/evals/*.json")) {
            JsonNode scenario = objectMapper.readTree(fixture.getInputStream());
            cases.add(DynamicTest.dynamicTest(scenario.get("name").asText(), () -> runScenario(scenario, tools)));
        }
        assertThat(cases).as("no eval fixtures found under evals/").isNotEmpty();
        return cases.stream();
    }

    private void runScenario(JsonNode scenario, Map<String, ToolCallback> tools) throws Exception {
        String name = scenario.get("name").asText();

        // Create the session through the real create_session tool so the eval exercises it too.
        ObjectNode createArgs = objectMapper.createObjectNode();
        createArgs.put("taxYear", scenario.get("taxYear").asText());
        createArgs.put("filingStatus", scenario.get("filingStatus").asText());
        String sessionId = objectMapper
                .readTree(invoke(tools, "create_session", createArgs))
                .get("sessionId")
                .asText();

        for (JsonNode step : scenario.get("steps")) {
            String tool = step.get("tool").asText();
            ObjectNode args =
                    step.has("args") ? (ObjectNode) step.get("args").deepCopy() : objectMapper.createObjectNode();
            args.put("sessionId", sessionId);

            JsonNode result = objectMapper.readTree(invoke(tools, tool, args));
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

    private String invoke(Map<String, ToolCallback> tools, String name, JsonNode args) throws Exception {
        ToolCallback cb = tools.get(name);
        assertThat(cb).as("unknown tool referenced by a fixture: %s", name).isNotNull();
        return cb.call(objectMapper.writeValueAsString(args));
    }
}

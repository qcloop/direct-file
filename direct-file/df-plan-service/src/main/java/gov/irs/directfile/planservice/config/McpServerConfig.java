package gov.irs.directfile.planservice.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import gov.irs.directfile.planservice.mcp.PlanningTools;

/**
 * Wires the planning-service tools into Spring AI's MCP server.
 *
 * <p>{@link MethodToolCallbackProvider} scans the supplied bean for
 * {@code @Tool}-annotated methods and exposes them through the MCP protocol —
 * tool discovery via {@code tools/list}, invocation via {@code tools/call},
 * and JSON Schema generation from the method signatures.
 *
 * <p>The actual transport (HTTP+SSE vs STDIO) is configured by the active
 * Spring profile and the {@code spring.ai.mcp.server.*} properties in
 * {@code application.yaml} / {@code application-stdio.yaml}.
 */
@Configuration
public class McpServerConfig {

    @Bean
    ToolCallbackProvider planningToolCallbacks(PlanningTools planningTools) {
        return MethodToolCallbackProvider.builder().toolObjects(planningTools).build();
    }
}

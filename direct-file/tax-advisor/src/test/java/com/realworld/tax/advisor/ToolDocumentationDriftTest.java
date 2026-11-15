package com.realworld.tax.advisor;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mechanical knowledge-base freshness check: the README's tool inventory must list every MCP tool
 * the service actually exposes. Documentation drifts the instant a new tool ships without a
 * README entry (this exact gap happened twice while adding the Form 8959 and QBI tools), and a stale
 * tool list is worse than none for an agent reading the repo as its source of truth.
 *
 * <p>This promotes "keep the README tool list current" from a convention into a build gate. It reads
 * the in-module {@code README.md} (surefire runs with the module directory as the working dir) and
 * reflects over every Spring component in the tools package for {@code @McpTool} names, so the source
 * of truth for "what tools exist" stays the code — and a tool added to a new class is still covered.
 */
class ToolDocumentationDriftTest {

    private static final String TOOLS_PACKAGE = "com.realworld.tax.advisor.mcp";

    @Test
    void everyMcpToolIsListedInTheReadme() throws Exception {
        String readme = Files.readString(Path.of("README.md"));

        List<String> toolNames = mcpToolNames();
        assertThat(toolNames)
                .as("no @McpTool methods found on the @Component classes under %s", TOOLS_PACKAGE)
                .isNotEmpty();

        // Expect the wire name in backticks, matching the README's tool table format.
        List<String> undocumented = toolNames.stream()
                .filter(name -> !readme.contains("`" + name + "`"))
                .toList();

        assertThat(undocumented)
                .as(
                        "These @McpTool methods are missing from tax-advisor/README.md's"
                                + " tool inventory: %s. Add a row to the '## Tools' table (wire name in"
                                + " backticks) so the repo's tool list stays in sync with the code.",
                        undocumented)
                .isEmpty();
    }

    /** Wire names of every {@code @McpTool} method on a Spring component in the tools package. */
    private static List<String> mcpToolNames() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> names = new ArrayList<>();
        for (BeanDefinition component : scanner.findCandidateComponents(TOOLS_PACKAGE)) {
            for (Method m : Class.forName(component.getBeanClassName()).getDeclaredMethods()) {
                McpTool mcpTool = m.getAnnotation(McpTool.class);
                if (mcpTool != null) {
                    names.add(mcpTool.name().isBlank() ? m.getName() : mcpTool.name());
                }
            }
        }
        return names;
    }
}

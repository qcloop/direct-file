/**
 * Spring AI MCP tools exposed by tax-advisor, grouped by topic: {@link com.realworld.tax.advisor.mcp.SessionTools},
 * {@link com.realworld.tax.advisor.mcp.SelfEmploymentTools}, {@link com.realworld.tax.advisor.mcp.TaxProjectionTools},
 * {@link com.realworld.tax.advisor.mcp.QuarterlyPaymentTools}, {@link com.realworld.tax.advisor.mcp.ExplanationTools} and
 * {@link com.realworld.tax.advisor.mcp.InterviewTools}.
 *
 * <p>Every tool method is annotated with {@code @McpTool} and is auto-registered with the MCP server by
 * Spring AI's annotation scanner. {@code generateOutputSchema = true} makes the server publish an MCP
 * {@code outputSchema} for each tool and return matching {@code structuredContent}; tools therefore
 * return typed records (not {@code Map}). plan_questions is the one exception
 * ({@code generateOutputSchema = false}) — its result is an open interview-planning structure whose
 * nested records carry business-significant null fields, so it is left without a strict schema.
 *
 * <p>Wire-format note: both the input parameter names and the output record field names are the Java
 * camelCase identifiers ({@code sessionId}, {@code filingStatus}). No {@code @JsonProperty} renames —
 * Spring AI's schema generator ignores them, which would desync the schema from the serialized
 * content. Result records keep all descriptive strings non-null (empty when absent) and type
 * heterogeneous/nullable fact values as {@code Object} so the generated schema validates.
 */
package com.realworld.tax.advisor.mcp;

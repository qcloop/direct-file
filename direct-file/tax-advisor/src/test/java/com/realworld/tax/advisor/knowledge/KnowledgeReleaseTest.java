package com.realworld.tax.advisor.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.realworld.tax.advisor.config.TaxAdvisorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Release selection and immutability cannot depend on an LLM respecting authoring statuses. */
class KnowledgeReleaseTest {
    @TempDir
    Path root;

    private Path write(String name, String text) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private TaxKnowledgeService load(boolean preview) throws Exception {
        write("sources/federal/citations.yaml", """
                citations:
                  authority:
                    authority: form
                    citation: Official authority
                    title: Official authority
                    url: https://www.irs.gov/original
                    plain_language: Original explanation.
                """);
        TaxKnowledgeService service =
                new TaxKnowledgeService(new TaxAdvisorProperties(root.toString(), List.of(), null), preview);
        service.load();
        return service;
    }

    private void parameters(String status, int metadataYear) throws Exception {
        write("rules/federal/2026/example-tax-parameters.yaml", """
                tax_year: %s
                jurisdiction: federal
                status: %s
                parameters:
                  - fact_path: /rate
                    type: dollar
                    value: '100'
                    source_id: authority
                """.formatted(metadataYear, status));
    }

    @Test
    void defaultPolicyRefusesDraftParametersWhilePreviewIsExplicit() throws Exception {
        parameters("draft", 2026);
        var released = load(false);
        assertThatThrownBy(() -> released.taxParametersForYear(2026))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved");
        var preview = load(true);
        assertThat(preview.taxParametersForYear(2026)).hasSize(1);
        assertThat(preview.provisionalWarning(2026)).contains("PREVIEW");
    }

    @Test
    void metadataYearCannotDisagreeWithTheParameterDirectory() throws Exception {
        parameters("published", 2025);
        var service = load(false);
        assertThatThrownBy(() -> service.taxParametersForYear(2026))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata");
    }

    @Test
    void authoringEditsCannotChangeLoadedParametersOrCitations() throws Exception {
        parameters("published", 2026);
        var service = load(false);
        String version = service.version();
        write("rules/federal/2026/example-tax-parameters.yaml", "not valid new parameters");
        write("sources/federal/citations.yaml", "citations: {}\n");
        assertThat(service.taxParametersForYear(2026).getFirst().value()).isEqualTo("100");
        assertThat(service.citation("authority").url()).isEqualTo("https://www.irs.gov/original");
        assertThat(service.version()).isEqualTo(version);
    }

    @Test
    void parametersFromAnIncompleteMixOfReleasedAndDraftFilesAreNotPartiallyLoaded() throws Exception {
        parameters("published", 2026);
        write("rules/federal/2026/unreviewed-tax-parameters.yaml", """
                tax_year: 2026
                jurisdiction: federal
                status: draft
                parameters:
                  - fact_path: /otherRate
                    type: dollar
                    value: '200'
                    source_id: authority
                """);
        var service = load(false);
        assertThatThrownBy(() -> service.taxParametersForYear(2026))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreviewed-tax-parameters.yaml");
    }

    @Test
    void plannerRefusesUnknownYearAndFiltersDraftItemsFromReleasedFiles() throws Exception {
        write("questions/federal/2026/questions.yaml", """
                tax_year: 2026
                jurisdiction: federal
                status: approved
                questions:
                  - question_id: released
                    prompt: Income year 2026
                  - question_id: draft
                    status: draft
                    prompt: Unreviewed question
                """);
        var service = load(false);
        var request = new TaxKnowledgeService.PlanRequest(2026, "federal", null, null, null, false, null);
        assertThat(service.plan(request).questions())
                .extracting(TaxKnowledgeService.PlannedQuestion::questionId)
                .containsExactly("released");
        assertThatThrownBy(() -> service.plan(
                        new TaxKnowledgeService.PlanRequest(2027, "federal", null, null, null, false, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2027");
    }
}

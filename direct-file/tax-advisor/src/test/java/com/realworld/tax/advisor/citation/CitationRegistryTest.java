package com.realworld.tax.advisor.citation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.parsers.DocumentBuilderFactory;

import com.realworld.tax.advisor.knowledge.TaxKnowledgeService;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService.Citation;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService.TaxParameter;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.w3c.dom.Element;

import static com.realworld.tax.advisor.PropertySupport.year;
import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code source_id} a published parameter references must resolve in the citation registry —
 * otherwise the export would print a dangling id with no authority behind it. This guards the
 * invariant: add a parameter with a new source_id and you must add its citation, or this fails.
 */
@SpringBootTest
class CitationRegistryTest {
    @Autowired
    CitationService citations;

    private static final Map<String, List<String>> DEPENDENCIES = new LinkedHashMap<>();

    @BeforeAll
    static void loadIndependentXmlDependencyIndex() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var input = CitationRegistryTest.class.getResourceAsStream("/tax-plan/selfEmployment.xml")) {
            var facts = factory.newDocumentBuilder().parse(input).getElementsByTagName("Fact");
            for (int i = 0; i < facts.getLength(); i++) {
                Element fact = (Element) facts.item(i);
                List<String> paths = new ArrayList<>();
                var dependencies = fact.getElementsByTagName("Dependency");
                for (int j = 0; j < dependencies.getLength(); j++)
                    paths.add(((Element) dependencies.item(j)).getAttribute("path"));
                DEPENDENCIES.put(fact.getAttribute("path"), paths);
            }
        }
    }

    @Autowired
    TaxKnowledgeService taxKnowledge;

    @Test
    void everyParameterSourceIdResolvesToACitation() {
        for (int year : new int[] {2024, 2025, 2026}) {
            for (TaxParameter p : taxKnowledge.taxParametersForYear(year)) {
                if (p.sourceId() == null) {
                    continue;
                }
                assertThat(taxKnowledge.citation(p.sourceId()))
                        .as("citation for source_id '%s' (referenced by %s in %d)", p.sourceId(), p.factPath(), year)
                        .isNotNull();
            }
        }
    }

    @Test
    void citationsCarryFormalCitationAndFriendlyPlainLanguage() {
        Citation c = taxKnowledge.citation("irc_6654");
        assertThat(c).isNotNull();
        assertThat(c.citation()).isEqualTo("26 U.S.C. § 6654");
        assertThat(c.authority()).isEqualTo("statute");
        assertThat(c.url()).startsWith("https://");
        assertThat(c.plainLanguage()).isNotBlank();
        // Friendly register: no section-symbol jargon in the plain-language explanation itself.
        assertThat(c.plainLanguage()).doesNotContain("§");
    }

    @HegelTest(testCases = 1000)
    void citationSourcesMatchIndependentXmlClosureInParameterOrder(TestCase tc) {
        int year = year(tc);
        String path = tc.draw(sampledFrom(new ArrayList<>(DEPENDENCIES.keySet())), "factPath");
        List<TaxParameter> parameters = taxKnowledge.taxParametersForYear(year);
        List<TaxParameter> reordered = tc.draw(lists(sampledFrom(parameters)), "parametersWithDuplicates");
        var closure = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(path);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (closure.add(current)) pending.addAll(DEPENDENCIES.getOrDefault(current, List.of()));
        }
        List<String> expected = reordered.stream()
                .filter(p -> closure.contains(p.factPath()))
                .map(TaxParameter::sourceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        assertThat(citations.sourceIdsForFact(reordered, path)).containsExactlyElementsOf(expected);
        assertThat(citations.citationsForFact(reordered, path)).isEqualTo(citations.resolve(expected));
        assertThat(citations.sourceIdsForFact(year, path)).isEqualTo(citations.sourceIdsForFact(parameters, path));
        assertThat(citations.citationsForFact(year, path)).isEqualTo(citations.citationsForFact(parameters, path));
    }

    @HegelTest(testCases = 1000)
    void resolvingSourcesPreservesKnownEntriesAndDropsUnknownIds(TestCase tc) {
        List<String> ids = new ArrayList<>(tc.draw(
                lists(sampledFrom(new ArrayList<>(taxKnowledge.citations().keySet()))), "sourceIds"));
        ids.add("unknown:" + tc.draw(text(), "unknownSource"));
        assertThat(citations.resolve(ids))
                .containsExactlyElementsOf(ids.stream()
                        .map(taxKnowledge::citation)
                        .filter(Objects::nonNull)
                        .toList());
    }
}

package com.realworld.tax.advisor.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService.CandidateFact;

/**
 * The interview-planning artifacts authored in tax-knowledge YAML — the questions that can be asked,
 * the evidence maps that turn a document into topic signals and candidate facts, and the conflict
 * rules that flag contradictions for review — together with the code that loads them.
 */
record KnowledgeBundle(List<Question> questions, List<EvidenceMap> evidenceMaps, List<ConflictRule> conflictRules) {
    private static final String TAX_YEAR = "tax_year";
    private static final String JURISDICTION = "jurisdiction";

    /** The bundle used when no tax-knowledge root is available: question planning is disabled. */
    static KnowledgeBundle empty() {
        return new KnowledgeBundle(List.of(), List.of(), List.of());
    }

    /** Load every artifact under {@code questions/}, {@code evidence/} and {@code conflicts/} of the root. */
    static KnowledgeBundle load(Path knowledgeRoot, ObjectMapper yamlMapper, boolean allowDrafts) throws IOException {
        return new KnowledgeBundle(
                loadQuestionFiles(knowledgeRoot.resolve("questions"), yamlMapper, allowDrafts),
                loadEvidenceFiles(knowledgeRoot.resolve("evidence"), yamlMapper, allowDrafts),
                loadConflictFiles(knowledgeRoot.resolve("conflicts"), yamlMapper, allowDrafts));
    }

    KnowledgeBundle forYear(int taxYear, String jurisdiction) {
        return new KnowledgeBundle(
                questions.stream()
                        .filter(q -> q.taxYear() == taxYear && jurisdiction.equals(q.jurisdiction()))
                        .toList(),
                evidenceMaps.stream()
                        .filter(m -> m.taxYear() == taxYear && jurisdiction.equals(m.jurisdiction()))
                        .toList(),
                conflictRules.stream()
                        .filter(c -> c.taxYear() == taxYear && jurisdiction.equals(c.jurisdiction()))
                        .toList());
    }

    private static List<Question> loadQuestionFiles(Path root, ObjectMapper yamlMapper, boolean allowDrafts)
            throws IOException {
        List<Question> out = new ArrayList<>();
        for (Map<String, Object> doc : yamlDocuments(root, yamlMapper)) {
            if (!ArtifactPolicy.allowed(doc, allowDrafts)) {
                continue;
            }
            int taxYear = YamlValues.asInt(doc.get(TAX_YEAR), 0);
            String jurisdiction = YamlValues.asString(doc.get(JURISDICTION));
            for (Map<String, Object> item : YamlValues.maps(doc.get("questions"))) {
                if (!ArtifactPolicy.allowedItem(item, allowDrafts)) {
                    continue;
                }
                out.add(new Question(
                        YamlValues.asString(item.get("question_id")),
                        YamlValues.asString(item.get("topic_id")),
                        taxYear,
                        jurisdiction,
                        YamlValues.asString(item.get("prompt")),
                        YamlValues.asString(item.get("help_text")),
                        YamlValues.asString(item.get("why_we_ask")),
                        YamlValues.strings(item.get("writes")),
                        ConditionGroup.from(item.get("asks_when")),
                        ConditionGroup.from(item.get("skip_when")),
                        YamlValues.asString(item.get("priority")),
                        YamlValues.strings(item.get("source_ids"))));
            }
        }
        return out;
    }

    private static List<EvidenceMap> loadEvidenceFiles(Path root, ObjectMapper yamlMapper, boolean allowDrafts)
            throws IOException {
        List<EvidenceMap> out = new ArrayList<>();
        for (Map<String, Object> doc : yamlDocuments(root, yamlMapper)) {
            if (!ArtifactPolicy.allowed(doc, allowDrafts)) {
                continue;
            }
            for (Map<String, Object> item : YamlValues.maps(doc.get("evidence_maps"))) {
                if (!ArtifactPolicy.allowedItem(item, allowDrafts)) {
                    continue;
                }
                List<Signal> signals = YamlValues.maps(item.get("signals")).stream()
                        .map(signal -> new Signal(
                                YamlValues.asString(signal.get("topic_id")),
                                YamlValues.asString(signal.get("confidence")),
                                YamlValues.asString(signal.get("reason"))))
                        .toList();
                List<CandidateFact> candidateFacts = YamlValues.maps(item.get("candidate_facts")).stream()
                        .map(candidate -> new CandidateFact(
                                YamlValues.asString(candidate.get("path")),
                                candidate.get("value"),
                                YamlValues.asString(candidate.get("source_field")),
                                YamlValues.asBoolean(candidate.get("requires_confirmation")),
                                null,
                                null))
                        .toList();
                out.add(new EvidenceMap(
                        YamlValues.asString(item.get("evidence_map_id")),
                        YamlValues.asInt(doc.get(TAX_YEAR), 0),
                        YamlValues.asString(doc.get(JURISDICTION)),
                        YamlValues.asString(item.get("document_type")),
                        signals,
                        candidateFacts,
                        YamlValues.strings(item.get("conflict_rules"))));
            }
        }
        return out;
    }

    private static List<ConflictRule> loadConflictFiles(Path root, ObjectMapper yamlMapper, boolean allowDrafts)
            throws IOException {
        List<ConflictRule> out = new ArrayList<>();
        for (Map<String, Object> doc : yamlDocuments(root, yamlMapper)) {
            if (!ArtifactPolicy.allowed(doc, allowDrafts)) {
                continue;
            }
            for (Map<String, Object> item : YamlValues.maps(doc.get("conflicts"))) {
                if (!ArtifactPolicy.allowedItem(item, allowDrafts)) {
                    continue;
                }
                out.add(new ConflictRule(
                        YamlValues.asString(item.get("conflict_id")),
                        YamlValues.asInt(doc.get(TAX_YEAR), 0),
                        YamlValues.asString(doc.get(JURISDICTION)),
                        YamlValues.asString(item.get("severity")),
                        ConditionGroup.from(item.get("when")),
                        YamlValues.asString(item.get("message")),
                        YamlValues.asString(item.get("resolution_prompt")),
                        YamlValues.strings(item.get("source_ids"))));
            }
        }
        return out;
    }

    private static List<Map<String, Object>> yamlDocuments(Path root, ObjectMapper yamlMapper) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> yamlFiles = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();

            List<Map<String, Object>> docs = new ArrayList<>();
            for (Path file : yamlFiles) {
                Map<String, Object> doc = yamlMapper.readValue(file.toFile(), YamlValues.MAP_TYPE);
                Path relative = root.relativize(file);
                if (relative.getNameCount() < 3
                        || !relative.getName(0).toString().equals(doc.get(JURISDICTION))
                        || !relative.getName(1).toString().equals(String.valueOf(doc.get(TAX_YEAR)))) {
                    throw new IllegalArgumentException(
                            "Interview artifact metadata must match its jurisdiction/year directory: " + relative);
                }
                docs.add(doc);
            }
            return docs;
        }
    }

    record Question(
            String questionId,
            String topicId,
            int taxYear,
            String jurisdiction,
            String prompt,
            String helpText,
            String whyWeAsk,
            List<String> writes,
            ConditionGroup asksWhen,
            ConditionGroup skipWhen,
            String priority,
            List<String> sourceIds) {}

    record EvidenceMap(
            String evidenceMapId,
            int taxYear,
            String jurisdiction,
            String documentType,
            List<Signal> signals,
            List<CandidateFact> candidateFacts,
            List<String> conflictRules) {}

    record Signal(String topicId, String confidence, String reason) {}

    record ConflictRule(
            String conflictId,
            int taxYear,
            String jurisdiction,
            String severity,
            ConditionGroup when,
            String message,
            String resolutionPrompt,
            List<String> sourceIds) {}
}

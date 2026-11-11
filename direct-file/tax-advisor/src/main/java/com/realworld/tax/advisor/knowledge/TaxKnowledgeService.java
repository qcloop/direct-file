package com.realworld.tax.advisor.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.realworld.tax.advisor.config.TaxAdvisorProperties;
import com.realworld.tax.advisor.knowledge.KnowledgeBundle.EvidenceMap;
import com.realworld.tax.advisor.knowledge.KnowledgeBundle.Question;
import com.realworld.tax.advisor.knowledge.KnowledgeBundle.Signal;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads prototype tax-knowledge artifacts and evaluates their simple conditions.
 *
 * <p>This service deliberately treats artifacts as planning metadata. It proposes
 * questions, candidate facts, and review conflicts, but it does not write anything
 * into the fact graph.
 */
@Service
public class TaxKnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(TaxKnowledgeService.class);

    private static final String PARAMETER_FILE_SUFFIX = "-tax-parameters.yaml";

    private final TaxAdvisorProperties properties;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private Path root;

    private KnowledgeBundle bundle = KnowledgeBundle.empty();

    private Map<String, Citation> citations = Map.of();

    public TaxKnowledgeService(TaxAdvisorProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void load() throws IOException {
        this.root = resolveRoot(properties.taxKnowledgeRoot());
        if (!Files.exists(root)) {
            log.warn("Tax knowledge root {} does not exist; question planning artifacts are disabled", root);
            return;
        }

        this.bundle = KnowledgeBundle.load(root, yamlMapper);
        this.citations = loadCitations(root);

        log.info(
                "Tax knowledge artifacts loaded from {}: {} questions, {} evidence maps, {} conflict rules, "
                        + "{} citations",
                root,
                bundle.questions().size(),
                bundle.evidenceMaps().size(),
                bundle.conflictRules().size(),
                citations.size());
    }

    public PlanResult plan(PlanRequest request) {
        EvaluationContext context = EvaluationContext.from(request);

        List<PlannedQuestion> questions = new ArrayList<>();
        List<SkippedQuestion> skipped = new ArrayList<>();
        for (Question question : bundle.questions()) {
            Optional<SkippedQuestion> skip = skipReason(question, request, context);
            if (skip.isPresent()) {
                skipped.add(skip.get());
            } else {
                questions.add(PlannedQuestion.from(question));
            }
        }

        int limit = request.limit() == null || request.limit() < 1 ? questions.size() : request.limit();
        return new PlanResult(
                request.taxYear(),
                request.jurisdiction(),
                evidenceSignals(context),
                candidateFacts(context),
                reviewConflicts(context),
                questions.stream().limit(limit).toList(),
                skipped);
    }

    /** Topic signals raised by each piece of evidence, via the evidence maps for its document type. */
    private List<EvidenceSignal> evidenceSignals(EvaluationContext context) {
        List<EvidenceSignal> signals = new ArrayList<>();
        for (EvidenceState evidence : context.evidence()) {
            for (EvidenceMap map : evidenceMapsFor(evidence)) {
                for (Signal signal : map.signals()) {
                    signals.add(new EvidenceSignal(
                            map.evidenceMapId(),
                            evidence.documentType(),
                            signal.topicId(),
                            signal.confidence(),
                            signal.reason()));
                }
            }
        }
        return signals;
    }

    /** Facts each piece of evidence suggests, to be confirmed with the taxpayer rather than assumed. */
    private List<CandidateFact> candidateFacts(EvaluationContext context) {
        List<CandidateFact> candidates = new ArrayList<>();
        for (EvidenceState evidence : context.evidence()) {
            for (EvidenceMap map : evidenceMapsFor(evidence)) {
                for (CandidateFact candidate : map.candidateFacts()) {
                    candidates.add(candidate.withEvidence(map.evidenceMapId(), evidence));
                }
            }
        }
        return candidates;
    }

    private List<EvidenceMap> evidenceMapsFor(EvidenceState evidence) {
        return bundle.evidenceMaps().stream()
                .filter(map -> map.documentType().equals(evidence.documentType()))
                .toList();
    }

    private List<ReviewConflict> reviewConflicts(EvaluationContext context) {
        return bundle.conflictRules().stream()
                .filter(conflict -> conflict.when().matches(context))
                .map(conflict -> new ReviewConflict(
                        conflict.conflictId(),
                        conflict.severity(),
                        conflict.message(),
                        conflict.resolutionPrompt(),
                        conflict.sourceIds()))
                .toList();
    }

    /** Why a question should not be asked now, or empty if it should. */
    private static Optional<SkippedQuestion> skipReason(
            Question question, PlanRequest request, EvaluationContext context) {
        if (!request.includeAnswered() && context.hasConfirmedAnswerForAny(question.writes())) {
            return Optional.of(SkippedQuestion.answered(question));
        }
        if (!question.asksWhen().matches(context)) {
            return Optional.of(SkippedQuestion.notApplicable(question));
        }
        if (question.skipWhen().matchesWhenPresent(context)) {
            return Optional.of(SkippedQuestion.suppressed(question));
        }
        return Optional.empty();
    }

    private Path resolveRoot(String configuredRoot) {
        Path configured = Path.of(configuredRoot);
        if (configured.isAbsolute() || Files.exists(configured)) {
            return configured.normalize();
        }

        List<Path> fallbacks = List.of(
                configured,
                Path.of("tax-knowledge"),
                Path.of("direct-file", "tax-knowledge"),
                Path.of("..", "tax-knowledge"));
        return fallbacks.stream()
                .filter(Files::exists)
                .findFirst()
                .orElse(configured)
                .normalize();
    }

    /**
     * Year/jurisdiction-scoped tax parameters for {@code taxYear}, merged from every
     * {@code *-tax-parameters.yaml} under tax-knowledge {@code rules/federal/<year>/}. The loader
     * is topic-agnostic: each topic contributes its own parameter file (self-employment today,
     * standard deduction / brackets / credits later) and all are merged by fact_path. Throws if the
     * year has no parameter files — callers must not silently fall back to another year's values.
     */
    public List<TaxParameter> taxParametersForYear(int taxYear) {
        // User-facing messages reference the logical location, never the absolute filesystem path
        // (which leaks the host's directory layout to the agent / taxpayer).
        String location = "rules/federal/" + taxYear + "/";
        List<Path> files = parameterFiles(taxYear, location);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No published tax parameters for tax year " + taxYear
                    + " in the tax-knowledge base (expected a <topic>-tax-parameters.yaml under " + location
                    + "). The planning year's parameters may not be published yet.");
        }
        List<TaxParameter> out = new ArrayList<>();
        for (Path file : files) {
            out.addAll(readParameters(file, taxYear));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(
                    "Tax parameter files for " + taxYear + " under " + location + " list no parameters.");
        }
        return out;
    }

    /** The year's {@code *-tax-parameters.yaml} files in a stable (sorted) order; empty if none exist. */
    private List<Path> parameterFiles(int taxYear, String location) {
        Path base = root != null ? root : resolveRoot(properties.taxKnowledgeRoot());
        Path dir = base.resolve("rules").resolve("federal").resolve(Integer.toString(taxYear));
        try (Stream<Path> walk = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(PARAMETER_FILE_SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan tax parameters for " + taxYear + " under " + location, e);
        }
    }

    private List<TaxParameter> readParameters(Path file, int taxYear) {
        try {
            Map<String, Object> doc = yamlMapper.readValue(file.toFile(), YamlValues.MAP_TYPE);
            return YamlValues.maps(doc.get("parameters")).stream()
                    .map(TaxParameter::fromYaml)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read tax parameters for " + taxYear + " from " + file.getFileName(), e);
        }
    }

    /**
     * One year-indexed tax parameter from a {@code *-tax-parameters.yaml} file. {@code factPath},
     * {@code type}, and {@code value} drive injection into the session graph; {@code name},
     * {@code sourceId}, and {@code note} carry the human-readable provenance surfaced in the
     * taxpayer-held export so each statutory constant can be traced back to its IRS source.
     *
     * <p>{@code filingStatus} (nullable) scopes a parameter to one filing status — e.g. the
     * Additional Medicare Tax threshold is $200k single / $250k MFJ / $125k MFS. A null value
     * means the parameter applies to every status (the common case); a non-null value is injected
     * only into sessions created with the matching status.
     */
    public record TaxParameter(
            String factPath,
            String type,
            String value,
            String name,
            String sourceId,
            String note,
            boolean provisional,
            String filingStatus) {

        static TaxParameter fromYaml(Map<String, Object> row) {
            return new TaxParameter(
                    YamlValues.asString(row.get("fact_path")),
                    YamlValues.asString(row.get("type")),
                    YamlValues.asString(row.get("value")),
                    YamlValues.asString(row.get("name")),
                    YamlValues.asString(row.get("source_id")),
                    YamlValues.asString(row.get("note")),
                    Boolean.TRUE.equals(row.get("provisional")),
                    YamlValues.asString(row.get("filing_status")));
        }

        /** The human-readable name, falling back to the fact path for an unnamed parameter. */
        public String displayName() {
            return name == null || name.isBlank() ? factPath : name;
        }
    }

    /**
     * Display names of parameters for {@code taxYear} explicitly marked {@code provisional: true}
     * (i.e. values published as drafts pending official confirmation). Empty when the year is fully
     * finalized. Used to keep the planner from presenting unverified constants as authoritative.
     */
    public List<String> provisionalParameterNames(int taxYear) {
        return taxParametersForYear(taxYear).stream()
                .filter(TaxParameter::provisional)
                .map(TaxParameter::displayName)
                .toList();
    }

    /**
     * A taxpayer-facing warning naming the year's provisional constants, or {@code null} if the year
     * is fully finalized. Surfaced in tool responses and the export so an unverified value is never
     * presented as authoritative.
     */
    public String provisionalWarning(int taxYear) {
        List<String> names = provisionalParameterNames(taxYear);
        if (names.isEmpty()) {
            return null;
        }
        return "Tax year " + taxYear + " uses provisional, unverified constants pending official "
                + "confirmation: " + String.join(", ", names) + ". Treat these results as estimates, not "
                + "final figures, until the values are confirmed against the official IRS/SSA announcements.";
    }

    /**
     * Resolve a {@code source_id} (as carried by parameters and rules) to its formal legal citation
     * and friendly plain-language explanation. Returns {@code null} if the id is not in the registry.
     */
    public Citation citation(String sourceId) {
        return sourceId == null ? null : citations.get(sourceId);
    }

    /** The full citation registry, keyed by {@code source_id}. */
    public Map<String, Citation> citations() {
        return Collections.unmodifiableMap(citations);
    }

    private Map<String, Citation> loadCitations(Path knowledgeRoot) {
        Path file = knowledgeRoot.resolve("sources").resolve("federal").resolve("citations.yaml");
        if (!Files.exists(file)) {
            log.warn("Citation registry {} not found; authority citations will be unavailable", file);
            return Map.of();
        }
        try {
            Map<String, Object> doc = yamlMapper.readValue(file.toFile(), YamlValues.MAP_TYPE);
            if (!(doc.get("citations") instanceof Map<?, ?> raw)) {
                return Map.of();
            }
            Map<String, Citation> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> fields)) {
                    continue;
                }
                String id = String.valueOf(entry.getKey());
                out.put(id, Citation.fromYaml(id, fields));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read citation registry " + file, e);
        }
    }

    /** Collapse folded-scalar whitespace (newlines and runs of spaces) into single spaces. */
    private static String collapse(String s) {
        return s == null ? null : s.strip().replaceAll("\\s+", " ");
    }

    /**
     * A resolved citation for a {@code source_id}: the {@code authority} kind (statute, form,
     * instruction, publication, agency_announcement), the formal {@code citation} string and
     * {@code title}, an official {@code url}, and a friendly {@code plainLanguage} explanation.
     */
    public record Citation(
            String sourceId, String authority, String citation, String title, String url, String plainLanguage) {

        static Citation fromYaml(String sourceId, Map<?, ?> fields) {
            return new Citation(
                    sourceId,
                    YamlValues.asString(fields.get("authority")),
                    YamlValues.asString(fields.get("citation")),
                    YamlValues.asString(fields.get("title")),
                    YamlValues.asString(fields.get("url")),
                    collapse(YamlValues.asString(fields.get("plain_language"))));
        }
    }

    public record PlanRequest(
            int taxYear,
            String jurisdiction,
            Map<String, Object> facts,
            List<EvidenceState> evidence,
            Map<String, Object> profile,
            boolean includeAnswered,
            Integer limit) {
        public PlanRequest {
            jurisdiction = jurisdiction == null || jurisdiction.isBlank() ? "federal" : jurisdiction;
            facts = facts == null ? Map.of() : facts;
            evidence = evidence == null ? List.of() : evidence;
            profile = profile == null ? Map.of() : profile;
        }
    }

    public record EvidenceState(String documentType, Map<String, Object> attributes) {
        public EvidenceState {
            attributes = attributes == null ? Map.of() : attributes;
        }
    }

    public record PlanResult(
            int taxYear,
            String jurisdiction,
            List<EvidenceSignal> evidenceSignals,
            List<CandidateFact> candidateFacts,
            List<ReviewConflict> conflicts,
            List<PlannedQuestion> questions,
            List<SkippedQuestion> skipped) {}

    public record EvidenceSignal(
            String evidenceMapId, String documentType, String topicId, String confidence, String reason) {}

    public record CandidateFact(
            String path,
            Object value,
            String sourceField,
            boolean requiresConfirmation,
            String evidenceMapId,
            String documentType) {
        CandidateFact withEvidence(String evidenceMapId, EvidenceState evidence) {
            Object resolvedValue = value;
            if (resolvedValue == null && sourceField != null) {
                resolvedValue = evidence.attributes().get(sourceField);
            }
            return new CandidateFact(
                    path, resolvedValue, sourceField, requiresConfirmation, evidenceMapId, evidence.documentType());
        }
    }

    public record ReviewConflict(
            String conflictId, String severity, String message, String resolutionPrompt, List<String> sourceIds) {}

    public record PlannedQuestion(
            String questionId,
            String topicId,
            String priority,
            String prompt,
            String helpText,
            String whyWeAsk,
            List<String> writes,
            List<String> sourceIds) {
        static PlannedQuestion from(Question question) {
            return new PlannedQuestion(
                    question.questionId(),
                    question.topicId(),
                    question.priority(),
                    question.prompt(),
                    question.helpText(),
                    question.whyWeAsk(),
                    question.writes(),
                    question.sourceIds());
        }
    }

    public record SkippedQuestion(String questionId, String reason) {
        static SkippedQuestion answered(Question question) {
            return new SkippedQuestion(question.questionId(), "answered");
        }

        static SkippedQuestion notApplicable(Question question) {
            return new SkippedQuestion(question.questionId(), "asks_when_not_met");
        }

        static SkippedQuestion suppressed(Question question) {
            return new SkippedQuestion(question.questionId(), "skip_when_matched");
        }
    }
}

package com.realworld.tax.advisor.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.realworld.tax.advisor.config.TaxAdvisorProperties;
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

    private static final String STATUS = "status";

    /** Status of a fact the taxpayer has confirmed (the default when a fact carries no status). */
    private static final String CONFIRMED = "confirmed";

    private static final String PARAMETER_FILE_SUFFIX = "-tax-parameters.yaml";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final TaxAdvisorProperties properties;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private Path root;
    private KnowledgeBundle bundle = new KnowledgeBundle(List.of(), List.of(), List.of());
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

        List<Question> questions = loadQuestionFiles(root.resolve("questions"));
        List<EvidenceMap> evidenceMaps = loadEvidenceFiles(root.resolve("evidence"));
        List<ConflictRule> conflictRules = loadConflictFiles(root.resolve("conflicts"));
        this.bundle = new KnowledgeBundle(questions, evidenceMaps, conflictRules);
        this.citations = loadCitations(root);

        log.info(
                "Tax knowledge artifacts loaded from {}: {} questions, {} evidence maps, {} conflict rules, "
                        + "{} citations",
                root,
                questions.size(),
                evidenceMaps.size(),
                conflictRules.size(),
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
            Map<String, Object> doc = yamlMapper.readValue(file.toFile(), MAP_TYPE);
            return maps(doc.get("parameters")).stream()
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
                    asString(row.get("fact_path")),
                    asString(row.get("type")),
                    asString(row.get("value")),
                    asString(row.get("name")),
                    asString(row.get("source_id")),
                    asString(row.get("note")),
                    Boolean.TRUE.equals(row.get("provisional")),
                    asString(row.get("filing_status")));
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
            Map<String, Object> doc = yamlMapper.readValue(file.toFile(), MAP_TYPE);
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
                    asString(fields.get("authority")),
                    asString(fields.get("citation")),
                    asString(fields.get("title")),
                    asString(fields.get("url")),
                    collapse(asString(fields.get("plain_language"))));
        }
    }

    private List<Question> loadQuestionFiles(Path root) throws IOException {
        List<Question> out = new ArrayList<>();
        for (Map<String, Object> doc : yamlDocuments(root)) {
            int taxYear = asInt(doc.get("tax_year"), 0);
            String jurisdiction = asString(doc.get("jurisdiction"));
            for (Map<String, Object> item : maps(doc.get("questions"))) {
                out.add(new Question(
                        asString(item.get("question_id")),
                        asString(item.get("topic_id")),
                        taxYear,
                        jurisdiction,
                        asString(item.get("prompt")),
                        asString(item.get("help_text")),
                        asString(item.get("why_we_ask")),
                        strings(item.get("writes")),
                        ConditionGroup.from(item.get("asks_when")),
                        ConditionGroup.from(item.get("skip_when")),
                        asString(item.get("priority")),
                        strings(item.get("source_ids"))));
            }
        }
        return out;
    }

    private List<EvidenceMap> loadEvidenceFiles(Path root) throws IOException {
        List<EvidenceMap> out = new ArrayList<>();
        for (Map<String, Object> doc : yamlDocuments(root)) {
            for (Map<String, Object> item : maps(doc.get("evidence_maps"))) {
                List<Signal> signals = maps(item.get("signals")).stream()
                        .map(signal -> new Signal(
                                asString(signal.get("topic_id")),
                                asString(signal.get("confidence")),
                                asString(signal.get("reason"))))
                        .toList();
                List<CandidateFact> candidateFacts = maps(item.get("candidate_facts")).stream()
                        .map(candidate -> new CandidateFact(
                                asString(candidate.get("path")),
                                candidate.get("value"),
                                asString(candidate.get("source_field")),
                                asBoolean(candidate.get("requires_confirmation")),
                                null,
                                null))
                        .toList();
                out.add(new EvidenceMap(
                        asString(item.get("evidence_map_id")),
                        asString(item.get("document_type")),
                        signals,
                        candidateFacts,
                        strings(item.get("conflict_rules"))));
            }
        }
        return out;
    }

    private List<ConflictRule> loadConflictFiles(Path root) throws IOException {
        List<ConflictRule> out = new ArrayList<>();
        for (Map<String, Object> doc : yamlDocuments(root)) {
            for (Map<String, Object> item : maps(doc.get("conflicts"))) {
                out.add(new ConflictRule(
                        asString(item.get("conflict_id")),
                        asString(item.get("severity")),
                        ConditionGroup.from(item.get("when")),
                        asString(item.get("message")),
                        asString(item.get("resolution_prompt")),
                        strings(item.get("source_ids"))));
            }
        }
        return out;
    }

    private List<Map<String, Object>> yamlDocuments(Path root) throws IOException {
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
                docs.add(yamlMapper.readValue(file.toFile(), MAP_TYPE));
            }
            return docs;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private record KnowledgeBundle(
            List<Question> questions, List<EvidenceMap> evidenceMaps, List<ConflictRule> conflictRules) {}

    private record Question(
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

    private record EvidenceMap(
            String evidenceMapId,
            String documentType,
            List<Signal> signals,
            List<CandidateFact> candidateFacts,
            List<String> conflictRules) {}

    private record Signal(String topicId, String confidence, String reason) {}

    private record ConflictRule(
            String conflictId,
            String severity,
            ConditionGroup when,
            String message,
            String resolutionPrompt,
            List<String> sourceIds) {}

    private record ConditionGroup(List<Condition> all, List<Condition> any) {
        static ConditionGroup from(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                return new ConditionGroup(List.of(), List.of());
            }
            return new ConditionGroup(toConditions(map.get("all")), toConditions(map.get("any")));
        }

        boolean matches(EvaluationContext context) {
            return all.stream().allMatch(condition -> condition.matches(context))
                    && (any.isEmpty() || any.stream().anyMatch(condition -> condition.matches(context)));
        }

        boolean matchesWhenPresent(EvaluationContext context) {
            return !(all.isEmpty() && any.isEmpty()) && matches(context);
        }

        @SuppressWarnings("unchecked")
        private static List<Condition> toConditions(Object value) {
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> Condition.from((Map<String, Object>) item))
                    .toList();
        }
    }

    private record Condition(
            String fact,
            Object equals,
            String requiredStatus,
            String evidence,
            Boolean exists,
            String profile,
            Object contains,
            Map<String, Object> attributes) {
        static Condition from(Map<String, Object> map) {
            Map<String, Object> attributes = new LinkedHashMap<>(map);
            attributes
                    .keySet()
                    .removeAll(Set.of("fact", "equals", STATUS, "evidence", "exists", "profile", "contains"));
            return new Condition(
                    asString(map.get("fact")),
                    map.get("equals"),
                    asString(map.get(STATUS)),
                    asString(map.get("evidence")),
                    map.containsKey("exists") ? asBoolean(map.get("exists")) : null,
                    asString(map.get("profile")),
                    map.get("contains"),
                    attributes);
        }

        boolean matches(EvaluationContext context) {
            if (fact != null && !matchesFact(context)) {
                return false;
            }
            if (evidence != null && !matchesEvidence(context)) {
                return false;
            }
            if (profile != null && !matchesProfile(context)) {
                return false;
            }
            return fact != null || evidence != null || profile != null;
        }

        private boolean matchesFact(EvaluationContext context) {
            FactState state = context.facts().get(fact);
            if (state == null) {
                return false;
            }
            if (requiredStatus != null && !requiredStatus.equals(state.status())) {
                return false;
            }
            return equals == null || Objects.equals(normalize(equals), normalize(state.value()));
        }

        private boolean matchesEvidence(EvaluationContext context) {
            boolean present = context.evidence().stream()
                    .filter(state -> evidence.equals(state.documentType()))
                    .anyMatch(state -> attributes.entrySet().stream()
                            .allMatch(attribute -> Objects.equals(
                                    normalize(attribute.getValue()),
                                    normalize(state.attributes().get(attribute.getKey())))));
            return exists == null ? present : present == exists;
        }

        private boolean matchesProfile(EvaluationContext context) {
            Object value = context.profile().get(profile);
            if (contains == null) {
                return value != null;
            }
            if (value instanceof List<?> list) {
                return list.stream().anyMatch(item -> Objects.equals(normalize(item), normalize(contains)));
            }
            return Objects.equals(normalize(value), normalize(contains));
        }

        private static Object normalize(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.toString();
            }
            return value;
        }
    }

    private record EvaluationContext(
            Map<String, FactState> facts, List<EvidenceState> evidence, Map<String, Object> profile) {
        static EvaluationContext from(PlanRequest request) {
            Map<String, FactState> facts = new LinkedHashMap<>();
            request.facts().forEach((path, value) -> facts.put(path, FactState.from(value)));
            return new EvaluationContext(facts, request.evidence(), request.profile());
        }

        boolean hasConfirmedAnswerForAny(List<String> paths) {
            return paths.stream()
                    .map(facts::get)
                    .filter(Objects::nonNull)
                    .anyMatch(fact -> CONFIRMED.equals(fact.status()));
        }
    }

    private record FactState(Object value, String status) {
        @SuppressWarnings("unchecked")
        static FactState from(Object value) {
            if (value instanceof Map<?, ?> map) {
                Object status = map.containsKey(STATUS) ? map.get(STATUS) : CONFIRMED;
                return new FactState(((Map<String, Object>) map).get("value"), asString(status));
            }
            return new FactState(value, CONFIRMED);
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

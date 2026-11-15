package com.realworld.tax.advisor.graph;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realworld.tax.advisor.config.TaxAdvisorProperties;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService.TaxParameter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import gov.irs.factgraph.FactDictionary;
import gov.irs.factgraph.Graph;
import gov.irs.factgraph.monads.Result;
import gov.irs.factgraph.persisters.InMemoryPersister;

import gov.irs.directfile.api.loaders.domain.TaxCompNode;
import gov.irs.directfile.api.loaders.domain.TaxDictionaryDigest;
import gov.irs.directfile.api.loaders.domain.TaxFact;
import gov.irs.directfile.api.loaders.processor.FactGraphLoader;
import gov.irs.directfile.api.loaders.processor.XmlProcessor;
import gov.irs.directfile.models.FactTypeWithItem;

/**
 * Loads the combined fact dictionary (production tax/ XMLs + planning tax-plan/ XMLs)
 * once at startup, then answers reads and writes against per-session fact state.
 *
 * <p>Each planning conversation gets its own session id and its own state — a
 * {@link SessionSnapshot} kept in a {@link SessionStore}. The {@link Graph} is not kept: it is
 * rebuilt from the session's facts for every read and every write validation, which is what lets
 * the state live outside this JVM.
 *
 * <p>By default the store is in-memory, so nothing is written to durable storage and planning
 * data lives only for the life of the process — that keeps the FTI surface tiny. With the Redis
 * store switched on, sessions are shared across instances and survive restarts, bounded by an
 * idle timeout; the service itself holds no session state either way.
 */
@Service
public class PlanningGraphService {
    private static final Logger log = LoggerFactory.getLogger(PlanningGraphService.class);

    /**
     * Optional dollar inputs initialized to 0 at session creation so the derived income-tax / total-tax
     * chain is computable for a pure self-employed filer with no other income. Each is overwritten when
     * the agent supplies a real value (see {@link #zeroDefaultOptionalIncome}).
     */
    /**
     * How many times an update is retried when another writer (possibly another instance) changes the
     * same session between the read and the compare-and-set. Tool calls on one session are normally
     * sequential, so contention is rare and a handful of attempts is ample.
     */
    private static final int MAX_UPDATE_ATTEMPTS = 5;

    private static final List<String> INCOME_ZERO_DEFAULTS = List.of(
            FactPaths.OTHER_TAXABLE_INCOME,
            FactPaths.NET_CAPITAL_GAINS,
            FactPaths.SE_SOCIAL_SECURITY_WAGES_FROM_W2,
            FactPaths.MEDICARE_WAGES_FROM_W2);

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final TaxAdvisorProperties properties;
    private final TaxKnowledgeService taxKnowledge;

    private FactDictionary dictionary;
    private TaxDictionaryDigest digest;
    private String knowledgeVersion;

    private final SessionStore sessions;

    public PlanningGraphService(
            ApplicationContext applicationContext,
            ObjectMapper objectMapper,
            TaxAdvisorProperties properties,
            TaxKnowledgeService taxKnowledge,
            SessionStore sessions) {
        this.sessions = sessions;
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.taxKnowledge = taxKnowledge;
    }

    @PostConstruct
    void load() throws IOException {
        List<Resource> all = new ArrayList<>();
        for (String pattern : properties.factXmlPatterns()) {
            Resource[] matched = applicationContext.getResources(pattern);
            log.info("Pattern {} matched {} fact XML resources", pattern, matched.length);
            all.addAll(Arrays.asList(matched));
        }
        if (all.isEmpty()) {
            throw new IllegalStateException("No fact XML resources found for patterns " + properties.factXmlPatterns());
        }

        String beforeLoad = fingerprint(all);
        this.digest = new XmlProcessor().process("combined", all.toArray(new Resource[0]));
        this.dictionary = new FactGraphLoader().createFactDictionary(digest);
        this.knowledgeVersion = fingerprint(all);
        if (!beforeLoad.equals(knowledgeVersion)) {
            throw new IllegalStateException(
                    "Fact-graph XML changed during startup. Restart with immutable runtime resources.");
        }
        log.info(
                "Planning fact dictionary loaded: {} facts across {} patterns",
                digest.getFacts().size(),
                properties.factXmlPatterns().size());
    }

    /** Create a session for the given year, defaulting to the {@code single} filing status. */
    public SessionId createSession(int taxYear) {
        return newSession(taxYear, FilingStatus.SINGLE);
    }

    /** Create a session for the given year and filing status ({@code single | mfj | mfs}; blank = single). */
    public SessionId createSession(int taxYear, String filingStatus) {
        return newSession(taxYear, FilingStatus.parse(filingStatus));
    }

    private SessionId newSession(int taxYear, FilingStatus status) {
        SessionId id = SessionId.random();
        sessions.create(id, freshSnapshot(id, taxYear, status));
        return id;
    }

    /**
     * The state a session starts in: the year's constants for its filing status plus the zero-defaulted
     * optional income inputs. Checked against the fact graph before it is ever stored, so a malformed
     * parameter fails the creation instead of leaving a broken session behind.
     */
    private SessionSnapshot freshSnapshot(SessionId id, int taxYear, FilingStatus status) {
        // Resolve the year's tax parameters first — this throws if the year has no published
        // parameters, so we never create a session that silently uses another year's values.
        List<TaxParameter> params = taxKnowledge.taxParametersForYear(taxYear);
        Map<String, FactTypeWithItem> facts = new LinkedHashMap<>();
        addYearParameters(facts, params, status, taxYear);
        addZeroDefaultedOptionalIncome(facts);
        facts.put(FactPaths.SE_MILEAGE_ESTIMATED, fact(PersisterTypes.BOOLEAN, false));
        buildGraph(id, facts).save();
        return new SessionSnapshot(taxYear, status, facts, Map.of(), knowledgeVersion);
    }

    /**
     * The year-indexed constants, so SE-tax and safe-harbor math use the right year's values.
     * Status-scoped parameters (e.g. the Additional Medicare threshold) are included only for a
     * session whose filing status matches; status-agnostic parameters always are.
     */
    private void addYearParameters(
            Map<String, FactTypeWithItem> facts, List<TaxParameter> params, FilingStatus status, int taxYear) {
        for (TaxParameter p : params) {
            if (status.appliesTo(p.filingStatus())) {
                PersisterTypes.TypedValue typed = PersisterTypes.forParameter(p.type(), p.value(), p.factPath());
                if (facts.putIfAbsent(p.factPath(), fact(typed.typeCode(), typed.value())) != null) {
                    throw new IllegalArgumentException("Overlapping tax parameters for " + p.factPath()
                            + " in income year " + taxYear + " / " + status.code());
                }
            }
        }
        // A partially copied year must fail at session creation, before any tool can claim a result.
        List<String> required = new ArrayList<>(List.of(
                FactPaths.STANDARD_MILEAGE_RATE,
                "/socialSecurityWageBase",
                "/seFilingThreshold",
                "/highIncomeSafeHarborThreshold",
                "/seSocialSecurityRate",
                "/seMedicareRate",
                "/seWageMultiplier",
                "/additionalMedicareRate",
                FactPaths.ADDITIONAL_MEDICARE_THRESHOLD,
                "/qbiRate",
                FactPaths.QBI_THRESHOLD,
                FactPaths.QBI_MINIMUM_DEDUCTION,
                FactPaths.QBI_MINIMUM_INCOME,
                FactPaths.SE_MILEAGE_SPLIT_RATE,
                FactPaths.SE_MILEAGE_CHANGE_MONTH,
                FactPaths.STANDARD_DEDUCTION,
                "/planning/highIncomeSafeHarborRate",
                "/planning/currentYearSafeHarborRate"));
        for (int bracket = 1; bracket <= 7; bracket++) {
            required.add("/incomeTax/bracket" + bracket + "Rate");
            if (bracket < 7) {
                required.add("/incomeTax/bracket" + bracket + "Ceiling");
            }
        }
        if (facts.containsKey(FactPaths.SE_MILEAGE_SPLIT_RATE)
                && facts.get(FactPaths.SE_MILEAGE_SPLIT_RATE).item().asBoolean()) {
            required.add(FactPaths.STANDARD_MILEAGE_RATE_AFTER_CHANGE);
        }
        List<String> missing =
                required.stream().filter(path -> !facts.containsKey(path)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Incomplete tax parameters for income year " + taxYear + " / "
                    + status.code() + ": " + String.join(", ", missing) + ". Install the complete knowledge release.");
        }
    }

    /**
     * Zero-default the optional income inputs so the derived income-tax / total-tax chain is
     * computable from just the Schedule C inputs (the pure-gig case). Each is overwritten when the
     * agent supplies a real value: otherTaxableIncome via project_total_tax, netCapitalGains via
     * estimate_qbi_deduction, and the W-2 wage facts via calculate_se_tax /
     * calculate_additional_medicare. Without these defaults projectedAGI (hence total tax) would stay
     * incomplete until every optional input was set by hand.
     */
    private void addZeroDefaultedOptionalIncome(Map<String, FactTypeWithItem> facts) {
        for (String path : INCOME_ZERO_DEFAULTS) {
            facts.put(path, fact(PersisterTypes.DOLLAR, "0"));
        }
    }

    private FactTypeWithItem fact(String typeCode, Object rawValue) {
        return new FactTypeWithItem(typeCode, objectMapper.valueToTree(rawValue));
    }

    /**
     * Create a new session for the same tax year but a different filing status, copying the source
     * session's INPUT facts. The injected year/filing-status parameters (standard deduction, brackets,
     * QBI and Additional Medicare thresholds, rates, …) are deliberately NOT copied — the new session
     * re-injects the rows for its own status. Used by compare_filing_statuses to project identical
     * income across statuses without the agent re-entering anything. Returns the new session id.
     */
    public SessionId cloneSessionWithFilingStatus(SessionId sourceSessionId, FilingStatus filingStatus) {
        SessionSnapshot source = require(sourceSessionId).snapshot();
        Set<String> paramPaths = taxKnowledge.taxParametersForYear(source.taxYear()).stream()
                .map(TaxParameter::factPath)
                .collect(Collectors.toSet());

        SessionId id = SessionId.random();
        SessionSnapshot fresh = freshSnapshot(id, source.taxYear(), filingStatus);
        Map<String, FactTypeWithItem> facts = new LinkedHashMap<>(fresh.facts());
        // FactTypeWithItem is effectively immutable (a typeCode + a JsonNode we never mutate), so
        // sharing the reference between sessions is safe.
        source.facts().forEach((path, fact) -> {
            if (!paramPaths.contains(path)) {
                facts.put(path, fact);
            }
        });
        sessions.create(id, new SessionSnapshot(source.taxYear(), filingStatus, facts, Map.of(), knowledgeVersion));
        return id;
    }

    /** Discard a session — e.g. the throwaway sessions a filing-status comparison spins up. */
    public void discardSession(SessionId sessionId) {
        sessions.delete(sessionId);
    }

    public int taxYearOf(SessionId sessionId) {
        return require(sessionId).snapshot().taxYear();
    }

    public String knowledgeVersionOf(SessionId sessionId) {
        return require(sessionId).snapshot().knowledgeVersion();
    }

    private String fingerprint(List<Resource> resources) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(taxKnowledge.version().getBytes(StandardCharsets.UTF_8));
            // Content digests, rather than classpath URLs, keep the identity stable across hosts.
            List<String> xmlHashes = new ArrayList<>();
            for (Resource resource : resources) {
                try (var input = resource.getInputStream()) {
                    xmlHashes.add(HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
                }
            }
            for (String xmlHash : xmlHashes.stream().sorted().toList()) {
                hash.update(xmlHash.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public String filingStatusOf(SessionId sessionId) {
        return require(sessionId).snapshot().filingStatus().code();
    }

    public Graph graphFor(SessionId sessionId) {
        return buildGraph(sessionId, require(sessionId).snapshot().facts());
    }

    /** Assemble a fact graph over {@code facts}. The graph is a throwaway calculator; the facts are the state. */
    private Graph buildGraph(SessionId sessionId, Map<String, FactTypeWithItem> facts) {
        try {
            String json = objectMapper.writeValueAsString(facts);
            return new Graph(dictionary, InMemoryPersister.apply(json));
        } catch (Exception e) {
            // Log the underlying cause (and fact paths, not values) so build failures are
            // diagnosable; the cause was previously swallowed behind the generic message.
            log.error("Failed to build fact graph for session {} (fact paths: {})", sessionId, facts.keySet(), e);
            // Distinct from an expired/unknown session (which require() reports): the session
            // exists, but these facts could not be assembled into a graph — usually the most
            // recent write was malformed for its declared type.
            throw new IllegalStateException(
                    "Internal error building the fact graph for session " + sessionId
                            + " — the session still exists (this is not an expiry). The most recent fact write"
                            + " may be malformed for its type; re-check the last set_fact, or recreate the"
                            + " session if this persists.",
                    e);
        }
    }

    /**
     * Write a fact to a session. {@code typeCode} is the persister type tag
     * (e.g. "gov.irs.factgraph.persisters.DollarWrapper",
     * "gov.irs.factgraph.persisters.BooleanWrapper"). {@code rawValue} is shaped to match.
     *
     * <p>The write is atomic. The new state is assembled into a graph <em>before</em> anything is
     * stored, so a write the graph cannot accept (e.g. an invalid persister typeCode, which makes the
     * fact-graph's upickle abort) throws with the session untouched — one malformed set_fact cannot
     * poison a session. It is then stored with compare-and-set, so a concurrent writer on the same
     * session, in this instance or another, is never silently overwritten.
     */
    public WriteResult writeFact(SessionId sessionId, String path, String typeCode, Object rawValue) {
        FactTypeWithItem fact = fact(typeCode, rawValue);
        for (int attempt = 0; attempt < MAX_UPDATE_ATTEMPTS; attempt++) {
            SessionStore.Stored current = require(sessionId);
            if (taxKnowledge.taxParametersForYear(current.snapshot().taxYear()).stream()
                    .anyMatch(parameter -> parameter.factPath().equals(path))) {
                throw new IllegalArgumentException(
                        "Tax parameter " + path + " is fixed by this session's knowledge release. "
                                + "It cannot be overwritten with taxpayer or agent input.");
            }
            SessionSnapshot updated = current.snapshot().withFact(path, fact);
            boolean ok = (boolean) buildGraph(sessionId, updated.facts()).save()._1();
            if (sessions.replace(sessionId, current, updated)) {
                return new WriteResult(ok, ok ? List.of() : List.of("Save reported limit violation for " + path));
            }
        }
        throw contended(sessionId);
    }

    public ReadResult readFact(SessionId sessionId, String path) {
        Graph g = graphFor(sessionId);
        try {
            Result<Object> r = g.get(path);
            if (!r.hasValue() || !r.complete()) {
                String note = digest.getFacts().containsKey(path)
                        ? "Fact is known but not yet computable — depends on missing writable facts."
                        : "Unknown fact path.";
                return new ReadResult(path, null, null, false, note);
            }
            String typeName = r.typeName() == null ? null : r.typeName().trim();
            return new ReadResult(path, scalarize(r.get()), typeName, true, null);
        } catch (UnsupportedOperationException e) {
            return new ReadResult(path, null, null, false, "Unknown fact path: " + path);
        }
    }

    /** Save related inputs together, clearing superseded inputs before evaluating the new graph. */
    public void writeFacts(SessionId sessionId, Map<String, PersisterTypes.TypedValue> values, Set<String> removals) {
        for (int attempt = 0; attempt < MAX_UPDATE_ATTEMPTS; attempt++) {
            SessionStore.Stored current = require(sessionId);
            Set<String> protectedPaths = taxKnowledge
                    .taxParametersForYear(current.snapshot().taxYear())
                    .stream()
                    .map(TaxParameter::factPath)
                    .collect(Collectors.toSet());
            if (values.keySet().stream().anyMatch(protectedPaths::contains)
                    || removals.stream().anyMatch(protectedPaths::contains)) {
                throw new IllegalArgumentException("Tax parameters cannot be overwritten or removed.");
            }
            Map<String, FactTypeWithItem> facts =
                    new LinkedHashMap<>(current.snapshot().facts());
            removals.forEach(facts::remove);
            values.forEach((path, value) -> facts.put(path, fact(value.typeCode(), value.value())));
            buildGraph(sessionId, facts).save();
            SessionSnapshot snapshot = current.snapshot();
            SessionSnapshot updated = new SessionSnapshot(
                    snapshot.taxYear(),
                    snapshot.filingStatus(),
                    facts,
                    snapshot.sourceNotes(),
                    snapshot.knowledgeVersion());
            if (sessions.replace(sessionId, current, updated)) {
                return;
            }
        }
        throw contended(sessionId);
    }

    /**
     * Read a fact and coerce it to a plain {@link BigDecimal}, whatever its underlying type. Dollars
     * and ints convert directly; a {@code Rational} (which {@link #readFact} returns as the engine's
     * own type, rendering as "n/d") is divided out so numeric consumers — tool JSON payloads, tests —
     * get a clean number instead of an engine object. Returns {@code null} if the fact is not
     * computable. Note: the report deliberately still shows rationals as "n/d" via {@code readFact}.
     */
    public BigDecimal readDecimal(SessionId sessionId, String path) {
        return toDecimal(readFact(sessionId, path).value());
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = v.toString().trim();
        int slash = s.indexOf('/');
        if (slash > 0) {
            BigDecimal num = new BigDecimal(s.substring(0, slash).trim());
            BigDecimal den = new BigDecimal(s.substring(slash + 1).trim());
            BigDecimal quotient = num.divide(den, 10, RoundingMode.HALF_UP).stripTrailingZeros();
            return new BigDecimal(quotient.toPlainString());
        }
        return new BigDecimal(s);
    }

    public Map<String, Object> sessionFacts(SessionId sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        require(sessionId).snapshot().facts().forEach((path, fact) -> out.put(path, scalarizePersistedItem(fact)));
        return out;
    }

    /**
     * Record a taxpayer-stated provenance note for a fact (e.g. "2024 1099-NEC, Uber"). This is
     * metadata only — it is never written into the fact graph and never verified; it is surfaced
     * in the export report as self-reported provenance. Blank notes are ignored.
     */
    public void setSourceNote(SessionId sessionId, String path, String note) {
        if (note == null || note.isBlank()) {
            return;
        }
        for (int attempt = 0; attempt < MAX_UPDATE_ATTEMPTS; attempt++) {
            SessionStore.Stored current = require(sessionId);
            if (sessions.replace(sessionId, current, current.snapshot().withSourceNote(path, note.trim()))) {
                return;
            }
        }
        throw contended(sessionId);
    }

    /** Taxpayer-stated provenance notes by fact path (see {@link #setSourceNote}). */
    public Map<String, String> sourceNotes(SessionId sessionId) {
        return new LinkedHashMap<>(require(sessionId).snapshot().sourceNotes());
    }

    /**
     * One-level explanation: name + description + direct dependency paths and their
     * current resolved values. A full recursive walker is a trivial follow-up;
     * one level is already enough to power useful "why is this what it is?" answers.
     */
    public ExplainResult explain(SessionId sessionId, String path) {
        TaxFact fact = digest.getFacts().get(path);
        if (fact == null) {
            return new ExplainResult(path, null, null, null, false, List.of());
        }
        ReadResult value = readFact(sessionId, path);

        List<ExplainResult.Dep> deps = directDependencies(fact).stream()
                .map(dependency -> explainDependency(sessionId, dependency))
                .toList();
        return new ExplainResult(path, fact.name(), fact.description(), value.value(), value.complete(), deps);
    }

    private ExplainResult.Dep explainDependency(SessionId sessionId, String path) {
        ReadResult value = readFact(sessionId, path);
        TaxFact fact = digest.getFacts().get(path);
        return new ExplainResult.Dep(
                path,
                fact == null ? null : fact.name(),
                fact == null ? null : fact.description(),
                value.value(),
                value.complete());
    }

    /**
     * Transitive set of fact paths that {@code path} depends on (directly or indirectly), walking
     * {@code Dependency} nodes through the dictionary. Used to attribute a computed result to the
     * authorities behind the parameters in its computation. Excludes {@code path} itself and bottoms
     * out at writable facts (inputs and injected parameters), which have no derivation to recurse into.
     */
    public Set<String> dependencyClosure(String path) {
        Set<String> visited = new LinkedHashSet<>();
        TaxFact start = digest.getFacts().get(path);
        if (start == null) {
            return visited;
        }
        Deque<String> stack = new ArrayDeque<>(directDependencies(start));
        while (!stack.isEmpty()) {
            String p = stack.pop();
            if (!visited.add(p)) {
                continue;
            }
            TaxFact f = digest.getFacts().get(p);
            if (f != null) {
                stack.addAll(directDependencies(f));
            }
        }
        return visited;
    }

    /** Paths a fact reads directly, from its derivation and then its placeholder, in document order. */
    private List<String> directDependencies(TaxFact fact) {
        List<String> direct = new ArrayList<>();
        collectDependencyPaths(fact.derived(), direct);
        collectDependencyPaths(fact.placeholder(), direct);
        return direct;
    }

    private void collectDependencyPaths(TaxCompNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if ("Dependency".equals(node.typeName())) {
            String p = node.options().get("path");
            if (p != null) {
                out.add(p);
            }
        }
        for (TaxCompNode child : node.children()) {
            collectDependencyPaths(child, out);
        }
    }

    /** Coerce Scala types into JSON-friendly Java values for tool responses. */
    private Object scalarize(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof scala.math.BigDecimal bd) {
            return bd.bigDecimal();
        }
        return v;
    }

    private Object scalarizePersistedItem(FactTypeWithItem fact) {
        if (fact == null || fact.item() == null || fact.item().isNull()) {
            return null;
        }
        var item = fact.item();
        if (item.isBoolean()) {
            return item.asBoolean();
        }
        if (item.isInt() || item.isLong()) {
            return item.asLong();
        }
        if (item.isFloat() || item.isDouble() || item.isBigDecimal()) {
            return item.decimalValue();
        }
        if (item.isTextual()) {
            return item.asText();
        }
        return objectMapper.convertValue(item, Object.class);
    }

    private SessionStore.Stored require(SessionId id) {
        SessionStore.Stored stored = sessions.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or expired planning session: " + id
                        + ". Sessions expire when idle and may not survive a service restart; "
                        + "call create_session to start a new one, then re-enter the facts."));
        if (!knowledgeVersion.equals(stored.snapshot().knowledgeVersion())) {
            throw new IllegalArgumentException("This session belongs to a different tax-knowledge release. "
                    + "Call create_session for income year " + stored.snapshot().taxYear()
                    + " and re-enter the facts so calculations and citations use the same release.");
        }
        return stored;
    }

    private static IllegalStateException contended(SessionId id) {
        return new IllegalStateException("Planning session " + id + " is being changed by another request and this"
                + " update could not be applied after " + MAX_UPDATE_ATTEMPTS + " attempts. Nothing was written;"
                + " retry the call.");
    }

    public record WriteResult(boolean ok, List<String> violations) {}

    // ReadResult/ExplainResult are returned by the get_fact/explain MCP tools, which publish an MCP
    // outputSchema (via @McpTool generateOutputSchema). The schema generator marks every String field
    // `required: string`, so a null string makes the result fail output-schema validation. Normalize
    // the descriptive strings to "" here (null `value` stays null — it maps to a permissive `{}`
    // schema). path is always supplied non-null by callers.
    public record ReadResult(String path, Object value, String typeName, boolean complete, String note) {
        public ReadResult {
            typeName = typeName == null ? "" : typeName;
            note = note == null ? "" : note;
        }
    }

    public record ExplainResult(
            String path, String name, String description, Object value, boolean complete, List<Dep> dependencies) {
        public ExplainResult {
            name = name == null ? "" : name;
            description = description == null ? "" : description;
            dependencies = dependencies == null ? List.of() : dependencies;
        }

        public record Dep(String path, String name, String description, Object value, boolean complete) {
            public Dep {
                name = name == null ? "" : name;
                description = description == null ? "" : description;
            }
        }
    }
}

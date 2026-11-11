package com.realworld.tax.advisor.graph;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * once at startup, then hands out per-session {@link Graph} instances backed by an
 * in-memory persister.
 *
 * <p>Each planning conversation gets its own session id and its own persister state.
 * Nothing is written to durable storage in this MVP — planning data lives only in
 * memory for the duration of the conversation. That keeps the FTI surface tiny and
 * lets the agent operate freely on what-if scenarios.
 */
@Service
public class PlanningGraphService {
    private static final Logger log = LoggerFactory.getLogger(PlanningGraphService.class);

    /**
     * Optional dollar inputs initialized to 0 at session creation so the derived income-tax / total-tax
     * chain is computable for a pure self-employed filer with no other income. Each is overwritten when
     * the agent supplies a real value (see {@link #zeroDefaultOptionalIncome}).
     */
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

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public PlanningGraphService(
            ApplicationContext applicationContext,
            ObjectMapper objectMapper,
            TaxAdvisorProperties properties,
            TaxKnowledgeService taxKnowledge) {
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

        this.digest = new XmlProcessor().process("combined", all.toArray(new Resource[0]));
        this.dictionary = new FactGraphLoader().createFactDictionary(digest);
        log.info(
                "Planning fact dictionary loaded: {} facts across {} patterns",
                digest.getFacts().size(),
                properties.factXmlPatterns().size());
    }

    /** Create a session for the given year, defaulting to the {@code single} filing status. */
    public String createSession(int taxYear) {
        return newSession(taxYear, FilingStatus.SINGLE);
    }

    /** Create a session for the given year and filing status ({@code single | mfj | mfs}; blank = single). */
    public String createSession(int taxYear, String filingStatus) {
        return newSession(taxYear, FilingStatus.parse(filingStatus));
    }

    private String newSession(int taxYear, FilingStatus status) {
        // Resolve the year's tax parameters first — this throws if the year has no published
        // parameters, so we never create a session that silently uses another year's values.
        List<TaxParameter> params = taxKnowledge.taxParametersForYear(taxYear);
        String id = UUID.randomUUID().toString();
        sessions.put(id, new SessionState(taxYear, status, new LinkedHashMap<>(), new LinkedHashMap<>()));
        injectYearParameters(id, params, status);
        zeroDefaultOptionalIncome(id);
        return id;
    }

    /**
     * Inject the year-indexed constants so SE-tax and safe-harbor math use the right year's values.
     * Status-scoped parameters (e.g. the Additional Medicare threshold) are injected only into a
     * session whose filing status matches; status-agnostic parameters always inject.
     */
    private void injectYearParameters(String sessionId, List<TaxParameter> params, FilingStatus status) {
        for (TaxParameter p : params) {
            if (status.appliesTo(p.filingStatus())) {
                PersisterTypes.TypedValue typed = PersisterTypes.forParameter(p.type(), p.value(), p.factPath());
                writeFact(sessionId, p.factPath(), typed.typeCode(), typed.value());
            }
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
    private void zeroDefaultOptionalIncome(String sessionId) {
        for (String path : INCOME_ZERO_DEFAULTS) {
            writeFact(sessionId, path, PersisterTypes.DOLLAR, "0");
        }
    }

    /**
     * Create a new session for the same tax year but a different filing status, copying the source
     * session's INPUT facts. The injected year/filing-status parameters (standard deduction, brackets,
     * QBI and Additional Medicare thresholds, rates, …) are deliberately NOT copied — the new session
     * re-injects the rows for its own status. Used by compare_filing_statuses to project identical
     * income across statuses without the agent re-entering anything. Returns the new session id.
     */
    public String cloneSessionWithFilingStatus(String sourceSessionId, FilingStatus filingStatus) {
        int year = taxYearOf(sourceSessionId);
        Set<String> paramPaths = taxKnowledge.taxParametersForYear(year).stream()
                .map(TaxParameter::factPath)
                .collect(Collectors.toSet());
        SessionState src = require(sourceSessionId);
        // The new session is freshly created and not yet shared with any other thread, so only the
        // source needs locking while we read its facts.
        String newSid = newSession(year, filingStatus);
        SessionState dst = require(newSid);
        synchronized (src) {
            src.facts.forEach((path, fact) -> {
                // FactTypeWithItem is effectively immutable (a typeCode + a JsonNode we never mutate),
                // so sharing the reference between sessions is safe.
                if (!paramPaths.contains(path)) {
                    dst.facts.put(path, fact);
                }
            });
        }
        return newSid;
    }

    /** Discard an in-memory session — e.g. the throwaway sessions a filing-status comparison spins up. */
    public void discardSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public int taxYearOf(String sessionId) {
        return require(sessionId).taxYear();
    }

    public String filingStatusOf(String sessionId) {
        return require(sessionId).filingStatus().code();
    }

    public Graph graphFor(String sessionId) {
        SessionState state = require(sessionId);
        // Serialize under the session lock: building the graph iterates the facts map, and a
        // concurrent writeFact mutating it would corrupt serialization. Holding the lock here (and in
        // writeFact) is what prevents the transient "failed to build graph" faults under concurrent
        // tool calls on the same session.
        synchronized (state) {
            try {
                String json = objectMapper.writeValueAsString(state.facts);
                return new Graph(dictionary, InMemoryPersister.apply(json));
            } catch (Exception e) {
                // Log the underlying cause (and fact paths, not values) so build failures are
                // diagnosable; the cause was previously swallowed behind the generic message.
                log.error(
                        "Failed to build fact graph for session {} (fact paths: {})",
                        sessionId,
                        state.facts.keySet(),
                        e);
                // Distinct from an expired/unknown session (which require() reports): the session
                // exists, but its current facts could not be assembled into a graph — usually the
                // most recent write was malformed for its declared type.
                throw new IllegalStateException(
                        "Internal error building the fact graph for session " + sessionId
                                + " — the session still exists (this is not an expiry). The most recent fact write"
                                + " may be malformed for its type; re-check the last set_fact, or recreate the"
                                + " session if this persists.",
                        e);
            }
        }
    }

    /**
     * Write a fact to a session. {@code typeCode} is the persister type tag
     * (e.g. "gov.irs.factgraph.persisters.DollarWrapper",
     * "gov.irs.factgraph.persisters.BooleanWrapper"). {@code rawValue} is shaped to match.
     */
    public WriteResult writeFact(String sessionId, String path, String typeCode, Object rawValue) {
        SessionState state = require(sessionId);
        // The put and the subsequent graph build/save must be atomic w.r.t. other writers on this
        // session; graphFor re-acquires the same (reentrant) lock.
        synchronized (state) {
            // Snapshot the prior mapping so a write that the graph can't assemble (e.g. an invalid
            // persister typeCode, which makes the fact-graph's upickle abort) can be rolled back.
            // Without this, the bad entry lingers in state.facts and every later graphFor() for this
            // session fails too — one malformed set_fact would poison the whole session.
            FactTypeWithItem previous =
                    state.facts.put(path, new FactTypeWithItem(typeCode, objectMapper.valueToTree(rawValue)));
            try {
                Graph g = graphFor(sessionId);
                var saveResult = g.save();
                boolean ok = (boolean) saveResult._1();
                return new WriteResult(ok, ok ? List.of() : List.of("Save reported limit violation for " + path));
            } catch (RuntimeException e) {
                if (previous == null) {
                    state.facts.remove(path);
                } else {
                    state.facts.put(path, previous);
                }
                throw e;
            }
        }
    }

    public ReadResult readFact(String sessionId, String path) {
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

    /**
     * Read a fact and coerce it to a plain {@link BigDecimal}, whatever its underlying type. Dollars
     * and ints convert directly; a {@code Rational} (which {@link #readFact} returns as the engine's
     * own type, rendering as "n/d") is divided out so numeric consumers — tool JSON payloads, tests —
     * get a clean number instead of an engine object. Returns {@code null} if the fact is not
     * computable. Note: the report deliberately still shows rationals as "n/d" via {@code readFact}.
     */
    public BigDecimal readDecimal(String sessionId, String path) {
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

    public Map<String, Object> sessionFacts(String sessionId) {
        SessionState state = require(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (state) {
            state.facts.forEach((path, fact) -> out.put(path, scalarizePersistedItem(fact)));
        }
        return out;
    }

    /**
     * Record a taxpayer-stated provenance note for a fact (e.g. "2024 1099-NEC, Uber"). This is
     * metadata only — it is never written into the fact graph and never verified; it is surfaced
     * in the export report as self-reported provenance. Blank notes are ignored.
     */
    public void setSourceNote(String sessionId, String path, String note) {
        if (note == null || note.isBlank()) {
            return;
        }
        SessionState state = require(sessionId);
        synchronized (state) {
            state.sourceNotes().put(path, note.trim());
        }
    }

    /** Taxpayer-stated provenance notes by fact path (see {@link #setSourceNote}). */
    public Map<String, String> sourceNotes(String sessionId) {
        SessionState state = require(sessionId);
        synchronized (state) {
            return new LinkedHashMap<>(state.sourceNotes());
        }
    }

    /**
     * One-level explanation: name + description + direct dependency paths and their
     * current resolved values. A full recursive walker is a trivial follow-up;
     * one level is already enough to power useful "why is this what it is?" answers.
     */
    public ExplainResult explain(String sessionId, String path) {
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

    private ExplainResult.Dep explainDependency(String sessionId, String path) {
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

    private SessionState require(String id) {
        SessionState s = sessions.get(id);
        if (s == null) {
            throw new IllegalArgumentException("Unknown or expired planning session: " + id
                    + ". Sessions are held in memory and are lost when the service restarts; "
                    + "call create_session to start a new one, then re-enter the facts.");
        }
        return s;
    }

    private record SessionState(
            int taxYear,
            FilingStatus filingStatus,
            LinkedHashMap<String, FactTypeWithItem> facts,
            LinkedHashMap<String, String> sourceNotes) {}

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

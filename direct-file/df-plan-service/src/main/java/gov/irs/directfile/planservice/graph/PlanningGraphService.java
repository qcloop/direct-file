package gov.irs.directfile.planservice.graph;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import gov.irs.directfile.planservice.config.PlanServiceProperties;

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

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final PlanServiceProperties properties;

    private FactDictionary dictionary;
    private TaxDictionaryDigest digest;

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public PlanningGraphService(
            ApplicationContext applicationContext, ObjectMapper objectMapper, PlanServiceProperties properties) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    void load() throws IOException {
        List<Resource> all = new ArrayList<>();
        for (String pattern : properties.factXmlPatterns()) {
            Resource[] matched = applicationContext.getResources(pattern);
            log.info("Pattern {} matched {} fact XML resources", pattern, matched.length);
            for (Resource r : matched) {
                all.add(r);
            }
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

    public String createSession() {
        String id = java.util.UUID.randomUUID().toString();
        sessions.put(id, new SessionState(new LinkedHashMap<>()));
        return id;
    }

    public Graph graphFor(String sessionId) {
        SessionState state = require(sessionId);
        try {
            String json = objectMapper.writeValueAsString(state.facts);
            return new Graph(dictionary, InMemoryPersister.apply(json));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to materialize graph for session " + sessionId, e);
        }
    }

    /**
     * Write a fact to a session. {@code typeCode} is the persister type tag
     * (e.g. "gov.irs.factgraph.persisters.DollarWrapper",
     * "gov.irs.factgraph.persisters.BooleanWrapper"). {@code rawValue} is shaped to match.
     */
    public WriteResult writeFact(String sessionId, String path, String typeCode, Object rawValue) {
        SessionState state = require(sessionId);
        state.facts.put(path, new FactTypeWithItem(typeCode, objectMapper.valueToTree(rawValue)));

        Graph g = graphFor(sessionId);
        var saveResult = g.save();
        boolean ok = (boolean) saveResult._1();
        return new WriteResult(ok, ok ? List.of() : List.of("Save reported limit violation for " + path));
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

    public Map<String, Object> sessionFacts(String sessionId) {
        SessionState state = require(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        state.facts.forEach((path, fact) -> out.put(path, scalarizePersistedItem(fact)));
        return out;
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

        List<String> depPaths = new ArrayList<>();
        collectDependencyPaths(fact.derived(), depPaths);
        collectDependencyPaths(fact.placeholder(), depPaths);

        List<ExplainResult.Dep> deps = new ArrayList<>();
        for (String dep : depPaths) {
            ReadResult dv = readFact(sessionId, dep);
            TaxFact df = digest.getFacts().get(dep);
            deps.add(new ExplainResult.Dep(
                    dep,
                    df == null ? null : df.name(),
                    df == null ? null : df.description(),
                    dv.value(),
                    dv.complete()));
        }
        return new ExplainResult(path, fact.name(), fact.description(), value.value(), value.complete(), deps);
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

    public Map<String, String> writableFactCatalog() {
        Map<String, String> out = new HashMap<>();
        digest.getFacts().forEach((path, fact) -> {
            if (fact.writable() != null) {
                out.put(path, fact.name() == null ? fact.description() : fact.name());
            }
        });
        return out;
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
            throw new IllegalArgumentException("Unknown planning session: " + id);
        }
        return s;
    }

    private record SessionState(LinkedHashMap<String, FactTypeWithItem> facts) {}

    public record WriteResult(boolean ok, List<String> violations) {}

    public record ReadResult(String path, Object value, String typeName, boolean complete, String note) {}

    public record ExplainResult(
            String path, String name, String description, Object value, boolean complete, List<Dep> dependencies) {
        public record Dep(String path, String name, String description, Object value, boolean complete) {}
    }
}

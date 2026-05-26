package gov.irs.directfile.api.factgraph;

import scala.Option;
import scala.xml.NodeSeq;
import scala.xml.NodeSeq$;

import gov.irs.factgraph.definitions.fact.CompNodeConfigTrait;
import gov.irs.factgraph.definitions.fact.FactConfigTrait;
import gov.irs.factgraph.definitions.fact.WritableConfigTrait;

/**
 * Java-side implementation of the Scala {@link FactConfigTrait}.
 *
 * <p>As of fact-graph 3.x the trait adds three abstract members beyond
 * {@code path/writable/derived/placeholder}:
 * <ul>
 *   <li>{@link #node()} — the raw XML representation of the fact, used by the
 *       engine only to store the node in {@code FactDictionary.definitionsAsNodes}.
 *       Our XML loader parses to a typed digest rather than passing the raw
 *       NodeSeq through, so we return {@link NodeSeq$#Empty NodeSeq.Empty}; the
 *       engine doesn't read this value during normal get/set/save/explain.</li>
 *   <li>{@link #overrideCondition()} and {@link #overrideDefault()} — power the
 *       {@code Override} compnode wrapper. The current XML schema doesn't expose
 *       these, so we return {@link Option#empty()}.</li>
 * </ul>
 */
public class FactConfig implements FactConfigTrait {

    public FactConfig(
            String path, WritableConfigTrait writable, CompNodeConfigTrait derived, CompNodeConfigTrait placeholder) {
        this.path = path;
        this.writable = writable;
        this.derived = derived;
        this.placeholder = placeholder;
    }

    public String path;
    public WritableConfigTrait writable;
    public CompNodeConfigTrait derived;
    public CompNodeConfigTrait placeholder;

    @Override
    public String path() {
        return path;
    }

    @Override
    public Option<WritableConfigTrait> writable() {
        return Option.apply(writable);
    }

    @Override
    public Option<CompNodeConfigTrait> derived() {
        return Option.apply(derived);
    }

    @Override
    public Option<CompNodeConfigTrait> placeholder() {
        return Option.apply(placeholder);
    }

    @Override
    public NodeSeq node() {
        return NodeSeq$.MODULE$.Empty();
    }

    @Override
    public Option<CompNodeConfigTrait> overrideCondition() {
        return Option.empty();
    }

    @Override
    public Option<CompNodeConfigTrait> overrideDefault() {
        return Option.empty();
    }
}

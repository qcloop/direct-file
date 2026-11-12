package com.realworld.tax.advisor.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fact-graph persister wrappers this service writes through, and the rules for turning a
 * client- or YAML-supplied string into the JSON shape each wrapper expects.
 *
 * <p>Every real persister lives under {@code gov.irs.factgraph.persisters}; a type tag outside that
 * package makes the engine's uPickle layer hard-abort, so type codes are validated here before they
 * can reach a graph (CLAUDE.md invariant 5).
 */
public final class PersisterTypes {

    public static final String PACKAGE_PREFIX = "gov.irs.factgraph.persisters.";
    public static final String DOLLAR = PACKAGE_PREFIX + "DollarWrapper";
    public static final String INT = PACKAGE_PREFIX + "IntWrapper";
    public static final String RATIONAL = PACKAGE_PREFIX + "RationalWrapper";
    public static final String BOOLEAN = PACKAGE_PREFIX + "BooleanWrapper";

    /** Short aliases a tool caller may use instead of a full type code, in the order they are documented. */
    private static final Map<String, String> ALIASES = aliases();

    private PersisterTypes() {}

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("dollar", DOLLAR);
        aliases.put("int", INT);
        aliases.put("boolean", BOOLEAN);
        aliases.put("string", PACKAGE_PREFIX + "StringWrapper");
        aliases.put("day", PACKAGE_PREFIX + "DayWrapper");
        aliases.put("enum", PACKAGE_PREFIX + "EnumWrapper");
        aliases.put("ein", PACKAGE_PREFIX + "EinWrapper");
        aliases.put("tin", PACKAGE_PREFIX + "TinWrapper");
        return Collections.unmodifiableMap(aliases);
    }

    /**
     * Resolve the persister type code for a write: an explicit {@code typeCode} wins, otherwise the
     * short {@code alias} is looked up. Rejects a missing/unknown alias and any type code outside the
     * persister package.
     */
    public static String resolve(String alias, String typeCode) {
        String resolved = typeCode == null || typeCode.isBlank() ? fromAlias(alias) : typeCode;

        // Every real fact-graph persister lives under gov.irs.factgraph.persisters. A client-supplied
        // typeCode outside that package (e.g. an LLM-invented "us.gov.irs.df.plan.FilingStatusPersister")
        // would otherwise reach the graph and trigger an opaque upickle "invalid tag" abort that also
        // poisons the session. Reject it here with a message the caller can act on. Enums — including
        // filing status — use the EnumWrapper persister, reachable via type="enum".
        if (!resolved.startsWith(PACKAGE_PREFIX)) {
            throw new IllegalArgumentException("Unknown persister typeCode '" + resolved
                    + "'. Fact-graph persisters live under " + PACKAGE_PREFIX
                    + "; prefer the 'type' alias instead (one of " + ALIASES.keySet()
                    + ") — e.g. type=\"enum\" for a filing-status fact.");
        }
        return resolved;
    }

    private static String fromAlias(String alias) {
        if (alias == null) {
            throw new IllegalArgumentException("Must supply 'type' or 'typeCode'.");
        }
        String typeCode = ALIASES.get(alias.toLowerCase());
        if (typeCode == null) {
            throw new IllegalArgumentException("Unknown type alias: " + alias);
        }
        return typeCode;
    }

    /**
     * Convert a string value to the JSON shape its persister expects. DollarWrapper and the other
     * string-backed wrappers read a string as-is; IntWrapper expects a JSON number and BooleanWrapper
     * a JSON boolean.
     */
    public static Object shape(String typeCode, String value) {
        if (INT.equals(typeCode)) {
            return Long.parseLong(value.trim());
        }
        if (BOOLEAN.equals(typeCode)) {
            String trimmed = value.trim();
            if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
                throw new IllegalArgumentException(
                        "Boolean facts require true or false. Leave an unknown answer unset; "
                                + "do not coerce it to false.");
            }
            return Boolean.parseBoolean(trimmed);
        }
        return value;
    }

    /** The Rational persister serializes as {@code {"n":<numerator>,"d":<denominator>}}. */
    public static Map<String, Integer> rational(int numerator, int denominator) {
        return Map.of("n", numerator, "d", denominator);
    }

    /**
     * Type code and shaped value for a year-indexed tax parameter. The parameter's declared value type
     * drives the persister wrapper and how its string is shaped: {@code int} becomes a JSON number,
     * {@code rational} (authored in the readable "n/d" form) the Rational object, and {@code dollar}
     * (the default) stays the string as-is.
     */
    public static TypedValue forParameter(String type, String value, String factPath) {
        return switch (type == null ? "dollar" : type.toLowerCase()) {
            case "int" -> new TypedValue(INT, Long.parseLong(value.trim()));
            case "rational" -> new TypedValue(RATIONAL, parseRational(value, factPath));
            case "boolean" -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new IllegalArgumentException("Boolean parameter " + factPath + " must be true or false.");
                }
                yield new TypedValue(BOOLEAN, Boolean.parseBoolean(value));
            }
            case "dollar" -> new TypedValue(DOLLAR, value);
            default -> throw new IllegalArgumentException("Unsupported parameter type " + type + " for " + factPath);
        };
    }

    private static Map<String, Integer> parseRational(String value, String factPath) {
        String[] nd = value.trim().split("/", 2);
        if (nd.length != 2) {
            throw new IllegalArgumentException("Rational parameter " + factPath + " must be 'n/d', got: " + value);
        }
        return rational(Integer.parseInt(nd[0].trim()), Integer.parseInt(nd[1].trim()));
    }

    /** A value shaped for its persister, paired with that persister's type code. */
    public record TypedValue(String typeCode, Object value) {}
}

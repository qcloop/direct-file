package com.realworld.tax.advisor.knowledge;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Lenient accessors for values read from the tax-knowledge YAML as untyped maps: a missing or
 * wrongly-shaped value reads as null / empty / the fallback instead of failing the whole load.
 */
final class YamlValues {

    static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private YamlValues() {}

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static int asInt(Object value, int fallback) {
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

    static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}

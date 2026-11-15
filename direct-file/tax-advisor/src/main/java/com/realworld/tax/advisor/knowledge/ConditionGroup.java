package com.realworld.tax.advisor.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A declarative rule from the tax-knowledge YAML ({@code asks_when}, {@code skip_when}, a conflict's
 * {@code when}): every condition under {@code all} must hold, and — if any are listed — at least one
 * under {@code any}. Each condition tests a fact, a piece of document evidence, or a profile signal.
 */
record ConditionGroup(List<Condition> all, List<Condition> any) {
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

    record Condition(
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
                    .removeAll(Set.of(
                            "fact", "equals", EvaluationContext.STATUS, "evidence", "exists", "profile", "contains"));
            return new Condition(
                    YamlValues.asString(map.get("fact")),
                    map.get("equals"),
                    YamlValues.asString(map.get(EvaluationContext.STATUS)),
                    YamlValues.asString(map.get("evidence")),
                    map.containsKey("exists") ? YamlValues.asBoolean(map.get("exists")) : null,
                    YamlValues.asString(map.get("profile")),
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
            EvaluationContext.FactState state = context.facts().get(fact);
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
}

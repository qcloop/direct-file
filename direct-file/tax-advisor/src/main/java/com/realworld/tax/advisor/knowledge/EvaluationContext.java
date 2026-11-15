package com.realworld.tax.advisor.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.realworld.tax.advisor.knowledge.TaxKnowledgeService.EvidenceState;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService.PlanRequest;

/**
 * What a tax-knowledge condition is evaluated against for one planning request: the known facts
 * (each with its confirmation status), the document evidence on hand, and the taxpayer profile.
 */
record EvaluationContext(Map<String, FactState> facts, List<EvidenceState> evidence, Map<String, Object> profile) {

    /** Key under which a fact supplied as {@code {value, status}} carries its status. */
    static final String STATUS = "status";

    /** Status of a fact the taxpayer has confirmed (the default when a fact carries no status). */
    static final String CONFIRMED = "confirmed";

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

    record FactState(Object value, String status) {
        @SuppressWarnings("unchecked")
        static FactState from(Object value) {
            if (value instanceof Map<?, ?> map) {
                Object status = map.containsKey(STATUS) ? map.get(STATUS) : CONFIRMED;
                return new FactState(((Map<String, Object>) map).get("value"), YamlValues.asString(status));
            }
            return new FactState(value, CONFIRMED);
        }
    }
}

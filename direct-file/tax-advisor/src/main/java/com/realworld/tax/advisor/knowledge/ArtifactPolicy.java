package com.realworld.tax.advisor.knowledge;

import java.util.Map;
import java.util.Set;

/** One release vocabulary for parameter and interview artifacts. Missing status is draft. */
final class ArtifactPolicy {
    private static final Set<String> RELEASED = Set.of("approved", "published", "compiled");

    private ArtifactPolicy() {}

    static boolean released(Map<String, Object> artifact) {
        return RELEASED.contains(YamlValues.asString(artifact.get("status")));
    }

    static boolean allowed(Map<String, Object> artifact, boolean allowDrafts) {
        String status = YamlValues.asString(artifact.get("status"));
        return released(artifact)
                || (allowDrafts
                        && (status == null || Set.of("draft", "reviewed").contains(status)));
    }

    static boolean allowedItem(Map<String, Object> artifact, boolean allowDrafts) {
        return !artifact.containsKey("status") || allowed(artifact, allowDrafts);
    }
}

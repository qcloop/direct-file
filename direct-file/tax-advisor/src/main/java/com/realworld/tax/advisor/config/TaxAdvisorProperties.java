package com.realworld.tax.advisor.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tax-advisor")
public record TaxAdvisorProperties(
        /**
         * Filesystem root for prototype tax-knowledge authoring artifacts. These are
         * loaded as planning inputs only; they do not mutate filed-return facts.
         */
        String taxKnowledgeRoot,

        /**
         * Resource patterns from which to load fact-dictionary XML. The planning service
         * loads BOTH the production tax/ XMLs (from the directfile-api jar's classpath)
         * AND its own tax-plan/ XMLs, so the combined dictionary can compute SE tax and
         * estimated payments alongside the standard income/AGI/tax-liability chain.
         */
        List<String> factXmlPatterns,

        /** Where planning sessions are kept between tool calls. */
        SessionStoreProperties sessionStore) {

    public TaxAdvisorProperties {
        if (taxKnowledgeRoot == null || taxKnowledgeRoot.isBlank()) {
            taxKnowledgeRoot = "../tax-knowledge";
        }
        if (factXmlPatterns == null || factXmlPatterns.isEmpty()) {
            factXmlPatterns = List.of("classpath:/tax/*.xml", "classpath:/tax-plan/*.xml");
        }
        if (sessionStore == null) {
            sessionStore = new SessionStoreProperties(null, null, null);
        }
    }

    /**
     * {@code tax-advisor.session-store.*}. {@code type} is {@code memory} (the default: sessions live
     * only in this JVM and nothing is persisted) or {@code redis} (sessions are shared through the
     * Redis at {@code spring.data.redis.url}, so any instance can serve any session).
     *
     * <p>{@code idleTimeout} and {@code keyPrefix} apply to the Redis store: a session untouched for
     * the timeout expires, and the prefix namespaces this service's keys within a Redis shared with
     * other applications.
     */
    public record SessionStoreProperties(String type, Duration idleTimeout, String keyPrefix) {

        public SessionStoreProperties {
            if (type == null || type.isBlank()) {
                type = "memory";
            }
            if (idleTimeout == null) {
                idleTimeout = Duration.ofMinutes(60);
            }
            if (keyPrefix == null || keyPrefix.isBlank()) {
                keyPrefix = "tax-advisor:session:";
            }
        }
    }
}

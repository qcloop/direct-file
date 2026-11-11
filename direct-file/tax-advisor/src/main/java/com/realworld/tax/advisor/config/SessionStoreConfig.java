package com.realworld.tax.advisor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realworld.tax.advisor.graph.InMemorySessionStore;
import com.realworld.tax.advisor.graph.RedisSessionStore;
import com.realworld.tax.advisor.graph.SessionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Chooses where planning sessions are kept, from {@code tax-advisor.session-store.type}.
 *
 * <p>{@code memory} (the default, and what the {@code stdio} transport and the tests use) keeps the
 * service stateless outside its own process. {@code redis} — switched on by the {@code redis} profile —
 * shares sessions through Redis so the service can run as more than one instance.
 */
@Configuration
public class SessionStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "tax-advisor.session-store.type", havingValue = "memory", matchIfMissing = true)
    SessionStore inMemorySessionStore() {
        return new InMemorySessionStore();
    }

    // The session JSON is written with this service's Jackson 2 mapper (CLAUDE.md invariant 3) through
    // a StringRedisTemplate, deliberately not Spring Data Redis's own value serializers: the stored
    // FactTypeWithItem carries a Jackson 2 JsonNode, and Boot 4's default mapper is Jackson 3.
    @Bean
    @ConditionalOnProperty(name = "tax-advisor.session-store.type", havingValue = "redis")
    SessionStore redisSessionStore(
            StringRedisTemplate redis, ObjectMapper objectMapper, TaxAdvisorProperties properties) {
        TaxAdvisorProperties.SessionStoreProperties store = properties.sessionStore();
        return new RedisSessionStore(redis, objectMapper, store.keyPrefix(), store.idleTimeout());
    }
}

package com.realworld.tax.advisor.graph;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the {@link SessionStoreContract} — plus the Redis-only expiry behavior — against a real Redis:
 * the one at {@code REDIS_URL}, defaulting to the local development endpoint
 * {@code redis://localhost:6379} that the client web app also uses.
 *
 * <p>If no Redis answers there the tests are skipped rather than failed, so the build does not
 * depend on local infrastructure. Every key written here sits under a prefix unique to this run and
 * is deleted (or expires within seconds), so a shared Redis is left as it was found.
 */
class RedisSessionStoreTest extends SessionStoreContract {

    private static final String PREFIX = "tax-advisor:test:" + UUID.randomUUID() + ":";

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static SessionStore store;

    @BeforeAll
    static void connect() {
        String url = System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379");
        try {
            connectionFactory = new LettuceConnectionFactory(LettuceConnectionFactory.createRedisConfiguration(url));
            connectionFactory.afterPropertiesSet();
            redis = new StringRedisTemplate(connectionFactory);
            redis.afterPropertiesSet();
            redis.hasKey(PREFIX + "ping");
        } catch (RuntimeException e) {
            Assumptions.abort("No Redis reachable at " + url + " — skipping the Redis session-store tests");
        }
        store = new RedisSessionStore(redis, MAPPER, PREFIX, Duration.ofMinutes(5));
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Override
    protected SessionStore store() {
        return store;
    }

    private static SessionSnapshot anySession() {
        return new SessionSnapshot(2025, FilingStatus.SINGLE, Map.of(), Map.of());
    }

    @Test
    void sessionsAreStoredUnderThePrefixWithAnExpiry() {
        SessionId id = SessionId.random();
        store.create(id, anySession());

        Long ttlSeconds = redis.getExpire(PREFIX + id.value());
        assertThat(ttlSeconds).isBetween(1L, 300L);
        store.delete(id);
        assertThat(redis.hasKey(PREFIX + id.value())).isFalse();
    }

    @Test
    void anIdleSessionExpires() throws InterruptedException {
        SessionStore shortLived = new RedisSessionStore(redis, MAPPER, PREFIX, Duration.ofMillis(300));
        SessionId id = SessionId.random();
        shortLived.create(id, anySession());

        Thread.sleep(700);

        assertThat(shortLived.find(id)).isEmpty();
    }

    @Test
    void usingASessionKeepsItAlive() throws InterruptedException {
        SessionStore shortLived = new RedisSessionStore(redis, MAPPER, PREFIX, Duration.ofMillis(1500));
        SessionId id = SessionId.random();
        shortLived.create(id, anySession());

        // Touch it every 500 ms for 2.5 s — well past the 1.5 s idle limit in total.
        for (int i = 0; i < 5; i++) {
            Thread.sleep(500);
            assertThat(shortLived.find(id)).as("read %d", i).isPresent();
        }
        shortLived.delete(id);
    }

    @Test
    void aSecondStoreOnTheSameRedisSeesTheSameSession() {
        // Stands in for a second instance of the service: separate store object, same Redis.
        SessionStore otherInstance = new RedisSessionStore(redis, MAPPER, PREFIX, Duration.ofMinutes(5));
        SessionId id = SessionId.random();
        store.create(id, anySession());

        assertThat(otherInstance.find(id)).isPresent();
        otherInstance.delete(id);
        assertThat(store.find(id)).isEmpty();
    }
}

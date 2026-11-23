package com.realworld.tax.advisor.graph;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.github.fppt.jedismock.RedisServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the production Redis adapter through Lettuce against a local Jedis-Mock RESP server.
 * An explicit REDIS_URL uses that real endpoint instead, and connection failures fail the tests.
 * Keys are isolated under a unique prefix; neither mode flushes an existing database.
 */
class RedisSessionStoreTest extends SessionStoreContract {

    private static final String PREFIX = "tax-advisor:test:" + UUID.randomUUID() + ":";

    private static RedisServer server;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static LettuceConnectionFactory peerFactory;
    private static StringRedisTemplate peerRedis;
    private static SessionStore store;

    @BeforeAll
    static void connect() throws IOException {
        String url = System.getenv("REDIS_URL");
        if (url == null || url.isBlank()) {
            server = RedisServer.newRedisServer(0, InetAddress.getLoopbackAddress())
                    .start();
            url = "redis://" + server.getHost() + ":" + server.getBindPort();
        }
        connectionFactory = new LettuceConnectionFactory(LettuceConnectionFactory.createRedisConfiguration(url));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        redis.hasKey(PREFIX + "ping");
        peerFactory = new LettuceConnectionFactory(LettuceConnectionFactory.createRedisConfiguration(url));
        peerFactory.afterPropertiesSet();
        peerRedis = new StringRedisTemplate(peerFactory);
        peerRedis.afterPropertiesSet();
        store = new RedisSessionStore(redis, MAPPER, PREFIX, Duration.ofMinutes(5));
    }

    @AfterAll
    static void disconnect() throws IOException {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (peerFactory != null) {
            peerFactory.destroy();
        }
        if (server != null) {
            server.stop();
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
        SessionStore.Stored beforeExpiry = shortLived.find(id).orElseThrow();

        Thread.sleep(700);

        assertThat(shortLived.find(id)).isEmpty();
        assertThat(shortLived.replace(id, beforeExpiry, anySession())).isFalse();
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
        SessionStore otherInstance = new RedisSessionStore(peerRedis, MAPPER, PREFIX, Duration.ofMinutes(5));
        SessionId id = SessionId.random();
        store.create(id, anySession());

        SessionStore.Stored firstRead = store.find(id).orElseThrow();
        SessionStore.Stored peerRead = otherInstance.find(id).orElseThrow();
        assertThat(otherInstance.replace(id, peerRead, anySession().withSourceNote("/winner", "peer")))
                .isTrue();
        assertThat(store.replace(id, firstRead, anySession().withSourceNote("/loser", "stale")))
                .isFalse();
        assertThat(store.find(id).orElseThrow().snapshot().sourceNotes()).containsEntry("/winner", "peer");
        otherInstance.delete(id);
        assertThat(store.find(id)).isEmpty();
    }
}

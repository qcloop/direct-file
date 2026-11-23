package com.realworld.tax.advisor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetAddress;

import com.github.fppt.jedismock.RedisServer;
import com.realworld.tax.advisor.graph.PersisterTypes;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.RedisSessionStore;
import com.realworld.tax.advisor.graph.SessionStore;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.realworld.tax.advisor.PropertySupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Qualifies fact-graph reconstruction and session JSON through the actual Redis-backed service. */
@SpringBootTest(properties = "tax-advisor.session-store.type=redis")
class RedisPlanningGraphIntegrationTest {
    private static final RedisServer REDIS = startRedis();

    @Autowired
    PlanningGraphService graph;

    @Autowired
    SessionStore sessions;

    private static RedisServer startRedis() {
        try {
            return RedisServer.newRedisServer(0, InetAddress.getLoopbackAddress())
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getBindPort());
    }

    @AfterAll
    static void stopRedis() throws IOException {
        REDIS.stop();
    }

    @HegelTest(testCases = 3000)
    void persistedFactsRebuildWithoutChangingTheirValues(TestCase tc) {
        assertThat(sessions).isInstanceOf(RedisSessionStore.class);
        BigDecimal amount = dollars(tc, "amount");
        try (var owned = session(graph, tc)) {
            graph.writeFact(
                    owned.sessionId(), "/planning/priorYearTotalTax", PersisterTypes.DOLLAR, amount.toPlainString());
            graph.setSourceNote(owned.sessionId(), "/planning/priorYearTotalTax", "synthetic Redis compatibility case");
            // Each read constructs a fresh graph from the Redis snapshot, rather than a cached Graph.
            assertThat(graph.readDecimal(owned.sessionId(), "/planning/priorYearTotalTax"))
                    .isEqualByComparingTo(amount);
            assertThat(graph.graphFor(owned.sessionId())
                            .get("/planning/priorYearTotalTax")
                            .complete())
                    .isTrue();
            assertThat(sessions.find(owned.sessionId()).orElseThrow().snapshot().sourceNotes())
                    .containsEntry("/planning/priorYearTotalTax", "synthetic Redis compatibility case");
        }
    }
}

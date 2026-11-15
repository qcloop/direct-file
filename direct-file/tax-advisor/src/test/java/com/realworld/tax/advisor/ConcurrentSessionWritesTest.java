package com.realworld.tax.advisor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.realworld.tax.advisor.graph.PersisterTypes;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session updates are read-modify-write against the session store, applied with compare-and-set
 * instead of a lock (a lock would only protect writers inside one JVM). This pins the property that
 * matters: simultaneous writes to one session all land — none is silently overwritten by a writer
 * that read the session a moment earlier.
 */
@SpringBootTest
class ConcurrentSessionWritesTest {

    private static final List<String> PATHS = List.of(
            "/planning/priorYearTotalTax",
            "/planning/priorYearAGI",
            "/planning/ytdWithholding",
            "/planning/ytdEstimatedPaymentsMade");

    @Autowired
    PlanningGraphService graph;

    @Test
    void simultaneousWritesToOneSessionAreAllKept() throws Exception {
        SessionId session = graph.createSession(2025);
        ExecutorService pool = Executors.newFixedThreadPool(PATHS.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < PATHS.size(); i++) {
                String path = PATHS.get(i);
                String amount = Integer.toString(1000 * (i + 1));
                writes.add(pool.submit(() -> {
                    start.await();
                    return graph.writeFact(session, path, PersisterTypes.DOLLAR, amount);
                }));
            }
            start.countDown();
            for (Future<?> write : writes) {
                write.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(graph.sessionFacts(session)).containsKeys(PATHS.toArray(String[]::new));
        for (int i = 0; i < PATHS.size(); i++) {
            assertThat(graph.readDecimal(session, PATHS.get(i))).isEqualByComparingTo(Integer.toString(1000 * (i + 1)));
        }
    }
}

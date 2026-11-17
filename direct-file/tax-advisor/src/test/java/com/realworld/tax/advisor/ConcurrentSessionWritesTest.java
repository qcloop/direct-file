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
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.realworld.tax.advisor.PropertySupport.*;
import static dev.hegel.Generators.*;
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

    @HegelTest(testCases = 1000)
    void simultaneousWritesToOneSessionAreAllKept(TestCase tc) throws Exception {
        int writers = tc.draw(integers().min(1).max(PATHS.size()), "writers");
        List<String> amounts = new ArrayList<>();
        for (int i = 0; i < writers; i++) amounts.add(dollars(tc, "amount" + i).toPlainString());
        try (var owned = session(graph, tc)) {
            SessionId session = owned.sessionId();
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<?>> writes = new ArrayList<>();
                for (int i = 0; i < writers; i++) {
                    String path = PATHS.get(i);
                    String amount = amounts.get(i);
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

            assertThat(graph.sessionFacts(session))
                    .containsKeys(PATHS.subList(0, writers).toArray(String[]::new));
            for (int i = 0; i < writers; i++) {
                assertThat(graph.readDecimal(session, PATHS.get(i))).isEqualByComparingTo(amounts.get(i));
            }
        }
    }
}

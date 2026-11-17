package com.realworld.tax.advisor;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.realworld.tax.advisor.graph.FilingStatus;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import dev.hegel.TestCase;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;

/** Shared input domains and per-example cleanup: a generated run must not retain thousands of sessions. */
public final class PropertySupport {
    private PropertySupport() {}

    public static int year(TestCase tc) {
        return tc.draw(sampledFrom(2024, 2025, 2026), "taxYear");
    }

    public static String filingStatus(TestCase tc) {
        return tc.draw(sampledFrom(FilingStatus.codes()), "filingStatus");
    }

    public static BigDecimal dollars(TestCase tc, String label) {
        return BigDecimal.valueOf(tc.draw(integers().min(0), label + "Dollars"))
                .add(BigDecimal.valueOf(tc.draw(integers().min(0).max(99), label + "Cents"), 2));
    }

    /** The graph's explicit Round nodes round nonnegative dollar amounts to the nearest whole dollar. */
    public static BigDecimal rounded(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP);
    }

    /** Dollar operations preserve cents with HALF_EVEN before an explicit whole-dollar Round. */
    public static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    public static Session session(PlanningGraphService graph, TestCase tc) {
        return session(graph, year(tc), filingStatus(tc));
    }

    public static Session session(PlanningGraphService graph, int year, String status) {
        return new Session(graph, graph.createSession(year, status));
    }

    public record Session(PlanningGraphService graph, SessionId sessionId) implements AutoCloseable {
        public String id() {
            return sessionId.value();
        }

        @Override
        public void close() {
            graph.discardSession(sessionId);
        }
    }
}

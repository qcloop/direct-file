package com.realworld.tax.advisor.graph;

import java.util.UUID;

/**
 * Identifies one in-memory planning session. A distinct type rather than a bare {@code String} so a
 * session id cannot be confused with the fact paths, type codes, and amounts that travel beside it —
 * most calls into {@link PlanningGraphService} take a session and a path, both of which were strings.
 *
 * <p>The MCP wire format still carries the id as a plain string: tool parameters and result records
 * stay {@code String} (the output-schema generator would publish a record as an object), and each tool
 * converts with {@link #of(String)} as its first step.
 */
public record SessionId(String value) {

    public SessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "A planning session id is required. Call create_session to start a session and pass the"
                            + " sessionId it returns.");
        }
    }

    /** The id a caller supplied, e.g. the {@code sessionId} argument of an MCP tool call. */
    public static SessionId of(String value) {
        return new SessionId(value);
    }

    /** A fresh, unguessable id for a new session. */
    static SessionId random() {
        return new SessionId(UUID.randomUUID().toString());
    }

    /** The bare id, so messages and log lines read the same as when ids were strings. */
    @Override
    public String toString() {
        return value;
    }
}

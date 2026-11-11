package com.realworld.tax.advisor.graph;

import java.util.Optional;

/**
 * Where planning sessions live between tool calls. The default keeps them in this JVM's memory
 * ({@link InMemorySessionStore}); a shared implementation ({@link RedisSessionStore}) lets any instance
 * of the service serve any session and lets sessions outlive a restart.
 *
 * <p>Updates are optimistic: read a {@link Stored} snapshot, derive the new one, and {@link #replace}
 * it only if nobody else changed the session in between. That gives the same per-session atomicity a
 * lock would, without requiring every writer to be in the same process.
 */
public interface SessionStore {

    /** Store a new session. */
    void create(SessionId id, SessionSnapshot snapshot);

    /** The session's current state, or empty if it was never created, was deleted, or has expired. */
    Optional<Stored> find(SessionId id);

    /**
     * Replace the session's state with {@code updated}, provided it is still at {@code expected}'s
     * version. Returns false — storing nothing — if the session changed or disappeared in the meantime,
     * in which case the caller re-reads and tries again.
     */
    boolean replace(SessionId id, Stored expected, SessionSnapshot updated);

    /** Remove a session; a no-op if it does not exist. */
    void delete(SessionId id);

    /** A snapshot together with the version it was read at, for a later {@link #replace}. */
    record Stored(SessionSnapshot snapshot, long version) {}
}

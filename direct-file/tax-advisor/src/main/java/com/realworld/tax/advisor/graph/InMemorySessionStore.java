package com.realworld.tax.advisor.graph;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps sessions in this JVM's memory and nowhere else: nothing is persisted, sessions are lost on
 * restart, and no other instance can see them. This is the default, and what keeps the service
 * outside the FTI-retention surface unless a shared store is explicitly switched on.
 *
 * <p>Sessions are not evicted here — they live until deleted or until the process stops.
 */
public class InMemorySessionStore implements SessionStore {

    private final Map<SessionId, Stored> sessions = new ConcurrentHashMap<>();

    @Override
    public void create(SessionId id, SessionSnapshot snapshot) {
        sessions.put(id, new Stored(snapshot, 0));
    }

    @Override
    public Optional<Stored> find(SessionId id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public boolean replace(SessionId id, Stored expected, SessionSnapshot updated) {
        AtomicBoolean swapped = new AtomicBoolean(false);
        // computeIfPresent runs atomically per key, so the version check and the swap cannot interleave
        // with another writer on the same session.
        sessions.computeIfPresent(id, (key, current) -> {
            if (current.version() != expected.version()) {
                return current;
            }
            swapped.set(true);
            return new Stored(updated, current.version() + 1);
        });
        return swapped.get();
    }

    @Override
    public void delete(SessionId id) {
        sessions.remove(id);
    }
}

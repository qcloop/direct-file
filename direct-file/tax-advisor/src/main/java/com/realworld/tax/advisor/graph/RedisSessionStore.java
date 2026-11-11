package com.realworld.tax.advisor.graph;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Keeps sessions in Redis (or any store speaking its protocol) so every instance of the service sees
 * the same sessions and a restart does not lose them.
 *
 * <p>Each session is one hash, {@code <prefix><session id>}, with two fields: {@code data} (the
 * {@link SessionSnapshot} as JSON) and {@code v} (a version bumped on every change). Create and
 * replace are single Lua scripts so the write, the version check, and the expiry are atomic.
 *
 * <p>Expiry is sliding: every read and write pushes the session's time-to-live back out to the
 * configured idle limit, so a session in use stays alive and an abandoned one disappears on its own.
 * That bounded lifetime is the retention control for the taxpayer figures now held outside the JVM;
 * encryption in transit and at rest is the store's configuration, not this class's.
 */
public class RedisSessionStore implements SessionStore {

    private static final String VERSION = "v";
    private static final String DATA = "data";

    /** KEYS[1]=session key; ARGV[1]=snapshot JSON, ARGV[2]=ttl millis. */
    private static final RedisScript<Long> CREATE = new DefaultRedisScript<>(
            "redis.call('HSET', KEYS[1], 'v', '0', 'data', ARGV[1]) "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
                    + "return 1",
            Long.class);

    /** KEYS[1]=session key; ARGV[1]=expected version, ARGV[2]=snapshot JSON, ARGV[3]=ttl millis. Returns 1 if swapped. */
    private static final RedisScript<Long> REPLACE = new DefaultRedisScript<>(
            "if redis.call('HGET', KEYS[1], 'v') ~= ARGV[1] then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'v', tostring(tonumber(ARGV[1]) + 1), 'data', ARGV[2]) "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[3]) "
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration idleTimeout;

    public RedisSessionStore(
            StringRedisTemplate redis, ObjectMapper objectMapper, String keyPrefix, Duration idleTimeout) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
        this.idleTimeout = idleTimeout;
    }

    @Override
    public void create(SessionId id, SessionSnapshot snapshot) {
        redis.execute(CREATE, List.of(key(id)), toJson(snapshot), ttlMillis());
    }

    @Override
    public Optional<Stored> find(SessionId id) {
        String key = key(id);
        List<Object> fields = redis.opsForHash().multiGet(key, List.of(VERSION, DATA));
        Object version = fields.get(0);
        Object data = fields.get(1);
        if (version == null || data == null) {
            return Optional.empty();
        }
        // Sliding expiry: reading a session counts as using it.
        redis.expire(key, idleTimeout);
        return Optional.of(new Stored(fromJson(data.toString()), Long.parseLong(version.toString())));
    }

    @Override
    public boolean replace(SessionId id, Stored expected, SessionSnapshot updated) {
        Long swapped = redis.execute(
                REPLACE, List.of(key(id)), Long.toString(expected.version()), toJson(updated), ttlMillis());
        return Long.valueOf(1).equals(swapped);
    }

    @Override
    public void delete(SessionId id) {
        redis.delete(key(id));
    }

    private String key(SessionId id) {
        return keyPrefix + id.value();
    }

    private String ttlMillis() {
        return Long.toString(idleTimeout.toMillis());
    }

    private String toJson(SessionSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Could not serialize a planning session", e);
        }
    }

    private SessionSnapshot fromJson(String json) {
        try {
            return objectMapper.readValue(json, SessionSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Could not read a stored planning session", e);
        }
    }
}

package com.aicontent.platform.lock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * SET NX PX based lock with owner token and compare-and-delete release.
 * Redis outages are tolerated: the caller proceeds without the lock (see {@link DistributedLock}).
 */
public class RedisDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private static final long POLL_MS = 200;

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Handle> acquire(String key, Duration ttl, Duration wait) {
        String redisKey = "lock:" + key;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            while (true) {
                Boolean ok = redis.opsForValue().setIfAbsent(redisKey, token, ttl);
                if (Boolean.TRUE.equals(ok)) {
                    return Optional.of(() -> release(redisKey, token));
                }
                if (System.nanoTime() >= deadline) {
                    return Optional.empty();
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Redis lock unavailable for '{}', continuing without it: {}", key, e.toString());
            return Optional.of(() -> {});
        }
    }

    private void release(String redisKey, String token) {
        try {
            redis.execute(RELEASE, List.of(redisKey), token);
        } catch (RuntimeException e) {
            log.warn("Redis lock release failed for '{}' (it will expire by TTL): {}", redisKey, e.toString());
        }
    }
}

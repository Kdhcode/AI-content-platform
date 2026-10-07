package com.aicontent.platform.lock;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-JVM lock. Correct for a single backend instance; use the Redis implementation when scaling out.
 * Deliberately NOT reentrant (a semaphore, not a ReentrantLock): the same thread asking twice must wait/time out
 * exactly like another instance would, otherwise tests on this implementation would hide real contention.
 */
public class InJvmDistributedLock implements DistributedLock {

    private final Map<String, Semaphore> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<Handle> acquire(String key, Duration ttl, Duration wait) {
        Semaphore sem = locks.computeIfAbsent(key, k -> new Semaphore(1));
        try {
            if (!sem.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        AtomicBoolean released = new AtomicBoolean();
        return Optional.of(() -> {
            if (released.compareAndSet(false, true)) {
                sem.release();
            }
        });
    }
}

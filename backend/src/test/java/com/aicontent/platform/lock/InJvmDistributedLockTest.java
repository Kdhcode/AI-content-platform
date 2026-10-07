package com.aicontent.platform.lock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class InJvmDistributedLockTest {

    @Test
    void secondAcquireTimesOutUntilReleased() {
        var lock = new InJvmDistributedLock();
        var h1 = lock.acquire("k", Duration.ofSeconds(5), Duration.ofMillis(10));
        assertTrue(h1.isPresent());
        assertFalse(lock.acquire("k", Duration.ofSeconds(5), Duration.ofMillis(50)).isPresent());
        h1.get().close();
        assertTrue(lock.acquire("k", Duration.ofSeconds(5), Duration.ofMillis(50)).isPresent());
    }

    @Test
    void differentKeysDoNotBlock() {
        var lock = new InJvmDistributedLock();
        assertTrue(lock.acquire("a", Duration.ofSeconds(5), Duration.ofMillis(10)).isPresent());
        assertTrue(lock.acquire("b", Duration.ofSeconds(5), Duration.ofMillis(10)).isPresent());
    }
}

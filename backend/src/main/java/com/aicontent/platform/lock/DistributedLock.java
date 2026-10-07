package com.aicontent.platform.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * Short-lived mutual exclusion helper. It is only an optimisation / safety net: correctness of the data
 * never depends on it (PostgreSQL constraints and ASYNC_JOB claiming do), which is why the Redis
 * implementation degrades to "no lock" when Redis is unreachable instead of failing the work.
 */
public interface DistributedLock {

    /**
     * @param ttl  automatic expiry so a crashed owner cannot block others forever
     * @param wait how long to wait for the lock before giving up
     * @return a handle to close when done, or empty if the lock could not be acquired in time
     */
    Optional<Handle> acquire(String key, Duration ttl, Duration wait);

    interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}

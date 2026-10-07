package com.aicontent.platform.job;

import com.aicontent.platform.common.Json;
import com.aicontent.platform.config.AppProperties;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls PostgreSQL for due jobs and executes them. No broker: ASYNC_JOB is the queue, SKIP LOCKED gives
 * safe multi-worker claiming, heartbeats + stale recovery give crash/restart recovery.
 *
 * <p>Shutdown: in-flight jobs get {@code shutdownWaitSeconds} to finish; anything still running keeps
 * RUNNING in the database and is re-queued by {@link #recover()} once its heartbeat is stale.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final int MAX_ERROR_MESSAGE = 2000;

    private final AsyncJobRepository repository;
    private final Map<String, JobHandler> handlers;
    private final AppProperties.Worker config;
    private final Json json;
    private final String workerId;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<Long, Boolean> running = new ConcurrentHashMap<>();

    public JobWorker(AsyncJobRepository repository, List<JobHandler> handlerList, AppProperties props, Json json) {
        this.repository = repository;
        this.handlers = handlerList.stream().collect(Collectors.toMap(h -> h.type().name(), Function.identity()));
        this.config = props.worker();
        this.json = json;
        this.workerId = hostName() + ":" + UUID.randomUUID().toString().substring(0, 8);
        this.executor = Executors.newFixedThreadPool(Math.max(1, config.concurrency()), r -> {
            Thread t = new Thread(r, "job-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public String workerId() {
        return workerId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (config.enabled()) {
            recover();
        }
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-interval-ms:2000}", initialDelayString = "${app.worker.poll-interval-ms:2000}")
    public void poll() {
        if (!config.enabled()) {
            return;
        }
        try {
            int free = config.concurrency() - inFlight.get();
            if (free <= 0) {
                return;
            }
            for (AsyncJob job : repository.claimNext(workerId, handlers.keySet(), free)) {
                inFlight.incrementAndGet();
                running.put(job.id(), Boolean.TRUE);
                executor.submit(() -> {
                    try {
                        execute(job);
                    } finally {
                        running.remove(job.id());
                        inFlight.decrementAndGet();
                    }
                });
            }
        } catch (RuntimeException e) {
            log.error("job poll failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.worker.heartbeat-interval-ms:5000}", initialDelayString = "${app.worker.heartbeat-interval-ms:5000}")
    public void heartbeat() {
        if (!config.enabled() || running.isEmpty()) {
            return;
        }
        try {
            repository.heartbeat(running.keySet(), workerId);
        } catch (RuntimeException e) {
            log.warn("job heartbeat failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.worker.recovery-interval-ms:30000}", initialDelayString = "${app.worker.recovery-interval-ms:30000}")
    public void recover() {
        if (!config.enabled()) {
            return;
        }
        try {
            for (var recovered : repository.recoverStale(config.staleAfterSeconds())) {
                log.warn("recovered stale job id={} -> {}", recovered.id(), recovered.status());
                if (recovered.status() == AsyncJobStatus.FAILED) {
                    repository.findById(recovered.id()).ifPresent(job -> notifyFinalFailure(job, "WORKER_LOST", job.errorMessage()));
                }
            }
        } catch (RuntimeException e) {
            log.error("stale job recovery failed", e);
        }
    }

    /**
     * Synchronous variant for tests and one-off tooling: claims up to {@code max} due jobs and runs them in the
     * calling thread. Independent of {@code app.worker.enabled}.
     *
     * @return number of jobs executed
     */
    public int runPending(int max) {
        List<AsyncJob> claimed = repository.claimNext(workerId, handlers.keySet(), max);
        claimed.forEach(this::execute);
        return claimed.size();
    }

    void execute(AsyncJob job) {
        MDC.put("correlationId", job.correlationId());
        MDC.put("jobId", Long.toString(job.id()));
        try {
            JobHandler handler = handlers.get(job.jobType());
            if (handler == null) {
                fail(job, null, "NO_HANDLER", "no handler registered for " + job.jobType(), false);
                return;
            }
            try {
                Map<String, Object> result = handler.handle(new JobContext(job, workerId));
                String resultJson = json.write(result == null ? Map.of() : result);
                if (!repository.markSuccess(job.id(), workerId, resultJson)) {
                    log.warn("job {} finished but is no longer owned by {} (recovered by another worker?)", job.id(), workerId);
                }
            } catch (JobExecutionException e) {
                fail(job, handler, e.errorCode(), e.getMessage(), e.retryable());
            } catch (Exception e) {
                log.error("job {} ({}) failed unexpectedly", job.id(), job.jobType(), e);
                fail(job, handler, "UNEXPECTED_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), true);
            }
        } finally {
            MDC.remove("correlationId");
            MDC.remove("jobId");
        }
    }

    private void fail(AsyncJob job, JobHandler handler, String code, String message, boolean retryable) {
        String msg = message == null ? "" : message.length() > MAX_ERROR_MESSAGE ? message.substring(0, MAX_ERROR_MESSAGE) : message;
        Optional<AsyncJobStatus> status = repository.markFailure(job.id(), workerId, code, msg, retryable,
                config.retryBaseDelaySeconds(), config.retryMaxDelaySeconds());
        if (status.isEmpty()) {
            log.warn("job {} failed ({}) but is no longer owned by {}", job.id(), code, workerId);
            return;
        }
        log.warn("job {} ({}) failed code={} retryable={} -> {}", job.id(), job.jobType(), code, retryable, status.get());
        if (status.get() == AsyncJobStatus.FAILED) {
            notifyFinalFailure(job, code, msg, handler);
        }
    }

    private void notifyFinalFailure(AsyncJob job, String code, String message) {
        notifyFinalFailure(job, code, message, handlers.get(job.jobType()));
    }

    private void notifyFinalFailure(AsyncJob job, String code, String message, JobHandler handler) {
        if (handler == null) {
            return;
        }
        try {
            handler.onFinalFailure(job, code, message);
        } catch (RuntimeException e) {
            log.error("onFinalFailure hook failed for job {}", job.id(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(config.shutdownWaitSeconds(), TimeUnit.SECONDS)) {
                log.warn("{} job(s) still running at shutdown; they will be re-queued after the heartbeat expires", inFlight.get());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }
}

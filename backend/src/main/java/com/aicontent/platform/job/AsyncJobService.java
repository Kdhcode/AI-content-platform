package com.aicontent.platform.job;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.common.Json;
import com.aicontent.platform.config.AppProperties;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Enqueue / inspect / retry / cancel. The worker itself lives in {@link JobWorker}. */
@Service
public class AsyncJobService {

    public static final String SYSTEM = "system";

    private final AsyncJobRepository repository;
    private final Json json;
    private final AppProperties props;

    public AsyncJobService(AsyncJobRepository repository, Json json, AppProperties props) {
        this.repository = repository;
        this.json = json;
        this.props = props;
    }

    public record EnqueueResult(AsyncJob job, boolean created) {}

    /**
     * @param dedupeKey optional; while a PENDING/RUNNING job with the same key exists, no second job is created
     *                  and the existing one is returned with {@code created = false}
     */
    public EnqueueResult enqueue(JobType type, Map<String, Object> payload, String requestedBy,
                                 String dedupeKey, String correlationId) {
        var newJob = new AsyncJobRepository.NewJob(
                type.name(),
                json.write(payload),
                requestedBy == null ? SYSTEM : requestedBy,
                props.worker().defaultMaxRetries(),
                correlationId == null ? UUID.randomUUID().toString() : correlationId,
                dedupeKey);
        // The existing job may finish between the conflict and the lookup; a few attempts close that window.
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<AsyncJob> inserted = repository.insertIfNoActiveDuplicate(newJob);
            if (inserted.isPresent()) {
                return new EnqueueResult(inserted.get(), true);
            }
            Optional<AsyncJob> existing = repository.findActiveByDedupeKey(dedupeKey);
            if (existing.isPresent()) {
                return new EnqueueResult(existing.get(), false);
            }
        }
        throw new IllegalStateException("could not enqueue job " + type + " with dedupe key " + dedupeKey);
    }

    public AsyncJob get(long id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.JOB_NOT_FOUND));
    }

    public AsyncJob retry(long id) {
        AsyncJob job = get(id);
        if (job.status() != AsyncJobStatus.FAILED) {
            throw new BusinessException(ErrorCode.JOB_NOT_RETRYABLE);
        }
        boolean requeued;
        try {
            requeued = repository.requeueFailed(id, props.worker().defaultMaxRetries());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // another active job with the same dedupe key already exists (e.g. the article was re-queued meanwhile)
            throw new BusinessException(ErrorCode.JOB_NOT_RETRYABLE, "같은 작업이 이미 대기 또는 실행 중입니다.");
        }
        if (!requeued) {
            throw new BusinessException(ErrorCode.JOB_NOT_RETRYABLE);
        }
        return get(id);
    }

    public AsyncJob cancel(long id) {
        AsyncJob job = get(id);
        if (job.status() != AsyncJobStatus.PENDING || !repository.cancelPending(id)) {
            throw new BusinessException(ErrorCode.JOB_NOT_CANCELLABLE);
        }
        return get(id);
    }
}

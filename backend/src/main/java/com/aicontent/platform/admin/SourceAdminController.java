package com.aicontent.platform.admin;

import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.job.AsyncJobService;
import com.aicontent.platform.job.JobType;
import com.aicontent.platform.news.NewsSourceRepository;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/sources")
public class SourceAdminController {

    /** Source config may hold credentials, so it is never returned. */
    public record SourceView(long id, String name, String type, String baseUrl, boolean enabled, String status,
                             int collectionIntervalSeconds, OffsetDateTime lastAttemptAt, OffsetDateTime lastSuccessAt,
                             int failureCount, String lastError) {}

    private final NewsSourceRepository sources;
    private final AsyncJobService jobs;

    public SourceAdminController(NewsSourceRepository sources, AsyncJobService jobs) {
        this.sources = sources;
        this.jobs = jobs;
    }

    @GetMapping
    public ApiResponse<List<SourceView>> list() {
        return ApiResponse.ok(sources.findAll().stream().map(s -> new SourceView(s.id(), s.name(), s.type().name(),
                s.baseUrl(), s.enabled(), s.status(), s.collectionIntervalSeconds(), s.lastAttemptAt(), s.lastSuccessAt(),
                s.failureCount(), s.lastError())).toList());
    }

    /** Async: enqueues a COLLECT_NEWS job right now (202). Returns the existing job if one is already queued/running. */
    @PostMapping("/{id}/collect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<JobView> collect(@PathVariable long id, Principal principal) {
        sources.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.SOURCE_NOT_FOUND));
        var r = jobs.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", id), principal.getName(), "collect:" + id,
                CorrelationIdFilter.current());
        return ApiResponse.ok(JobView.from(r.job()));
    }
}

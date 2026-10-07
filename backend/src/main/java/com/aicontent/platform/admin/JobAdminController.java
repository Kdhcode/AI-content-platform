package com.aicontent.platform.admin;

import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.common.PageParams;
import com.aicontent.platform.common.PageResponse;
import com.aicontent.platform.job.AsyncJobService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/jobs")
public class JobAdminController {

    private final JobQueryRepository query;
    private final AsyncJobService jobs;

    public JobAdminController(JobQueryRepository query, AsyncJobService jobs) {
        this.query = query;
        this.jobs = jobs;
    }

    @GetMapping
    public ApiResponse<PageResponse<JobView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long articleId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        PageParams params = PageParams.of(page, size, sort, JobQueryRepository.SORTS, "j.id DESC");
        return ApiResponse.ok(query.list(status, type, articleId, params));
    }

    @GetMapping("/{id}")
    public ApiResponse<JobView> get(@PathVariable long id) {
        return ApiResponse.ok(JobView.from(jobs.get(id)));
    }

    /** FAILED -> PENDING with extra retries; 409 JOB_NOT_RETRYABLE otherwise. */
    @PostMapping("/{id}/retry")
    public ApiResponse<JobView> retry(@PathVariable long id) {
        return ApiResponse.ok(JobView.from(jobs.retry(id)));
    }

    /** Only PENDING jobs can be cancelled; a RUNNING job is left to finish. */
    @PostMapping("/{id}/cancel")
    public ApiResponse<JobView> cancel(@PathVariable long id) {
        return ApiResponse.ok(JobView.from(jobs.cancel(id)));
    }
}

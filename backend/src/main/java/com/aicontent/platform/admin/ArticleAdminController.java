package com.aicontent.platform.admin;

import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.common.PageParams;
import com.aicontent.platform.common.PageResponse;
import com.aicontent.platform.issue.IssueManagementService;
import com.aicontent.platform.job.AsyncJobService;
import com.aicontent.platform.job.JobType;
import com.aicontent.platform.news.NewsArticleRepository;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/articles")
public class ArticleAdminController {

    private final ArticleQueryRepository query;
    private final NewsArticleRepository articles;
    private final AsyncJobService jobs;
    private final IssueManagementService management;

    public ArticleAdminController(ArticleQueryRepository query, NewsArticleRepository articles, AsyncJobService jobs,
                                  IssueManagementService management) {
        this.query = query;
        this.articles = articles;
        this.jobs = jobs;
        this.management = management;
    }

    @GetMapping
    public ApiResponse<PageResponse<ArticleQueryRepository.ListItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String classificationStatus,
            @RequestParam(required = false) Long sourceId,
            @RequestParam(required = false) Long issueId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        PageParams params = PageParams.of(page, size, sort, ArticleQueryRepository.SORTS, "a.collected_at DESC, a.id DESC");
        return ApiResponse.ok(query.list(new ArticleQueryRepository.Filter(status, classificationStatus, sourceId, issueId, q,
                publisher), params));
    }

    @GetMapping("/{id}")
    public ApiResponse<ArticleQueryRepository.Detail> get(@PathVariable long id) {
        return ApiResponse.ok(query.detail(id).orElseThrow(() -> new BusinessException(ErrorCode.ARTICLE_NOT_FOUND)));
    }

    /** Async: re-runs analysis -> embedding -> classification. Returns the job (202); poll GET /api/admin/jobs/{id}. */
    @PostMapping("/{id}/reanalyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<JobView> reanalyze(@PathVariable long id, Principal principal) {
        var row = articles.findRow(id).orElseThrow(() -> new BusinessException(ErrorCode.ARTICLE_NOT_FOUND));
        if ("DUPLICATE".equals(row.status())) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_ANALYZABLE, "중복 기사는 재분석할 수 없습니다.");
        }
        var result = jobs.enqueue(JobType.ANALYZE_ARTICLE, Map.of("articleId", id, "reanalyze", true), principal.getName(),
                "analyze:" + id, CorrelationIdFilter.current());
        return ApiResponse.ok(JobView.from(result.job()));
    }

    @PostMapping("/{id}/move")
    public ApiResponse<IssueManagementService.MoveResult> move(@PathVariable long id,
                                                               @Valid @RequestBody AdminRequests.MoveRequest body,
                                                               Principal principal) {
        return ApiResponse.ok(management.move(id, body.targetIssueId(), principal.getName(), body.reason(),
                CorrelationIdFilter.current()));
    }
}

package com.aicontent.platform.admin;

import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.common.PageParams;
import com.aicontent.platform.common.PageResponse;
import com.aicontent.platform.issue.IssueManagementService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/issues")
public class IssueAdminController {

    private final IssueQueryRepository query;
    private final IssueManagementService management;

    public IssueAdminController(IssueQueryRepository query, IssueManagementService management) {
        this.query = query;
        this.management = management;
    }

    @GetMapping
    public ApiResponse<PageResponse<IssueQueryRepository.ListItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        PageParams params = PageParams.of(page, size, sort, IssueQueryRepository.SORTS, "i.last_updated_at DESC NULLS LAST, i.id DESC");
        return ApiResponse.ok(query.list(new IssueQueryRepository.Filter(status, category, q), params));
    }

    @GetMapping("/{id}")
    public ApiResponse<IssueQueryRepository.Detail> get(@PathVariable long id) {
        return ApiResponse.ok(query.detail(id).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<IssueQueryRepository.Detail> patch(@PathVariable long id,
                                                          @Valid @RequestBody AdminRequests.IssuePatchRequest body,
                                                          Principal principal) {
        management.update(id, new IssueManagementService.IssuePatch(body.title(), body.summary(), body.category(), body.status()),
                principal.getName(), body.reason(), CorrelationIdFilter.current());
        return get(id);
    }

    /** Path id is the surviving (target) issue; the body lists the issues merged into it. */
    @PostMapping("/{id}/merge")
    public ApiResponse<IssueManagementService.MergeResult> merge(@PathVariable long id,
                                                                 @Valid @RequestBody AdminRequests.MergeRequest body,
                                                                 Principal principal) {
        if (body.targetIssueId() != null && body.targetIssueId() != id) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "본문의 targetIssueId가 경로의 이슈 id와 다릅니다.");
        }
        return ApiResponse.ok(management.merge(id, body.sourceIssueIds(), principal.getName(), body.reason(),
                CorrelationIdFilter.current()));
    }

    @PostMapping("/{id}/split")
    public ApiResponse<IssueManagementService.SplitResult> split(@PathVariable long id,
                                                                 @Valid @RequestBody AdminRequests.SplitRequest body,
                                                                 Principal principal) {
        return ApiResponse.ok(management.split(id, body.articleIds(), body.newTitle(), principal.getName(), body.reason(),
                CorrelationIdFilter.current()));
    }
}

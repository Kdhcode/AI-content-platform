package com.aicontent.platform.admin;

import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.common.PageParams;
import com.aicontent.platform.common.PageResponse;
import com.aicontent.platform.content.ContentRepository;
import com.aicontent.platform.content.ContentService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual NEWS / LIFE content (bundle A2). Admin only (OPERATOR or SYSTEM_ADMIN, see SecurityConfig); nothing here is
 * reachable from /api/public, and an APPROVED content is not published anywhere.
 */
@RestController
public class ContentAdminController {

    private final ContentService service;
    private final ContentRepository query;

    public ContentAdminController(ContentService service, ContentRepository query) {
        this.service = service;
        this.query = query;
    }

    /** 201 with the new DRAFT; 200 with the same content when the Idempotency-Key was already used for this request. */
    @PostMapping("/api/admin/issues/{issueId}/contents")
    public ResponseEntity<ApiResponse<ContentRepository.Detail>> create(
            @PathVariable long issueId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AdminRequests.ContentCreateRequest body,
            Principal principal) {
        var result = service.create(issueId, body.contentType(), body.title(), body.body(), idempotencyKey,
                principal.getName(), CorrelationIdFilter.current());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .header("Idempotent-Replayed", String.valueOf(!result.created()))
                .body(ApiResponse.ok(result.content()));
    }

    @GetMapping("/api/admin/contents")
    public ApiResponse<PageResponse<ContentRepository.ListItem>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long issueId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        allowed("type", type, ContentService.TYPES);
        allowed("status", status, ContentService.STATUSES);
        if (q != null && q.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "q는 200자 이하여야 합니다.");
        }
        PageParams params = PageParams.of(page, size, sort, ContentRepository.SORTS, "c.updated_at DESC, c.id DESC");
        return ApiResponse.ok(query.list(new ContentRepository.Filter(type, status, issueId, q), params));
    }

    @GetMapping("/api/admin/contents/{id}")
    public ApiResponse<ContentRepository.Detail> get(@PathVariable long id) {
        return ApiResponse.ok(query.detail(id).orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND)));
    }

    /** {@code statusChanged=true} when the save sent a REVIEW / APPROVED content back to DRAFT. */
    @PatchMapping("/api/admin/contents/{id}")
    public ApiResponse<ContentService.PatchResult> patch(@PathVariable long id,
                                                        @Valid @RequestBody AdminRequests.ContentPatchRequest body,
                                                        Principal principal) {
        return ApiResponse.ok(service.patch(id, body.version(), body.title(), body.body(), principal.getName(),
                CorrelationIdFilter.current()));
    }

    private static void allowed(String name, String value, Set<String> values) {
        if (value != null && !values.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, name + " 값이 올바르지 않습니다: " + value);
        }
    }
}

package com.aicontent.platform.admin;

import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.common.PageParams;
import com.aicontent.platform.common.PageResponse;
import com.aicontent.platform.content.ContentChanges;
import com.aicontent.platform.content.ContentRelationService;
import com.aicontent.platform.content.ContentRepository;
import com.aicontent.platform.content.ContentService;
import com.aicontent.platform.content.ContentStatusPolicy;
import com.aicontent.platform.content.ContentStatusPolicy.Action;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual NEWS / LIFE content: create / read / edit (A2) and status actions (A3). Admin only (OPERATOR or SYSTEM_ADMIN;
 * approve and restore SYSTEM_ADMIN only, see SecurityConfig and ContentStatusPolicy). Nothing here is reachable from
 * /api/public, and an APPROVED content is not published anywhere.
 */
@RestController
public class ContentAdminController {

    private final ContentService service;
    private final ContentRelationService relations;
    private final ContentRepository query;

    public ContentAdminController(ContentService service, ContentRelationService relations, ContentRepository query) {
        this.service = service;
        this.relations = relations;
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

    /** {@code changed=false}: same values, nothing written. {@code statusChanged=true}: REVIEW / APPROVED went back to DRAFT. */
    @PatchMapping("/api/admin/contents/{id}")
    public ApiResponse<ContentChanges.Result> patch(@PathVariable long id,
                                                        @Valid @RequestBody AdminRequests.ContentPatchRequest body,
                                                        Principal principal) {
        return ApiResponse.ok(service.patch(id, body.version(), body.title(), body.body(), principal.getName(),
                CorrelationIdFilter.current()));
    }

    // --- status actions (docs/CONTENT_A_PLAN.md §4): body {version, reason?}; the response is the updated detail ---

    @PostMapping("/api/admin/contents/{id}/submit")
    public ApiResponse<ContentRepository.Detail> submit(@PathVariable long id, @Valid @RequestBody AdminRequests.ContentActionRequest body, Authentication auth) {
        return act(id, Action.SUBMIT, body, auth);
    }

    @PostMapping("/api/admin/contents/{id}/approve")
    public ApiResponse<ContentRepository.Detail> approve(@PathVariable long id, @Valid @RequestBody AdminRequests.ContentActionRequest body, Authentication auth) {
        return act(id, Action.APPROVE, body, auth);
    }

    @PostMapping("/api/admin/contents/{id}/return")
    public ApiResponse<ContentRepository.Detail> returnToDraft(@PathVariable long id, @Valid @RequestBody AdminRequests.ContentActionRequest body, Authentication auth) {
        return act(id, Action.RETURN, body, auth);
    }

    @PostMapping("/api/admin/contents/{id}/hold")
    public ApiResponse<ContentRepository.Detail> hold(@PathVariable long id, @Valid @RequestBody AdminRequests.ContentActionRequest body, Authentication auth) {
        return act(id, Action.HOLD, body, auth);
    }

    @PostMapping("/api/admin/contents/{id}/resume")
    public ApiResponse<ContentRepository.Detail> resume(@PathVariable long id, @Valid @RequestBody AdminRequests.ContentActionRequest body, Authentication auth) {
        return act(id, Action.RESUME, body, auth);
    }

    @PostMapping("/api/admin/contents/{id}/exclude")
    public ApiResponse<ContentRepository.Detail> exclude(@PathVariable long id, @Valid @RequestBody AdminRequests.ContentActionRequest body, Authentication auth) {
        return act(id, Action.EXCLUDE, body, auth);
    }

    @PostMapping("/api/admin/contents/{id}/archive")
    public ApiResponse<ContentRepository.Detail> archive(@PathVariable long id, @Valid @RequestBody AdminRequests.ContentActionRequest body, Authentication auth) {
        return act(id, Action.ARCHIVE, body, auth);
    }

    @PostMapping("/api/admin/contents/{id}/restore")
    public ApiResponse<ContentRepository.Detail> restore(@PathVariable long id, @Valid @RequestBody AdminRequests.ContentActionRequest body, Authentication auth) {
        return act(id, Action.RESTORE, body, auth);
    }

    // --- sources (A4) and related URLs (A5): every change sends the content version; the response carries the content
    //     (new version, status), whether anything changed, whether the status went back to DRAFT, and the item id ---

    @GetMapping("/api/admin/contents/{id}/sources")
    public ApiResponse<List<ContentRepository.Source>> sources(@PathVariable long id) {
        return ApiResponse.ok(detail(id).sources());
    }

    @PostMapping("/api/admin/contents/{id}/sources")
    public ResponseEntity<ApiResponse<ContentChanges.Result>> addSource(@PathVariable long id,
            @Valid @RequestBody AdminRequests.ContentSourceAddRequest b, Principal principal) {
        var result = relations.addSource(id, b.version(), new ContentRelationService.SourceInput(b.kind(), b.issueId(),
                b.newsArticleId(), b.externalUrl(), b.label(), b.role()), principal.getName(), CorrelationIdFilter.current());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
    }

    @PatchMapping("/api/admin/contents/{id}/sources/{sourceId}")
    public ApiResponse<ContentChanges.Result> updateSource(@PathVariable long id, @PathVariable long sourceId,
            @Valid @RequestBody AdminRequests.ContentSourcePatchRequest b, Principal principal) {
        return ApiResponse.ok(relations.updateSource(id, sourceId, b.version(), b.role(), b.label(), b.label() != null,
                principal.getName(), CorrelationIdFilter.current()));
    }

    @DeleteMapping("/api/admin/contents/{id}/sources/{sourceId}")
    public ApiResponse<ContentChanges.Result> removeSource(@PathVariable long id, @PathVariable long sourceId,
            @RequestParam long version, Principal principal) {
        return ApiResponse.ok(relations.removeSource(id, sourceId, version, principal.getName(), CorrelationIdFilter.current()));
    }

    @GetMapping("/api/admin/contents/{id}/links")
    public ApiResponse<List<ContentRepository.Link>> links(@PathVariable long id) {
        return ApiResponse.ok(detail(id).links());
    }

    @PostMapping("/api/admin/contents/{id}/links")
    public ResponseEntity<ApiResponse<ContentChanges.Result>> addLink(@PathVariable long id,
            @Valid @RequestBody AdminRequests.ContentLinkAddRequest b, Principal principal) {
        var result = relations.addLink(id, b.version(), new ContentRelationService.LinkInput(b.linkType(), b.title(), b.url(),
                b.siteVisible(), b.blogVisible(), b.displayPosition()), principal.getName(), CorrelationIdFilter.current());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
    }

    @PatchMapping("/api/admin/contents/{id}/links/{linkId}")
    public ApiResponse<ContentChanges.Result> updateLink(@PathVariable long id, @PathVariable long linkId,
            @Valid @RequestBody AdminRequests.ContentLinkPatchRequest b, Principal principal) {
        return ApiResponse.ok(relations.updateLink(id, linkId, b.version(), new ContentRelationService.LinkInput(b.linkType(),
                b.title(), b.url(), b.siteVisible(), b.blogVisible(), b.displayPosition()), principal.getName(),
                CorrelationIdFilter.current()));
    }

    @DeleteMapping("/api/admin/contents/{id}/links/{linkId}")
    public ApiResponse<ContentChanges.Result> removeLink(@PathVariable long id, @PathVariable long linkId,
            @RequestParam long version, Principal principal) {
        return ApiResponse.ok(relations.removeLink(id, linkId, version, principal.getName(), CorrelationIdFilter.current()));
    }

    @PutMapping("/api/admin/contents/{id}/links/order")
    public ApiResponse<ContentChanges.Result> reorderLinks(@PathVariable long id,
            @Valid @RequestBody AdminRequests.ContentLinkOrderRequest b, Principal principal) {
        return ApiResponse.ok(relations.reorderLinks(id, b.version(), b.linkIds(), principal.getName(), CorrelationIdFilter.current()));
    }

    private ContentRepository.Detail detail(long id) {
        return query.detail(id).orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND));
    }

    private ApiResponse<ContentRepository.Detail> act(long id, Action action, AdminRequests.ContentActionRequest body, Authentication auth) {
        boolean systemAdmin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_SYSTEM_ADMIN".equals(a.getAuthority()));
        return ApiResponse.ok(service.transition(id, action, body.version(), body.reason(), auth.getName(), systemAdmin,
                CorrelationIdFilter.current()));
    }

    private static void allowed(String name, String value, Set<String> values) {
        if (value != null && !values.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, name + " 값이 올바르지 않습니다: " + value);
        }
    }
}

package com.aicontent.platform.content;

import com.aicontent.platform.audit.AuditService;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one path for every change of a content's substance: title / body (A2), sources (A4), related URLs (A5).
 * Row lock -> locked-status check -> version check -> the change itself -> only if something really changed:
 * version +1, REVIEW / APPROVED back to DRAFT with the review record cleared, audit records. All in the caller's
 * transaction, so a failure anywhere leaves content, sources, links and audit untouched.
 */
@Component
public class ContentChanges {

    /** Editable after restoring only (A3: resume / restore). */
    static final Set<String> LOCKED_STATUSES = Set.of("HOLD", "EXCLUDED", "ARCHIVED");
    /** A real change invalidates the review request / approval. */
    static final Set<String> BACK_TO_DRAFT_ON_CHANGE = Set.of("REVIEW", "APPROVED");

    /**
     * What a change did. {@code before} / {@code after} go to the audit record; {@code itemId} is the source or link
     * concerned (for the response), may be null.
     */
    record Outcome(boolean changed, Map<String, Object> before, Map<String, Object> after, Long itemId) {
        static Outcome unchanged(Long itemId) {
            return new Outcome(false, Map.of(), Map.of(), itemId);
        }
    }

    /** {@code changed=false}: nothing was written (same values), version and status are as before. */
    public record Result(ContentRepository.Detail content, boolean changed, boolean statusChanged, Long itemId) {}

    @FunctionalInterface
    interface Change {
        Outcome apply(ContentRepository.Locked current);
    }

    private final ContentRepository contents;
    private final AuditService audit;

    ContentChanges(ContentRepository contents, AuditService audit) {
        this.contents = contents;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Result apply(long contentId, long version, String actor, String correlationId, String auditAction, Change change) {
        var current = contents.lock(contentId).orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND));
        if (LOCKED_STATUSES.contains(current.status())) {
            throw new BusinessException(ErrorCode.CONTENT_STATE_INVALID,
                    current.status() + " 상태의 콘텐츠는 수정할 수 없습니다. 먼저 보류 해제 또는 복귀하세요.",
                    Map.of("status", current.status()));
        }
        if (current.version() != version) {
            throw conflict(current, version);
        }
        Outcome outcome = change.apply(current);
        if (!outcome.changed()) {
            return new Result(contents.detail(contentId).orElseThrow(), false, false, outcome.itemId());
        }
        boolean backToDraft = BACK_TO_DRAFT_ON_CHANGE.contains(current.status());
        if (contents.bump(contentId, version, backToDraft, actor) != 1) {
            throw new IllegalStateException("content " + contentId + " changed while locked");   // cannot happen under FOR UPDATE
        }
        Map<String, Object> before = new LinkedHashMap<>(outcome.before());
        before.put("status", current.status());
        before.put("version", current.version());
        Map<String, Object> after = new LinkedHashMap<>(outcome.after());
        after.put("status", backToDraft ? "DRAFT" : current.status());
        after.put("version", current.version() + 1);
        audit.record(actor, auditAction, "CONTENT", contentId, before, after, null, correlationId);
        if ("APPROVED".equals(current.status())) {
            audit.record(actor, "CONTENT_APPROVAL_REVOKED_BY_EDIT", "CONTENT", contentId, Map.of("status", "APPROVED"),
                    Map.of("status", "DRAFT", "cause", auditAction), "승인된 콘텐츠를 수정해 승인이 해제됨", correlationId);
        }
        return new Result(contents.detail(contentId).orElseThrow(), true, backToDraft, outcome.itemId());
    }

    /** 409 with what an editor needs to reload. */
    static BusinessException conflict(ContentRepository.Locked current, long sentVersion) {
        Map<String, Object> details = new HashMap<>();
        details.put("currentVersion", current.version());
        details.put("updatedBy", current.updatedBy());
        details.put("updatedAt", current.updatedAt());
        details.put("sentVersion", sentVersion);
        details.put("status", current.status());
        return new BusinessException(ErrorCode.CONTENT_VERSION_CONFLICT,
                "다른 곳에서 먼저 저장했습니다(현재 version " + current.version() + "). 최신 내용을 다시 불러오세요.", details);
    }
}

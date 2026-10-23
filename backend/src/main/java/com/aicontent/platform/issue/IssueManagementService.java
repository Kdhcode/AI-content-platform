package com.aicontent.platform.issue;

import com.aicontent.platform.audit.AuditService;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.news.NewsArticleRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin corrections of the issue structure. Every operation is one transaction: membership change, aggregate refresh
 * of every touched issue, article classification status and the audit row succeed or fail together.
 * Issues are locked in ascending id order so concurrent admin actions cannot deadlock.
 */
@Service
public class IssueManagementService {

    private static final Set<String> OPEN = Set.of("ACTIVE", "REVIEW");
    private static final Set<String> SETTABLE_STATUS = Set.of("ACTIVE", "REVIEW", "CLOSED", "EXCLUDED");

    private final IssueRepository issues;
    private final IssueAggregateService aggregates;
    private final NewsArticleRepository articles;
    private final AuditService audit;

    public IssueManagementService(IssueRepository issues, IssueAggregateService aggregates, NewsArticleRepository articles,
                                  AuditService audit) {
        this.issues = issues;
        this.aggregates = aggregates;
        this.articles = articles;
        this.audit = audit;
    }

    public record MergeResult(long targetIssueId, List<Long> mergedIssueIds, List<Long> movedArticleIds) {}

    public record SplitResult(long sourceIssueId, long newIssueId, List<Long> movedArticleIds) {}

    public record MoveResult(long articleId, long fromIssueId, long toIssueId) {}

    @Transactional
    public MergeResult merge(long targetId, List<Long> sourceIds, String actor, String reason, String correlationId) {
        Set<Long> sources = new LinkedHashSet<>(sourceIds == null ? List.of() : sourceIds);
        if (sources.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "병합할 sourceIssueIds가 비어 있습니다.");
        }
        if (sources.contains(targetId)) {
            throw new BusinessException(ErrorCode.ISSUE_MERGE_SELF);
        }
        TreeSet<Long> lockOrder = new TreeSet<>(sources);
        lockOrder.add(targetId);
        for (long id : lockOrder) {
            issues.lockStatus(id).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND, "이슈 #" + id + "를 찾을 수 없습니다."));
        }
        var target = issues.findHead(targetId).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
        if (!OPEN.contains(target.status())) {
            throw new BusinessException(ErrorCode.ISSUE_STATE_INVALID, "병합 대상 이슈의 상태가 " + target.status() + "입니다.");
        }
        List<Long> moved = new ArrayList<>();
        Map<Long, Integer> before = new java.util.LinkedHashMap<>();
        for (long sid : sources) {
            var head = issues.findHead(sid).orElseThrow();
            if (!OPEN.contains(head.status())) {
                throw new BusinessException(ErrorCode.ISSUE_MERGE_SOURCE_INVALID,
                        "이슈 #" + sid + "의 상태가 " + head.status() + "라 병합할 수 없습니다.");
            }
            before.put(sid, head.articleCount());
            moved.addAll(issues.moveAllMembers(sid, targetId, "MERGE"));
            issues.markMerged(sid, targetId);
        }
        for (long sid : sources) {
            aggregates.refresh(sid);
        }
        aggregates.refresh(targetId);
        if ("ACTIVE".equals(target.status())) {
            articles.setClassificationStatusBatch(moved, "CLASSIFIED");
        }
        audit.record(actor, "ISSUE_MERGE", "ISSUE", targetId, Map.of("sourceArticleCounts", before),
                Map.of("targetIssueId", targetId, "movedArticleIds", moved), reason, correlationId);
        return new MergeResult(targetId, List.copyOf(sources), moved);
    }

    @Transactional
    public SplitResult split(long sourceId, List<Long> articleIds, String newTitle, String actor, String reason,
                             String correlationId) {
        Set<Long> toMove = new LinkedHashSet<>(articleIds == null ? List.of() : articleIds);
        if (toMove.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "분리할 articleIds가 비어 있습니다.");
        }
        issues.lockStatus(sourceId).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
        var source = issues.findHead(sourceId).orElseThrow();
        if (!OPEN.contains(source.status())) {
            throw new BusinessException(ErrorCode.ISSUE_STATE_INVALID, "이슈 상태가 " + source.status() + "라 분리할 수 없습니다.");
        }
        List<Long> members = issues.memberArticleIds(sourceId);
        for (long a : toMove) {
            if (!members.contains(a)) {
                throw new BusinessException(ErrorCode.ARTICLE_NOT_IN_ISSUE, "기사 #" + a + "는 이슈 #" + sourceId + "의 기사가 아닙니다.");
            }
        }
        if (members.size() - toMove.size() < 1) {
            throw new BusinessException(ErrorCode.ISSUE_SPLIT_WOULD_EMPTY);
        }
        long firstMoved = toMove.iterator().next();
        var row = articles.findRow(firstMoved).orElseThrow(() -> new BusinessException(ErrorCode.ARTICLE_NOT_FOUND));
        String title = newTitle == null || newTitle.isBlank() ? row.title() : newTitle.trim();
        long newId = issues.create(title, null, row.category(), "ACTIVE");
        if (newTitle != null && !newTitle.isBlank()) {
            issues.markTitleManual(newId);   // the admin chose it: keep it when members change later
        }
        for (long a : toMove) {
            issues.repointArticle(a, sourceId, newId, "SPLIT");
        }
        aggregates.refresh(newId);
        aggregates.refresh(sourceId);
        articles.setClassificationStatusBatch(toMove, "CLASSIFIED");
        audit.record(actor, "ISSUE_SPLIT", "ISSUE", sourceId, Map.of("articleCount", source.articleCount()),
                Map.of("newIssueId", newId, "movedArticleIds", List.copyOf(toMove)), reason, correlationId);
        return new SplitResult(sourceId, newId, List.copyOf(toMove));
    }

    @Transactional
    public MoveResult move(long articleId, long targetId, String actor, String reason, String correlationId) {
        articles.findRow(articleId).orElseThrow(() -> new BusinessException(ErrorCode.ARTICLE_NOT_FOUND));
        long fromId = issues.findPrimaryIssueId(articleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ARTICLE_NOT_IN_ISSUE, "기사가 어떤 이슈에도 연결되어 있지 않습니다."));
        if (fromId == targetId) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "이미 해당 이슈에 연결된 기사입니다.");
        }
        TreeSet<Long> lockOrder = new TreeSet<>(List.of(fromId, targetId));
        for (long id : lockOrder) {
            issues.lockStatus(id).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND, "이슈 #" + id + "를 찾을 수 없습니다."));
        }
        var target = issues.findHead(targetId).orElseThrow();
        if (!OPEN.contains(target.status())) {
            throw new BusinessException(ErrorCode.ISSUE_STATE_INVALID, "대상 이슈의 상태가 " + target.status() + "입니다.");
        }
        // re-check under lock: the article may have been moved by someone else meanwhile
        if (issues.repointArticle(articleId, fromId, targetId, "MANUAL") != 1) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_IN_ISSUE, "기사의 연결 상태가 변경되었습니다. 다시 시도하세요.");
        }
        aggregates.refresh(fromId);
        aggregates.refresh(targetId);
        if ("ACTIVE".equals(target.status())) {
            articles.setClassificationStatus(articleId, "CLASSIFIED");
        }
        audit.record(actor, "ARTICLE_MOVE", "ARTICLE", articleId, Map.of("issueId", fromId), Map.of("issueId", targetId),
                reason, correlationId);
        return new MoveResult(articleId, fromId, targetId);
    }

    public record IssuePatch(String title, String summary, String category, String status) {}

    /**
     * Applies the given fields (an omitted field keeps its value). A request with no field at all is refused; fields
     * equal to the current value are dropped, and when nothing is left the issue, its updated_at and the audit log stay
     * as they are. The audit record lists only the fields that really changed.
     */
    @Transactional
    public void update(long issueId, IssuePatch patch, String actor, String reason, String correlationId) {
        if (patch.title() == null && patch.summary() == null && patch.category() == null && patch.status() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "title, summary, category, status 중 하나는 보내야 합니다.");
        }
        issues.lockStatus(issueId).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
        var head = issues.findHead(issueId).orElseThrow();
        if ("MERGED".equals(head.status())) {
            throw new BusinessException(ErrorCode.ISSUE_STATE_INVALID, "병합된 이슈는 수정할 수 없습니다(병합 대상 #" + head.mergedIntoIssueId() + ").");
        }
        if (patch.title() != null && patch.title().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "title은 비어 있을 수 없습니다.");
        }
        if (patch.status() != null && !SETTABLE_STATUS.contains(patch.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "status는 ACTIVE, REVIEW, CLOSED, EXCLUDED 중 하나여야 합니다.");
        }
        String title = patch.title() == null || patch.title().trim().equals(head.title()) ? null : patch.title().trim();
        String summary = Objects.equals(patch.summary(), head.summary()) ? null : patch.summary();
        String category = Objects.equals(patch.category(), head.category()) ? null : patch.category();
        String status = Objects.equals(patch.status(), head.status()) ? null : patch.status();
        if (title == null && summary == null && category == null && status == null) {
            return;                                   // same values: nothing to save, nothing to audit
        }
        if (status != null) {
            if (("ACTIVE".equals(status) || "REVIEW".equals(status)) && head.articleCount() == 0) {
                throw new BusinessException(ErrorCode.ISSUE_STATE_INVALID, "기사가 없는 이슈는 " + status + " 상태가 될 수 없습니다.");
            }
            // CLOSED issues are public: an issue still waiting for review must be confirmed (ACTIVE) or excluded first,
            // and no issue may be closed while it still holds articles waiting for review (e.g. REVIEW -> EXCLUDED -> CLOSED).
            if ("CLOSED".equals(status)
                    && ("REVIEW".equals(head.status()) || issues.countReviewMembers(issueId) > 0)) {
                throw new BusinessException(ErrorCode.ISSUE_STATE_INVALID,
                        "검수 대기 기사가 있는 이슈는 종료할 수 없습니다. 같은 사건으로 확정(다시 활성화)하거나 제외 상태로 두세요.");
            }
        }
        issues.updateFields(issueId, title, summary, category, status);
        // An admin making an issue ACTIVE (confirming a REVIEW issue, or re-activating an excluded / closed one that
        // may hold articles still flagged REVIEW) is the decision for all its articles.
        if ("ACTIVE".equals(status)) {
            articles.setClassificationStatusBatch(issues.memberArticleIds(issueId), "CLASSIFIED");
        }
        // only changed fields: an omitted key means "not changed", never a value set to null
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (title != null) { before.put("title", head.title()); after.put("title", title); }
        if (summary != null) { after.put("summaryChanged", true); }
        if (category != null) { before.put("category", head.category()); after.put("category", category); }
        if (status != null) { before.put("status", head.status()); after.put("status", status); }
        audit.record(actor, "ISSUE_UPDATE", "ISSUE", issueId, before, after, reason, correlationId);
    }
}

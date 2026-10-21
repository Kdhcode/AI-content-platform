package com.aicontent.platform.content;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sources (A4) and related URLs (A5) of a content. Every change goes through {@link ContentChanges}: row lock, locked
 * status refused, version check, and only a real change bumps the version, sends REVIEW / APPROVED back to DRAFT and is
 * audited. Rules: docs/CONTENT_A_PLAN.md §5 "출처" and "관련 URL".
 */
@Service
public class ContentRelationService {

    public static final Set<String> SOURCE_KINDS = Set.of("ISSUE", "NEWS_ARTICLE", "EXTERNAL_URL", "MANUAL");
    public static final Set<String> SOURCE_ROLES = Set.of("PRIMARY", "SUPPORTING", "REFERENCE");
    public static final Set<String> LINK_TYPES = Set.of("PRODUCT", "OFFICIAL", "MAP", "RESERVATION", "PURCHASE", "REFERENCE", "OTHER");
    public static final Set<String> DISPLAY_POSITIONS = Set.of("BODY_TOP", "BODY_BOTTOM", "SOURCES");
    /** Issues that may be added as an extra source: the same states content can be created from. */
    static final Set<String> SOURCE_ISSUE_STATUSES = Set.of("ACTIVE", "CLOSED");
    static final int MAX_LABEL_CHARS = 300;
    static final int MAX_LINK_TITLE_CHARS = 200;
    /** Links per content; the reorder request lists every link, so it is capped the same (ContentLinkOrderRequest). */
    public static final int MAX_LINKS_PER_CONTENT = 500;

    public record SourceInput(String kind, Long issueId, Long newsArticleId, String externalUrl, String label, String role) {}

    /** For a link update, null fields keep their value. */
    public record LinkInput(String linkType, String title, String url, Boolean siteVisible, Boolean blogVisible,
                            String displayPosition) {}

    private final ContentChanges changes;
    private final ContentItemRepository items;

    public ContentRelationService(ContentChanges changes, ContentItemRepository items) {
        this.changes = changes;
        this.items = items;
    }

    // ------------------------------------------------------------------ sources (A4)

    @Transactional
    public ContentChanges.Result addSource(long contentId, long version, SourceInput in, String actor, String corr) {
        String kind = oneOf("kind", in.kind(), SOURCE_KINDS, null);
        String role = oneOf("role", in.role(), SOURCE_ROLES, "SUPPORTING");
        String label = label(in.label());
        Long issueId = null, articleId = null;
        String url = null;
        switch (kind) {
            case "ISSUE" -> { only(kind, in, true, false, false); issueId = in.issueId(); }
            case "NEWS_ARTICLE" -> { only(kind, in, false, true, false); articleId = in.newsArticleId(); }
            case "EXTERNAL_URL" -> { only(kind, in, false, false, true); url = ContentUrls.normalize(in.externalUrl(), "externalUrl"); }
            default -> {
                only(kind, in, false, false, false);
                if (label == null) throw invalid("label", "수동 출처에는 설명(label)이 필요합니다.");
            }
        }
        final Long fIssue = issueId, fArticle = articleId;
        final String fUrl = url;
        return changes.apply(contentId, version, actor, corr, "CONTENT_SOURCE_ADD", current -> {
            if (fIssue != null) {
                String status = items.issueStatus(fIssue).orElseThrow(() ->
                        new BusinessException(ErrorCode.ISSUE_NOT_FOUND, "출처로 지정한 이슈 #" + fIssue + "가 없습니다."));
                if (!SOURCE_ISSUE_STATUSES.contains(status)) {
                    throw new BusinessException(ErrorCode.ISSUE_STATE_INVALID,
                            "이슈 #" + fIssue + "의 상태가 " + status + "라 출처로 추가할 수 없습니다(ACTIVE·CLOSED만).");
                }
            }
            if (fArticle != null) {
                long duplicateOf = items.articleDuplicateOf(fArticle).orElseThrow(() ->
                        new BusinessException(ErrorCode.ARTICLE_NOT_FOUND, "출처로 지정한 기사 #" + fArticle + "가 없습니다."));
                if (duplicateOf != 0) {
                    throw new BusinessException(ErrorCode.CONTENT_SOURCE_INVALID,
                            "기사 #" + fArticle + "는 중복 기사입니다. 원본 기사 #" + duplicateOf + "를 출처로 지정하세요.",
                            Map.of("duplicateOfArticleId", duplicateOf));
                }
            }
            if (!"MANUAL".equals(kind) && items.sourceExists(contentId, kind, fIssue, fArticle, fUrl)) {
                throw new BusinessException(ErrorCode.CONTENT_SOURCE_DUPLICATE, "이미 이 콘텐츠의 출처로 등록돼 있습니다.");
            }
            long id = items.insertSource(contentId, kind, fIssue, fArticle, fUrl, label, role, actor);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("sourceId", id);
            after.put("kind", kind);
            after.put("role", role);
            after.put("ref", fIssue != null ? fIssue : fArticle != null ? fArticle : fUrl != null ? fUrl : label);
            return new ContentChanges.Outcome(true, Map.of(), after, id);
        });
    }

    /** Role and label only; to point at another issue / article / URL, remove the source and add a new one. */
    @Transactional
    public ContentChanges.Result updateSource(long contentId, long sourceId, long version, String role, String label,
                                              boolean labelGiven, String actor, String corr) {
        String newRoleArg = role == null ? null : oneOf("role", role, SOURCE_ROLES, null);
        String newLabelArg = labelGiven ? label(label) : null;
        return changes.apply(contentId, version, actor, corr, "CONTENT_SOURCE_UPDATE", current -> {
            var s = items.findSource(contentId, sourceId).orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_SOURCE_NOT_FOUND));
            String newRole = newRoleArg == null ? s.role() : newRoleArg;
            String newLabel = labelGiven ? newLabelArg : s.label();
            if ("MANUAL".equals(s.kind()) && newLabel == null) {
                throw invalid("label", "수동 출처의 설명(label)은 비울 수 없습니다.");
            }
            if (Objects.equals(newRole, s.role()) && Objects.equals(newLabel, s.label())) {
                return ContentChanges.Outcome.unchanged(sourceId);
            }
            items.updateSource(sourceId, newRole, newLabel);
            return new ContentChanges.Outcome(true, Map.of("sourceId", sourceId, "role", s.role(), "label", String.valueOf(s.label())),
                    Map.of("sourceId", sourceId, "role", newRole, "label", String.valueOf(newLabel)), sourceId);
        });
    }

    /** The origin issue's own source (registered at creation) is kept: it records where the content came from. */
    @Transactional
    public ContentChanges.Result removeSource(long contentId, long sourceId, long version, String actor, String corr) {
        return changes.apply(contentId, version, actor, corr, "CONTENT_SOURCE_REMOVE", current -> {
            var s = items.findSource(contentId, sourceId).orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_SOURCE_NOT_FOUND));
            if ("ISSUE".equals(s.kind()) && Objects.equals(s.issueId(), current.originIssueId())) {
                throw new BusinessException(ErrorCode.CONTENT_SOURCE_PROTECTED,
                        "원천 이슈 출처는 삭제할 수 없습니다. 필요하면 역할(role)을 바꾸세요.", Map.of("sourceId", sourceId));
            }
            items.deleteSource(sourceId);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("sourceId", sourceId);
            before.put("kind", s.kind());
            before.put("role", s.role());
            before.put("ref", s.issueId() != null ? s.issueId() : s.newsArticleId() != null ? s.newsArticleId()
                    : s.externalUrl() != null ? s.externalUrl() : String.valueOf(s.label()));
            return new ContentChanges.Outcome(true, before, Map.of("sourceId", sourceId, "removed", true), sourceId);
        });
    }

    // ------------------------------------------------------------------ related URLs (A5)

    @Transactional
    public ContentChanges.Result addLink(long contentId, long version, LinkInput in, String actor, String corr) {
        var row = new ContentItemRepository.LinkRow(0, oneOf("linkType", in.linkType(), LINK_TYPES, null), linkTitle(in.title()),
                ContentUrls.normalize(in.url(), "url"), in.siteVisible() == null || in.siteVisible(),
                in.blogVisible() != null && in.blogVisible(), oneOf("displayPosition", in.displayPosition(), DISPLAY_POSITIONS, "BODY_BOTTOM"), 0);
        return changes.apply(contentId, version, actor, corr, "CONTENT_LINK_ADD", current -> {
            // counted under the content row lock, so concurrent adds cannot pass the limit together
            long count = items.linkCount(contentId);
            if (count >= MAX_LINKS_PER_CONTENT) {
                throw new BusinessException(ErrorCode.CONTENT_LINK_LIMIT_EXCEEDED,
                        "관련 URL은 콘텐츠당 " + MAX_LINKS_PER_CONTENT + "개까지입니다(현재 " + count + "개). 필요 없는 링크를 먼저 삭제하세요.",
                        Map.of("limit", MAX_LINKS_PER_CONTENT, "current", count));
            }
            long id = items.insertLink(contentId, row);
            return new ContentChanges.Outcome(true, Map.of(), linkAudit(id, row), id);
        });
    }

    @Transactional
    public ContentChanges.Result updateLink(long contentId, long linkId, long version, LinkInput in, String actor, String corr) {
        String type = in.linkType() == null ? null : oneOf("linkType", in.linkType(), LINK_TYPES, null);
        String title = in.title() == null ? null : linkTitle(in.title());
        String url = in.url() == null ? null : ContentUrls.normalize(in.url(), "url");
        String position = in.displayPosition() == null ? null : oneOf("displayPosition", in.displayPosition(), DISPLAY_POSITIONS, null);
        return changes.apply(contentId, version, actor, corr, "CONTENT_LINK_UPDATE", current -> {
            var old = items.findLink(contentId, linkId).orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_LINK_NOT_FOUND));
            var next = new ContentItemRepository.LinkRow(linkId, type == null ? old.linkType() : type,
                    title == null ? old.title() : title, url == null ? old.url() : url,
                    in.siteVisible() == null ? old.siteVisible() : in.siteVisible(),
                    in.blogVisible() == null ? old.blogVisible() : in.blogVisible(),
                    position == null ? old.displayPosition() : position, old.sortOrder());
            if (next.equals(old)) {
                return ContentChanges.Outcome.unchanged(linkId);
            }
            items.updateLink(linkId, next);
            return new ContentChanges.Outcome(true, linkAudit(linkId, old), linkAudit(linkId, next), linkId);
        });
    }

    @Transactional
    public ContentChanges.Result removeLink(long contentId, long linkId, long version, String actor, String corr) {
        return changes.apply(contentId, version, actor, corr, "CONTENT_LINK_REMOVE", current -> {
            var old = items.findLink(contentId, linkId).orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_LINK_NOT_FOUND));
            items.deleteLink(linkId);
            return new ContentChanges.Outcome(true, linkAudit(linkId, old), Map.of("linkId", linkId, "removed", true), linkId);
        });
    }

    /**
     * Sets the display order. {@code linkIds} must list every link of this content exactly once: a duplicate, an
     * unknown id (including another content's link) or a missing id is a 400 and nothing changes. The same order as
     * now changes nothing.
     */
    @Transactional
    public ContentChanges.Result reorderLinks(long contentId, long version, List<Long> linkIds, String actor, String corr) {
        if (linkIds == null) {
            throw invalid("linkIds", "링크 id 목록이 필요합니다.");
        }
        return changes.apply(contentId, version, actor, corr, "CONTENT_LINKS_REORDER", current -> {
            List<ContentItemRepository.LinkRow> existing = items.links(contentId);
            List<Long> currentOrder = existing.stream().map(ContentItemRepository.LinkRow::id).toList();
            Set<Long> known = new HashSet<>(currentOrder);
            Set<Long> seen = new HashSet<>();
            List<Long> duplicates = new ArrayList<>(), unknown = new ArrayList<>();
            for (Long id : linkIds) {
                if (id == null || !known.contains(id)) unknown.add(id);
                else if (!seen.add(id)) duplicates.add(id);
            }
            List<Long> missing = currentOrder.stream().filter(id -> !seen.contains(id)).toList();
            if (!duplicates.isEmpty() || !unknown.isEmpty() || !missing.isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("duplicateIds", duplicates);
                details.put("unknownIds", unknown);
                details.put("missingIds", missing);
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "linkIds에는 이 콘텐츠의 링크를 빠짐없이 한 번씩 넣어야 합니다.", details);
            }
            if (linkIds.equals(currentOrder)) {
                return ContentChanges.Outcome.unchanged(null);      // same order: nothing is written (gaps in numbers are fine)
            }
            for (int i = 0; i < linkIds.size(); i++) items.setSortOrder(linkIds.get(i), i);
            return new ContentChanges.Outcome(true, Map.of("linkIds", currentOrder), Map.of("linkIds", List.copyOf(linkIds)), null);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> linkAudit(long id, ContentItemRepository.LinkRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("linkId", id);
        m.put("linkType", r.linkType());
        m.put("title", r.title());
        m.put("url", r.url());
        m.put("siteVisible", r.siteVisible());
        m.put("blogVisible", r.blogVisible());
        m.put("displayPosition", r.displayPosition());
        return m;
    }

    /** Exactly the reference field of the kind, no other. */
    private static void only(String kind, SourceInput in, boolean issue, boolean article, boolean url) {
        List<String> problems = new ArrayList<>();
        if (issue != (in.issueId() != null)) problems.add(issue ? "issueId가 필요합니다" : "issueId는 " + kind + "에 쓸 수 없습니다");
        if (article != (in.newsArticleId() != null)) problems.add(article ? "newsArticleId가 필요합니다" : "newsArticleId는 " + kind + "에 쓸 수 없습니다");
        if (!url && in.externalUrl() != null) problems.add("externalUrl은 " + kind + "에 쓸 수 없습니다");
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, kind + " 출처: " + String.join(", ", problems) + ".",
                    Map.of("kind", kind, "problems", problems));
        }
    }

    private static String oneOf(String field, String value, Set<String> allowed, String defaultValue) {
        if (value == null) {
            if (defaultValue != null) return defaultValue;
            throw invalid(field, "값이 필요합니다.");
        }
        if (!allowed.contains(value)) {
            throw invalid(field, "허용되지 않는 값입니다: " + value + " (" + String.join(", ", allowed.stream().sorted().toList()) + ")");
        }
        return value;
    }

    /** Trimmed label, null when blank; at most 300 characters, one line. */
    private static String label(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String label = raw.strip();
        if (label.length() > MAX_LABEL_CHARS) throw invalid("label", MAX_LABEL_CHARS + "자 이하여야 합니다.");
        if (label.indexOf('\n') >= 0 || label.indexOf('\r') >= 0) throw invalid("label", "줄바꿈을 넣을 수 없습니다.");
        return label;
    }

    private static String linkTitle(String raw) {
        String title = raw == null ? "" : raw.strip();
        if (title.length() > MAX_LINK_TITLE_CHARS) throw invalid("title", MAX_LINK_TITLE_CHARS + "자 이하여야 합니다.");
        if (title.indexOf('\n') >= 0 || title.indexOf('\r') >= 0) throw invalid("title", "줄바꿈을 넣을 수 없습니다.");
        return title;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, field + ": " + message,
                Map.of("violations", List.of(new ContentBodyValidator.Violation(field, message))));
    }
}

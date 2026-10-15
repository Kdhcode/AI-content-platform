package com.aicontent.platform.content;

import com.aicontent.platform.audit.AuditService;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.common.Json;
import com.aicontent.platform.issue.IssueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual NEWS / LIFE content: create, read, edit (A2) and status actions (A3). Statuses change only through
 * {@link #transition} and the edit rule (editing a REVIEW or APPROVED content sends it back to DRAFT).
 */
@Service
public class ContentService {

    public static final Set<String> TYPES = Set.of("NEWS", "LIFE");
    public static final Set<String> STATUSES = Set.of("DRAFT", "REVIEW", "APPROVED", "HOLD", "EXCLUDED", "ARCHIVED");
    /** Content can be made from these issue states only (REVIEW is still undecided, EXCLUDED / MERGED are gone). */
    static final Set<String> CREATABLE_ISSUE_STATUSES = Set.of("ACTIVE", "CLOSED");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    public record CreateResult(ContentRepository.Detail content, boolean created) {}

    private final ContentRepository contents;
    private final ContentChanges changes;
    private final IssueRepository issues;
    private final AuditService audit;
    private final Json json;

    public ContentService(ContentRepository contents, ContentChanges changes, IssueRepository issues, AuditService audit,
                          Json json) {
        this.contents = contents;
        this.changes = changes;
        this.issues = issues;
        this.audit = audit;
        this.json = json;
    }

    /**
     * Creates a DRAFT from an ACTIVE or CLOSED issue with the issue as PRIMARY source, in one transaction. The same
     * Idempotency-Key with the same request returns the first content ({@code created=false}); with a different issue,
     * type, title or body it is refused. Concurrent requests with one key produce one content and one audit record.
     */
    @Transactional
    public CreateResult create(long issueId, String type, String title, JsonNode body, String idempotencyKey,
                               String actor, String correlationId) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key 헤더가 필요합니다(영문·숫자·._:- 8~100자, 요청마다 새로 만든 값).");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "contentType은 NEWS 또는 LIFE여야 합니다.");
        }
        String effectiveTitle = title == null ? "" : title;
        ContentBodyValidator.validateTitle(effectiveTitle);
        JsonNode effectiveBody = body == null || body.isNull() ? emptyBody() : body;
        ContentBodyValidator.validate(effectiveBody);
        String fingerprint = fingerprint(issueId, type, effectiveTitle, effectiveBody);

        var replay = contents.findByCreationKey(idempotencyKey);
        if (replay.isPresent()) {
            return replayed(replay.get(), fingerprint);
        }
        String issueStatus = issues.lockStatus(issueId).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
        if (!CREATABLE_ISSUE_STATUSES.contains(issueStatus)) {
            String hint = "MERGED".equals(issueStatus)
                    ? " 병합된 이슈입니다. 병합 대상 이슈 #" + issues.findHead(issueId).orElseThrow().mergedIntoIssueId() + "에서 만드세요."
                    : " ACTIVE 또는 CLOSED 이슈에서만 콘텐츠를 만들 수 있습니다.";
            throw new BusinessException(ErrorCode.ISSUE_STATE_INVALID, "이슈 상태가 " + issueStatus + "입니다." + hint);
        }
        var inserted = contents.insertIfNewKey(type, effectiveTitle, effectiveBody, issueId, idempotencyKey, fingerprint, actor);
        if (inserted.isEmpty()) {
            // another request with this key committed first: answer like a replay (no second source, no second audit)
            return replayed(contents.findByCreationKey(idempotencyKey).orElseThrow(), fingerprint);
        }
        long id = inserted.get();
        contents.insertPrimaryIssueSource(id, issueId, actor);
        audit.record(actor, "CONTENT_CREATE", "CONTENT", id, null,
                Map.of("contentType", type, "originIssueId", issueId, "title", effectiveTitle, "status", "DRAFT", "version", 0),
                null, correlationId);
        return new CreateResult(contents.detail(id).orElseThrow(), true);
    }

    /**
     * Saves title and/or body (an omitted field keeps its value) through {@link ContentChanges}: at the current version
     * only (409 otherwise), never in HOLD / EXCLUDED / ARCHIVED, and an unchanged title and body change nothing. A real
     * change of a REVIEW or APPROVED content sends it back to DRAFT.
     */
    @Transactional
    public ContentChanges.Result patch(long id, long version, String title, JsonNode body, String actor, String correlationId) {
        if (title == null && (body == null || body.isNull())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "title 또는 body 중 하나는 보내야 합니다.");
        }
        ContentBodyValidator.validateTitle(title);
        if (body != null && !body.isNull()) {
            ContentBodyValidator.validate(body);
        }
        return changes.apply(id, version, actor, correlationId, "CONTENT_UPDATE", current -> {
            String newTitle = title == null ? current.title() : title;
            JsonNode newBody = body == null || body.isNull() ? current.body() : body;
            boolean titleChanged = !newTitle.equals(current.title());
            boolean bodyChanged = !newBody.equals(current.body());
            if (!titleChanged && !bodyChanged) {
                return ContentChanges.Outcome.unchanged(null);
            }
            contents.setTitleBody(id, newTitle, newBody);
            return new ContentChanges.Outcome(true, Map.of("title", current.title()),
                    Map.of("title", newTitle, "titleChanged", titleChanged, "bodyChanged", bodyChanged), null);
        });
    }

    /**
     * Performs one status action (docs/CONTENT_A_PLAN.md §4) on the version the caller has: row lock, version check,
     * role and source-status check, reason check, readiness for submit / approve, then status + version + review record
     * + audit in this transaction. A concurrent edit or action at the same version makes this one fail with 409.
     */
    @Transactional
    public ContentRepository.Detail transition(long id, ContentStatusPolicy.Action action, long version, String rawReason,
                                               String actor, boolean systemAdmin, String correlationId) {
        String reason = ContentStatusPolicy.reason(action, rawReason);
        var current = contents.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND));
        if (current.version() != version) {
            throw ContentChanges.conflict(current, version);
        }
        String to = ContentStatusPolicy.decide(action, current.status(), systemAdmin);
        if (action == ContentStatusPolicy.Action.SUBMIT || action == ContentStatusPolicy.Action.APPROVE) {
            var violations = new ArrayList<>(ContentBodyValidator.readinessViolations(current.title(), current.body()));
            if (contents.primarySourceCount(id) == 0) {
                violations.add(new ContentBodyValidator.Violation("sources", "PRIMARY 출처가 하나 이상 있어야 합니다."));
            }
            if (!violations.isEmpty()) {
                throw new BusinessException(ErrorCode.CONTENT_NOT_READY,
                        ContentStatusPolicy.name(action) + "에 필요한 내용이 부족합니다: " + violations.get(0).path() + " "
                                + violations.get(0).message(), Map.of("violations", violations));
            }
        }
        var rule = ContentStatusPolicy.rule(action);
        if (contents.transition(id, version, rule, reason, actor) != 1) {
            throw new IllegalStateException("content " + id + " changed while locked");   // cannot happen under FOR UPDATE
        }
        audit.record(actor, "CONTENT_" + action.name(), "CONTENT", id,
                Map.of("status", current.status(), "version", current.version()),
                Map.of("status", to, "version", version + 1), reason, correlationId);
        return contents.detail(id).orElseThrow();
    }

    private CreateResult replayed(ContentRepository.CreationRecord existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "이 Idempotency-Key는 다른 요청(이슈·유형·제목·본문이 다름)에 이미 쓰였습니다. 새 키로 요청하세요.",
                    Map.of("contentId", existing.id()));
        }
        return new CreateResult(contents.detail(existing.id()).orElseThrow(), false);
    }

    static JsonNode emptyBody() {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("schemaVersion", ContentBodyValidator.SCHEMA_VERSION);
        body.putArray("sections");
        return body;
    }

    /** SHA-256 of the request with object keys sorted, so the same request in another key order matches. */
    String fingerprint(long issueId, String type, String title, JsonNode body) {
        String canonical = issueId + "\n" + type + "\n" + title + "\n" + json.write(canonical(body));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                var e = it.next();
                sorted.put(e.getKey(), canonical(e.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(n -> items.add(canonical(n)));
            return items;
        }
        return node;
    }
}

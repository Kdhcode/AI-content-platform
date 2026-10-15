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
 * Manual NEWS / LIFE content (bundle A2: create, read, edit). Status actions (submit, approve, ...) come with A3; the
 * only status change here is the rule that editing a REVIEW or APPROVED content sends it back to DRAFT.
 */
@Service
public class ContentService {

    public static final Set<String> TYPES = Set.of("NEWS", "LIFE");
    public static final Set<String> STATUSES = Set.of("DRAFT", "REVIEW", "APPROVED", "HOLD", "EXCLUDED", "ARCHIVED");
    /** Content can be made from these issue states only (REVIEW is still undecided, EXCLUDED / MERGED are gone). */
    static final Set<String> CREATABLE_ISSUE_STATUSES = Set.of("ACTIVE", "CLOSED");
    /** Editable after restoring only (A3: resume / restore). */
    static final Set<String> LOCKED_STATUSES = Set.of("HOLD", "EXCLUDED", "ARCHIVED");
    /** Editing these invalidates the review request / approval. */
    static final Set<String> BACK_TO_DRAFT_ON_EDIT = Set.of("REVIEW", "APPROVED");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    public record CreateResult(ContentRepository.Detail content, boolean created) {}

    public record PatchResult(ContentRepository.Detail content, boolean statusChanged) {}

    private final ContentRepository contents;
    private final IssueRepository issues;
    private final AuditService audit;
    private final Json json;

    public ContentService(ContentRepository contents, IssueRepository issues, AuditService audit, Json json) {
        this.contents = contents;
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
     * Saves title and/or body (an omitted field keeps its value) when {@code version} is the current one; otherwise
     * 409 with the current version, who saved it and when. The check and the update happen under the row lock, and
     * the audit record is written in the same transaction. Saving an unchanged title and body changes nothing.
     */
    @Transactional
    public PatchResult patch(long id, long version, String title, JsonNode body, String actor, String correlationId) {
        if (title == null && (body == null || body.isNull())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "title 또는 body 중 하나는 보내야 합니다.");
        }
        ContentBodyValidator.validateTitle(title);
        if (body != null && !body.isNull()) {
            ContentBodyValidator.validate(body);
        }
        var current = contents.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND));
        if (LOCKED_STATUSES.contains(current.status())) {
            throw new BusinessException(ErrorCode.CONTENT_STATE_INVALID,
                    current.status() + " 상태의 콘텐츠는 수정할 수 없습니다. 먼저 보류 해제 또는 복귀하세요.",
                    Map.of("status", current.status()));
        }
        if (current.version() != version) {
            Map<String, Object> details = new HashMap<>();
            details.put("currentVersion", current.version());
            details.put("updatedBy", current.updatedBy());
            details.put("updatedAt", current.updatedAt());
            details.put("sentVersion", version);
            throw new BusinessException(ErrorCode.CONTENT_VERSION_CONFLICT,
                    "다른 곳에서 먼저 저장했습니다(현재 version " + current.version() + "). 최신 내용을 다시 불러오세요.", details);
        }
        String newTitle = title == null ? current.title() : title;
        JsonNode newBody = body == null || body.isNull() ? current.body() : body;
        boolean titleChanged = !newTitle.equals(current.title());
        boolean bodyChanged = !newBody.equals(current.body());
        if (!titleChanged && !bodyChanged) {
            return new PatchResult(contents.detail(id).orElseThrow(), false);
        }
        boolean backToDraft = BACK_TO_DRAFT_ON_EDIT.contains(current.status());
        if (contents.update(id, version, newTitle, newBody, backToDraft, actor) != 1) {
            throw new IllegalStateException("content " + id + " changed while locked");   // cannot happen under FOR UPDATE
        }
        String newStatus = backToDraft ? "DRAFT" : current.status();
        audit.record(actor, "CONTENT_UPDATE", "CONTENT", id,
                Map.of("title", current.title(), "status", current.status(), "version", current.version()),
                Map.of("title", newTitle, "status", newStatus, "version", current.version() + 1,
                        "titleChanged", titleChanged, "bodyChanged", bodyChanged),
                null, correlationId);
        if ("APPROVED".equals(current.status())) {
            audit.record(actor, "CONTENT_APPROVAL_REVOKED_BY_EDIT", "CONTENT", id, Map.of("status", "APPROVED"),
                    Map.of("status", "DRAFT"), "승인된 콘텐츠를 수정해 승인이 해제됨", correlationId);
        }
        return new PatchResult(contents.detail(id).orElseThrow(), backToDraft);
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

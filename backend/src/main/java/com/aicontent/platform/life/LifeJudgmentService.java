package com.aicontent.platform.life;

import com.aicontent.platform.audit.AuditService;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.common.Json;
import com.aicontent.platform.life.LifeJudgmentRepository.Exclusion;
import com.aicontent.platform.life.LifeJudgmentRepository.Judgment;
import com.aicontent.platform.life.LifeJudgmentRepository.JudgmentInput;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LIFE candidate judgments and admin exclusion (B1, docs/LIFE_B_PLAN.md). Storage rules only: running a judgment
 * (AI call, job), the API and the screen come later. A judgment never changes the issue, its status or NEWS content.
 */
@Service
public class LifeJudgmentService {

    public static final int MAX_CATEGORY_CHARS = 50;
    public static final int MAX_EXCLUSION_REASON_CHARS = 500;

    /** Computed on read, never stored. Exclusion is shown next to it, not instead of it. */
    public enum Status { UNJUDGED, JUDGED, STALE }

    /**
     * One judgment result to store. {@code input} should come from {@link #readInput}; {@link #record} re-checks the
     * parts it can (issue exists, change_seq not ahead of the issue, snapshot format and hash) but cannot prove the
     * snapshot was the issue's content at that change_seq.
     */
    public record NewJudgment(JudgmentInput input, boolean lifeRelated, String category, BigDecimal relevanceScore,
                              String reason, String promptVersion, String model, Long sourceJobId, String judgedBy) {}

    /**
     * How a {@link #record} call ended.
     * <ul>
     *   <li>CREATED: a new row was stored.</li>
     *   <li>REUSED: the job had already stored a row with the same issue, input (change_seq + hash) and result; that
     *       row is returned and nothing is written. Same outcome as CREATED for the caller.</li>
     *   <li>CONFLICT: the job id already has a row for another issue, input or result. That row is returned unchanged
     *       and nothing is written. Not a successful save: the caller reports it as its own error and does not retry
     *       blindly (a retry with the same job id can only conflict again).</li>
     * </ul>
     */
    public enum Outcome { CREATED, REUSED, CONFLICT }

    /**
     * @param judgment      the row now stored for this call or job (on CONFLICT: the earlier row, unchanged)
     * @param inputMatches  false when the stored row has another issue, input_change_seq or input_hash
     * @param resultMatches false when the stored row has another answer (related, category, score, reason, prompt
     *                      version, model) or requester
     */
    public record RecordResult(Outcome outcome, Judgment judgment, boolean inputMatches, boolean resultMatches) {

        /** CREATED or REUSED: the job's judgment is stored. Never true for CONFLICT. */
        public boolean saved() {
            return outcome != Outcome.CONFLICT;
        }
    }

    public record LifeState(long issueId, long issueChangeSeq, Status status, Judgment current, boolean excluded,
                            Exclusion exclusion) {}

    private final LifeJudgmentRepository repository;
    private final AuditService audit;
    private final Json json;

    public LifeJudgmentService(LifeJudgmentRepository repository, AuditService audit, Json json) {
        this.repository = repository;
        this.audit = audit;
        this.json = json;
    }

    public JudgmentInput readInput(long issueId) {
        return repository.readInput(issueId).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
    }

    /**
     * The row a job already stored, if any. B3 contract: a job handler calls this first and, when a row exists, skips
     * the AI call (the job was stopped after the save but before the worker marked it SUCCESS).
     */
    public Optional<Judgment> findBySourceJob(long sourceJobId) {
        return repository.findBySourceJob(sourceJobId);
    }

    /**
     * Appends a judgment (D2: a re-judgment is a new row); see {@link Outcome} for a job id that already has a row.
     * The current judgment is not "the newest row": an older input finishing late is stored and stays history (see
     * {@link #state}), so the input is NOT required to match the issue as it is now.
     * <p>
     * Only the row is idempotent here. Marking the job SUCCESS is a separate step of the job worker (not in this
     * transaction): if the worker stops between the two, the job runs again and the UNIQUE source_job_id makes this
     * call return the stored row instead of adding a second one. Concurrent calls with one job id are serialised by
     * that UNIQUE index: one inserts, the other waits for it and then compares against the committed row.
     */
    @Transactional
    public RecordResult record(NewJudgment j) {
        validate(j);
        checkInput(j.input());
        String category = j.category() == null || j.category().isBlank() ? null : j.category().strip();
        String reason = j.reason().strip();
        String promptVersion = j.promptVersion().strip();
        var id = repository.insert(j.input(), j.lifeRelated(), category, j.relevanceScore(), reason, promptVersion,
                j.model(), j.sourceJobId(), j.judgedBy());
        if (id.isPresent()) {
            return new RecordResult(Outcome.CREATED, repository.findById(id.get()).orElseThrow(), true, true);
        }
        Judgment stored = repository.findBySourceJob(j.sourceJobId()).orElseThrow();
        boolean inputMatches = stored.issueId() == j.input().issueId()
                && stored.inputChangeSeq() == j.input().changeSeq()
                && stored.inputHash().equals(j.input().hash());
        boolean resultMatches = stored.lifeRelated() == j.lifeRelated()
                && Objects.equals(stored.category(), category)
                && stored.relevanceScore().compareTo(j.relevanceScore()) == 0
                && stored.reason().equals(reason)
                && stored.promptVersion().equals(promptVersion)
                && Objects.equals(stored.model(), j.model())
                && stored.judgedBy().equals(j.judgedBy());
        Outcome outcome = inputMatches && resultMatches ? Outcome.REUSED : Outcome.CONFLICT;
        return new RecordResult(outcome, stored, inputMatches, resultMatches);
    }

    /**
     * Minimal checks of a caller-built input. Deliberately no "equals the issue now" check: an older input that was
     * read correctly must still be storable.
     */
    private void checkInput(JudgmentInput input) {
        long issueChangeSeq = repository.currentChangeSeq(input.issueId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
        if (input.changeSeq() < 0 || input.changeSeq() > issueChangeSeq) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "판정 입력의 change_seq가 이슈와 맞지 않습니다.");
        }
        JsonNode snap;
        try {
            snap = input.snapshotJson() == null ? null : json.parse(input.snapshotJson());
        } catch (IllegalStateException e) {
            snap = null;
        }
        if (snap == null || !snap.isObject() || !snap.path("schemaVersion").isIntegralNumber()
                || snap.get("schemaVersion").asInt() != LifeJudgmentRepository.SNAPSHOT_SCHEMA_VERSION
                || !snap.path("articleIds").isArray()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "판정 입력 스냅샷 형식이 올바르지 않습니다(schemaVersion " + LifeJudgmentRepository.SNAPSHOT_SCHEMA_VERSION + ").");
        }
        if (input.hash() == null || !input.hash().equals(repository.hashOf(input.snapshotJson()))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "판정 입력 스냅샷과 해시가 맞지 않습니다.");
        }
    }

    public LifeState state(long issueId) {
        var row = repository.state(issueId).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
        Status status = row.current() == null ? Status.UNJUDGED
                : row.current().inputChangeSeq() < row.issueChangeSeq() ? Status.STALE : Status.JUDGED;
        boolean excluded = row.exclusion() != null && row.exclusion().excluded();
        return new LifeState(row.issueId(), row.issueChangeSeq(), status, row.current(), excluded, row.exclusion());
    }

    public List<Judgment> history(long issueId) {
        repository.state(issueId).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
        return repository.history(issueId);
    }

    /** Hides the issue from LIFE candidates (not issue.status EXCLUDED). A reason is required. */
    @Transactional
    public Exclusion exclude(long issueId, String actor, String rawReason, String correlationId) {
        String reason = reason(rawReason);
        if (reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "LIFE 후보 제외에는 사유가 필요합니다.");
        }
        return change(issueId, true, reason, actor, correlationId);
    }

    /** Lifts the exclusion; the reason is optional and goes to the audit record. */
    @Transactional
    public Exclusion include(long issueId, String actor, String rawReason, String correlationId) {
        return change(issueId, false, reason(rawReason), actor, correlationId);
    }

    private Exclusion change(long issueId, boolean excluded, String reason, String actor, String correlationId) {
        if (!repository.lockIssue(issueId)) {
            throw new BusinessException(ErrorCode.ISSUE_NOT_FOUND);
        }
        var before = repository.findExclusion(issueId);
        boolean wasExcluded = before.isPresent() && before.get().excluded();
        // nothing changes: no write and no audit row (same rule as an unchanged issue save)
        if (wasExcluded == excluded && (!excluded || Objects.equals(before.get().reason(), reason))) {
            return before.orElse(null);
        }
        // the stored reason belongs to an exclusion; including again clears it (the audit row keeps both)
        repository.saveExclusion(issueId, excluded, excluded ? reason : null, actor);
        audit.record(actor, excluded ? "LIFE_EXCLUDE" : "LIFE_INCLUDE", "ISSUE", issueId,
                snapshot(wasExcluded, before.map(Exclusion::reason).orElse(null)),
                snapshot(excluded, excluded ? reason : null), reason, correlationId);
        return repository.findExclusion(issueId).orElseThrow();
    }

    private static Map<String, Object> snapshot(boolean excluded, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lifeExcluded", excluded);
        m.put("reason", reason);
        return m;
    }

    private static String reason(String raw) {
        String reason = raw == null || raw.isBlank() ? null : raw.strip();
        if (reason != null && reason.length() > MAX_EXCLUSION_REASON_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "사유는 " + MAX_EXCLUSION_REASON_CHARS + "자 이하여야 합니다.");
        }
        return reason;
    }

    /** D1: the score is checked here and by the DB CHECK. */
    static void validate(NewJudgment j) {
        if (j.input() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "판정 입력이 없습니다.");
        }
        BigDecimal score = j.relevanceScore();
        if (score == null || score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "relevanceScore는 0 이상 1 이하여야 합니다.");
        }
        if (score.stripTrailingZeros().scale() > 3) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "relevanceScore는 소수 셋째 자리까지입니다.");
        }
        if (j.category() != null && j.category().strip().length() > MAX_CATEGORY_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "category는 " + MAX_CATEGORY_CHARS + "자 이하여야 합니다.");
        }
        if (j.reason() == null || j.reason().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "판정 이유가 필요합니다.");
        }
        if (j.promptVersion() == null || j.promptVersion().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "promptVersion이 필요합니다.");
        }
        if (j.judgedBy() == null || j.judgedBy().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "judgedBy가 필요합니다.");
        }
    }
}

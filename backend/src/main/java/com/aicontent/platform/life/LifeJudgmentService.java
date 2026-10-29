package com.aicontent.platform.life;

import com.aicontent.platform.audit.AuditService;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.life.LifeJudgmentRepository.Exclusion;
import com.aicontent.platform.life.LifeJudgmentRepository.Judgment;
import com.aicontent.platform.life.LifeJudgmentRepository.JudgmentInput;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** One judgment result to store; {@code input} must come from {@link #readInput}. */
    public record NewJudgment(JudgmentInput input, boolean lifeRelated, String category, BigDecimal relevanceScore,
                              String reason, String promptVersion, String model, Long sourceJobId, String judgedBy) {}

    /**
     * @param created       false when the job had already stored its row (a retry); {@code judgment} is that row
     * @param inputMatches  on a retry: the stored row was made from the same issue, change_seq and snapshot hash
     * @param resultMatches on a retry: the stored row has the same answer (related, category, score, reason, prompt,
     *                      model). The stored row is never replaced; a mismatch is only reported to the caller.
     */
    public record RecordResult(Judgment judgment, boolean created, boolean inputMatches, boolean resultMatches) {

        public boolean mismatch() {
            return !inputMatches || !resultMatches;
        }
    }

    public record LifeState(long issueId, long issueChangeSeq, Status status, Judgment current, boolean excluded,
                            Exclusion exclusion) {}

    private final LifeJudgmentRepository repository;
    private final AuditService audit;

    public LifeJudgmentService(LifeJudgmentRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public JudgmentInput readInput(long issueId) {
        return repository.readInput(issueId).orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
    }

    /**
     * Appends a judgment (D2: a re-judgment is a new row). A retry of the same job returns the row it already stored.
     * The current judgment is not "the newest row": an older input finishing late stays history (see {@link #state}).
     * <p>
     * Only the row is idempotent here. Marking the job SUCCESS is a separate step of the job worker (not in this
     * transaction): if the worker stops between the two, the job runs again and the UNIQUE source_job_id makes this
     * call return the stored row instead of adding a second one.
     */
    @Transactional
    public RecordResult record(NewJudgment j) {
        validate(j);
        String category = j.category() == null || j.category().isBlank() ? null : j.category().strip();
        String reason = j.reason().strip();
        String promptVersion = j.promptVersion().strip();
        var id = repository.insert(j.input(), j.lifeRelated(), category, j.relevanceScore(), reason, promptVersion,
                j.model(), j.sourceJobId(), j.judgedBy());
        if (id.isPresent()) {
            return new RecordResult(repository.findById(id.get()).orElseThrow(), true, true, true);
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
                && Objects.equals(stored.model(), j.model());
        return new RecordResult(stored, false, inputMatches, resultMatches);
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

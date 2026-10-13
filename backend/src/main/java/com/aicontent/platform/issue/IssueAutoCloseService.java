package com.aicontent.platform.issue;

import com.aicontent.platform.audit.AuditService;
import com.aicontent.platform.config.AppProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes ACTIVE issues that had no new article and no change for {@code app.issue-lifecycle.auto-close-after-days}
 * (OPEN ITEM #9). Off by default (0): when an issue is "over" is a policy decision.
 *
 * <ul>
 *   <li>REVIEW issues are never closed automatically; they wait for an admin.</li>
 *   <li>Each closed issue gets an audit row (actor {@value #ACTOR}, action {@value #ACTION}) in the same transaction.</li>
 *   <li>A CLOSED issue is no longer a classification candidate, so a later article about the same event starts a new
 *       issue. An admin can reopen it (status ACTIVE), which also restarts the inactivity clock.</li>
 *   <li>No global lock: issue rows are locked with SKIP LOCKED, and classification re-checks the matched issue's
 *       status under a row lock before linking (a just-closed issue turns the decision into REVIEW).</li>
 * </ul>
 */
@Service
public class IssueAutoCloseService {

    private static final Logger log = LoggerFactory.getLogger(IssueAutoCloseService.class);
    static final String ACTOR = "system";
    static final String ACTION = "ISSUE_AUTO_CLOSE";
    static final int MAX_BATCHES_PER_RUN = 50;

    private final IssueRepository issues;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public IssueAutoCloseService(IssueRepository issues, AuditService audit, TransactionTemplate tx, AppProperties props) {
        this.issues = issues;
        this.audit = audit;
        this.tx = tx;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void describe() {
        int days = props.issueLifecycle().autoCloseAfterDays();
        if (days <= 0) {
            return;
        }
        log.info("issue auto-close: ACTIVE issues inactive for {} days become CLOSED", days);
        int window = props.classifier().candidateWindowDays();
        if (days < window) {
            log.warn("app.issue-lifecycle.auto-close-after-days ({}) is shorter than app.classifier.candidate-window-days ({}):"
                    + " follow-up articles of a closed issue will start a new issue earlier than the candidate window implies",
                    days, window);
        }
    }

    @Scheduled(fixedDelayString = "${app.issue-lifecycle.interval-ms:3600000}",
            initialDelayString = "${app.issue-lifecycle.initial-delay-ms:180000}")
    public void tick() {
        try {
            closeInactive();
        } catch (RuntimeException e) {
            log.warn("issue auto-close run failed: {}", e.getMessage());
        }
    }

    /** @return number of issues closed in this run (0 when auto-close is off). */
    public int closeInactive() {
        int days = props.issueLifecycle().autoCloseAfterDays();
        if (days <= 0) {
            return 0;
        }
        int batch = Math.max(1, props.issueLifecycle().batchSize());
        String correlationId = "auto-close-" + UUID.randomUUID();
        String reason = days + "일 동안 새 기사와 변경이 없어 자동 종료 (app.issue-lifecycle.auto-close-after-days)";
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            List<IssueRepository.ClosedIssue> closed = tx.execute(status -> closeBatch(days, batch, reason, correlationId));
            int n = closed == null ? 0 : closed.size();
            total += n;
            if (n < batch) {
                break;
            }
        }
        if (total > 0) {
            log.info("issue auto-close: closed {} issue(s) inactive for {} days (correlationId={})", total, days, correlationId);
        }
        return total;
    }

    private List<IssueRepository.ClosedIssue> closeBatch(int days, int batch, String reason, String correlationId) {
        List<IssueRepository.ClosedIssue> closed = issues.closeInactive(days, batch);
        for (IssueRepository.ClosedIssue c : closed) {
            audit.record(ACTOR, ACTION, "ISSUE", c.id(),
                    Map.of("status", "ACTIVE", "lastActivityAt", String.valueOf(c.lastActivityAt())),
                    Map.of("status", "CLOSED"), reason, correlationId);
        }
        return closed;
    }
}

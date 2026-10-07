package com.aicontent.platform.issue;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClassificationRunRepository {

    public record NewRun(long articleId, Long asyncJobId, String candidatesJson, String rawDecision, String decision,
                         Double confidence, String reason, Long matchedIssueId, String method, String promptKey,
                         String promptVersion, String model) {}

    private final JdbcClient jdbc;

    public ClassificationRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(NewRun r) {
        return jdbc.sql("""
                INSERT INTO classification_run (article_id, async_job_id, candidates, raw_decision, decision, confidence,
                    reason, matched_issue_id, method, prompt_key, prompt_version, model)
                VALUES (:article, :job, CAST(:candidates AS jsonb), :raw, :decision, :conf, :reason, :matched, :method,
                    :pk, :pv, :model)
                RETURNING id""")
                .param("article", r.articleId()).param("job", r.asyncJobId()).param("candidates", r.candidatesJson())
                .param("raw", r.rawDecision()).param("decision", r.decision()).param("conf", r.confidence())
                .param("reason", r.reason()).param("matched", r.matchedIssueId()).param("method", r.method())
                .param("pk", r.promptKey()).param("pv", r.promptVersion()).param("model", r.model())
                .query(Long.class).single();
    }

    public void markApplied(long runId, long issueId) {
        jdbc.sql("UPDATE classification_run SET applied = TRUE, applied_issue_id = :issue WHERE id = :id")
                .param("issue", issueId).param("id", runId).update();
    }
}

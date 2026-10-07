package com.aicontent.platform.ai;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AiJobRepository {

    private final JdbcClient jdbc;

    public AiJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One ai_job row per async job; a retried job reuses it and refreshes the prompt/model facts. */
    public long ensure(long asyncJobId, String purpose, Long articleId, String promptKey, String promptVersion,
                       String schemaVersion) {
        return jdbc.sql("""
                INSERT INTO ai_job (async_job_id, purpose, article_id, prompt_key, prompt_version, schema_version)
                VALUES (:job, :purpose, :article, :pk, :pv, :sv)
                ON CONFLICT (async_job_id) DO UPDATE SET prompt_key = EXCLUDED.prompt_key,
                    prompt_version = EXCLUDED.prompt_version, schema_version = EXCLUDED.schema_version
                RETURNING id""")
                .param("job", asyncJobId).param("purpose", purpose).param("article", articleId)
                .param("pk", promptKey).param("pv", promptVersion).param("sv", schemaVersion)
                .query(Long.class).single();
    }

    public void setModel(long aiJobId, String model) {
        jdbc.sql("UPDATE ai_job SET model = :model WHERE id = :id").param("model", model).param("id", aiJobId).update();
    }

    public Optional<Long> findIdByAsyncJob(long asyncJobId) {
        return jdbc.sql("SELECT id FROM ai_job WHERE async_job_id = :id").param("id", asyncJobId).query(Long.class).optional();
    }
}

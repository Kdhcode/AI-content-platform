-- ============================================================================
-- Phase 1 schema: news ISSUE engine
--
-- Scope (frozen P0): NEWS_SOURCE, NEWS_ARTICLE, ISSUE, ISSUE_ARTICLE, ASYNC_JOB
-- plus the minimum supporting tables required by the confirmed flow:
--   AI_JOB / AI_LOG        (AI call history, prompt versions)
--   CLASSIFICATION_RUN     (candidate issues + LLM decision per attempt; admin tracing + eval data)
--   APP_USER / USER_ROLE   (minimum admin auth, same USER+ROLE model as the future public site)
--   ADMIN_AUDIT_LOG        (merge / split / move history)
--
-- Deliberately NOT created here (later phases): CONTENT, CONTENT_SOURCE, CONTENT_LINK,
-- PUBLISH_*, TV_*, likes/bookmarks, analytics. Nothing below conflicts with them:
--   * ISSUE / NEWS_ARTICLE keep plain bigint ids so CONTENT_SOURCE can reference them (N:M).
--   * ASYNC_JOB is the generic lifecycle table; PUBLISH_JOB / AI_JOB specialise it by FK.
--
-- Embedding dimension is fixed at 1536 (OPEN ITEM: depends on the chosen embedding model;
-- changing it requires a new migration that re-creates the vector columns and HNSW index).
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- NEWS_SOURCE
-- ---------------------------------------------------------------------------
CREATE TABLE news_source (
    id                          BIGSERIAL PRIMARY KEY,
    name                        TEXT        NOT NULL,
    source_type                 TEXT        NOT NULL CHECK (source_type IN ('RSS', 'API', 'WEB', 'FIXTURE')),
    base_url                    TEXT,
    enabled                     BOOLEAN     NOT NULL DEFAULT TRUE,
    status                      TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DEGRADED', 'DISABLED')),
    collection_interval_seconds INTEGER     NOT NULL DEFAULT 600 CHECK (collection_interval_seconds >= 30),
    config                      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    last_attempt_at             TIMESTAMPTZ,
    last_success_at             TIMESTAMPTZ,
    failure_count               INTEGER     NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    last_error                  TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_news_source_name ON news_source (name);
CREATE TRIGGER trg_news_source_updated BEFORE UPDATE ON news_source
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- NEWS_ARTICLE
--   normalized_url is the real de-duplication key (tracking params stripped);
--   original_url is kept verbatim for display / audit.
--   status follows the design doc; classification_status is tracked separately so the
--   admin can see "analysed but waiting for review" without overloading `status`.
-- ---------------------------------------------------------------------------
CREATE TABLE news_article (
    id                      BIGSERIAL PRIMARY KEY,
    source_id               BIGINT      NOT NULL REFERENCES news_source (id),
    title                   TEXT        NOT NULL,
    original_url            TEXT        NOT NULL,
    normalized_url          TEXT        NOT NULL,
    title_hash              CHAR(64)    NOT NULL,
    publisher_name          TEXT        NOT NULL,
    author                  TEXT,
    category                TEXT,
    published_at            TIMESTAMPTZ,
    published_at_raw        TEXT,
    collected_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    analysis_text           TEXT,
    status                  TEXT        NOT NULL DEFAULT 'COLLECTED'
        CHECK (status IN ('COLLECTED', 'DUPLICATE', 'ANALYSIS_PENDING', 'ANALYZED', 'FAILED')),
    duplicate_of_article_id BIGINT REFERENCES news_article (id),
    analysis_json           JSONB,
    analysis_prompt_version TEXT,
    analyzed_at             TIMESTAMPTZ,
    analysis_error          TEXT,
    embedding               vector(1536),
    embedding_model         TEXT,
    embedded_at             TIMESTAMPTZ,
    classification_status   TEXT        NOT NULL DEFAULT 'NOT_CLASSIFIED'
        CHECK (classification_status IN ('NOT_CLASSIFIED', 'CLASSIFIED', 'REVIEW', 'FAILED')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_article_duplicate_ref CHECK ((status = 'DUPLICATE') = (duplicate_of_article_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_news_article_normalized_url ON news_article (normalized_url);
CREATE INDEX ix_news_article_published_at ON news_article (published_at DESC NULLS LAST);
CREATE INDEX ix_news_article_status_collected ON news_article (status, collected_at DESC);
CREATE INDEX ix_news_article_classification ON news_article (classification_status, collected_at DESC);
CREATE INDEX ix_news_article_publisher_title ON news_article (publisher_name, title_hash);
CREATE INDEX ix_news_article_source ON news_article (source_id, collected_at DESC);
CREATE TRIGGER trg_news_article_updated BEFORE UPDATE ON news_article
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- ISSUE
--   MERGED issues are kept (never deleted) and point to the surviving issue.
--   embedding is the centroid (avg) of the member article embeddings, recomputed in SQL
--   after every membership change, so it stays correct after merge / split / move.
-- ---------------------------------------------------------------------------
CREATE TABLE issue (
    id                   BIGSERIAL PRIMARY KEY,
    title                TEXT        NOT NULL,
    summary              TEXT,
    summary_source       TEXT        NOT NULL DEFAULT 'AUTO' CHECK (summary_source IN ('AUTO', 'MANUAL')),
    category             TEXT,
    key_facts            JSONB       NOT NULL DEFAULT '[]'::jsonb,
    entities             JSONB       NOT NULL DEFAULT '[]'::jsonb,
    first_published_at   TIMESTAMPTZ,
    last_updated_at      TIMESTAMPTZ,
    article_count        INTEGER     NOT NULL DEFAULT 0 CHECK (article_count >= 0),
    publisher_count      INTEGER     NOT NULL DEFAULT 0 CHECK (publisher_count >= 0),
    status               TEXT        NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'REVIEW', 'MERGED', 'CLOSED', 'EXCLUDED')),
    merged_into_issue_id BIGINT REFERENCES issue (id),
    embedding            vector(1536),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_issue_merged_ref CHECK ((status = 'MERGED') = (merged_into_issue_id IS NOT NULL)),
    CONSTRAINT ck_issue_not_merged_into_self CHECK (merged_into_issue_id IS DISTINCT FROM id)
);
CREATE INDEX ix_issue_status_updated ON issue (status, last_updated_at DESC NULLS LAST);
CREATE INDEX ix_issue_category ON issue (category);
CREATE INDEX ix_issue_embedding_hnsw ON issue USING hnsw (embedding vector_cosine_ops);
CREATE TRIGGER trg_issue_updated BEFORE UPDATE ON issue
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- ASYNC_JOB  (PostgreSQL is the source of truth; Redis is only an optional lock helper)
-- ---------------------------------------------------------------------------
CREATE TABLE async_job (
    id             BIGSERIAL PRIMARY KEY,
    job_type       TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    payload        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    result         JSONB,
    requested_by   TEXT        NOT NULL DEFAULT 'system',
    requested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    available_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    heartbeat_at   TIMESTAMPTZ,
    locked_by      TEXT,
    retry_count    INTEGER     NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    max_retries    INTEGER     NOT NULL DEFAULT 3 CHECK (max_retries >= 0),
    error_code     TEXT,
    error_message  TEXT,
    correlation_id TEXT        NOT NULL,
    dedupe_key     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- At most one queued/running job per dedupe_key => duplicate enqueue is impossible even with many writers.
CREATE UNIQUE INDEX ux_async_job_active_dedupe ON async_job (dedupe_key)
    WHERE dedupe_key IS NOT NULL AND status IN ('PENDING', 'RUNNING');
CREATE INDEX ix_async_job_claim ON async_job (available_at, id) WHERE status = 'PENDING';
CREATE INDEX ix_async_job_status_requested ON async_job (status, requested_at DESC);
CREATE INDEX ix_async_job_running_heartbeat ON async_job (heartbeat_at) WHERE status = 'RUNNING';
CREATE INDEX ix_async_job_correlation ON async_job (correlation_id);
CREATE TRIGGER trg_async_job_updated BEFORE UPDATE ON async_job
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- CLASSIFICATION_RUN: one row per classification attempt, whether or not it changed a link.
--   Holds the embedding candidates (with similarity) and the LLM decision, so the admin can
--   trace "why did this article go there", and so decisions accumulate as evaluation data.
-- ---------------------------------------------------------------------------
CREATE TABLE classification_run (
    id                BIGSERIAL PRIMARY KEY,
    article_id        BIGINT      NOT NULL REFERENCES news_article (id) ON DELETE CASCADE,
    async_job_id      BIGINT REFERENCES async_job (id) ON DELETE SET NULL,
    candidates        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    raw_decision      TEXT CHECK (raw_decision IN ('SAME_ISSUE', 'NEW_ISSUE', 'REVIEW')),
    decision          TEXT        NOT NULL CHECK (decision IN ('SAME_ISSUE', 'NEW_ISSUE', 'REVIEW')),
    confidence        NUMERIC(4, 3) CHECK (confidence BETWEEN 0 AND 1),
    reason            TEXT,
    matched_issue_id  BIGINT REFERENCES issue (id),
    method            TEXT        NOT NULL
        CHECK (method IN ('LLM', 'NO_CANDIDATE', 'GUARD_DOWNGRADE', 'MULTI_EVENT', 'INVALID_OUTPUT')),
    applied           BOOLEAN     NOT NULL DEFAULT FALSE,
    applied_issue_id  BIGINT REFERENCES issue (id),
    prompt_key        TEXT,
    prompt_version    TEXT,
    model             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_classification_run_article ON classification_run (article_id, created_at DESC);
CREATE INDEX ix_classification_run_decision ON classification_run (decision, created_at DESC);

-- ---------------------------------------------------------------------------
-- ISSUE_ARTICLE: relation table (N:M capable). Exactly one *primary* issue per article today;
-- a future multi-event article may add non-primary links without a schema change.
-- ---------------------------------------------------------------------------
CREATE TABLE issue_article (
    issue_id              BIGINT      NOT NULL REFERENCES issue (id),
    article_id            BIGINT      NOT NULL REFERENCES news_article (id) ON DELETE CASCADE,
    is_primary            BOOLEAN     NOT NULL DEFAULT TRUE,
    classification_method TEXT        NOT NULL
        CHECK (classification_method IN ('LLM', 'LLM_REVIEW', 'NO_CANDIDATE', 'MANUAL', 'MERGE', 'SPLIT')),
    similarity_score      NUMERIC(6, 5),
    llm_decision          TEXT CHECK (llm_decision IN ('SAME_ISSUE', 'NEW_ISSUE', 'REVIEW')),
    llm_confidence        NUMERIC(4, 3) CHECK (llm_confidence BETWEEN 0 AND 1),
    llm_reason            TEXT,
    classification_run_id BIGINT REFERENCES classification_run (id) ON DELETE SET NULL,
    manually_corrected    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, article_id)
);
CREATE UNIQUE INDEX ux_issue_article_primary ON issue_article (article_id) WHERE is_primary;
CREATE INDEX ix_issue_article_article ON issue_article (article_id);
CREATE TRIGGER trg_issue_article_updated BEFORE UPDATE ON issue_article
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- AI_JOB / AI_LOG
-- ---------------------------------------------------------------------------
CREATE TABLE ai_job (
    id             BIGSERIAL PRIMARY KEY,
    async_job_id   BIGINT      NOT NULL UNIQUE REFERENCES async_job (id) ON DELETE CASCADE,
    purpose        TEXT        NOT NULL,
    article_id     BIGINT REFERENCES news_article (id) ON DELETE SET NULL,
    input_ref      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    prompt_key     TEXT,
    prompt_version TEXT,
    model          TEXT,
    schema_version TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ai_log (
    id                 BIGSERIAL PRIMARY KEY,
    ai_job_id          BIGINT REFERENCES ai_job (id) ON DELETE SET NULL,
    article_id         BIGINT REFERENCES news_article (id) ON DELETE SET NULL,
    call_type          TEXT        NOT NULL CHECK (call_type IN ('ARTICLE_ANALYSIS', 'EMBEDDING', 'ISSUE_CLASSIFY')),
    provider           TEXT,
    model              TEXT,
    prompt_key         TEXT,
    prompt_version     TEXT,
    attempt            INTEGER     NOT NULL DEFAULT 1,
    input_tokens       INTEGER,
    output_tokens      INTEGER,
    estimated_cost_usd NUMERIC(12, 6),
    latency_ms         INTEGER,
    success            BOOLEAN     NOT NULL,
    error_code         TEXT,
    error_message      TEXT,
    response           TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_log_article ON ai_log (article_id, created_at DESC);
CREATE INDEX ix_ai_log_created ON ai_log (created_at DESC);

-- ---------------------------------------------------------------------------
-- APP_USER / USER_ROLE  (table is app_user because USER is a reserved word in PostgreSQL)
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    display_name  TEXT,
    status        TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_app_user_username ON app_user (lower(username));
CREATE TRIGGER trg_app_user_updated BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE user_role (
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role       TEXT        NOT NULL CHECK (role IN ('USER', 'OPERATOR', 'SYSTEM_ADMIN')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

-- ---------------------------------------------------------------------------
-- ADMIN_AUDIT_LOG
-- ---------------------------------------------------------------------------
CREATE TABLE admin_audit_log (
    id             BIGSERIAL PRIMARY KEY,
    actor          TEXT        NOT NULL,
    action         TEXT        NOT NULL,
    target_type    TEXT        NOT NULL,
    target_id      TEXT        NOT NULL,
    before_state   JSONB,
    after_state    JSONB,
    reason         TEXT,
    correlation_id TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_admin_audit_target ON admin_audit_log (target_type, target_id, created_at DESC);
CREATE INDEX ix_admin_audit_created ON admin_audit_log (created_at DESC);

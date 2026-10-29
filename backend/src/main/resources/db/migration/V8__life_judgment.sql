-- B1: LIFE candidate judgments (docs/LIFE_B_PLAN.md). Storage only: no AI call, job, API or screen yet.
--
-- Two tables because their lifetimes differ:
--   * life_judgment: one row per judgment, appended and never changed (a re-judgment is a new row);
--   * life_exclusion: one current admin decision per issue. Kept apart so no number of re-judgments can lift it.
-- No issue trigger changes: a judgment is stale when issue.change_seq (V7) has moved past the change_seq its input
-- was read at. That includes status-only changes (user decision 2026-10-29: no separate judgment counter).
-- Text checks reject NULL, empty and whitespace-only values ("[^[:space:]]": at least one visible character).
CREATE TABLE life_judgment (
    id               BIGSERIAL PRIMARY KEY,
    issue_id         BIGINT        NOT NULL REFERENCES issue (id),
    input_change_seq BIGINT        NOT NULL CHECK (input_change_seq >= 0),
    -- what the judgment was made from: issue fields covered by change_seq and the member article ids, no article text
    input_snapshot   JSONB         NOT NULL,
    input_hash       CHAR(64)      NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    life_related     BOOLEAN       NOT NULL,
    category         VARCHAR(50)   CHECK (category IS NULL OR category ~ '[^[:space:]]'),
    relevance_score  NUMERIC(4, 3) NOT NULL CHECK (relevance_score >= 0 AND relevance_score <= 1),
    reason           TEXT          NOT NULL CHECK (reason ~ '[^[:space:]]'),
    prompt_version   TEXT          NOT NULL CHECK (prompt_version ~ '[^[:space:]]'),
    model            TEXT,
    -- a retried job finds its row already there instead of adding a second one
    source_job_id    BIGINT        UNIQUE REFERENCES async_job (id) ON DELETE SET NULL,
    judged_by        TEXT          NOT NULL,
    judged_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- current judgment = largest input_change_seq, then largest id (a late result of an older input never wins)
CREATE INDEX ix_life_judgment_current ON life_judgment (issue_id, input_change_seq DESC, id DESC);

COMMENT ON COLUMN life_judgment.input_change_seq IS 'issue.change_seq read in the same statement as input_snapshot.';
COMMENT ON COLUMN life_judgment.input_hash IS 'SHA-256 (hex) of input_snapshot::text.';

CREATE TABLE life_exclusion (
    issue_id   BIGINT      PRIMARY KEY REFERENCES issue (id),
    excluded   BOOLEAN     NOT NULL,
    reason     TEXT        CHECK (reason IS NULL OR char_length(reason) <= 500),
    updated_by TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_life_exclusion_reason CHECK (NOT excluded OR (reason IS NOT NULL AND reason ~ '[^[:space:]]'))
);

COMMENT ON TABLE life_exclusion IS
    'Admin exclusion from LIFE candidates; separate from issue.status EXCLUDED. History is in admin_audit_log.';

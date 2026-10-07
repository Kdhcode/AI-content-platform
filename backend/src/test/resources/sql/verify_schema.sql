-- Plain-SQL assertions for the V1 schema and the ASYNC_JOB claim / recovery statements.
-- Runs under psql with ON_ERROR_STOP=1. Every `expect_error` block must raise, otherwise the script fails.
-- Usage (see docs/VERIFY.md):  psql -v ON_ERROR_STOP=1 -d <db with V1 applied> -f verify_schema.sql
-- (If pgvector is not installed, apply V1 with `vector(1536)` replaced by `float8[]`; nothing below touches vectors.)

BEGIN;

CREATE FUNCTION pg_temp.expect_error(stmt text, label text) RETURNS void AS $$
BEGIN
    EXECUTE stmt;
    RAISE EXCEPTION 'EXPECTED ERROR BUT STATEMENT SUCCEEDED: %', label;
EXCEPTION
    WHEN check_violation OR unique_violation OR foreign_key_violation OR not_null_violation THEN
        RAISE NOTICE 'ok (rejected): %', label;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.assert_true(cond boolean, label text) RETURNS void AS $$
BEGIN
    IF cond IS NOT TRUE THEN RAISE EXCEPTION 'ASSERTION FAILED: %', label; END IF;
    RAISE NOTICE 'ok: %', label;
END;
$$ LANGUAGE plpgsql;

INSERT INTO news_source (name, source_type) VALUES ('verify-source', 'FIXTURE');

-- ---- news_article -----------------------------------------------------------
INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name)
SELECT id, 'a', 'https://x.test/a?utm_source=z', 'https://x.test/a', repeat('a', 64), 'P1' FROM news_source;

SELECT pg_temp.expect_error($$
    INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name)
    SELECT id, 'a2', 'https://x.test/a', 'https://x.test/a', repeat('b', 64), 'P1' FROM news_source
$$, 'same normalized_url is rejected');

SELECT pg_temp.expect_error($$
    INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name, status)
    SELECT id, 'd', 'https://x.test/d', 'https://x.test/d', repeat('c', 64), 'P1', 'DUPLICATE' FROM news_source
$$, 'DUPLICATE without duplicate_of_article_id is rejected');

INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name, status, duplicate_of_article_id)
SELECT s.id, 'd', 'https://x.test/d', 'https://x.test/d', repeat('c', 64), 'P1', 'DUPLICATE', a.id
FROM news_source s, news_article a WHERE a.normalized_url = 'https://x.test/a';

SELECT pg_temp.expect_error($$
    INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name, status)
    SELECT id, 'z', 'https://x.test/z', 'https://x.test/z', repeat('z', 64), 'P1', 'NOPE' FROM news_source
$$, 'unknown article status is rejected');

-- ---- updated_at trigger ------------------------------------------------------
UPDATE news_article SET updated_at = now() - interval '1 day' WHERE normalized_url = 'https://x.test/a';
-- the trigger overrides the manual value on any UPDATE
UPDATE news_article SET title = 'a-renamed' WHERE normalized_url = 'https://x.test/a';
SELECT pg_temp.assert_true(
    (SELECT updated_at > now() - interval '1 minute' FROM news_article WHERE normalized_url = 'https://x.test/a'),
    'updated_at trigger refreshes the timestamp');

-- ---- issue -------------------------------------------------------------------
INSERT INTO issue (title) VALUES ('issue-1'), ('issue-2');

SELECT pg_temp.expect_error($$
    UPDATE issue SET status = 'MERGED' WHERE title = 'issue-1'
$$, 'MERGED status without merged_into_issue_id is rejected');

SELECT pg_temp.expect_error($$
    UPDATE issue SET status = 'MERGED', merged_into_issue_id = id WHERE title = 'issue-1'
$$, 'issue cannot be merged into itself');

UPDATE issue SET status = 'MERGED', merged_into_issue_id = (SELECT id FROM issue WHERE title = 'issue-2')
WHERE title = 'issue-1';

SELECT pg_temp.expect_error($$
    UPDATE issue SET merged_into_issue_id = (SELECT id FROM issue WHERE title = 'issue-2') WHERE title = 'issue-2'
$$, 'non-MERGED issue cannot carry merged_into_issue_id');

-- ---- issue_article -----------------------------------------------------------
INSERT INTO issue_article (issue_id, article_id, classification_method)
SELECT i.id, a.id, 'MANUAL' FROM issue i, news_article a
WHERE i.title = 'issue-2' AND a.normalized_url = 'https://x.test/a';

SELECT pg_temp.expect_error($$
    INSERT INTO issue_article (issue_id, article_id, classification_method)
    SELECT i.id, a.id, 'MANUAL' FROM issue i, news_article a
    WHERE i.title = 'issue-1' AND a.normalized_url = 'https://x.test/a'
$$, 'second PRIMARY issue for the same article is rejected');

INSERT INTO issue_article (issue_id, article_id, is_primary, classification_method)
SELECT i.id, a.id, FALSE, 'MANUAL' FROM issue i, news_article a
WHERE i.title = 'issue-1' AND a.normalized_url = 'https://x.test/a';
SELECT pg_temp.assert_true(
    (SELECT count(*) = 2 FROM issue_article), 'non-primary link allowed (future multi-event articles)');

SELECT pg_temp.expect_error($$
    INSERT INTO issue_article (issue_id, article_id, classification_method, llm_confidence)
    SELECT i.id, a.id, 'LLM', 1.5 FROM issue i, news_article a
    WHERE i.title = 'issue-1' AND a.normalized_url = 'https://x.test/d'
$$, 'confidence above 1 is rejected');

-- ---- classification_run ------------------------------------------------------
SELECT pg_temp.expect_error($$
    INSERT INTO classification_run (article_id, decision, method)
    SELECT id, 'MAYBE', 'LLM' FROM news_article LIMIT 1
$$, 'unknown decision value is rejected');

-- ---- async_job dedupe / claim / recovery ---------------------------------------
INSERT INTO async_job (job_type, correlation_id, dedupe_key) VALUES ('ANALYZE_ARTICLE', 'c1', 'ANALYZE:1');

SELECT pg_temp.expect_error($$
    INSERT INTO async_job (job_type, correlation_id, dedupe_key) VALUES ('ANALYZE_ARTICLE', 'c2', 'ANALYZE:1')
$$, 'second active job with the same dedupe_key is rejected');

UPDATE async_job SET status = 'SUCCESS', finished_at = now() WHERE dedupe_key = 'ANALYZE:1';
INSERT INTO async_job (job_type, correlation_id, dedupe_key) VALUES ('ANALYZE_ARTICLE', 'c3', 'ANALYZE:1');
SELECT pg_temp.assert_true(
    (SELECT count(*) = 2 FROM async_job WHERE dedupe_key = 'ANALYZE:1'),
    'dedupe_key can be reused once the previous job finished');

SELECT pg_temp.expect_error($$
    UPDATE async_job SET status = 'DONE' WHERE correlation_id = 'c3'
$$, 'unknown job status is rejected');

-- claim statement exactly as used by AsyncJobRepository.claimNext
DELETE FROM async_job;
INSERT INTO async_job (job_type, correlation_id, available_at) VALUES
    ('COLLECT_NEWS', 'j1', now() - interval '1 minute'),
    ('COLLECT_NEWS', 'j2', now() - interval '1 minute'),
    ('COLLECT_NEWS', 'future', now() + interval '1 hour');

WITH next AS (
    SELECT id FROM async_job
    WHERE status = 'PENDING' AND available_at <= now() AND job_type IN ('COLLECT_NEWS')
    ORDER BY available_at, id
    LIMIT 10
    FOR UPDATE SKIP LOCKED
)
UPDATE async_job j
SET status = 'RUNNING', started_at = now(), heartbeat_at = now(), locked_by = 'w1'
FROM next WHERE j.id = next.id;

SELECT pg_temp.assert_true(
    (SELECT count(*) = 2 FROM async_job WHERE status = 'RUNNING' AND locked_by = 'w1'),
    'claim takes only due PENDING jobs');
SELECT pg_temp.assert_true(
    (SELECT status = 'PENDING' FROM async_job WHERE correlation_id = 'future'), 'future job is left untouched');

-- stale recovery statement exactly as used by AsyncJobRepository.recoverStale
UPDATE async_job SET heartbeat_at = now() - interval '10 minutes' WHERE correlation_id = 'j1';
UPDATE async_job SET heartbeat_at = now() - interval '10 minutes', retry_count = max_retries WHERE correlation_id = 'j2';

UPDATE async_job SET
    status        = CASE WHEN retry_count < max_retries THEN 'PENDING' ELSE 'FAILED' END,
    retry_count   = CASE WHEN retry_count < max_retries THEN retry_count + 1 ELSE retry_count END,
    available_at  = now(),
    finished_at   = CASE WHEN retry_count < max_retries THEN NULL ELSE now() END,
    error_code    = 'WORKER_LOST',
    error_message = 'worker heartbeat expired (locked_by=' || coalesce(locked_by, '?') || ')',
    locked_by     = NULL
WHERE status = 'RUNNING' AND heartbeat_at < now() - make_interval(secs => 300);

SELECT pg_temp.assert_true(
    (SELECT status = 'PENDING' AND retry_count = 1 AND locked_by IS NULL AND error_code = 'WORKER_LOST'
     FROM async_job WHERE correlation_id = 'j1'),
    'stale RUNNING job with retries left goes back to PENDING (retry_count+1)');
SELECT pg_temp.assert_true(
    (SELECT status = 'FAILED' AND finished_at IS NOT NULL FROM async_job WHERE correlation_id = 'j2'),
    'stale RUNNING job without retries left becomes FAILED');

-- ---- users -----------------------------------------------------------------------
INSERT INTO app_user (username, password_hash) VALUES ('Admin', 'x');
SELECT pg_temp.expect_error($$
    INSERT INTO app_user (username, password_hash) VALUES ('admin', 'y')
$$, 'username uniqueness is case-insensitive');
SELECT pg_temp.expect_error($$
    INSERT INTO user_role (user_id, role) SELECT id, 'ROOT' FROM app_user
$$, 'unknown role is rejected');

ROLLBACK;
\echo 'ALL SCHEMA ASSERTIONS PASSED'

-- Runs the application's key SQL statements (copied from the repositories, :name -> psql variables) against a real
-- PostgreSQL schema. The only deviation: vector(1536) columns are float8[] when pgvector is not installed, so
-- avg(embedding), <=> and CAST(... AS vector) statements are NOT covered here (they need the docker-compose DB).
-- Usage: psql -d <db with V1 applied> -f verify_queries.sql
\set ON_ERROR_STOP on
\set QUIET on

CREATE OR REPLACE FUNCTION assert_eq(label text, actual text, expected text) RETURNS void AS $$
BEGIN
  IF actual IS DISTINCT FROM expected THEN
    RAISE EXCEPTION 'ASSERT FAILED [%]: expected <%> but was <%>', label, expected, actual;
  END IF;
END $$ LANGUAGE plpgsql;

TRUNCATE ai_log, ai_job, classification_run, issue_article, issue, news_article, news_source, async_job, admin_audit_log, user_role, app_user RESTART IDENTITY CASCADE;

-- NewsSourceRepository.upsertDefinition (twice: idempotent)
\set name '''src-a'''
\set type '''FIXTURE'''
\set url '''/tmp/x.json'''
\set enabled true
\set interval 600
\set config '''{"publisher":"x"}'''
INSERT INTO news_source (name, source_type, base_url, enabled, collection_interval_seconds, config)
VALUES (:name, :type, :url, :enabled, :interval, CAST(:config AS jsonb))
ON CONFLICT (name) DO UPDATE SET source_type = EXCLUDED.source_type, base_url = EXCLUDED.base_url,
    enabled = EXCLUDED.enabled, collection_interval_seconds = EXCLUDED.collection_interval_seconds, config = EXCLUDED.config;
INSERT INTO news_source (name, source_type, base_url, enabled, collection_interval_seconds, config)
VALUES (:name, :type, :url, :enabled, :interval, CAST(:config AS jsonb))
ON CONFLICT (name) DO UPDATE SET source_type = EXCLUDED.source_type, base_url = EXCLUDED.base_url,
    enabled = EXCLUDED.enabled, collection_interval_seconds = EXCLUDED.collection_interval_seconds, config = EXCLUDED.config;
SELECT assert_eq('one source after double upsert', (SELECT count(*)::text FROM news_source), '1');

-- NewsSourceRepository.findDueIds: never attempted -> due; just attempted -> not due; failing source waits longer
SELECT assert_eq('due when never attempted', (SELECT string_agg(id::text, ',') FROM (
  SELECT id FROM news_source WHERE enabled AND status <> 'DISABLED'
    AND (last_attempt_at IS NULL OR last_attempt_at + make_interval(secs => collection_interval_seconds * (1 + LEAST(failure_count, 5))) <= now())
  ORDER BY last_attempt_at NULLS FIRST, id) q), '1');
UPDATE news_source SET last_attempt_at = now() WHERE id = 1;
SELECT assert_eq('not due right after attempt', (SELECT count(*)::text FROM (
  SELECT id FROM news_source WHERE enabled AND status <> 'DISABLED'
    AND (last_attempt_at IS NULL OR last_attempt_at + make_interval(secs => collection_interval_seconds * (1 + LEAST(failure_count, 5))) <= now())) q), '0');
UPDATE news_source SET last_attempt_at = now() - interval '700 seconds' WHERE id = 1;
SELECT assert_eq('due after interval', (SELECT count(*)::text FROM (
  SELECT id FROM news_source WHERE enabled AND status <> 'DISABLED'
    AND (last_attempt_at IS NULL OR last_attempt_at + make_interval(secs => collection_interval_seconds * (1 + LEAST(failure_count, 5))) <= now())) q), '1');
UPDATE news_source SET failure_count = 3 WHERE id = 1;
SELECT assert_eq('failing source backs off (700s < 4*600s)', (SELECT count(*)::text FROM (
  SELECT id FROM news_source WHERE enabled AND status <> 'DISABLED'
    AND (last_attempt_at IS NULL OR last_attempt_at + make_interval(secs => collection_interval_seconds * (1 + LEAST(failure_count, 5))) <= now())) q), '0');

-- NewsSourceRepository.markFailure -> DEGRADED at threshold 3, markSuccess -> ACTIVE
UPDATE news_source SET failure_count = 2, status = 'ACTIVE' WHERE id = 1;
\set id 1
\set error '''NETWORK: boom'''
\set degradedAfter 3
UPDATE news_source SET failure_count = failure_count + 1, last_error = :error,
    status = CASE WHEN status = 'DISABLED' THEN status
                  WHEN failure_count + 1 >= :degradedAfter THEN 'DEGRADED' ELSE status END
WHERE id = :id;
SELECT assert_eq('degraded after 3rd failure', (SELECT status || '/' || failure_count FROM news_source WHERE id = 1), 'DEGRADED/3');
UPDATE news_source SET last_success_at = now(), failure_count = 0, last_error = NULL,
    status = CASE WHEN status = 'DISABLED' THEN status ELSE 'ACTIVE' END WHERE id = :id;
SELECT assert_eq('recovered', (SELECT status || '/' || failure_count FROM news_source WHERE id = 1), 'ACTIVE/0');

-- NewsArticleRepository.insertIfNewUrl: first insert returns id, same normalized_url returns nothing
\set source 1
\set title '''가상시 상가 화재'''
\set orig '''https://example.test/n/1'''
\set norm '''https://example.test/n/1'''
\set hash '''aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'''
\set publisher '''가상일보'''
\set author null
\set category null
\set publishedAt '''2026-10-01T09:00:00+09:00'''
\set raw '''2026-10-01T09:00:00+09:00'''
\set text null
\set status '''ANALYSIS_PENDING'''
\set dupOf null
INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name,
    author, category, published_at, published_at_raw, analysis_text, status, duplicate_of_article_id)
VALUES (:source, :title, :orig, :norm, :hash, :publisher, :author, :category, :publishedAt, :raw, :text, :status, :dupOf)
ON CONFLICT (normalized_url) DO NOTHING RETURNING id;
SELECT assert_eq('first insert stored', (SELECT count(*)::text FROM news_article), '1');
INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name,
    author, category, published_at, published_at_raw, analysis_text, status, duplicate_of_article_id)
VALUES (:source, :title, :orig, :norm, :hash, :publisher, :author, :category, :publishedAt, :raw, :text, :status, :dupOf)
ON CONFLICT (normalized_url) DO NOTHING RETURNING id;
SELECT assert_eq('url duplicate not stored', (SELECT count(*)::text FROM news_article), '1');

-- NewsArticleRepository.findTitleDuplicate: same publisher (case-insensitive) + hash inside window, original only
\set days 7
SELECT assert_eq('title duplicate found', (SELECT id::text FROM news_article
  WHERE lower(publisher_name) = lower('가상일보') AND title_hash = :hash AND status <> 'DUPLICATE'
    AND collected_at >= now() - make_interval(days => :days) ORDER BY id LIMIT 1), '1');
SELECT assert_eq('other publisher is no title duplicate', (SELECT count(*)::text FROM news_article
  WHERE lower(publisher_name) = lower('다른신문') AND title_hash = :hash AND status <> 'DUPLICATE'
    AND collected_at >= now() - make_interval(days => :days)), '0');

-- ON CONFLICT DO NOTHING still consumes a sequence value (expected PostgreSQL behaviour): realign so ids below are 2 and 3
ALTER SEQUENCE news_article_id_seq RESTART WITH 2;
-- two more articles + an issue with both members (as the classifier would create)
INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name, published_at, status, classification_status, analysis_json)
VALUES (1, 'a2', 'https://example.test/n/2', 'https://example.test/n/2', repeat('b', 64), '샘플뉴스', '2026-10-01T10:00:00+09:00', 'ANALYZED', 'CLASSIFIED',
        '{"summary":"요약2","keyFacts":["사실 A","사실 B"],"entities":[{"name":"가상시","type":"PLACE"}]}'),
       (1, 'a3', 'https://example.test/n/3', 'https://example.test/n/3', repeat('c', 64), '가상일보', NULL, 'ANALYZED', 'CLASSIFIED',
        '{"summary":"요약3","keyFacts":["사실 b","사실 C"],"entities":[{"name":"가상시","type":"PLACE"},{"name":"소방서","type":"ORG"}]}');
UPDATE news_article SET analysis_json = '{"summary":"요약1","keyFacts":["사실 A"],"entities":[]}', status = 'ANALYZED' WHERE id = 1;

-- IssueRepository.create / link
INSERT INTO issue (title, summary, category, status) VALUES ('이슈1', NULL, 'SOCIETY', 'ACTIVE') RETURNING id;
INSERT INTO issue (title, summary, category, status) VALUES ('이슈2', NULL, 'SOCIETY', 'ACTIVE') RETURNING id;
INSERT INTO issue_article (issue_id, article_id, is_primary, classification_method) VALUES (1, 1, TRUE, 'NO_CANDIDATE'), (1, 2, TRUE, 'LLM'), (2, 3, TRUE, 'NO_CANDIDATE');

-- IssueRepository.recomputeAggregates (avg(embedding) replaced by NULL: needs pgvector)
UPDATE issue i SET
    article_count = s.cnt, publisher_count = s.pubs, first_published_at = s.first_at, last_updated_at = s.last_at,
    embedding = s.emb,
    status = CASE WHEN s.cnt = 0 AND i.status IN ('ACTIVE', 'REVIEW') THEN 'CLOSED' ELSE i.status END
FROM (
    SELECT count(*) AS cnt, count(DISTINCT lower(trim(a.publisher_name))) AS pubs,
           min(COALESCE(a.published_at, a.collected_at)) AS first_at, max(COALESCE(a.published_at, a.collected_at)) AS last_at,
           NULL::float8[] AS emb
    FROM issue_article ia JOIN news_article a ON a.id = ia.article_id
    WHERE ia.issue_id = 1 AND ia.is_primary
) s WHERE i.id = 1;
SELECT assert_eq('issue 1 counts', (SELECT article_count || '/' || publisher_count FROM issue WHERE id = 1), '2/2');
SELECT assert_eq('issue 1 range', (SELECT (first_published_at = '2026-10-01T09:00:00+09:00')::text || (last_updated_at = '2026-10-01T10:00:00+09:00')::text FROM issue WHERE id = 1), 'truetrue');

-- empty issue closes
DELETE FROM issue_article WHERE issue_id = 2;
UPDATE issue i SET
    article_count = s.cnt, publisher_count = s.pubs, first_published_at = s.first_at, last_updated_at = s.last_at, embedding = s.emb,
    status = CASE WHEN s.cnt = 0 AND i.status IN ('ACTIVE', 'REVIEW') THEN 'CLOSED' ELSE i.status END
FROM (
    SELECT count(*) AS cnt, count(DISTINCT lower(trim(a.publisher_name))) AS pubs, min(COALESCE(a.published_at, a.collected_at)) AS first_at,
           max(COALESCE(a.published_at, a.collected_at)) AS last_at, NULL::float8[] AS emb
    FROM issue_article ia JOIN news_article a ON a.id = ia.article_id WHERE ia.issue_id = 2 AND ia.is_primary
) s WHERE i.id = 2;
SELECT assert_eq('empty issue closed', (SELECT status || '/' || article_count FROM issue WHERE id = 2), 'CLOSED/0');
INSERT INTO issue_article (issue_id, article_id, is_primary, classification_method) VALUES (1, 3, TRUE, 'MANUAL');

-- IssueRepository.memberAnalyses ordering (published_at, then collected_at fallback)
SELECT assert_eq('member analyses count', (SELECT count(*)::text FROM (
  SELECT CAST(a.analysis_json AS text) FROM issue_article ia JOIN news_article a ON a.id = ia.article_id
  WHERE ia.issue_id = 1 AND ia.is_primary AND a.analysis_json IS NOT NULL
  ORDER BY COALESCE(a.published_at, a.collected_at), a.id) q), '3');

-- IssueRepository.updateFields: NULL keeps, summary marks MANUAL, status change
\set title null
\set summary '''관리자 요약'''
\set category null
\set status null
UPDATE issue SET title = COALESCE(:title, title), summary = COALESCE(:summary, summary),
    summary_source = CASE WHEN CAST(:summary AS text) IS NULL THEN summary_source ELSE 'MANUAL' END,
    category = COALESCE(:category, category), status = COALESCE(:status, status) WHERE id = 1;
SELECT assert_eq('manual summary', (SELECT title || '/' || summary || '/' || summary_source || '/' || status FROM issue WHERE id = 1), '이슈1/관리자 요약/MANUAL/ACTIVE');
\set summary null
UPDATE issue SET title = COALESCE(:title, title), summary = COALESCE(:summary, summary),
    summary_source = CASE WHEN CAST(:summary AS text) IS NULL THEN summary_source ELSE 'MANUAL' END,
    category = COALESCE(:category, category), status = COALESCE(:status, status) WHERE id = 1;
SELECT assert_eq('null patch keeps MANUAL', (SELECT summary || '/' || summary_source FROM issue WHERE id = 1), '관리자 요약/MANUAL');

-- IssueRepository.moveAllMembers + markMerged (CHECK ck_issue_merged_ref requires both)
UPDATE issue_article SET issue_id = 2, classification_method = 'SPLIT', manually_corrected = TRUE WHERE article_id = 3 AND issue_id = 1 AND is_primary;
UPDATE issue SET status = 'MERGED', merged_into_issue_id = 1 WHERE id = 2;
SELECT assert_eq('merged marker', (SELECT status || '/' || merged_into_issue_id FROM issue WHERE id = 2), 'MERGED/1');

-- ClassificationRunRepository.insert + markApplied
INSERT INTO classification_run (article_id, async_job_id, candidates, raw_decision, decision, confidence, reason, matched_issue_id, method, prompt_key, prompt_version, model)
VALUES (2, NULL, CAST('[{"issueId":1,"title":"t","similarity":0.8,"articleCount":1}]' AS jsonb), 'SAME_ISSUE', 'SAME_ISSUE', 0.9, 'r', 1, 'LLM', 'issue-classifier', 'v1', 'stub') RETURNING id;
UPDATE classification_run SET applied = TRUE, applied_issue_id = 1 WHERE id = 1;
SELECT assert_eq('run applied', (SELECT applied::text FROM classification_run WHERE id = 1), 'true');
-- invalid method is rejected by the CHECK
DO $$ BEGIN
  BEGIN
    INSERT INTO classification_run (article_id, decision, method) VALUES (2, 'REVIEW', 'WHATEVER');
    RAISE EXCEPTION 'should have been rejected';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;

-- AiJobRepository.ensure: upsert per async job
INSERT INTO async_job (job_type, payload, correlation_id) VALUES ('ANALYZE_ARTICLE', '{"articleId":1}', 'c1');
INSERT INTO ai_job (async_job_id, purpose, article_id, prompt_key, prompt_version, schema_version)
VALUES (1, 'ARTICLE_ANALYSIS', 1, 'article-analysis', 'v1', 's1')
ON CONFLICT (async_job_id) DO UPDATE SET prompt_key = EXCLUDED.prompt_key, prompt_version = EXCLUDED.prompt_version, schema_version = EXCLUDED.schema_version RETURNING id;
INSERT INTO ai_job (async_job_id, purpose, article_id, prompt_key, prompt_version, schema_version)
VALUES (1, 'ARTICLE_ANALYSIS', 1, 'article-analysis', 'v2', 's1')
ON CONFLICT (async_job_id) DO UPDATE SET prompt_key = EXCLUDED.prompt_key, prompt_version = EXCLUDED.prompt_version, schema_version = EXCLUDED.schema_version RETURNING id;
SELECT assert_eq('ai_job reused', (SELECT count(*)::text || '/' || max(prompt_version) FROM ai_job), '1/v2');
INSERT INTO ai_log (ai_job_id, article_id, call_type, provider, attempt, success) VALUES (1, 1, 'ARTICLE_ANALYSIS', 'stub', 1, TRUE);

-- AuditService.record
INSERT INTO admin_audit_log (actor, action, target_type, target_id, before_state, after_state, reason, correlation_id)
VALUES ('admin', 'ISSUE_MERGE', 'ISSUE', '1', CAST('{"a":1}' AS jsonb), CAST('{"b":2}' AS jsonb), NULL, 'c1');

-- ArticleQueryRepository.list (joins, ILIKE ESCAPE, order by from PageParams.resolveOrderBy)
\set q '''%화재%'''
SELECT assert_eq('article list filter+join', (SELECT string_agg(id::text, ',' ORDER BY id) FROM (
  SELECT a.id FROM news_article a LEFT JOIN issue_article ia ON ia.article_id = a.id AND ia.is_primary
    LEFT JOIN issue i ON i.id = ia.issue_id
  WHERE a.title ILIKE :q ESCAPE '\' ORDER BY a.published_at ASC NULLS LAST, a.id DESC LIMIT 20 OFFSET 0) q), '1');
SELECT assert_eq('article list by issue', (SELECT count(*)::text FROM news_article a LEFT JOIN issue_article ia ON ia.article_id = a.id AND ia.is_primary
    LEFT JOIN issue i ON i.id = ia.issue_id WHERE ia.issue_id = 1), '2');

-- IssueQueryRepository.list
SELECT assert_eq('issue list', (SELECT string_agg(id::text, ',' ORDER BY id) FROM (
  SELECT i.id FROM issue i WHERE i.status = 'ACTIVE' AND (i.title ILIKE '%이슈%' ESCAPE '\' OR i.summary ILIKE '%이슈%' ESCAPE '\')
  ORDER BY i.last_updated_at DESC NULLS LAST, i.id DESC LIMIT 20 OFFSET 0) q), '1');

-- JobQueryRepository payload filter
SELECT assert_eq('job payload filter', (SELECT count(*)::text FROM async_job j WHERE (j.payload ->> 'articleId') = '1'), '1');

-- SecurityConfig / AppUserDetailsService / AdminBootstrap
INSERT INTO app_user (username, password_hash, display_name) VALUES ('Root', 'x', 'Root') ON CONFLICT (lower(username)) DO NOTHING RETURNING id;
INSERT INTO app_user (username, password_hash, display_name) VALUES ('root', 'y', 'root') ON CONFLICT (lower(username)) DO NOTHING RETURNING id;
SELECT assert_eq('case-insensitive username', (SELECT count(*)::text FROM app_user), '1');
INSERT INTO user_role (user_id, role) VALUES (1, 'SYSTEM_ADMIN');
SELECT assert_eq('user lookup', (SELECT username FROM app_user WHERE lower(username) = lower('ROOT')), 'Root');

-- (moved to the end so that article ids above stay 1..3) DUPLICATE row must reference its original (CHECK ck_article_duplicate_ref)
INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name, status, duplicate_of_article_id)
VALUES (1, 't', 'https://example.test/n/9', 'https://example.test/n/9', :hash, '가상일보', 'DUPLICATE', 1);
SELECT assert_eq('duplicate stored with ref', (SELECT duplicate_of_article_id::text FROM news_article WHERE original_url LIKE '%/n/9'), '1');

\echo ALL QUERY ASSERTIONS PASSED

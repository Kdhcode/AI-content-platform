-- 실제 AI 공급자 소량 검증 결과 확인 (읽기 전용, 데이터를 바꾸지 않음)
-- 사용: psql -h 127.0.0.1 -p 55432 -U aicontent -d aicontent_live -f docs/sql/real_ai_smoke_check.sql
-- 기준: docs/REAL_AI_SMOKE.md 의 완료 기준. 실제 모델 전용 DB(aicontent_live)에서만 실행한다.

\echo '== 1. 기사 상태 (ANALYZED + CLASSIFIED/REVIEW 가 기대값)'
SELECT id, left(title, 40) AS title, publisher_name, status, classification_status,
       embedding IS NOT NULL AS has_embedding, embedding_model,
       CASE WHEN embedding IS NULL THEN NULL ELSE vector_dims(embedding) END AS dims
FROM news_article
ORDER BY id;

\echo '== 2. ai_log: 호출 유형별 공급자·모델·성공·토큰'
SELECT call_type, provider, model, success, count(*) AS calls,
       sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens,
       round(avg(latency_ms)) AS avg_latency_ms
FROM ai_log
GROUP BY call_type, provider, model, success
ORDER BY call_type, success DESC;

\echo '== 3. ai_log 실패 상세 (값이 없으면 실패 없음)'
SELECT id, created_at, call_type, model, attempt, error_code, left(error_message, 120) AS error_message
FROM ai_log
WHERE NOT success
ORDER BY id;

\echo '== 4. 분류 판단 (method LLM/NO_CANDIDATE 등, 근거 reason)'
SELECT article_id, method, raw_decision, decision, confidence, matched_issue_id, applied, applied_issue_id,
       model, prompt_version, left(reason, 100) AS reason
FROM classification_run
ORDER BY id;

\echo '== 5. 이슈'
SELECT id, left(title, 40) AS title, status, article_count, embedding IS NOT NULL AS has_centroid
FROM issue
ORDER BY id;

\echo '== 6. 작업 상태 (FAILED 가 있으면 error_code 확인)'
SELECT job_type, status, count(*) AS jobs, max(retry_count) AS max_retry,
       string_agg(DISTINCT error_code, ', ') AS error_codes
FROM async_job
GROUP BY job_type, status
ORDER BY job_type, status;

\echo '== 7. 판정 (모두 true 여야 통과)'
SELECT
  (SELECT count(*) FROM news_article WHERE status <> 'DUPLICATE') BETWEEN 1 AND 3        AS article_count_1_to_3,
  NOT EXISTS (SELECT 1 FROM ai_log WHERE provider IS DISTINCT FROM 'openai')              AS only_openai_in_log,
  NOT EXISTS (SELECT 1 FROM news_article WHERE status <> 'DUPLICATE'
              AND (status <> 'ANALYZED' OR classification_status NOT IN ('CLASSIFIED', 'REVIEW'))) AS all_analyzed_and_classified,
  NOT EXISTS (SELECT 1 FROM news_article WHERE status = 'ANALYZED'
              AND (embedding IS NULL OR vector_dims(embedding) <> 1536))                   AS all_embeddings_1536,
  EXISTS (SELECT 1 FROM ai_log WHERE call_type = 'ARTICLE_ANALYSIS' AND success AND input_tokens > 0 AND output_tokens > 0)
                                                                                          AS analysis_tokens_logged,
  EXISTS (SELECT 1 FROM ai_log WHERE call_type = 'EMBEDDING' AND success AND input_tokens > 0)
                                                                                          AS embedding_tokens_logged,
  NOT EXISTS (SELECT 1 FROM async_job WHERE status IN ('PENDING', 'RUNNING'))             AS no_jobs_in_flight,
  NOT EXISTS (SELECT 1 FROM async_job WHERE status = 'FAILED')                            AS no_failed_jobs;
-- ISSUE_CLASSIFY 호출은 후보 이슈가 있을 때만 생긴다. 첫 기사는 후보가 없어 LLM 없이 NEW_ISSUE(method NO_CANDIDATE)가 정상이다.

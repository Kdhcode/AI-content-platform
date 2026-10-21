-- Fictional public issues so the newsroom has more than one page (12 per page) for verify-public-news.cjs.
-- Only for a verify database (name aicontent_verify_*): it stops on any other database, including aicontent_main.
-- Adds 10 ACTIVE issues, each with one ANALYZED article on example.com. Running it again adds nothing.
-- Usage (2026-10-19): copy a verify DB, run this, start a backend + UI on it, then
--   VERIFY_UI_URL=http://127.0.0.1:<ui port> node admin-ui/scripts/verify-public-news.cjs
--   psql -h 127.0.0.1 -p 55432 -U aicontent -d aicontent_verify_<name> -v ON_ERROR_STOP=1 -f docs/verify/public-news-pagination.sql
DO $$
DECLARE
    src  bigint;
    iss  bigint;
    art  bigint;
    n    int;
    at   timestamptz;
BEGIN
    IF current_database() NOT LIKE 'aicontent\_verify\_%' THEN
        RAISE EXCEPTION 'public-news-pagination.sql only runs on an aicontent_verify_* database, not %', current_database();
    END IF;
    IF EXISTS (SELECT 1 FROM issue WHERE title LIKE '[페이지 확인] %') THEN
        RAISE NOTICE 'pagination issues already present; nothing added';
        RETURN;
    END IF;
    SELECT min(id) INTO src FROM news_source;
    IF src IS NULL THEN
        RAISE EXCEPTION 'no news_source row: use a copy of a verify DB that has collected data';
    END IF;
    FOR n IN 1..10 LOOP
        at := timestamptz '2026-10-19 09:00:00+09' + make_interval(mins => n);
        INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name,
                                  published_at, status, analyzed_at, classification_status)
        VALUES (src, '[페이지 확인] 가상 기사 ' || n, 'https://example.com/verify/pagination/' || n,
                'https://example.com/verify/pagination/' || n,
                encode(sha256(convert_to('[페이지 확인] 가상 기사 ' || n, 'UTF8')), 'hex'),
                '검증용 가상매체', at, 'ANALYZED', at, 'CLASSIFIED')
        RETURNING id INTO art;
        INSERT INTO issue (title, summary, category, first_published_at, last_updated_at, article_count,
                           publisher_count, status)
        VALUES ('[페이지 확인] 가상 이슈 ' || lpad(n::text, 2, '0'),
                '공개 뉴스 페이지 넘김 확인용 가상 이슈입니다. 실제 사건이 아닙니다.', 'OTHER', at, at, 1, 1, 'ACTIVE')
        RETURNING id INTO iss;
        INSERT INTO issue_article (issue_id, article_id, is_primary, classification_method, manually_corrected)
        VALUES (iss, art, true, 'MANUAL', true);
    END LOOP;
END $$;

SELECT count(*) AS public_issues FROM issue WHERE status IN ('ACTIVE', 'CLOSED');

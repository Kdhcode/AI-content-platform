-- Fix for V4 (already applied, so not edited): in ck_content_source_one_ref the EXTERNAL_URL branch only tested
-- external_url ~ '^https?://'. For a NULL url that test is NULL, the whole CHECK evaluates to NULL, and PostgreSQL
-- accepts a CHECK that is NULL, so an EXTERNAL_URL source without a URL could be stored.
-- Now the url must be present and look like http(s)://host... with no whitespace. Re-adding the constraint validates
-- existing rows; content was introduced in V4 and no environment holds EXTERNAL_URL sources yet.
ALTER TABLE content_source DROP CONSTRAINT ck_content_source_one_ref;
ALTER TABLE content_source ADD CONSTRAINT ck_content_source_one_ref CHECK (
        (source_kind = 'ISSUE'        AND issue_id IS NOT NULL AND news_article_id IS NULL AND external_url IS NULL)
     OR (source_kind = 'NEWS_ARTICLE' AND news_article_id IS NOT NULL AND issue_id IS NULL AND external_url IS NULL)
     OR (source_kind = 'EXTERNAL_URL' AND external_url IS NOT NULL AND external_url ~ '^https?://[^\s/?#]+[^\s]*$'
                                      AND issue_id IS NULL AND news_article_id IS NULL)
     OR (source_kind = 'MANUAL'       AND issue_id IS NULL AND news_article_id IS NULL AND external_url IS NULL
                                      AND source_label IS NOT NULL AND btrim(source_label) <> ''));

-- Issue titles follow the same AUTO / MANUAL rule as summaries:
--   AUTO   = title of the earliest member article, recomputed after every membership change (classify, merge, split, move)
--   MANUAL = written by an admin (issue edit, or a title given when splitting); automation never touches it
ALTER TABLE issue
    ADD COLUMN title_source TEXT NOT NULL DEFAULT 'AUTO' CHECK (title_source IN ('AUTO', 'MANUAL'));

-- Existing data: a title is MANUAL when an admin set it in an issue edit (audit log), or when no article has that exact
-- title (typed by hand, e.g. a split with a new title). A title that still equals some article's title, even one that
-- has since moved to another issue, stays AUTO, so it is corrected on the issue's next membership change.
UPDATE issue i SET title_source = 'MANUAL'
WHERE EXISTS (
          SELECT 1 FROM admin_audit_log l
          WHERE l.action = 'ISSUE_UPDATE' AND l.target_type = 'ISSUE' AND l.target_id = i.id::text
            AND l.after_state ->> 'title' IS NOT NULL AND l.after_state ->> 'title' <> 'null')
   OR NOT EXISTS (SELECT 1 FROM news_article a WHERE a.title = i.title);

COMMENT ON COLUMN issue.title_source IS
    'AUTO: title of the earliest member article, refreshed on membership change. MANUAL: set by an admin, never overwritten.';

-- A6 "origin issue changed" marker for content.
--
-- Comparing times (issue.updated_at / last_updated_at against content.origin_checked_at) misses changes:
--   * now() is the transaction START time, so a change committed after an acknowledgement can carry an older time;
--   * last_updated_at is the newest article's PUBLISH time, so adding an older article does not move it;
--   * updated_at also moves for changes a reader does not care about (centroid embedding, recounts).
-- Instead every issue gets a counter that only grows, raised by triggers on every path (services, batch jobs, SQL):
--   * the issue's own fields a content is written from: title, summary, category, status, merge target, key facts,
--     entities;
--   * its article membership: an article joining, leaving or moving (issue_article insert / delete / issue change).
ALTER TABLE issue
    ADD COLUMN change_seq    BIGINT      NOT NULL DEFAULT 0 CHECK (change_seq >= 0),
    ADD COLUMN change_seq_at TIMESTAMPTZ;

COMMENT ON COLUMN issue.change_seq IS
    'Raised on every change of title, summary, category, status, merge target, key facts, entities or article membership.';
COMMENT ON COLUMN issue.change_seq_at IS 'When change_seq was last raised.';

CREATE FUNCTION issue_raise_change_seq() RETURNS trigger AS $$
BEGIN
    IF (NEW.title, NEW.summary, NEW.category, NEW.status, NEW.merged_into_issue_id, NEW.key_facts, NEW.entities)
       IS DISTINCT FROM
       (OLD.title, OLD.summary, OLD.category, OLD.status, OLD.merged_into_issue_id, OLD.key_facts, OLD.entities) THEN
        NEW.change_seq = OLD.change_seq + 1;
        NEW.change_seq_at = now();
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_issue_change_seq BEFORE UPDATE ON issue
    FOR EACH ROW EXECUTE FUNCTION issue_raise_change_seq();

CREATE FUNCTION issue_article_raise_change_seq() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        UPDATE issue SET change_seq = change_seq + 1, change_seq_at = now() WHERE id = NEW.issue_id;
    END IF;
    IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND OLD.issue_id IS DISTINCT FROM NEW.issue_id) THEN
        UPDATE issue SET change_seq = change_seq + 1, change_seq_at = now() WHERE id = OLD.issue_id;
    END IF;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

-- membership only: re-classification details (llm_reason, ...) on an unchanged link do not count
CREATE TRIGGER trg_issue_article_change_seq AFTER INSERT OR DELETE ON issue_article
    FOR EACH ROW EXECUTE FUNCTION issue_article_raise_change_seq();
CREATE TRIGGER trg_issue_article_move_change_seq AFTER UPDATE OF issue_id, is_primary ON issue_article
    FOR EACH ROW WHEN (OLD.issue_id IS DISTINCT FROM NEW.issue_id OR OLD.is_primary IS DISTINCT FROM NEW.is_primary)
    EXECUTE FUNCTION issue_article_raise_change_seq();

-- Which origin change a content's editor has acknowledged. Unacknowledged when issue.change_seq > origin_ack_seq.
ALTER TABLE content ADD COLUMN origin_ack_seq BIGINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN content.origin_ack_seq IS
    'issue.change_seq of the origin issue that an admin last acknowledged (A6). -1: changed before V7, not acknowledged.';
COMMENT ON COLUMN content.origin_checked_at IS 'When the origin issue was last acknowledged (creation, or acknowledge-origin).';

-- Existing content: the counter starts at 0 for every issue, so history before V7 is not in it. The best evidence of
-- an earlier change is the issue row changing after the content was created / last checked (updated_at, which every
-- issue change moves), or the issue being merged / excluded. Such content starts as NOT acknowledged (-1); nothing
-- that may have changed is marked as seen. Content without that evidence starts at 0 (= the issue as it is now).
UPDATE content c SET origin_ack_seq = -1
FROM issue i
WHERE i.id = c.origin_issue_id
  AND (i.updated_at > c.origin_checked_at OR i.status IN ('MERGED', 'EXCLUDED'));

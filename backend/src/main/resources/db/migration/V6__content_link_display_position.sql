-- Related URLs (A5): display_position becomes a fixed value list, decided 2026-10-15:
--   BODY_TOP (above the body), BODY_BOTTOM (below the body, default), SOURCES (in the sources area).
-- Existing rows: a NULL position becomes BODY_BOTTOM (the new default). Any other value is left as it is, so the CHECK
-- below would stop this migration and a person decides; no row is deleted or guessed. (Checked 2026-10-15: no
-- environment has content_link rows yet; the column was free text with no API writing it.)
UPDATE content_link SET display_position = 'BODY_BOTTOM' WHERE display_position IS NULL;
ALTER TABLE content_link ALTER COLUMN display_position SET DEFAULT 'BODY_BOTTOM';
ALTER TABLE content_link ALTER COLUMN display_position SET NOT NULL;
ALTER TABLE content_link DROP CONSTRAINT content_link_display_position_check;
ALTER TABLE content_link ADD CONSTRAINT ck_content_link_display_position
    CHECK (display_position IN ('BODY_TOP', 'BODY_BOTTOM', 'SOURCES'));

-- Same URL rule as content_source since V5: http(s)://host..., no whitespace, at most 2000 characters
-- (V4 only required the '^https?://' prefix).
ALTER TABLE content_link DROP CONSTRAINT content_link_url_check;
ALTER TABLE content_link ADD CONSTRAINT ck_content_link_url
    CHECK (url ~ '^https?://[^\s/?#]+[^\s]*$' AND char_length(url) <= 2000);

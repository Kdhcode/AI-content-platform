-- NEWS / LIFE content written by admins from an issue (docs/CONTENT_A_PLAN.md). Only new tables: no existing row changes.
-- content.status is the editing / review state only. AI generation progress (async_job) and channel publishing are not
-- stored here, and nothing in this schema is read by the public newsroom API.

CREATE TABLE content (
    id                   BIGSERIAL PRIMARY KEY,
    content_type         TEXT        NOT NULL CHECK (content_type IN ('NEWS', 'LIFE')),
    status               TEXT        NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'REVIEW', 'APPROVED', 'HOLD', 'EXCLUDED', 'ARCHIVED')),
    title                TEXT        NOT NULL DEFAULT '' CHECK (char_length(title) <= 200),
    body                 JSONB       NOT NULL,
    version              BIGINT      NOT NULL DEFAULT 0 CHECK (version >= 0),
    origin_issue_id      BIGINT      NOT NULL REFERENCES issue (id),
    origin_checked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    creation_key         TEXT,
    creation_fingerprint TEXT,
    status_reason        TEXT        CHECK (status_reason IS NULL OR char_length(status_reason) <= 500),
    created_by           TEXT        NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by           TEXT        NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at         TIMESTAMPTZ,
    approved_by          TEXT,
    approved_at          TIMESTAMPTZ,
    CONSTRAINT ck_content_body_object CHECK (jsonb_typeof(body) = 'object'),
    -- a retried create (same Idempotency-Key) must find the first row; the fingerprint tells a retry from a misuse
    CONSTRAINT ux_content_creation_key UNIQUE (creation_key),
    CONSTRAINT ck_content_creation_key CHECK (creation_key IS NULL OR char_length(creation_key) BETWEEN 8 AND 100),
    CONSTRAINT ck_content_creation_pair CHECK ((creation_key IS NULL) = (creation_fingerprint IS NULL)),
    CONSTRAINT ck_content_approved CHECK (status <> 'APPROVED' OR (approved_at IS NOT NULL AND approved_by IS NOT NULL))
);
CREATE INDEX ix_content_status_updated ON content (status, updated_at DESC);
CREATE INDEX ix_content_type_status ON content (content_type, status);
CREATE INDEX ix_content_origin_issue ON content (origin_issue_id);

-- What a content is grounded on (N:M). Exactly one reference per row, matching its kind. TV_ITEM comes with Phase 4.
CREATE TABLE content_source (
    id                BIGSERIAL PRIMARY KEY,
    content_id        BIGINT      NOT NULL REFERENCES content (id) ON DELETE CASCADE,
    source_kind       TEXT        NOT NULL CHECK (source_kind IN ('ISSUE', 'NEWS_ARTICLE', 'EXTERNAL_URL', 'MANUAL')),
    issue_id          BIGINT      REFERENCES issue (id),
    news_article_id   BIGINT      REFERENCES news_article (id),
    external_url      TEXT        CHECK (external_url IS NULL OR char_length(external_url) <= 2000),
    source_label      TEXT        CHECK (source_label IS NULL OR char_length(source_label) <= 300),
    source_role       TEXT        NOT NULL DEFAULT 'SUPPORTING' CHECK (source_role IN ('PRIMARY', 'SUPPORTING', 'REFERENCE')),
    source_checked_at TIMESTAMPTZ,
    created_by        TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_source_one_ref CHECK (
        (source_kind = 'ISSUE'        AND issue_id IS NOT NULL AND news_article_id IS NULL AND external_url IS NULL)
     OR (source_kind = 'NEWS_ARTICLE' AND news_article_id IS NOT NULL AND issue_id IS NULL AND external_url IS NULL)
     OR (source_kind = 'EXTERNAL_URL' AND external_url ~ '^https?://' AND issue_id IS NULL AND news_article_id IS NULL)
     OR (source_kind = 'MANUAL'       AND issue_id IS NULL AND news_article_id IS NULL AND external_url IS NULL
                                      AND source_label IS NOT NULL AND btrim(source_label) <> ''))
);
CREATE INDEX ix_content_source_content ON content_source (content_id);
CREATE UNIQUE INDEX ux_content_source_issue ON content_source (content_id, issue_id) WHERE issue_id IS NOT NULL;
CREATE UNIQUE INDEX ux_content_source_article ON content_source (content_id, news_article_id) WHERE news_article_id IS NOT NULL;
CREATE UNIQUE INDEX ux_content_source_url ON content_source (content_id, external_url) WHERE external_url IS NOT NULL;

-- Related URLs (spec 02 §11: 상품·공식·지도·예약·구매·참고자료·기타) with per-channel visibility.
-- display_position values are decided with the link API (A5); the column is free text until then.
CREATE TABLE content_link (
    id               BIGSERIAL PRIMARY KEY,
    content_id       BIGINT      NOT NULL REFERENCES content (id) ON DELETE CASCADE,
    link_type        TEXT        NOT NULL
        CHECK (link_type IN ('PRODUCT', 'OFFICIAL', 'MAP', 'RESERVATION', 'PURCHASE', 'REFERENCE', 'OTHER')),
    title            TEXT        NOT NULL DEFAULT '' CHECK (char_length(title) <= 200),
    url              TEXT        NOT NULL CHECK (url ~ '^https?://' AND char_length(url) <= 2000),
    site_visible     BOOLEAN     NOT NULL DEFAULT TRUE,
    blog_visible     BOOLEAN     NOT NULL DEFAULT FALSE,
    display_position TEXT        CHECK (display_position IS NULL OR char_length(display_position) <= 30),
    sort_order       INTEGER     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_content_link_content ON content_link (content_id, sort_order);

COMMENT ON TABLE content IS 'Admin-made NEWS/LIFE content. Editing/review state only; never read by /api/public.';
COMMENT ON COLUMN content.version IS 'Incremented on every change; PATCH and status actions must send the current value (409 otherwise).';
COMMENT ON COLUMN content.origin_checked_at IS 'When an admin last took the origin issue into account (update-needed marker, A6).';
COMMENT ON COLUMN content.creation_fingerprint IS 'Hash of the create request; same Idempotency-Key with another fingerprint is refused.';

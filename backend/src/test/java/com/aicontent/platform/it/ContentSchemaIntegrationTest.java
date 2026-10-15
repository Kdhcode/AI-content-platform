package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** V4 constraints hold even for writes that bypass the service. */
class ContentSchemaIntegrationTest extends IntegrationTestBase {

    private long issueId;
    private long articleId;

    @BeforeEach
    void rows() throws Exception {
        collectSample();                                   // issue #1 with articles 1,2 and issue #2 with article 3
        issueId = 1;
        articleId = 1;
    }

    private long content(String extraCols, String extraVals) {
        return jdbc.sql("INSERT INTO content (content_type, body, origin_issue_id, created_by, updated_by" + extraCols
                + ") VALUES ('NEWS', '{\"schemaVersion\":1,\"sections\":[]}', 1, 't', 't'" + extraVals + ") RETURNING id")
                .query(Long.class).single();
    }

    private void fails(String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).update()).as(sql).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void contentRowsAreConstrained() {
        long id = content("", "");
        assertThat(jdbc.sql("SELECT status || '/' || version FROM content WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("DRAFT/0");
        String base = "INSERT INTO content (content_type, body, origin_issue_id, created_by, updated_by) VALUES ";
        fails(base + "('TV', '{}', 1, 't', 't')");                                   // type
        fails(base + "('NEWS', '[]', 1, 't', 't')");                                 // body must be an object
        fails(base + "('NEWS', '{}', 9999, 't', 't')");                              // origin issue must exist
        fails("UPDATE content SET status = 'PUBLISHED' WHERE id = " + id);           // publishing is not a content status
        fails("UPDATE content SET status = 'APPROVED' WHERE id = " + id);            // approval needs who/when
        fails("UPDATE content SET title = repeat('x', 201) WHERE id = " + id);
        fails("UPDATE content SET version = -1 WHERE id = " + id);
        fails("UPDATE content SET creation_key = 'only-key-0001' WHERE id = " + id); // key and fingerprint go together
        content(", creation_key, creation_fingerprint", ", 'unique-key-0001', 'fp'");
        assertThatThrownBy(() -> content(", creation_key, creation_fingerprint", ", 'unique-key-0001', 'fp'"))
                .isInstanceOf(DataIntegrityViolationException.class);                // one content per key
    }

    @Test
    void eachSourceRowCarriesExactlyTheReferenceItsKindNeeds() {
        long c = content("", "");
        String ins = "INSERT INTO content_source (content_id, source_kind, issue_id, news_article_id, external_url, source_label, created_by) VALUES ";
        jdbc.sql(ins + "(" + c + ", 'ISSUE', " + issueId + ", NULL, NULL, NULL, 't')").update();
        jdbc.sql(ins + "(" + c + ", 'NEWS_ARTICLE', NULL, " + articleId + ", NULL, NULL, 't')").update();
        jdbc.sql(ins + "(" + c + ", 'EXTERNAL_URL', NULL, NULL, 'https://example.test/a', NULL, 't')").update();
        jdbc.sql(ins + "(" + c + ", 'MANUAL', NULL, NULL, NULL, '현장 취재 메모', 't')").update();

        fails(ins + "(" + c + ", 'ISSUE', NULL, NULL, NULL, NULL, 't')");                            // missing reference
        fails(ins + "(" + c + ", 'ISSUE', " + issueId + ", " + articleId + ", NULL, NULL, 't')");    // two references
        fails(ins + "(" + c + ", 'EXTERNAL_URL', NULL, NULL, 'javascript:alert(1)', NULL, 't')");    // not http(s)
        // V5: a NULL url made the V4 CHECK evaluate to NULL, which PostgreSQL accepts
        fails(ins + "(" + c + ", 'EXTERNAL_URL', NULL, NULL, NULL, NULL, 't')");
        fails(ins + "(" + c + ", 'EXTERNAL_URL', NULL, NULL, NULL, '라벨만 있음', 't')");
        fails(ins + "(" + c + ", 'EXTERNAL_URL', NULL, NULL, '', NULL, 't')");
        fails(ins + "(" + c + ", 'EXTERNAL_URL', NULL, NULL, 'https://', NULL, 't')");                // no host
        fails(ins + "(" + c + ", 'EXTERNAL_URL', NULL, NULL, 'https://example.test/a b', NULL, 't')"); // whitespace
        jdbc.sql(ins + "(" + c + ", 'EXTERNAL_URL', NULL, NULL, 'http://example.test/path?q=1#f', NULL, 't')").update();
        fails(ins + "(" + c + ", 'MANUAL', NULL, NULL, NULL, '  ', 't')");                           // blank label
        fails(ins + "(" + c + ", 'TV_ITEM', NULL, NULL, NULL, 'x', 't')");                           // not in this phase
        fails(ins + "(" + c + ", 'ISSUE', " + issueId + ", NULL, NULL, NULL, 't')");                 // duplicate issue
        fails(ins + "(" + c + ", 'EXTERNAL_URL', NULL, NULL, 'https://example.test/a', NULL, 't')"); // duplicate URL
        fails("INSERT INTO content_source (content_id, source_kind, issue_id, source_role, created_by) VALUES ("
                + c + ", 'ISSUE', 2, 'MAIN', 't')");                                                    // role
    }

    @Test
    void linksNeedAnHttpUrlAndAKnownTypeAndGoAwayWithTheirContent() {
        long c = content("", "");
        jdbc.sql("INSERT INTO content_link (content_id, link_type, url) VALUES (" + c + ", 'MAP', 'https://map.example.test/x')").update();
        assertThat(jdbc.sql("SELECT site_visible || '/' || blog_visible FROM content_link").query(String.class).single())
                .isEqualTo("true/false");
        fails("INSERT INTO content_link (content_id, link_type, url) VALUES (" + c + ", 'MAP', 'ftp://x')");
        fails("INSERT INTO content_link (content_id, link_type, url) VALUES (" + c + ", 'COUPON', 'https://x.test')");
        jdbc.sql("INSERT INTO content_source (content_id, source_kind, issue_id, created_by) VALUES (" + c + ", 'ISSUE', 1, 't')").update();
        jdbc.sql("DELETE FROM content WHERE id = " + c).update();
        assertThat(count("SELECT count(*) FROM content_link")).isZero();
        assertThat(count("SELECT count(*) FROM content_source")).isZero();
        // issues and articles are untouched by content removal
        assertThat(count("SELECT count(*) FROM issue")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM news_article")).isEqualTo(3);
    }
}

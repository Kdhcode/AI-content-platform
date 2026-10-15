package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Sources (A4) and related URLs (A5) over HTTP. Sample data: issue #1 (articles 1, 2), issue #2 (article 3). */
@AutoConfigureMockMvc
class ContentRelationApiIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;

    private static final String READY_BODY = """
            {"schemaVersion": 1, "sections": [{"id": "s-1", "heading": "요약", "paragraphs": [{"id": "p-1", "text": "본문"}]}]}""";

    @BeforeEach
    void data() throws Exception {
        for (String[] u : new String[][] {{"op", "OPERATOR"}, {"root", "SYSTEM_ADMIN"}}) {
            jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES (:u, :h) ON CONFLICT (lower(username)) DO NOTHING")
                    .param("u", u[0]).param("h", encoder.encode(u[0] + "-pw")).update();
            jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, :r FROM app_user WHERE username = :u ON CONFLICT DO NOTHING")
                    .param("r", u[1]).param("u", u[0]).update();
        }
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES ('norole', :h) ON CONFLICT (lower(username)) DO NOTHING")
                .param("h", encoder.encode("norole-pw")).update();
        collectSample();
    }

    private MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder r) {
        return r.with(httpBasic(user, user + "-pw")).contentType(MediaType.APPLICATION_JSON);
    }

    /** A ready NEWS content from issue #1 (the origin source is its PRIMARY). */
    private long content() throws Exception {
        String res = mvc.perform(as("op", post("/api/admin/issues/1/contents")).header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"contentType\":\"NEWS\",\"title\":\"출처 테스트\",\"body\":" + READY_BODY + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.<Number>read(res, "$.data.id").longValue();
    }

    private ResultActions addSource(long id, long version, String fields) throws Exception {
        return mvc.perform(as("op", post("/api/admin/contents/" + id + "/sources")).content("{\"version\":" + version + "," + fields + "}"));
    }

    private ResultActions addLink(long id, long version, String fields) throws Exception {
        return mvc.perform(as("op", post("/api/admin/contents/" + id + "/links")).content("{\"version\":" + version + "," + fields + "}"));
    }

    private long itemId(ResultActions r) throws Exception {
        return com.jayway.jsonpath.JsonPath.<Number>read(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.data.itemId").longValue();
    }

    private long version(long id) {
        return jdbc.sql("SELECT version FROM content WHERE id = :id").param("id", id).query(Long.class).single();
    }

    private String state(long id) {
        return jdbc.sql("SELECT status || '/' || version || '/' || (approved_at IS NOT NULL) || '/' || (submitted_at IS NOT NULL) FROM content WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    @Test
    void allFourSourceKindsCanBeAddedChangedAndRemovedButTheOriginSourceStays() throws Exception {
        long id = content();
        long issueSrc = itemId(addSource(id, 0, "\"kind\":\"ISSUE\",\"issueId\":2").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.statusChanged").value(false))
                .andExpect(jsonPath("$.data.content.version").value(1)));
        long articleSrc = itemId(addSource(id, 1, "\"kind\":\"NEWS_ARTICLE\",\"newsArticleId\":3,\"role\":\"PRIMARY\",\"label\":\" 핵심 기사 \"")
                .andExpect(status().isCreated()));
        long urlSrc = itemId(addSource(id, 2, "\"kind\":\"EXTERNAL_URL\",\"externalUrl\":\"  HTTPS://Example.test/report?a=1  \",\"role\":\"REFERENCE\"")
                .andExpect(status().isCreated()));
        long manualSrc = itemId(addSource(id, 3, "\"kind\":\"MANUAL\",\"label\":\"현장 취재 메모\"").andExpect(status().isCreated()));

        String detail = mvc.perform(as("op", get("/api/admin/contents/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.sources.length()").value(5))
                .andExpect(jsonPath("$.data.sources[0].role").value("PRIMARY"))           // PRIMARY first, then SUPPORTING, REFERENCE
                .andExpect(jsonPath("$.data.sources[4].role").value("REFERENCE"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(detail).contains("https://Example.test/report?a=1", "핵심 기사\"");     // scheme lower-cased, label trimmed
        mvc.perform(as("op", get("/api/admin/contents/" + id + "/sources"))).andExpect(jsonPath("$.data.length()").value(5));

        // role / label change; the same values again change nothing
        mvc.perform(as("op", patch("/api/admin/contents/" + id + "/sources/" + issueSrc)).content("{\"version\":4,\"role\":\"REFERENCE\",\"label\":\"배경\"}"))
                .andExpect(jsonPath("$.data.changed").value(true)).andExpect(jsonPath("$.data.content.version").value(5));
        mvc.perform(as("op", patch("/api/admin/contents/" + id + "/sources/" + issueSrc)).content("{\"version\":5,\"role\":\"REFERENCE\",\"label\":\"배경\"}"))
                .andExpect(jsonPath("$.data.changed").value(false)).andExpect(jsonPath("$.data.content.version").value(5));
        // the label of a non-manual source can be cleared, a manual source must keep one
        mvc.perform(as("op", patch("/api/admin/contents/" + id + "/sources/" + issueSrc)).content("{\"version\":5,\"label\":\"\"}"))
                .andExpect(jsonPath("$.data.changed").value(true));
        mvc.perform(as("op", patch("/api/admin/contents/" + id + "/sources/" + manualSrc)).content("{\"version\":6,\"label\":\"  \"}"))
                .andExpect(status().isBadRequest());

        // removing
        mvc.perform(as("op", delete("/api/admin/contents/" + id + "/sources/" + urlSrc + "?version=6"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.sources.length()").value(4));
        mvc.perform(as("op", delete("/api/admin/contents/" + id + "/sources/" + urlSrc + "?version=7"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONTENT_SOURCE_NOT_FOUND"));

        // the origin issue's source: role may change, removal is refused, origin_issue_id never moves
        long originSrc = jdbc.sql("SELECT id FROM content_source WHERE content_id = :c AND issue_id = 1").param("c", id).query(Long.class).single();
        mvc.perform(as("op", delete("/api/admin/contents/" + id + "/sources/" + originSrc + "?version=7"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_SOURCE_PROTECTED"));
        mvc.perform(as("op", patch("/api/admin/contents/" + id + "/sources/" + originSrc)).content("{\"version\":7,\"role\":\"SUPPORTING\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT origin_issue_id FROM content WHERE id = :id").param("id", id).query(Long.class).single()).isEqualTo(1);
        assertThat(articleSrc).isPositive();
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action LIKE 'CONTENT_SOURCE_%'")).isEqualTo(8);   // 4 adds, 3 updates, 1 removal (the no-op update wrote nothing)
    }

    @Test
    void sourceReferencesAreCheckedPerKind() throws Exception {
        long id = content();
        jdbc.sql("UPDATE news_article SET status = 'DUPLICATE', duplicate_of_article_id = 1 WHERE id = 2").update();
        jdbc.sql("UPDATE issue SET status = 'REVIEW' WHERE id = 2").update();
        // wrong or missing reference fields for the kind
        addSource(id, 0, "\"kind\":\"ISSUE\",\"externalUrl\":\"https://x.test\"").andExpect(status().isBadRequest());
        addSource(id, 0, "\"kind\":\"ISSUE\",\"issueId\":2,\"newsArticleId\":1").andExpect(status().isBadRequest());
        addSource(id, 0, "\"kind\":\"NEWS_ARTICLE\"").andExpect(status().isBadRequest());
        addSource(id, 0, "\"kind\":\"MANUAL\",\"issueId\":2,\"label\":\"x\"").andExpect(status().isBadRequest());
        addSource(id, 0, "\"kind\":\"TV_ITEM\",\"label\":\"x\"").andExpect(status().isBadRequest());
        addSource(id, 0, "\"kind\":\"MANUAL\",\"label\":\"x\",\"role\":\"MAIN\"").andExpect(status().isBadRequest());
        // references that do not exist or may not be used
        addSource(id, 0, "\"kind\":\"ISSUE\",\"issueId\":999").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ISSUE_NOT_FOUND"));
        addSource(id, 0, "\"kind\":\"ISSUE\",\"issueId\":2").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ISSUE_STATE_INVALID"));
        addSource(id, 0, "\"kind\":\"NEWS_ARTICLE\",\"newsArticleId\":999").andExpect(status().isNotFound());
        addSource(id, 0, "\"kind\":\"NEWS_ARTICLE\",\"newsArticleId\":2").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_SOURCE_INVALID")).andExpect(jsonPath("$.error.details.duplicateOfArticleId").value(1));
        // duplicates of what the content already has (the origin issue counts)
        addSource(id, 0, "\"kind\":\"ISSUE\",\"issueId\":1").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTENT_SOURCE_DUPLICATE"));
        addSource(id, 0, "\"kind\":\"NEWS_ARTICLE\",\"newsArticleId\":1").andExpect(status().isCreated());
        addSource(id, 1, "\"kind\":\"NEWS_ARTICLE\",\"newsArticleId\":1").andExpect(status().isConflict());
        addSource(id, 1, "\"kind\":\"EXTERNAL_URL\",\"externalUrl\":\"https://x.test/a\"").andExpect(status().isCreated());
        addSource(id, 2, "\"kind\":\"EXTERNAL_URL\",\"externalUrl\":\" https://x.test/a \"").andExpect(status().isConflict());
        // URLs and manual descriptions
        for (String bad : List.of("\"\"", "\"   \"", "\"ftp://x.test\"", "\"javascript:alert(1)\"", "\"https://\"", "\"https://x.test/a b\"", "\"x.test\"")) {
            addSource(id, 2, "\"kind\":\"EXTERNAL_URL\",\"externalUrl\":" + bad).andExpect(status().isBadRequest());
        }
        addSource(id, 2, "\"kind\":\"EXTERNAL_URL\"").andExpect(status().isBadRequest());
        addSource(id, 2, "\"kind\":\"MANUAL\",\"label\":\"   \"").andExpect(status().isBadRequest());
        addSource(id, 2, "\"kind\":\"MANUAL\"").andExpect(status().isBadRequest());
        addSource(id, 2, "\"kind\":\"MANUAL\",\"label\":\"" + "가".repeat(301) + "\"").andExpect(status().isBadRequest());
        assertThat(version(id)).isEqualTo(2);                                              // only the two successful adds
        assertThat(count("SELECT count(*) FROM content_source WHERE content_id = " + id)).isEqualTo(3);
    }

    @Test
    void relatedUrlsKeepTheirSettingsAndOrder() throws Exception {
        long id = content();
        long a = itemId(addLink(id, 0, "\"linkType\":\"OFFICIAL\",\"title\":\"공식 안내\",\"url\":\"https://gov.example.test/notice\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content.links[0].siteVisible").value(true))
                .andExpect(jsonPath("$.data.content.links[0].blogVisible").value(false))
                .andExpect(jsonPath("$.data.content.links[0].displayPosition").value("BODY_BOTTOM"))
                .andExpect(jsonPath("$.data.content.links[0].sortOrder").value(0)));
        long b = itemId(addLink(id, 1, "\"linkType\":\"MAP\",\"url\":\"https://map.example.test/x\",\"siteVisible\":false,\"blogVisible\":true,\"displayPosition\":\"BODY_TOP\"")
                .andExpect(status().isCreated()));
        long c = itemId(addLink(id, 2, "\"linkType\":\"REFERENCE\",\"url\":\"http://ref.example.test\",\"displayPosition\":\"SOURCES\"")
                .andExpect(status().isCreated()));
        mvc.perform(as("op", get("/api/admin/contents/" + id + "/links"))).andExpect(jsonPath("$.data[*].id").value(org.hamcrest.Matchers.contains((int) a, (int) b, (int) c)));

        // partial update keeps the other fields; the same values change nothing
        mvc.perform(as("op", patch("/api/admin/contents/" + id + "/links/" + b)).content("{\"version\":3,\"blogVisible\":false,\"title\":\"지도\"}"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.content.links[1].siteVisible").value(false))
                .andExpect(jsonPath("$.data.content.links[1].displayPosition").value("BODY_TOP"))
                .andExpect(jsonPath("$.data.content.links[1].title").value("지도"));
        mvc.perform(as("op", patch("/api/admin/contents/" + id + "/links/" + b)).content("{\"version\":4,\"blogVisible\":false,\"title\":\"지도\"}"))
                .andExpect(jsonPath("$.data.changed").value(false)).andExpect(jsonPath("$.data.content.version").value(4));
        // invalid values
        for (String bad : List.of("\"displayPosition\":\"SIDEBAR\"", "\"linkType\":\"COUPON\"", "\"url\":\"ftp://x.test\"", "\"url\":\"\"",
                "\"url\":\"https://x.test/a b\"", "\"title\":\"" + "x".repeat(201) + "\"")) {
            mvc.perform(as("op", patch("/api/admin/contents/" + id + "/links/" + b)).content("{\"version\":4," + bad + "}")).andExpect(status().isBadRequest());
        }
        addLink(id, 4, "\"linkType\":\"MAP\"").andExpect(status().isBadRequest());                       // url required
        addLink(id, 4, "\"linkType\":\"MAP\",\"url\":null").andExpect(status().isBadRequest());

        // reordering: the full set, each once
        mvc.perform(as("op", put("/api/admin/contents/" + id + "/links/order")).content("{\"version\":4,\"linkIds\":[" + c + "," + a + "," + b + "]}"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.content.links[*].id").value(org.hamcrest.Matchers.contains((int) c, (int) a, (int) b)))
                .andExpect(jsonPath("$.data.content.version").value(5));
        mvc.perform(as("op", put("/api/admin/contents/" + id + "/links/order")).content("{\"version\":5,\"linkIds\":[" + c + "," + a + "," + b + "]}"))
                .andExpect(jsonPath("$.data.changed").value(false)).andExpect(jsonPath("$.data.content.version").value(5));
        for (String bad : List.of("[" + c + "," + a + "," + a + "," + b + "]", "[" + c + "," + a + "]", "[" + c + "," + a + "," + b + ",999]", "[]", "[" + c + "," + a + ",null]")) {
            mvc.perform(as("op", put("/api/admin/contents/" + id + "/links/order")).content("{\"version\":5,\"linkIds\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as("op", put("/api/admin/contents/" + id + "/links/order")).content("{\"version\":5,\"linkIds\":[" + c + "," + a + "]}"))
                .andExpect(jsonPath("$.error.details.missingIds[0]").value(b));
        assertThat(jdbc.sql("SELECT string_agg(id::text, ',' ORDER BY sort_order, id) FROM content_link WHERE content_id = :c")
                .param("c", id).query(String.class).single()).isEqualTo(c + "," + a + "," + b);       // nothing moved
        assertThat(version(id)).isEqualTo(5);

        mvc.perform(as("op", delete("/api/admin/contents/" + id + "/links/" + a + "?version=5"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.links.length()").value(2));
        mvc.perform(as("op", put("/api/admin/contents/" + id + "/links/order")).content("{\"version\":6,\"linkIds\":[" + c + "," + b + "]}"))
                .andExpect(jsonPath("$.data.changed").value(false));                                 // same order after the removal
    }

    @Test
    void itemsOfAnotherContentCannotBeTouched() throws Exception {
        long mine = content(), other = content();
        long otherSource = itemId(addSource(other, 0, "\"kind\":\"MANUAL\",\"label\":\"남의 출처\""));
        long otherLink = itemId(addLink(other, 1, "\"linkType\":\"MAP\",\"url\":\"https://x.test\""));
        mvc.perform(as("op", patch("/api/admin/contents/" + mine + "/sources/" + otherSource)).content("{\"version\":0,\"role\":\"PRIMARY\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(as("op", delete("/api/admin/contents/" + mine + "/sources/" + otherSource + "?version=0"))).andExpect(status().isNotFound());
        mvc.perform(as("op", patch("/api/admin/contents/" + mine + "/links/" + otherLink)).content("{\"version\":0,\"title\":\"x\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("CONTENT_LINK_NOT_FOUND"));
        mvc.perform(as("op", delete("/api/admin/contents/" + mine + "/links/" + otherLink + "?version=0"))).andExpect(status().isNotFound());
        mvc.perform(as("op", put("/api/admin/contents/" + mine + "/links/order")).content("{\"version\":0,\"linkIds\":[" + otherLink + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.unknownIds[0]").value(otherLink));
        assertThat(version(mine)).isZero();
        assertThat(version(other)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT source_role FROM content_source WHERE id = :id").param("id", otherSource).query(String.class).single()).isEqualTo("SUPPORTING");
    }

    @Test
    void sourceAndLinkChangesFollowTheReviewRules() throws Exception {
        // a draft may have no PRIMARY source, but cannot be submitted that way
        long draft = content();
        long origin = jdbc.sql("SELECT id FROM content_source WHERE content_id = :c").param("c", draft).query(Long.class).single();
        mvc.perform(as("op", patch("/api/admin/contents/" + draft + "/sources/" + origin)).content("{\"version\":0,\"role\":\"SUPPORTING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.status").value("DRAFT"));
        mvc.perform(as("op", post("/api/admin/contents/" + draft + "/submit")).content("{\"version\":1}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.details.violations[0].path").value("sources"));

        // REVIEW: adding a source sends it back to DRAFT
        long review = content();
        mvc.perform(as("op", post("/api/admin/contents/" + review + "/submit")).content("{\"version\":0}")).andExpect(status().isOk());
        addSource(review, 1, "\"kind\":\"MANUAL\",\"label\":\"보강 자료\"").andExpect(jsonPath("$.data.statusChanged").value(true))
                .andExpect(jsonPath("$.data.content.status").value("DRAFT"));
        assertThat(state(review)).isEqualTo("DRAFT/2/false/false");

        // APPROVED: an unchanged link request keeps the approval, a real change revokes it
        long approved = content();
        long link = itemId(addLink(approved, 0, "\"linkType\":\"OFFICIAL\",\"url\":\"https://x.test\""));
        mvc.perform(as("op", post("/api/admin/contents/" + approved + "/submit")).content("{\"version\":1}")).andExpect(status().isOk());
        mvc.perform(as("root", post("/api/admin/contents/" + approved + "/approve")).content("{\"version\":2}")).andExpect(status().isOk());
        mvc.perform(as("op", patch("/api/admin/contents/" + approved + "/links/" + link)).content("{\"version\":3,\"siteVisible\":true}"))
                .andExpect(jsonPath("$.data.changed").value(false));
        mvc.perform(as("op", put("/api/admin/contents/" + approved + "/links/order")).content("{\"version\":3,\"linkIds\":[" + link + "]}"))
                .andExpect(jsonPath("$.data.changed").value(false));
        assertThat(state(approved)).isEqualTo("APPROVED/3/true/true");
        mvc.perform(as("op", patch("/api/admin/contents/" + approved + "/links/" + link)).content("{\"version\":3,\"displayPosition\":\"BODY_TOP\"}"))
                .andExpect(jsonPath("$.data.statusChanged").value(true)).andExpect(jsonPath("$.data.content.status").value("DRAFT"));
        assertThat(state(approved)).isEqualTo("DRAFT/4/false/false");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_APPROVAL_REVOKED_BY_EDIT' AND target_id = '" + approved + "'"))
                .isEqualTo(1);

        // locked states refuse every change; stale versions are refused
        mvc.perform(as("op", post("/api/admin/contents/" + approved + "/hold")).content("{\"version\":4,\"reason\":\"검토\"}")).andExpect(status().isOk());
        addSource(approved, 5, "\"kind\":\"MANUAL\",\"label\":\"x\"").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTENT_STATE_INVALID"));
        addLink(approved, 5, "\"linkType\":\"MAP\",\"url\":\"https://x.test\"").andExpect(status().isConflict());
        mvc.perform(as("op", delete("/api/admin/contents/" + approved + "/links/" + link + "?version=5"))).andExpect(status().isConflict());
        addSource(review, 1, "\"kind\":\"MANUAL\",\"label\":\"늦은 저장\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_VERSION_CONFLICT")).andExpect(jsonPath("$.error.details.currentVersion").value(2));
    }

    @Test
    void onlyAdminsManageItemsAndNothingReachesThePublicNewsroom() throws Exception {
        long id = content();
        mvc.perform(get("/api/admin/contents/" + id + "/sources")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/contents/" + id + "/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"linkType\":\"MAP\",\"url\":\"https://x.test\"}")).andExpect(status().isUnauthorized());
        mvc.perform(as("norole", get("/api/admin/contents/" + id + "/links"))).andExpect(status().isForbidden());
        mvc.perform(as("norole", put("/api/admin/contents/" + id + "/links/order")).content("{\"version\":0,\"linkIds\":[]}")).andExpect(status().isForbidden());
        mvc.perform(as("op", get("/api/admin/contents/999/links"))).andExpect(status().isNotFound());

        addLink(id, 0, "\"linkType\":\"PURCHASE\",\"title\":\"비공개 링크\",\"url\":\"https://shop.example.test/secret\",\"blogVisible\":true");
        addSource(id, 1, "\"kind\":\"MANUAL\",\"label\":\"비공개 출처 메모\"");
        mvc.perform(as("op", post("/api/admin/contents/" + id + "/submit")).content("{\"version\":2}")).andExpect(status().isOk());
        mvc.perform(as("root", post("/api/admin/contents/" + id + "/approve")).content("{\"version\":3}")).andExpect(status().isOk());
        String publicJson = mvc.perform(get("/api/public/issues/1")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                + mvc.perform(get("/api/public/issues")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(publicJson).doesNotContain("shop.example.test", "비공개 링크", "비공개 출처 메모", "출처 테스트");
    }
}

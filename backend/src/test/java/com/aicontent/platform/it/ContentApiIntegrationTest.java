package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Manual content API (bundle A2) over HTTP with the real security chain. */
@AutoConfigureMockMvc
class ContentApiIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;

    private static final String BODY = """
            {"schemaVersion": 1, "sections": [{"id": "s-1", "heading": "핵심 요약", "paragraphs": [{"id": "p-1", "text": "첫 문단"}]}]}""";

    @BeforeEach
    void users() {
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES ('op', :h) ON CONFLICT (lower(username)) DO NOTHING")
                .param("h", encoder.encode("op-pw")).update();
        jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, 'OPERATOR' FROM app_user WHERE username = 'op' ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES ('norole', :h) ON CONFLICT (lower(username)) DO NOTHING")
                .param("h", encoder.encode("norole-pw")).update();
    }

    private long issue(String status) {
        long id = jdbc.sql("INSERT INTO issue (title, status) VALUES (:t, 'ACTIVE') RETURNING id")
                .param("t", "가상시 이슈 " + status).query(Long.class).single();
        if ("MERGED".equals(status)) {
            long target = jdbc.sql("INSERT INTO issue (title, status) VALUES ('병합 대상', 'ACTIVE') RETURNING id").query(Long.class).single();
            jdbc.sql("UPDATE issue SET status = 'MERGED', merged_into_issue_id = :t WHERE id = :id").param("t", target).param("id", id).update();
        } else {
            jdbc.sql("UPDATE issue SET status = :s WHERE id = :id").param("s", status).param("id", id).update();
        }
        return id;
    }

    private MockHttpServletRequestBuilder asOp(MockHttpServletRequestBuilder r) {
        return r.with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions create(long issueId, String key, String json) throws Exception {
        MockHttpServletRequestBuilder r = asOp(post("/api/admin/issues/" + issueId + "/contents")).content(json);
        if (key != null) r = r.header("Idempotency-Key", key);
        return mvc.perform(r);
    }

    private long createdId(long issueId, String type) throws Exception {
        String res = create(issueId, UUID.randomUUID().toString(), "{\"contentType\":\"" + type + "\",\"title\":\"" + type + " 초안\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.<Number>read(res, "$.data.id").longValue();
    }

    @Test
    void newsAndLifeDraftsAreCreatedFromActiveOrClosedIssuesWithThePrimarySource() throws Exception {
        long active = issue("ACTIVE"), closed = issue("CLOSED");
        create(active, "key-news-0001", "{\"contentType\":\"NEWS\",\"title\":\"가상시 화재 정리\",\"body\":" + BODY + "}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.data.contentType").value("NEWS"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.title").value("가상시 화재 정리"))
                .andExpect(jsonPath("$.data.body.sections[0].paragraphs[0].text").value("첫 문단"))
                .andExpect(jsonPath("$.data.originIssue.id").value(active))
                .andExpect(jsonPath("$.data.originIssue.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.sources.length()").value(1))
                .andExpect(jsonPath("$.data.sources[0].kind").value("ISSUE"))
                .andExpect(jsonPath("$.data.sources[0].role").value("PRIMARY"))
                .andExpect(jsonPath("$.data.sources[0].issueId").value(active))
                .andExpect(jsonPath("$.data.createdBy").value("op"));
        // empty title and body are fine for a draft
        create(closed, "key-life-0001", "{\"contentType\":\"LIFE\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentType").value("LIFE"))
                .andExpect(jsonPath("$.data.title").value(""))
                .andExpect(jsonPath("$.data.body.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.body.sections.length()").value(0));
        // several contents of the same type from one issue are allowed (new key each time)
        create(active, "key-news-0002", "{\"contentType\":\"NEWS\"}").andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM content")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM content_source")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_CREATE' AND actor = 'op'")).isEqualTo(3);
    }

    @Test
    void reviewExcludedAndMergedIssuesAreRefused() throws Exception {
        for (String s : new String[] {"REVIEW", "EXCLUDED", "MERGED"}) {
            create(issue(s), "key-refused-" + s, "{\"contentType\":\"NEWS\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ISSUE_STATE_INVALID"));
        }
        create(9999, "key-missing-issue", "{\"contentType\":\"NEWS\"}").andExpect(status().isNotFound());
        create(issue("ACTIVE"), "key-bad-type-01", "{\"contentType\":\"TV\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        create(issue("ACTIVE"), null, "{\"contentType\":\"NEWS\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        create(issue("ACTIVE"), "short", "{\"contentType\":\"NEWS\"}").andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM content")).isZero();
        assertThat(count("SELECT count(*) FROM content_source")).isZero();
    }

    @Test
    void aRetriedRequestReturnsTheFirstContentAndAReusedKeyIsRefused() throws Exception {
        long a = issue("ACTIVE"), b = issue("ACTIVE");
        String req = "{\"contentType\":\"NEWS\",\"title\":\"같은 요청\",\"body\":" + BODY + "}";
        String first = create(a, "retry-key-0001", req).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long id = com.jayway.jsonpath.JsonPath.<Number>read(first, "$.data.id").longValue();
        // same request again (key order of the JSON may differ) -> 200 with the same content
        create(a, "retry-key-0001", "{\"body\":" + BODY + ",\"title\":\"같은 요청\",\"contentType\":\"NEWS\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.data.id").value(id));
        // the same key with another issue, type, title or body -> 409
        create(b, "retry-key-0001", req).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        create(a, "retry-key-0001", req.replace("NEWS", "LIFE")).andExpect(status().isConflict());
        create(a, "retry-key-0001", req.replace("같은 요청", "다른 제목")).andExpect(status().isConflict());
        create(a, "retry-key-0001", req.replace("첫 문단", "다른 문단"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.details.contentId").value(id));
        assertThat(count("SELECT count(*) FROM content")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM content_source")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_CREATE'")).isEqualTo(1);
    }

    @Test
    void listFiltersPagesAndDetail() throws Exception {
        long a = issue("ACTIVE"), c = issue("CLOSED");
        long n1 = createdId(a, "NEWS"), n2 = createdId(a, "NEWS"), l1 = createdId(c, "LIFE");
        jdbc.sql("UPDATE content SET status = 'HOLD' WHERE id = :id").param("id", n2).update();

        mvc.perform(asOp(get("/api/admin/contents"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3));
        mvc.perform(asOp(get("/api/admin/contents?type=LIFE"))).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(l1))
                .andExpect(jsonPath("$.data.items[0].originIssueStatus").value("CLOSED"));
        mvc.perform(asOp(get("/api/admin/contents?status=HOLD"))).andExpect(jsonPath("$.data.items[0].id").value(n2));
        mvc.perform(asOp(get("/api/admin/contents?issueId=" + a))).andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(asOp(get("/api/admin/contents?q=LIFE"))).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(asOp(get("/api/admin/contents?q=%25"))).andExpect(jsonPath("$.data.totalElements").value(0));   // literal %
        mvc.perform(asOp(get("/api/admin/contents?size=2&page=1&sort=id,asc")))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(l1))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        mvc.perform(asOp(get("/api/admin/contents?type=TV"))).andExpect(status().isBadRequest());
        mvc.perform(asOp(get("/api/admin/contents?sort=body"))).andExpect(status().isBadRequest());

        mvc.perform(asOp(get("/api/admin/contents/" + n1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.originIssue.title").value("가상시 이슈 ACTIVE"))
                .andExpect(jsonPath("$.data.sources[0].role").value("PRIMARY"));
        mvc.perform(asOp(get("/api/admin/contents/9999")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("CONTENT_NOT_FOUND"));
    }

    @Test
    void editKeepsOmittedFieldsBumpsTheVersionAndDetectsConflicts() throws Exception {
        long id = createdId(issue("ACTIVE"), "NEWS");
        // title only: body stays
        mvc.perform(asOp(patch("/api/admin/contents/" + id)).content("{\"version\":0,\"title\":\"새 제목\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.version").value(1))
                .andExpect(jsonPath("$.data.content.title").value("새 제목"))
                .andExpect(jsonPath("$.data.content.body.sections.length()").value(0))
                .andExpect(jsonPath("$.data.statusChanged").value(false));
        // body only: title stays
        mvc.perform(asOp(patch("/api/admin/contents/" + id)).content("{\"version\":1,\"body\":" + BODY + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.version").value(2))
                .andExpect(jsonPath("$.data.content.title").value("새 제목"))
                .andExpect(jsonPath("$.data.content.body.sections[0].heading").value("핵심 요약"));
        // stale editor (still on version 1) -> 409 with what is needed to reload
        mvc.perform(asOp(patch("/api/admin/contents/" + id)).content("{\"version\":1,\"title\":\"늦은 저장\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.currentVersion").value(2))
                .andExpect(jsonPath("$.error.details.sentVersion").value(1))
                .andExpect(jsonPath("$.error.details.updatedBy").value("op"))
                .andExpect(jsonPath("$.error.details.updatedAt").isNotEmpty());
        // saving the same values again changes nothing
        mvc.perform(asOp(patch("/api/admin/contents/" + id)).content("{\"version\":2,\"title\":\"새 제목\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.version").value(2));
        // nothing to save, or no version
        mvc.perform(asOp(patch("/api/admin/contents/" + id)).content("{\"version\":2}")).andExpect(status().isBadRequest());
        mvc.perform(asOp(patch("/api/admin/contents/" + id)).content("{\"title\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(asOp(patch("/api/admin/contents/9999")).content("{\"version\":0,\"title\":\"x\"}")).andExpect(status().isNotFound());

        assertThat(jdbc.sql("SELECT title FROM content WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("새 제목");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_UPDATE'")).isEqualTo(2);
    }

    @Test
    void invalidBodiesAreRefusedWithTheirLocation() throws Exception {
        long active = issue("ACTIVE");
        String bad = "{\"schemaVersion\":1,\"sections\":[{\"id\":\"s-1\",\"heading\":\"h\",\"paragraphs\":[{\"id\":\"s-1\",\"text\":\"t\",\"html\":\"<b>\"}]}]}";
        create(active, "key-bad-body-01", "{\"contentType\":\"NEWS\",\"body\":" + bad + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CONTENT_BODY_INVALID"))
                .andExpect(jsonPath("$.error.details.violations[*].path",
                        hasItems("body.sections[0].paragraphs[0].html", "body.sections[0].paragraphs[0].id")));
        long id = createdId(active, "NEWS");
        String tooLong = "{\"schemaVersion\":1,\"sections\":[{\"id\":\"s-1\",\"heading\":\"h\",\"paragraphs\":[{\"id\":\"p-1\",\"text\":\""
                + "x".repeat(5_001) + "\"}]}]}";
        mvc.perform(asOp(patch("/api/admin/contents/" + id)).content("{\"version\":0,\"body\":" + tooLong + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].path").value("body.sections[0].paragraphs[0].text"));
        mvc.perform(asOp(patch("/api/admin/contents/" + id)).content("{\"version\":0,\"title\":\"" + "가".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT version FROM content WHERE id = :id").param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void editingAnApprovedOrReviewContentSendsItBackToDraftAndLockedStatesRefuseEdits() throws Exception {
        long approved = createdId(issue("ACTIVE"), "NEWS");
        jdbc.sql("UPDATE content SET status = 'APPROVED', approved_by = 'root', approved_at = now(), submitted_at = now() WHERE id = :id")
                .param("id", approved).update();
        mvc.perform(asOp(patch("/api/admin/contents/" + approved)).content("{\"version\":0,\"title\":\"승인 후 수정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusChanged").value(true))
                .andExpect(jsonPath("$.data.content.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.content.approvedAt").doesNotExist())
                .andExpect(jsonPath("$.data.content.submittedAt").doesNotExist());
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_APPROVAL_REVOKED_BY_EDIT'")).isEqualTo(1);

        long review = createdId(issue("ACTIVE"), "LIFE");
        jdbc.sql("UPDATE content SET status = 'REVIEW', submitted_at = now() WHERE id = :id").param("id", review).update();
        mvc.perform(asOp(patch("/api/admin/contents/" + review)).content("{\"version\":0,\"title\":\"검수 중 수정\"}"))
                .andExpect(jsonPath("$.data.content.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.statusChanged").value(true));

        for (String locked : new String[] {"HOLD", "EXCLUDED", "ARCHIVED"}) {
            long id = createdId(issue("ACTIVE"), "NEWS");
            jdbc.sql("UPDATE content SET status = :s WHERE id = :id").param("s", locked).param("id", id).update();
            mvc.perform(asOp(patch("/api/admin/contents/" + id)).content("{\"version\":0,\"title\":\"x\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONTENT_STATE_INVALID"));
        }
    }

    @Test
    void onlyAdminsReachTheContentApiAndTheNewsroomStaysIssueOnly() throws Exception {
        long active = issue("ACTIVE");
        long id = createdId(active, "NEWS");
        // no login -> 401, logged in without an admin role -> 403
        mvc.perform(get("/api/admin/contents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/contents/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/issues/" + active + "/contents").header("Idempotency-Key", "anon-key-0001")
                .contentType(MediaType.APPLICATION_JSON).content("{\"contentType\":\"NEWS\"}")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/admin/contents/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/contents").with(httpBasic("norole", "norole-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/contents").with(httpBasic("op", "wrong"))).andExpect(status().isUnauthorized());

        // even an APPROVED content is not part of the public newsroom, which still lists issues only
        String before = mvc.perform(get("/api/public/issues/" + active)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.sql("UPDATE content SET status = 'APPROVED', approved_by = 'root', approved_at = now() WHERE id = :id").param("id", id).update();
        String after = mvc.perform(get("/api/public/issues/" + active)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(after).isEqualTo(before).doesNotContain("NEWS 초안");
        assertThat(mvc.perform(get("/api/public/issues")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("NEWS 초안");
        mvc.perform(get("/api/public/contents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public/contents/" + id)).andExpect(status().isUnauthorized());
        assertThat(count("SELECT count(*) FROM content")).isEqualTo(1);
    }
}

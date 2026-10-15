package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Content status actions (A3) over HTTP: roles, transitions, readiness, reasons, review-record columns, audit. */
@AutoConfigureMockMvc
class ContentStatusApiIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;

    private static final String READY_BODY = """
            {"schemaVersion": 1, "sections": [{"id": "s-1", "heading": "핵심 요약", "paragraphs": [{"id": "p-1", "text": "첫 문단"}]}]}""";

    @BeforeEach
    void users() {
        for (String[] u : new String[][] {{"op", "OPERATOR"}, {"root", "SYSTEM_ADMIN"}}) {
            jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES (:u, :h) ON CONFLICT (lower(username)) DO NOTHING")
                    .param("u", u[0]).param("h", encoder.encode(u[0] + "-pw")).update();
            jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, :r FROM app_user WHERE username = :u ON CONFLICT DO NOTHING")
                    .param("r", u[1]).param("u", u[0]).update();
        }
    }

    private long issue() {
        return jdbc.sql("INSERT INTO issue (title, status) VALUES ('상태 전이 이슈', 'ACTIVE') RETURNING id").query(Long.class).single();
    }

    /** Creates a content as {@code user}; with a ready title and body unless {@code empty}. */
    private long content(String user, boolean empty) throws Exception {
        String json = empty ? "{\"contentType\":\"NEWS\"}" : "{\"contentType\":\"NEWS\",\"title\":\"검수용 제목\",\"body\":" + READY_BODY + "}";
        String res = mvc.perform(post("/api/admin/issues/" + issue() + "/contents").with(httpBasic(user, user + "-pw"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.<Number>read(res, "$.data.id").longValue();
    }

    private ResultActions act(String user, long id, String action, long version, String reason) throws Exception {
        String body = "{\"version\":" + version + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
        return mvc.perform(post("/api/admin/contents/" + id + "/" + action).with(httpBasic(user, user + "-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String row(long id) {
        return jdbc.sql("""
                SELECT status || '/' || version || '/' || (submitted_at IS NOT NULL) || '/' || coalesce(approved_by, '-')
                       || '/' || (approved_at IS NOT NULL) || '/' || coalesce(status_reason, '-')
                FROM content WHERE id = :id""").param("id", id).query(String.class).single();
    }

    private String lastAudit(long id) {
        return jdbc.sql("""
                SELECT action || '|' || actor || '|' || (before_state ->> 'status') || '>' || (after_state ->> 'status')
                       || '|' || coalesce(reason, '-')
                FROM admin_audit_log WHERE target_type = 'CONTENT' AND target_id = :id ORDER BY id DESC LIMIT 1""")
                .param("id", String.valueOf(id)).query(String.class).single();
    }

    @Test
    void submitApproveAndReturnFollowTheReviewFlow() throws Exception {
        long id = content("op", false);
        act("op", id, "submit", 0, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEW"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.submittedAt").isNotEmpty());
        assertThat(lastAudit(id)).isEqualTo("CONTENT_SUBMIT|op|DRAFT>REVIEW|-");

        // an OPERATOR cannot approve (security rule and policy), a SYSTEM_ADMIN can
        act("op", id, "approve", 1, null).andExpect(status().isForbidden());
        act("root", id, "approve", 1, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedBy").value("root"))
                .andExpect(jsonPath("$.data.approvedAt").isNotEmpty());
        assertThat(row(id)).isEqualTo("APPROVED/2/true/root/true/-");
        assertThat(lastAudit(id)).isEqualTo("CONTENT_APPROVE|root|REVIEW>APPROVED|-");

        // the same SYSTEM_ADMIN may approve what they wrote themselves (initial operation)
        long own = content("root", false);
        act("root", own, "submit", 0, null).andExpect(status().isOk());
        act("root", own, "approve", 1, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));

        // return: REVIEW -> DRAFT with a reason, which is shown until the next submit
        long returned = content("op", false);
        act("op", returned, "submit", 0, null).andExpect(status().isOk());
        act("op", returned, "return", 1, null).andExpect(status().isBadRequest());           // reason required
        act("op", returned, "return", 1, "   ").andExpect(status().isBadRequest());
        act("op", returned, "return", 1, "출처 보강 필요").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.statusReason").value("출처 보강 필요"))
                .andExpect(jsonPath("$.data.submittedAt").doesNotExist());
        assertThat(lastAudit(returned)).isEqualTo("CONTENT_RETURN|op|REVIEW>DRAFT|출처 보강 필요");
        act("op", returned, "submit", 2, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.statusReason").doesNotExist());
    }

    @Test
    void anEmptyDraftSavesButCannotBeSubmittedAndADraftCannotBeApprovedDirectly() throws Exception {
        long id = content("op", true);
        act("op", id, "submit", 0, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CONTENT_NOT_READY"))
                .andExpect(jsonPath("$.error.details.violations[*].path", hasItems("title", "body.sections")));
        // partly filled: blank heading, empty section, blank paragraph are all reported
        mvc.perform(patch("/api/admin/contents/" + id).with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"title\":\"제목\",\"body\":{\"schemaVersion\":1,\"sections\":["
                                + "{\"id\":\"s-1\",\"heading\":\" \",\"paragraphs\":[{\"id\":\"p-1\",\"text\":\"  \"}]},"
                                + "{\"id\":\"s-2\",\"heading\":\"둘\",\"paragraphs\":[]}]}}"))
                .andExpect(status().isOk());
        act("op", id, "submit", 1, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.violations[*].path",
                        hasItems("body.sections[0].heading", "body.sections[0].paragraphs[0].text", "body.sections[1].paragraphs")));
        // without the PRIMARY source it cannot be submitted either
        long noSource = content("op", false);
        jdbc.sql("DELETE FROM content_source WHERE content_id = :id").param("id", noSource).update();
        act("op", noSource, "submit", 0, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.violations[0].path").value("sources"));
        // DRAFT -> APPROVED directly is refused even for a SYSTEM_ADMIN
        long ready = content("op", false);
        act("root", ready, "approve", 0, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_STATE_INVALID"));
        assertThat(row(ready)).isEqualTo("DRAFT/0/false/-/false/-");
        // PATCH does not take a status
        mvc.perform(patch("/api/admin/contents/" + ready).with(httpBasic("root", "root-pw")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"status\":\"APPROVED\"}")).andExpect(status().isBadRequest());
        assertThat(row(ready)).startsWith("DRAFT/0/");
    }

    @Test
    void holdExcludeArchiveAndTheirWayBack() throws Exception {
        // APPROVED -> HOLD clears the approval; resume goes to DRAFT, never back to APPROVED
        long id = content("op", false);
        act("op", id, "submit", 0, null);
        act("root", id, "approve", 1, null);
        act("op", id, "hold", 2, null).andExpect(status().isBadRequest());
        act("op", id, "hold", 2, "법적 검토 대기").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("HOLD"));
        assertThat(row(id)).isEqualTo("HOLD/3/false/-/false/법적 검토 대기");
        act("op", id, "approve", 3, null).andExpect(status().isForbidden());
        act("root", id, "approve", 3, null).andExpect(status().isConflict());                // HOLD cannot be approved
        act("op", id, "resume", 3, null).andExpect(status().isOk());
        assertThat(row(id)).isEqualTo("DRAFT/4/false/-/false/-");

        // exclude needs a reason; restore is SYSTEM_ADMIN only, needs a reason and starts over from DRAFT
        act("op", id, "exclude", 4, "").andExpect(status().isBadRequest());
        act("op", id, "exclude", 4, "중복 콘텐츠").andExpect(status().isOk());
        assertThat(row(id)).isEqualTo("EXCLUDED/5/false/-/false/중복 콘텐츠");
        act("op", id, "resume", 5, null).andExpect(status().isConflict());                    // resume is for HOLD only
        act("op", id, "restore", 5, "다시 쓰기로 함").andExpect(status().isForbidden());
        act("root", id, "restore", 5, null).andExpect(status().isBadRequest());
        act("root", id, "restore", 5, "다시 쓰기로 함").andExpect(status().isOk());
        assertThat(row(id)).isEqualTo("DRAFT/6/false/-/false/다시 쓰기로 함");

        // archive keeps the record of what was approved; restoring an archived content clears it
        long archived = content("op", false);
        act("op", archived, "submit", 0, null);
        act("root", archived, "approve", 1, null);
        act("op", archived, "exclude", 2, "x").andExpect(status().isConflict());              // APPROVED cannot be excluded
        act("op", archived, "archive", 2, null).andExpect(status().isOk());
        assertThat(row(archived)).isEqualTo("ARCHIVED/3/true/root/true/-");
        act("op", archived, "submit", 3, null).andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/contents/" + archived).with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":3,\"title\":\"x\"}")).andExpect(status().isConflict());   // locked state
        act("root", archived, "restore", 3, "개정판 작성").andExpect(status().isOk());
        assertThat(row(archived)).isEqualTo("DRAFT/4/false/-/false/개정판 작성");
        assertThat(lastAudit(archived)).isEqualTo("CONTENT_RESTORE|root|ARCHIVED>DRAFT|개정판 작성");
    }

    @Test
    void editingAReviewedOrApprovedContentRevokesItButSavingTheSameValuesDoesNot() throws Exception {
        long id = content("op", false);
        act("op", id, "submit", 0, null);
        act("root", id, "approve", 1, null);
        // unchanged values: still approved
        mvc.perform(patch("/api/admin/contents/" + id).with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2,\"title\":\"검수용 제목\"}"))
                .andExpect(jsonPath("$.data.statusChanged").value(false));
        assertThat(row(id)).isEqualTo("APPROVED/2/true/root/true/-");
        // a real change: approval revoked, back to DRAFT, review record cleared
        mvc.perform(patch("/api/admin/contents/" + id).with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2,\"title\":\"고친 제목\"}"))
                .andExpect(jsonPath("$.data.statusChanged").value(true));
        assertThat(row(id)).isEqualTo("DRAFT/3/false/-/false/-");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_APPROVAL_REVOKED_BY_EDIT'")).isEqualTo(1);
    }

    @Test
    void staleVersionsAndUnknownContentAreRefused() throws Exception {
        long id = content("op", false);
        act("op", id, "submit", 0, null).andExpect(status().isOk());
        act("root", id, "approve", 0, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.currentVersion").value(1))
                .andExpect(jsonPath("$.error.details.status").value("REVIEW"));
        act("op", id, "hold", 9, "x").andExpect(status().isConflict());
        act("root", 9999, "approve", 0, null).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/contents/" + id + "/submit").with(httpBasic("op", "op-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());   // version required
        mvc.perform(post("/api/admin/contents/" + id + "/submit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}")).andExpect(status().isUnauthorized());
        assertThat(row(id)).startsWith("REVIEW/1/");
    }

    @Test
    void anApprovedContentStaysOutOfThePublicNewsroom() throws Exception {
        long issueId = issue();
        String res = mvc.perform(post("/api/admin/issues/" + issueId + "/contents").with(httpBasic("op", "op-pw"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"LIFE\",\"title\":\"공개되면 안 되는 제목\",\"body\":" + READY_BODY + "}"))
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.<Number>read(res, "$.data.id").longValue();
        String before = mvc.perform(get("/api/public/issues/" + issueId)).andReturn().getResponse().getContentAsString();
        act("op", id, "submit", 0, null).andExpect(status().isOk());
        act("root", id, "approve", 1, null).andExpect(status().isOk());
        assertThat(mvc.perform(get("/api/public/issues/" + issueId)).andReturn().getResponse().getContentAsString()).isEqualTo(before);
        assertThat(mvc.perform(get("/api/public/issues")).andReturn().getResponse().getContentAsString()).doesNotContain("공개되면 안 되는 제목");
        mvc.perform(get("/api/public/contents")).andExpect(status().isUnauthorized());
    }
}

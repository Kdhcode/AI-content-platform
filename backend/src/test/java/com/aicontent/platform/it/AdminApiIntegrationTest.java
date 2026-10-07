package com.aicontent.platform.it;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.admin.bootstrap-username=root", "app.admin.bootstrap-password=root-pw"})
class AdminApiIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void users() {
        // cleanDatabase() does not touch app_user; make sure root exists (bootstrap runs once at start-up) plus an operator
        jdbc.sql("""
                INSERT INTO app_user (username, password_hash) VALUES ('root', :h) ON CONFLICT (lower(username)) DO NOTHING""")
                .param("h", encoder.encode("root-pw")).update();
        jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, 'SYSTEM_ADMIN' FROM app_user WHERE username = 'root' ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES ('op', :h) ON CONFLICT (lower(username)) DO NOTHING")
                .param("h", encoder.encode("op-pw")).update();
        jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, 'OPERATOR' FROM app_user WHERE username = 'op' ON CONFLICT DO NOTHING").update();
    }

    @Test
    void unauthenticatedRequestGets401WithTheCommonEnvelope() throws Exception {
        mvc.perform(get("/api/admin/issues"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dashboardReportsStoredPipelineCountsAndDatabaseConfiguration() throws Exception {
        collectSample();
        mvc.perform(get("/api/admin/dashboard").with(httpBasic("op", "op-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.articles").value(3))
                .andExpect(jsonPath("$.data.counts.issues").value(2))
                .andExpect(jsonPath("$.data.counts.successfulJobs").value(10))
                .andExpect(jsonPath("$.data.counts.failedJobs").value(0))
                .andExpect(jsonPath("$.data.pipeline.length()").value(4))
                .andExpect(jsonPath("$.data.system.schemaVersion").value("1"))
                .andExpect(jsonPath("$.data.system.vectorVersion").isNotEmpty())
                .andExpect(jsonPath("$.data.system.aiProvider").value("stub"))
                .andExpect(jsonPath("$.data.system.password").doesNotExist())
                .andExpect(jsonPath("$.data.system.apiKey").doesNotExist());
    }

    @Test
    void listsArePagedInTheEnvelope() throws Exception {
        collectSample();
        mvc.perform(get("/api/admin/issues?size=1&page=0").with(httpBasic("op", "op-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        mvc.perform(get("/api/admin/articles?size=2&sort=publishedAt,asc").with(httpBasic("op", "op-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(1));
    }

    @Test
    void invalidParametersAre400AndUnknownIdsAre404() throws Exception {
        mvc.perform(get("/api/admin/issues?size=1000").with(httpBasic("op", "op-pw")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/admin/issues?sort=embedding,desc").with(httpBasic("op", "op-pw")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/issues/9999").with(httpBasic("op", "op-pw")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ISSUE_NOT_FOUND"));
    }

    @Test
    void mergeValidatesTheBodyAndTheOperation() throws Exception {
        collectSample();
        mvc.perform(post("/api/admin/issues/1/merge").with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceIssueIds\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/admin/issues/1/merge").with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceIssueIds\":[1]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ISSUE_MERGE_SELF"));
        mvc.perform(post("/api/admin/issues/1/merge").with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceIssueIds\":[2]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.movedArticleIds[0]").value(3));
    }

    @Test
    void operatorCannotRetryJobsButSystemAdminCan() throws Exception {
        mvc.perform(post("/api/admin/jobs/1/retry").with(httpBasic("op", "op-pw")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/jobs/1/retry").with(httpBasic("root", "root-pw")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void reanalyzeIsAsyncAndAnswers202WithTheJob() throws Exception {
        collectSample();
        mvc.perform(post("/api/admin/articles/1/reanalyze").with(httpBasic("op", "op-pw")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobType").value("ANALYZE_ARTICLE"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }
}

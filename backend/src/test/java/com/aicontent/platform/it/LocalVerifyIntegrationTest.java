package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicontent.platform.PlatformApplication;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The login bypass exists only in the local-verify profile; everywhere else the admin API keeps asking for login. */
class LocalVerifyIntegrationTest {

    /** Default configuration (test profile): bypass off, existing authentication unchanged. */
    @Nested
    @AutoConfigureMockMvc
    class Off extends IntegrationTestBase {
        @Autowired MockMvc mvc;

        @Test
        void adminApiStillNeedsLoginAndTheModeSaysOff() throws Exception {
            mvc.perform(get("/api/admin/issues")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/local-verify/mode")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.loginBypass").value(false))
                    .andExpect(jsonPath("$.data.dataLabel").doesNotExist());
        }
    }

    /** local-verify profile on top of the test profile (stub AI, server.address 127.0.0.1 from the profile). */
    @Nested
    @AutoConfigureMockMvc
    @ActiveProfiles(value = {"test", "local-verify"}, inheritProfiles = false)
    class On extends IntegrationTestBase {
        @Autowired MockMvc mvc;

        @Test
        void adminApiRunsWithoutLoginAsTheLocalVerifyUser() throws Exception {
            mvc.perform(get("/api/local-verify/mode")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.loginBypass").value(true))
                    .andExpect(jsonPath("$.data.aiProvider").value("stub"));
            mvc.perform(get("/api/admin/issues")).andExpect(status().isOk());
            mvc.perform(get("/api/admin/jobs")).andExpect(status().isOk());

            collectSample();
            mvc.perform(patch("/api/admin/issues/1").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"EXCLUDED\",\"reason\":\"검증\"}")).andExpect(status().isOk());
            assertThat(jdbc.sql("SELECT actor FROM admin_audit_log ORDER BY id DESC LIMIT 1").query(String.class).single())
                    .isEqualTo("local-verify");
        }

        @Test
        void requestsThatSendCredentialsAreStillChecked() throws Exception {
            mvc.perform(get("/api/admin/issues").with(httpBasic("nobody", "wrong"))).andExpect(status().isUnauthorized());
        }
    }

    /** The guard runs while the context starts, so a refused configuration never serves a request. */
    @Test
    void unsafeConfigurationsDoNotStart() {
        for (List<String> extra : List.of(
                List.of("--app.ai.provider=openai"),
                List.of("--app.ai.openai.api-key=not-a-real-key"),
                List.of("--server.address=0.0.0.0"),
                List.of("--spring.profiles.active=test,local-verify,live"))) {
            var args = new java.util.ArrayList<>(List.of("--server.port=0"));
            args.addAll(extra);
            var app = new SpringApplicationBuilder(PlatformApplication.class).profiles("test", "local-verify");
            assertThatThrownBy(() -> app.run(args.toArray(String[]::new)).close())
                    .as(extra.toString())
                    .hasStackTraceContaining("app.local-verify.login-bypass=true refused");
        }
    }
}

package com.aicontent.platform.it;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/admin/me: the signed-in account and its roles, so the admin UI can hide actions the account cannot take. */
@AutoConfigureMockMvc
class MeApiIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void users() {
        for (String[] u : new String[][] {{"me-op", "OPERATOR"}, {"me-root", "SYSTEM_ADMIN"}}) {
            jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES (:u, :h) ON CONFLICT (lower(username)) DO NOTHING")
                    .param("u", u[0]).param("h", encoder.encode(u[0] + "-pw")).update();
            jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, :r FROM app_user WHERE username = :u ON CONFLICT DO NOTHING")
                    .param("r", u[1]).param("u", u[0]).update();
        }
    }

    @Test
    void returnsTheSignedInAccountAndItsRoles() throws Exception {
        mvc.perform(get("/api/admin/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/me").with(httpBasic("me-op", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/me").with(httpBasic("me-op", "me-op-pw"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("me-op"))
                .andExpect(jsonPath("$.data.roles.length()").value(1))
                .andExpect(jsonPath("$.data.roles[0]").value("OPERATOR"));
        mvc.perform(get("/api/admin/me").with(httpBasic("me-root", "me-root-pw"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("me-root"))
                .andExpect(jsonPath("$.data.roles[0]").value("SYSTEM_ADMIN"));
    }
}

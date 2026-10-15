package com.aicontent.platform.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PublicNewsIntegrationTest extends IntegrationTestBase {
    @Autowired MockMvc mvc;

    @Test
    void publicListsAndDetailsExposeOnlyVisibleIssuesAndReaderFields() throws Exception {
        collectSample();
        jdbc.sql("UPDATE issue SET status='REVIEW' WHERE id=2").update();
        mvc.perform(get("/api/public/issues?size=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].articleCount").value(2));
        mvc.perform(get("/api/public/issues/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.articles.length()").value(2))
                .andExpect(jsonPath("$.data.articles[0].url").exists())
                .andExpect(jsonPath("$.data.articles[0].llmReason").doesNotExist())
                .andExpect(jsonPath("$.data.issue.embedding").doesNotExist());
        for (String state : new String[]{"REVIEW", "EXCLUDED", "MERGED"}) {
            jdbc.sql("UPDATE issue SET status=:state, merged_into_issue_id=CASE WHEN :state='MERGED' THEN 1 ELSE NULL END WHERE id=2")
                    .param("state", state).update();
            mvc.perform(get("/api/public/issues/2")).andExpect(status().isNotFound());
        }
        jdbc.sql("UPDATE issue SET status='CLOSED', merged_into_issue_id=NULL WHERE id=2").update();
        mvc.perform(get("/api/public/issues/2")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/issues")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/public/issues")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicSearchEscapesWildcardsAndValidatesPaging() throws Exception {
        jdbc.sql("INSERT INTO issue(title,category) VALUES ('100% news','ECONOMY'),('Other news','WORLD'),('Hidden','SECRET')").update();
        jdbc.sql("UPDATE issue SET status='REVIEW' WHERE category='SECRET'").update();
        mvc.perform(get("/api/public/issues").param("q", "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/public/issues?category=WORLD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].title").value("Other news"));
        mvc.perform(get("/api/public/categories"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/public/issues?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/issues?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/issues").param("q", "x".repeat(201))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/issues/99999")).andExpect(status().isNotFound());
    }
}

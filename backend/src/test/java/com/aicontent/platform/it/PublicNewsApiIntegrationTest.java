package com.aicontent.platform.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The public API is open without login, so what it must NOT show is tested as carefully as what it shows. */
@AutoConfigureMockMvc
class PublicNewsApiIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    @Test
    void listAndDetailAreReadableWithoutLoginAndCarryNoInternalFields() throws Exception {
        collectSample();   // issue 1 = articles 1,2 (two publishers), issue 2 = article 3

        mvc.perform(get("/api/public/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].title").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].summarySource").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].keyFacts").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].embedding").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].mergedIntoIssueId").doesNotExist());
        mvc.perform(get("/api/public/issues/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issue.id").value(1))
                .andExpect(jsonPath("$.data.issue.articleCount").value(2))
                .andExpect(jsonPath("$.data.issue.publisherCount").value(2))
                .andExpect(jsonPath("$.data.articles.length()").value(2))
                .andExpect(jsonPath("$.data.articles[0].url").value("https://example.test/n/2"))
                .andExpect(jsonPath("$.data.articles[0].publisherName").value("샘플뉴스"))
                .andExpect(jsonPath("$.data.articles[0].analysis").doesNotExist())
                .andExpect(jsonPath("$.data.articles[0].llmReason").doesNotExist())
                .andExpect(jsonPath("$.data.articles[0].classificationMethod").doesNotExist());
        mvc.perform(get("/api/public/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void onlyActiveAndClosedIssuesAreVisible() throws Exception {
        collectSample();

        for (String hidden : new String[] {"REVIEW", "EXCLUDED", "MERGED"}) {
            jdbc.sql("UPDATE issue SET status = :s, merged_into_issue_id = CASE WHEN :s = 'MERGED' THEN 1 END WHERE id = 2")
                    .param("s", hidden).update();
            mvc.perform(get("/api/public/issues"))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.items[0].id").value(1));
            mvc.perform(get("/api/public/issues/2"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ISSUE_NOT_FOUND"));
        }

        jdbc.sql("UPDATE issue SET status = 'CLOSED', merged_into_issue_id = NULL WHERE id = 2").update();
        mvc.perform(get("/api/public/issues")).andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/public/issues/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issue.status").value("CLOSED"));
    }

    @Test
    void categoriesComeFromVisibleIssuesOnly() throws Exception {
        collectSample();
        jdbc.sql("UPDATE issue SET category = 'VISIBLE_CATEGORY' WHERE id = 1").update();
        jdbc.sql("UPDATE issue SET category = 'HIDDEN_CATEGORY', status = 'REVIEW' WHERE id = 2").update();

        mvc.perform(get("/api/public/categories"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0]").value("VISIBLE_CATEGORY"));
        mvc.perform(get("/api/public/issues?category=VISIBLE_CATEGORY")).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/public/issues?category=HIDDEN_CATEGORY")).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void duplicateAndUnanalyzedArticlesAreNotListedOrCounted() throws Exception {
        collectSample();
        jdbc.sql("UPDATE news_article SET status = 'DUPLICATE', duplicate_of_article_id = 1 WHERE id = 2").update();

        mvc.perform(get("/api/public/issues/1"))
                .andExpect(jsonPath("$.data.issue.articleCount").value(1))
                .andExpect(jsonPath("$.data.issue.publisherCount").value(1))
                .andExpect(jsonPath("$.data.articles.length()").value(1))
                .andExpect(jsonPath("$.data.articles[0].id").value(1));

        jdbc.sql("UPDATE news_article SET status = 'FAILED' WHERE id = 1").update();
        mvc.perform(get("/api/public/issues/1"))
                .andExpect(jsonPath("$.data.issue.articleCount").value(0))
                .andExpect(jsonPath("$.data.articles.length()").value(0));
    }

    @Test
    void searchMatchesLiterallyAndInputIsValidated() throws Exception {
        collectSample();

        mvc.perform(get("/api/public/issues").param("q", "화재"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(1));
        // LIKE wildcards typed by a visitor are plain characters, not "match everything"
        for (String wildcard : new String[] {"%", "_", "\\"}) {
            mvc.perform(get("/api/public/issues").param("q", wildcard))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
        mvc.perform(get("/api/public/issues").param("q", "' OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mvc.perform(get("/api/public/issues").param("q", "가".repeat(201)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/public/issues").param("category", "c".repeat(101)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/issues?size=1000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/issues?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/issues/abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/issues/9999")).andExpect(status().isNotFound());
    }

    @Test
    void nothingElseIsOpenedWithoutLogin() throws Exception {
        collectSample();

        for (String path : new String[] {"/api/admin/issues", "/api/admin/issues/1", "/api/admin/articles", "/api/admin/jobs",
                "/api/admin/ai-usage", "/api/admin/dashboard", "/api/public/issues/1/articles", "/api/public/anything"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        // the public paths are read-only
        mvc.perform(post("/api/public/issues")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/public/issues/1")).andExpect(status().isUnauthorized());
    }
}

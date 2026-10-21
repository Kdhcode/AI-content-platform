package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.content.ContentRelationService;
import com.aicontent.platform.content.ContentService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** At most 500 related URLs per content, so the reorder API (which lists every link) always stays usable. */
@AutoConfigureMockMvc
class ContentLinkLimitIntegrationTest extends IntegrationTestBase {

    @Autowired ContentService contents;
    @Autowired ContentRelationService relations;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;

    private static final ContentRelationService.LinkInput LINK =
            new ContentRelationService.LinkInput("REFERENCE", "자료", "https://ref.example.test/x", null, null, null);

    @BeforeEach
    void user() {
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES ('op', :h) ON CONFLICT (lower(username)) DO NOTHING")
                .param("h", encoder.encode("op-pw")).update();
        jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, 'OPERATOR' FROM app_user WHERE username = 'op' ON CONFLICT DO NOTHING").update();
    }

    /** A content that already has {@code links} links (inserted directly, version stays 0). */
    private long contentWithLinks(int links) {
        long issue = jdbc.sql("INSERT INTO issue (title, status) VALUES ('링크 한도 이슈', 'ACTIVE') RETURNING id").query(Long.class).single();
        long id = contents.create(issue, "LIFE", "", null, "limit-" + System.nanoTime(), "op", null).content().id();
        jdbc.sql("""
                INSERT INTO content_link (content_id, link_type, url, sort_order)
                SELECT :id, 'REFERENCE', 'https://ref.example.test/' || n, n FROM generate_series(0, :last) AS n""")
                .param("id", id).param("last", links - 1).update();
        return id;
    }

    private long links(long id) {
        return count("SELECT count(*) FROM content_link WHERE content_id = " + id);
    }

    @Test
    void theFiveHundredthLinkIsAcceptedAndTheNextOneRefused() throws Exception {
        long id = contentWithLinks(499);
        assertThat(relations.addLink(id, 0, LINK, "op", null).changed()).isTrue();          // 500th
        assertThat(links(id)).isEqualTo(500);
        assertThatThrownBy(() -> relations.addLink(id, 1, LINK, "op", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONTENT_LINK_LIMIT_EXCEEDED));
        mvc.perform(post("/api/admin/contents/" + id + "/links").with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"linkType\":\"MAP\",\"url\":\"https://map.example.test\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_LINK_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.error.details.limit").value(500))
                .andExpect(jsonPath("$.error.details.current").value(500));
        assertThat(links(id)).isEqualTo(500);
        assertThat(count("SELECT version FROM content WHERE id = " + id)).isEqualTo(1);     // the refused adds changed nothing

        // all 500 can still be reordered in one request
        List<Long> ids = jdbc.sql("SELECT id FROM content_link WHERE content_id = :id ORDER BY sort_order, id").param("id", id).query(Long.class).list();
        List<Long> reversed = new ArrayList<>(ids);
        Collections.reverse(reversed);
        String json = reversed.stream().map(String::valueOf).collect(Collectors.joining(","));
        mvc.perform(put("/api/admin/contents/" + id + "/links/order").with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"linkIds\":[" + json + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.content.links.length()").value(500))
                .andExpect(jsonPath("$.data.content.links[0].id").value(reversed.get(0)));
        // a list longer than the limit is refused before anything is read
        mvc.perform(put("/api/admin/contents/" + id + "/links/order").with(httpBasic("op", "op-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2,\"linkIds\":[" + json + ",999999]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void twoAddsRacingForTheLastPlaceLeaveExactlyFiveHundred() throws Exception {
        for (int round = 0; round < 3; round++) {
            long id = contentWithLinks(499);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> { start.await(); return relations.addLink(id, 0, LINK, "op", null); }));
                }
                start.countDown();
                int ok = 0;
                for (Future<Object> f : futures) {
                    try {
                        f.get();
                        ok++;
                    } catch (ExecutionException e) {
                        assertThat(((BusinessException) e.getCause()).errorCode())
                                .isIn(ErrorCode.CONTENT_VERSION_CONFLICT, ErrorCode.CONTENT_LINK_LIMIT_EXCEEDED);
                    }
                }
                assertThat(ok).isEqualTo(1);
                assertThat(links(id)).isEqualTo(500);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}

package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.job.AsyncJobService;
import com.aicontent.platform.news.NewsCollectionScheduler;
import com.aicontent.platform.news.NewsCollectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class NewsCollectionIntegrationTest extends IntegrationTestBase {

    @Autowired NewsCollectionService collection;
    @Autowired NewsCollectionScheduler scheduler;

    @Test
    void storesNewArticlesAndQueuesAnalysis() throws Exception {
        long id = fixtureSource("src-a", "[" + String.join(",",
                article("가상시 상가 화재", "https://example.test/n/1", "가상일보", "2026-10-01T09:00:00+09:00"),
                article("허구군 정전", "https://example.test/n/2", "가상일보", "2026-10-01T10:00:00+09:00")) + "]");

        var result = collection.collect(id, "t");

        assertThat(result).containsEntry("created", 2).containsEntry("fetched", 2);
        assertThat(count("SELECT count(*) FROM news_article WHERE status = 'ANALYSIS_PENDING'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM async_job WHERE job_type = 'ANALYZE_ARTICLE' AND status = 'PENDING'")).isEqualTo(2);
    }

    @Test
    void sameUrlWithTrackingParamsIsAUrlDuplicateAndNotStoredTwice() throws Exception {
        long id = fixtureSource("src-a", "[" + String.join(",",
                article("가상시 상가 화재", "https://example.test/n/1", "가상일보", "2026-10-01T09:00:00+09:00"),
                article("가상시 상가 화재 (재전송)", "http://www.example.test/n/1/?utm_source=x", "가상일보", "2026-10-01T09:05:00+09:00")) + "]");

        var result = collection.collect(id, "t");

        assertThat(result).containsEntry("created", 1).containsEntry("urlDuplicates", 1);
        assertThat(count("SELECT count(*) FROM news_article")).isEqualTo(1);
        // running the same collection again creates nothing new either
        assertThat(collection.collect(id, "t")).containsEntry("created", 0).containsEntry("urlDuplicates", 2);
    }

    @Test
    void sameTitleSamePublisherWithDifferentUrlIsStoredAsDuplicateAndNotAnalysed() throws Exception {
        long id = fixtureSource("src-a", "[" + String.join(",",
                article("[속보] 가상시 상가 화재", "https://example.test/n/1", "가상일보", "2026-10-01T09:00:00+09:00"),
                article("가상시 상가 화재 [종합]", "https://example.test/n/9", "가상일보", "2026-10-01T09:30:00+09:00"),
                article("가상시 상가 화재", "https://example.test/n/10", "다른신문", "2026-10-01T09:40:00+09:00")) + "]");

        var result = collection.collect(id, "t");

        assertThat(result).containsEntry("created", 2).containsEntry("titleDuplicates", 1);
        var dup = jdbc.sql("SELECT status, duplicate_of_article_id FROM news_article WHERE original_url LIKE '%/n/9'")
                .query((rs, n) -> rs.getString(1) + "/" + rs.getLong(2)).single();
        assertThat(dup).isEqualTo("DUPLICATE/1");
        assertThat(count("SELECT count(*) FROM async_job WHERE job_type = 'ANALYZE_ARTICLE'")).isEqualTo(2);
    }

    @Test
    void invalidArticlesAreSkippedAndUnparseableDatesAreKeptRaw() throws Exception {
        long id = fixtureSource("src-a", "[" + String.join(",",
                article("", "https://example.test/n/1", "p", "2026-10-01T09:00:00+09:00"),                // no title
                article("제목", "not-a-url", "p", "2026-10-01T09:00:00+09:00"),                          // bad url
                article("날짜 이상한 기사", "https://example.test/n/3", "p", "어제 오후 3시")) + "]");

        var result = collection.collect(id, "t");

        assertThat(result).containsEntry("invalid", 2).containsEntry("created", 1);
        var row = jdbc.sql("SELECT published_at IS NULL, published_at_raw FROM news_article")
                .query((rs, n) -> rs.getBoolean(1) + "/" + rs.getString(2)).single();
        assertThat(row).isEqualTo("true/어제 오후 3시");
    }

    @Test
    void schedulerEnqueuesOneCollectJobPerDueSourceOnly() throws Exception {
        fixtureSource("src-a", "[]");
        assertThat(scheduler.enqueueDue()).isEqualTo(1);
        assertThat(scheduler.enqueueDue()).isEqualTo(0);     // already queued (dedupe key)
        assertThat(count("SELECT count(*) FROM async_job WHERE dedupe_key LIKE 'collect:%'")).isEqualTo(1);
    }
}

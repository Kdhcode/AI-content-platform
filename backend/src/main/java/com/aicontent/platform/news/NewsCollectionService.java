package com.aicontent.platform.news;

import com.aicontent.platform.config.AppProperties;
import com.aicontent.platform.job.AsyncJobService;
import com.aicontent.platform.job.JobExecutionException;
import com.aicontent.platform.job.JobType;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One collection run for one source: fetch -> validate -> normalize -> de-duplicate -> store -> enqueue analysis.
 *
 * <p>De-duplication order (design doc): (1) normalized URL (DB unique index, authoritative), (2) normalized title
 * hash of the same publisher inside a time window -> stored as DUPLICATE pointing at the original, never analysed.
 * Embedding-similarity de-duplication is optional in the design and intentionally not part of Phase 1.
 *
 * <p>Each article is stored in its own transaction together with its ANALYZE job, so a bad article never blocks
 * the others and an article can never exist without its follow-up job.
 */
@Service
public class NewsCollectionService {

    private static final Logger log = LoggerFactory.getLogger(NewsCollectionService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final NewsSourceRepository sources;
    private final NewsArticleRepository articles;
    private final NewsSourceAdapters adapters;
    private final AsyncJobService jobs;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public NewsCollectionService(NewsSourceRepository sources, NewsArticleRepository articles, NewsSourceAdapters adapters,
                                 AsyncJobService jobs, TransactionTemplate tx, AppProperties props) {
        this.sources = sources;
        this.articles = articles;
        this.adapters = adapters;
        this.jobs = jobs;
        this.tx = tx;
        this.props = props;
    }

    public Map<String, Object> collect(long sourceId, String correlationId) {
        NewsSource source = sources.findById(sourceId)
                .orElseThrow(() -> new JobExecutionException("SOURCE_NOT_FOUND", "news_source " + sourceId + " not found", false));
        if (!source.enabled() || "DISABLED".equals(source.status())) {
            return Map.of("skipped", "source disabled");
        }
        NewsSourceAdapter adapter = adapters.find(source.type()).orElseThrow(() ->
                new JobExecutionException("SOURCE_TYPE_UNSUPPORTED", "no adapter registered for " + source.type(), false));

        sources.markAttempt(sourceId);
        List<CollectedArticle> fetched;
        try {
            fetched = adapter.fetchLatest(source, props.news().maxArticlesPerRun());
        } catch (SourceFetchException e) {
            sources.markFailure(sourceId, e.kind() + ": " + e.getMessage(), props.news().degradedAfterFailures());
            throw new JobExecutionException("SOURCE_" + e.kind(), e.getMessage(), e.retryable());
        } catch (RuntimeException e) {
            sources.markFailure(sourceId, "UNEXPECTED: " + e.getMessage(), props.news().degradedAfterFailures());
            throw new JobExecutionException("SOURCE_UNEXPECTED", String.valueOf(e.getMessage()), true);
        }

        int created = 0, urlDup = 0, titleDup = 0, invalid = 0, errors = 0;
        for (CollectedArticle c : fetched) {
            try {
                Outcome o = store(source, c, correlationId);
                switch (o) {
                    case CREATED -> created++;
                    case URL_DUPLICATE -> urlDup++;
                    case TITLE_DUPLICATE -> titleDup++;
                    case INVALID -> invalid++;
                }
            } catch (DataAccessException | IllegalStateException e) {
                errors++;
                log.warn("storing article failed source={} url={}: {}", sourceId, c.url(), e.getMessage());
            }
        }
        if (!fetched.isEmpty() && errors == fetched.size()) {
            sources.markFailure(sourceId, "all " + errors + " articles failed to store", props.news().degradedAfterFailures());
            throw new JobExecutionException("STORE_FAILED", "all articles of source " + sourceId + " failed to store", true);
        }
        sources.markSuccess(sourceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fetched", fetched.size());
        result.put("created", created);
        result.put("urlDuplicates", urlDup);
        result.put("titleDuplicates", titleDup);
        result.put("invalid", invalid);
        result.put("errors", errors);
        return result;
    }

    enum Outcome { CREATED, URL_DUPLICATE, TITLE_DUPLICATE, INVALID }

    Outcome store(NewsSource source, CollectedArticle c, String correlationId) {
        if (isBlank(c.title()) || !isHttpUrl(c.url())) {
            return Outcome.INVALID;
        }
        String normalizedUrl = UrlNormalizer.normalize(c.url());
        String title = c.title().trim();
        String titleHash = TitleNormalizer.hash(title);
        String publisher = isBlank(c.publisherName()) ? source.name() : c.publisherName().trim();
        Optional<OffsetDateTime> published = PublishedAtParser.parse(c.publishedAtRaw(), DEFAULT_ZONE);
        var article = new NewsArticleRepository.NewArticle(source.id(), title, c.url().trim(), normalizedUrl, titleHash,
                publisher, c.author(), c.category(), published.orElse(null), c.publishedAtRaw(), c.analysisText());

        return tx.execute(status -> {
            Optional<Long> original = articles.findTitleDuplicate(publisher, titleHash, props.news().titleDedupeWindowDays());
            if (original.isPresent()) {
                Optional<Long> id = articles.insertIfNewUrl(article, "DUPLICATE", original.get());
                return id.isPresent() ? Outcome.TITLE_DUPLICATE : Outcome.URL_DUPLICATE;
            }
            Optional<Long> id = articles.insertIfNewUrl(article, "ANALYSIS_PENDING", null);
            if (id.isEmpty()) {
                return Outcome.URL_DUPLICATE;
            }
            jobs.enqueue(JobType.ANALYZE_ARTICLE, Map.of("articleId", id.get()), AsyncJobService.SYSTEM,
                    "analyze:" + id.get(), correlationId);
            return Outcome.CREATED;
        });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isHttpUrl(String url) {
        if (isBlank(url)) {
            return false;
        }
        try {
            URI u = URI.create(url.trim());
            return u.getHost() != null && ("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

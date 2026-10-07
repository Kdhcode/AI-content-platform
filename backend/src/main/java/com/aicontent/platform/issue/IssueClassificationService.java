package com.aicontent.platform.issue;

import com.aicontent.platform.ai.AiCallContext;
import com.aicontent.platform.ai.AiCallType;
import com.aicontent.platform.ai.AiException;
import com.aicontent.platform.ai.AiOutputInvalidException;
import com.aicontent.platform.ai.PromptRegistry;
import com.aicontent.platform.ai.PromptTemplate;
import com.aicontent.platform.ai.StructuredOutputService;
import com.aicontent.platform.common.Json;
import com.aicontent.platform.config.AppProperties;
import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Method;
import com.aicontent.platform.issue.DecisionPolicy.Outcome;
import com.aicontent.platform.job.JobExecutionException;
import com.aicontent.platform.lock.DistributedLock;
import com.aicontent.platform.news.NewsArticleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CLASSIFY_ARTICLE: embedding candidates -> LLM same-event judgement -> guard policy -> link / create issue.
 *
 * <ul>
 *   <li>The whole step runs under one lock ({@value #LOCK_KEY}), otherwise two articles about a brand-new event
 *       could both find no candidate and create two issues. Throughput is traded for correctness (documented).</li>
 *   <li>The LLM call happens outside any database transaction; only the final apply step is transactional.</li>
 *   <li>REVIEW creates a provisional issue (status REVIEW) so no article is left unlinked; the admin merges or
 *       confirms it.</li>
 *   <li>An article that is already linked is never moved automatically: re-classification only records a run.</li>
 * </ul>
 */
@Service
public class IssueClassificationService {

    static final String LOCK_KEY = "issue-classification";
    private static final String PROMPT_KEY = "issue-classifier";

    private final NewsArticleRepository articles;
    private final IssueCandidateFinder finder;
    private final IssueRepository issues;
    private final IssueAggregateService aggregates;
    private final ClassificationRunRepository runs;
    private final PromptRegistry prompts;
    private final StructuredOutputService structured;
    private final DistributedLock lock;
    private final TransactionTemplate tx;
    private final Json json;
    private final AppProperties props;

    public IssueClassificationService(NewsArticleRepository articles, IssueCandidateFinder finder, IssueRepository issues,
                                      IssueAggregateService aggregates, ClassificationRunRepository runs,
                                      PromptRegistry prompts, StructuredOutputService structured, DistributedLock lock,
                                      TransactionTemplate tx, Json json, AppProperties props) {
        this.articles = articles;
        this.finder = finder;
        this.issues = issues;
        this.aggregates = aggregates;
        this.runs = runs;
        this.prompts = prompts;
        this.structured = structured;
        this.lock = lock;
        this.tx = tx;
        this.json = json;
        this.props = props;
    }

    public Map<String, Object> classify(long articleId, long asyncJobId) {
        var article = articles.findRow(articleId).orElseThrow(() ->
                new JobExecutionException("ARTICLE_NOT_FOUND", "article " + articleId + " not found", false));
        if ("DUPLICATE".equals(article.status())) {
            return Map.of("skipped", "duplicate article");
        }
        if (article.analysisJson() == null || !article.hasEmbedding()) {
            throw new JobExecutionException("ARTICLE_NOT_READY",
                    "article " + articleId + " needs analysis and embedding before classification", false);
        }
        var handle = lock.acquire(LOCK_KEY, Duration.ofSeconds(props.lock().ttlSeconds()),
                Duration.ofSeconds(props.classifier().lockWaitSeconds()));
        if (handle.isEmpty()) {
            throw new JobExecutionException("LOCK_TIMEOUT", "could not obtain " + LOCK_KEY + " lock", true);
        }
        try (var ignored = handle.get()) {
            return classifyLocked(article, json.parse(article.analysisJson()), asyncJobId);
        }
    }

    private Map<String, Object> classifyLocked(NewsArticleRepository.ArticleRow article, JsonNode analysis, long asyncJobId) {
        long articleId = article.id();
        Optional<Long> existingIssue = issues.findPrimaryIssueId(articleId);
        List<IssueCandidate> candidates = finder.find(articleId);
        PromptTemplate prompt = prompts.get(PROMPT_KEY, props.prompts().issueClassifierVersion());

        String model = null;
        Outcome outcome;
        if (analysis.path("multiEvent").asBoolean(false)) {
            outcome = DecisionPolicy.multiEvent();
        } else if (candidates.isEmpty()) {
            outcome = DecisionPolicy.noCandidate();
        } else {
            try {
                var vars = promptVariables(article, analysis, candidates);
                var result = structured.execute(new StructuredOutputService.Call(AiCallType.ISSUE_CLASSIFY, prompt, vars,
                        props.ai().classifierMaxAttempts(), AiCallContext.of(asyncJobId, articleId)));
                model = result.model();
                outcome = DecisionPolicy.evaluate(toRaw(result.value()),
                        candidates.stream().map(IssueCandidate::id).collect(Collectors.toSet()),
                        new DecisionPolicy.Thresholds(props.classifier().sameIssueMinConfidence(),
                                props.classifier().newIssueMinConfidence()));
            } catch (AiOutputInvalidException e) {
                outcome = DecisionPolicy.invalidOutput(String.join("; ", e.errors()));
            } catch (AiException e) {
                throw new JobExecutionException(e.code(), e.getMessage(), e.retryable(), e);
            }
        }

        final Outcome decided = outcome;
        final String usedModel = model;
        return tx.execute(status -> apply(article, analysis, candidates, decided, existingIssue, asyncJobId, prompt, usedModel));
    }

    private Map<String, Object> apply(NewsArticleRepository.ArticleRow article, JsonNode analysis,
                                      List<IssueCandidate> candidates, Outcome outcome, Optional<Long> existingIssue,
                                      long asyncJobId, PromptTemplate prompt, String model) {
        long articleId = article.id();
        Outcome effective = outcome;
        // The matched issue may have been merged/closed by an admin since retrieval: re-check under a row lock.
        if (effective.decision() == Decision.SAME_ISSUE && existingIssue.isEmpty()) {
            Optional<String> st = issues.lockStatus(effective.matchedIssueId());
            if (st.isEmpty() || !"ACTIVE".equals(st.get())) {
                effective = new Outcome(Decision.REVIEW, Method.GUARD_DOWNGRADE, null, effective.confidence(),
                        "매칭된 ISSUE #" + effective.matchedIssueId() + "가 더 이상 ACTIVE가 아님 | " + effective.reason(),
                        effective.rawDecision());
            }
        }
        boolean hasPrompt = effective.method() == Method.LLM || effective.method() == Method.GUARD_DOWNGRADE;
        long runId = runs.insert(new ClassificationRunRepository.NewRun(articleId, asyncJobId, candidatesJson(candidates),
                effective.rawDecision() == null ? null : effective.rawDecision().name(), effective.decision().name(),
                effective.confidence(), effective.reason(), effective.matchedIssueId(), effective.method().name(),
                hasPrompt ? prompt.key() : null, hasPrompt ? prompt.version() : null, model));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("articleId", articleId);
        result.put("decision", effective.decision().name());
        result.put("method", effective.method().name());
        result.put("runId", runId);

        if (existingIssue.isPresent()) {
            // Already linked (re-classification requested by an admin): record only, never move automatically.
            result.put("applied", false);
            result.put("currentIssueId", existingIssue.get());
            return result;
        }

        long issueId;
        String linkMethod;
        String articleClassification;
        Double similarity = null;
        switch (effective.decision()) {
            case SAME_ISSUE -> {
                final long matchedId = effective.matchedIssueId();
                issueId = matchedId;
                linkMethod = "LLM";
                articleClassification = "CLASSIFIED";
                similarity = candidates.stream().filter(c -> c.id() == matchedId)
                        .map(IssueCandidate::similarity).findFirst().orElse(null);
            }
            case NEW_ISSUE -> {
                issueId = createIssueFrom(article, analysis, "ACTIVE");
                linkMethod = effective.method() == Method.NO_CANDIDATE ? "NO_CANDIDATE" : "LLM";
                articleClassification = "CLASSIFIED";
            }
            default -> {
                issueId = createIssueFrom(article, analysis, "REVIEW");
                linkMethod = "LLM_REVIEW";
                articleClassification = "REVIEW";
            }
        }
        issues.link(issueId, articleId, linkMethod, similarity,
                effective.rawDecision() == null ? null : effective.rawDecision().name(), effective.confidence(),
                effective.reason(), runId, false);
        aggregates.refresh(issueId);
        articles.setClassificationStatus(articleId, articleClassification);
        runs.markApplied(runId, issueId);
        result.put("applied", true);
        result.put("issueId", issueId);
        return result;
    }

    private long createIssueFrom(NewsArticleRepository.ArticleRow article, JsonNode analysis, String status) {
        String summary = analysis.hasNonNull("summary") ? analysis.get("summary").asText() : null;
        String category = analysis.hasNonNull("category") ? analysis.get("category").asText() : article.category();
        return issues.create(article.title(), summary, category, status);
    }

    private Map<String, Object> promptVariables(NewsArticleRepository.ArticleRow article, JsonNode analysis,
                                                List<IssueCandidate> candidates) {
        List<String> facts = new ArrayList<>();
        analysis.path("keyFacts").forEach(n -> facts.add(n.asText()));
        List<String> entities = new ArrayList<>();
        analysis.path("entities").forEach(n -> entities.add(n.path("name").asText()));
        var input = new ClassifierPromptVariables.ArticleInput(article.id(), article.title(), article.publisherName(),
                article.publishedAt() == null ? null : article.publishedAt().toString(), analysis.path("summary").asText(""),
                facts, entities, analysis.path("eventType").asText("-"));
        var candidateInputs = candidates.stream().map(c -> new ClassifierPromptVariables.CandidateInput(c.id(), c.title(),
                c.summary(), c.keyFacts(), String.valueOf(c.firstPublishedAt()), String.valueOf(c.lastUpdatedAt()),
                c.articleCount(), c.publisherCount())).toList();
        return ClassifierPromptVariables.build(input, candidateInputs);
    }

    private DecisionPolicy.RawDecision toRaw(JsonNode v) {
        Long matched = v.hasNonNull("matchedIssueId") ? v.get("matchedIssueId").asLong() : null;
        return new DecisionPolicy.RawDecision(Decision.valueOf(v.get("decision").asText()), v.get("confidence").asDouble(),
                matched, v.get("reason").asText());
    }

    private String candidatesJson(List<IssueCandidate> candidates) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (IssueCandidate c : candidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("issueId", c.id());
            m.put("title", c.title());
            m.put("similarity", Math.round(c.similarity() * 10000.0) / 10000.0);
            m.put("articleCount", c.articleCount());
            list.add(m);
        }
        return json.write(list);
    }
}

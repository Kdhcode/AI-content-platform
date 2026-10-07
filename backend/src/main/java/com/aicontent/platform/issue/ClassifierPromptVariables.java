package com.aicontent.platform.issue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the variables of the issue-classifier prompt. Shared by the production classification service and the
 * evaluation harness so both feed the model exactly the same text. Deliberately omits the embedding similarity: the
 * model must judge from content, not from a number that would anchor it.
 */
public final class ClassifierPromptVariables {

    public record ArticleInput(long articleId, String title, String publisher, String publishedAt, String summary,
                               List<String> keyFacts, List<String> entities, String eventType) {}

    public record CandidateInput(long id, String title, String summary, List<String> keyFacts, String firstPublishedAt,
                                 String lastUpdatedAt, int articleCount, int publisherCount) {}

    private ClassifierPromptVariables() {}

    public static Map<String, Object> build(ArticleInput article, List<CandidateInput> candidates) {
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> data = new ArrayList<>();
        for (CandidateInput c : candidates) {
            text.append("- id: ").append(c.id())
                    .append(" | 제목: ").append(c.title())
                    .append(" | 요약: ").append(blankToDash(c.summary()))
                    .append(" | 핵심 사실: ").append(c.keyFacts() == null || c.keyFacts().isEmpty() ? "-" : String.join("; ", c.keyFacts()))
                    .append(" | 기간: ").append(c.firstPublishedAt()).append(" ~ ").append(c.lastUpdatedAt())
                    .append(" | 기사 수: ").append(c.articleCount()).append(", 언론사 수: ").append(c.publisherCount())
                    .append('\n');
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("title", c.title());
            data.add(m);
        }
        List<String> facts = new ArrayList<>();
        if (article.keyFacts() != null) {
            article.keyFacts().forEach(f -> facts.add("- " + f));
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("articleId", article.articleId());
        vars.put("title", article.title());
        vars.put("publisher", article.publisher());
        vars.put("publishedAt", article.publishedAt() == null ? "알 수 없음" : article.publishedAt());
        vars.put("summary", article.summary() == null ? "" : article.summary());
        vars.put("keyFacts", String.join("\n", facts));
        vars.put("entities", article.entities() == null || article.entities().isEmpty() ? "-" : String.join(", ", article.entities()));
        vars.put("eventType", article.eventType() == null || article.eventType().isBlank() ? "-" : article.eventType());
        vars.put("candidates", text.toString().trim());
        // structured copy for the deterministic stub provider; real providers only see the rendered prompt text
        vars.put("candidatesData", data);
        return vars;
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}

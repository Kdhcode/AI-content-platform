package com.aicontent.platform.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic development/test provider ({@code app.ai.provider=stub}). It only exercises the pipeline: the
 * "analysis" is the title plus the start of the text, the "classification" is a title-token overlap. It is NOT a
 * model and its output must never be mistaken for real analysis.
 */
public class StubAiClient implements AiClient {

    private final ObjectMapper mapper;

    public StubAiClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String provider() {
        return "stub";
    }

    @Override
    public AiCompletion complete(AiRequest request) {
        Map<String, Object> out = switch (request.type()) {
            case ARTICLE_ANALYSIS -> analysis(request.variables());
            case ISSUE_CLASSIFY -> classify(request.variables());
            default -> throw new AiException("AI_UNSUPPORTED", "stub cannot complete " + request.type(), false);
        };
        try {
            return new AiCompletion(mapper.writeValueAsString(out), "stub-llm", null, null);
        } catch (JsonProcessingException e) {
            throw new AiException("AI_STUB_ERROR", e.getMessage(), false, e);
        }
    }

    private Map<String, Object> analysis(Map<String, Object> vars) {
        String title = String.valueOf(vars.getOrDefault("title", "")).trim();
        String text = String.valueOf(vars.getOrDefault("text", "")).trim();
        String summary = title + (text.isEmpty() ? "" : ". " + text.substring(0, Math.min(120, text.length())));
        if (summary.length() < 10) {
            summary = (summary + " (stub summary)").trim();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", summary);
        m.put("keyFacts", List.of(title.isEmpty() ? "stub fact" : title));
        m.put("entities", List.of());
        m.put("category", "OTHER");
        m.put("eventType", "stub");
        m.put("multiEvent", false);
        m.put("confidence", 0.6);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> classify(Map<String, Object> vars) {
        Set<String> articleTokens = tokens(String.valueOf(vars.getOrDefault("title", "")));
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) vars.getOrDefault("candidatesData", List.of());
        Long bestId = null;
        double best = 0;
        for (Map<String, Object> c : candidates) {
            Set<String> ct = tokens(String.valueOf(c.getOrDefault("title", "")));
            Set<String> inter = new HashSet<>(articleTokens);
            inter.retainAll(ct);
            Set<String> union = new HashSet<>(articleTokens);
            union.addAll(ct);
            double jaccard = union.isEmpty() ? 0 : (double) inter.size() / union.size();
            if (jaccard > best) {
                best = jaccard;
                bestId = ((Number) c.get("id")).longValue();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (bestId != null && best >= 0.5) {
            m.put("decision", "SAME_ISSUE");
            m.put("confidence", 0.9);
            m.put("matchedIssueId", bestId);
            m.put("reason", "stub: title token overlap " + Math.round(best * 100) + "%");
        } else {
            m.put("decision", "NEW_ISSUE");
            m.put("confidence", 0.8);
            m.put("matchedIssueId", null);
            m.put("reason", "stub: no candidate title overlaps enough");
        }
        return m;
    }

    private static Set<String> tokens(String s) {
        Set<String> t = new HashSet<>();
        for (String x : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (x.length() >= 2) {
                t.add(x);
            }
        }
        return t;
    }
}

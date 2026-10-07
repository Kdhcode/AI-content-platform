package com.aicontent.platform.issue;

import com.aicontent.platform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Single place that keeps an issue's derived data (counts, time range, centroid embedding, key facts, entities)
 * consistent with its members. Every membership change (classify, merge, split, move) ends with {@link #refresh}.
 * Call it inside the same transaction as the membership change.
 */
@Service
public class IssueAggregateService {

    private final IssueRepository issues;
    private final Json json;

    public IssueAggregateService(IssueRepository issues, Json json) {
        this.issues = issues;
        this.json = json;
    }

    public void refresh(long issueId) {
        issues.recomputeAggregates(issueId);
        List<IssueContentMerger.MemberAnalysis> members = new ArrayList<>();
        String firstSummary = null;
        for (String text : issues.memberAnalyses(issueId)) {
            JsonNode a = json.parse(text);
            if (firstSummary == null && a.hasNonNull("summary")) {
                firstSummary = a.get("summary").asText();
            }
            List<String> facts = new ArrayList<>();
            a.path("keyFacts").forEach(n -> facts.add(n.asText()));
            List<IssueContentMerger.Entity> entities = new ArrayList<>();
            a.path("entities").forEach(n -> entities.add(
                    new IssueContentMerger.Entity(n.path("name").asText(), n.path("type").asText("OTHER"))));
            members.add(new IssueContentMerger.MemberAnalysis(facts, entities));
        }
        IssueContentMerger.Merged merged = IssueContentMerger.merge(members);
        List<Map<String, String>> entityObjects = merged.entities().stream()
                .map(e -> Map.of("name", e.name(), "type", e.type() == null ? "OTHER" : e.type())).toList();
        issues.updateDerivedContent(issueId, json.write(merged.keyFacts()), json.write(entityObjects));
        if (firstSummary != null) {
            issues.fillAutoSummaryIfEmpty(issueId, firstSummary);
        }
    }
}

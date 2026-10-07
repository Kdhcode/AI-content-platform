package com.aicontent.platform.eval;

import com.aicontent.platform.issue.DecisionPolicy;
import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Outcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Replays the fixed set in chronological order through the same decision rules as production (no candidates ->
 * NEW, multiEvent -> REVIEW, judge -> {@link DecisionPolicy}) and applies each decision to an in-memory issue list.
 * Candidate retrieval is simplified to "the {@code candidateLimit} most recently updated issues"; embedding recall is
 * a separate evaluation that needs a real embedding provider (OPEN ITEM).
 */
public class EvalRunner {

    private static final class SimIssue {
        final long id;
        final String goldGroup;
        final String title;
        final String summary;
        final List<String> facts = new ArrayList<>();
        String lastPublishedAt;
        /** Only ACTIVE issues are offered as candidates (production: app.classifier.candidate-issue-statuses = ACTIVE). */
        final boolean active;

        SimIssue(long id, EvalCase first, boolean active) {
            this.active = active;
            this.id = id;
            this.goldGroup = first.eventGroup();
            this.title = first.title();
            this.summary = first.summary();
            this.facts.addAll(first.keyFacts());
            this.lastPublishedAt = first.publishedAt();
        }
    }

    private final Judge judge;
    private final DecisionPolicy.Thresholds thresholds;
    private final int candidateLimit;

    public EvalRunner(Judge judge, DecisionPolicy.Thresholds thresholds, int candidateLimit) {
        this.judge = judge;
        this.thresholds = thresholds;
        this.candidateLimit = candidateLimit;
    }

    public List<CaseResult> run(List<EvalCase> cases) {
        List<EvalCase> ordered = cases.stream().sorted(Comparator.comparing(EvalCase::publishedAt).thenComparing(EvalCase::id)).toList();
        List<SimIssue> issues = new ArrayList<>();
        Map<String, Boolean> seenGroups = new LinkedHashMap<>();
        List<CaseResult> results = new ArrayList<>();
        long nextId = 1;

        for (EvalCase c : ordered) {
            List<SimIssue> candidates = issues.stream().filter(i -> i.active)
                    .sorted(Comparator.comparing((SimIssue i) -> i.lastPublishedAt).reversed().thenComparing(i -> i.id))
                    .limit(candidateLimit).toList();
            CaseResult.Gold gold = c.multiEvent() ? CaseResult.Gold.BUNDLE
                    : seenGroups.containsKey(c.eventGroup()) ? CaseResult.Gold.SAME : CaseResult.Gold.NEW;

            Outcome outcome;
            if (c.multiEvent()) {
                outcome = DecisionPolicy.multiEvent();
            } else if (candidates.isEmpty()) {
                outcome = DecisionPolicy.noCandidate();
            } else {
                var views = candidates.stream().map(i -> new Judge.CandidateIssue(i.id, i.title, i.summary, List.copyOf(i.facts))).toList();
                Set<Long> ids = candidates.stream().map(i -> i.id).collect(Collectors.toSet());
                outcome = DecisionPolicy.evaluate(judge.judge(c, views), ids, thresholds);
            }

            boolean sameCorrect = false;
            if (outcome.decision() == Decision.SAME_ISSUE) {
                SimIssue target = issues.stream().filter(i -> i.id == outcome.matchedIssueId()).findFirst().orElseThrow();   // always an active one: only those were offered
                sameCorrect = target.goldGroup.equals(c.eventGroup());
                target.facts.addAll(c.keyFacts());
                target.lastPublishedAt = c.publishedAt();
            } else {
                // NEW and REVIEW both create an issue (REVIEW = provisional, not offered as candidate), exactly like production
                issues.add(new SimIssue(nextId++, c, outcome.decision() == Decision.NEW_ISSUE));
            }
            if (!c.multiEvent()) {
                seenGroups.put(c.eventGroup(), true);
            }
            results.add(new CaseResult(c.id(), gold, c.ambiguous(), outcome.decision(), outcome.method(), sameCorrect));
        }
        return results;
    }
}

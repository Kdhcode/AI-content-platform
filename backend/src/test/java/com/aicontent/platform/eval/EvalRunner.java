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
 *
 * <p>By default the bundle flag comes from the gold label (classification measured in isolation). Passing the
 * analysis model's predictions ({@link #run(List, Map)}) measures the two steps together: a bundle the model missed
 * goes to the judge like any article and can surface as BUNDLE_NOT_REVIEWED.
 */
public class EvalRunner {

    private static final class SimIssue {
        final long id;
        final String goldGroup;
        final String title;
        final String summary;
        final List<String> facts = new ArrayList<>();
        String lastPublishedAt;
        /** ACTIVE, or REVIEW (pending admin review). */
        final boolean active;
        /** A pending issue created for a bundle article: never offered as a candidate (production excludes it too). */
        final boolean bundle;

        SimIssue(long id, EvalCase first, boolean active, boolean bundle) {
            this.active = active;
            this.bundle = bundle;
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
    private final boolean offerPendingReviewIssues;

    /** Current production policy: pending (REVIEW) issues are offered as candidates too. */
    public EvalRunner(Judge judge, DecisionPolicy.Thresholds thresholds, int candidateLimit) {
        this(judge, thresholds, candidateLimit, true);
    }

    /**
     * @param offerPendingReviewIssues true = production since 2026-10-14 (a SAME_ISSUE to a pending issue attaches the
     *                                 article to it, still REVIEW); false = the earlier policy (ACTIVE issues only)
     */
    public EvalRunner(Judge judge, DecisionPolicy.Thresholds thresholds, int candidateLimit, boolean offerPendingReviewIssues) {
        this.judge = judge;
        this.thresholds = thresholds;
        this.candidateLimit = candidateLimit;
        this.offerPendingReviewIssues = offerPendingReviewIssues;
    }

    public List<CaseResult> run(List<EvalCase> cases) {
        return run(cases, null);
    }

    /**
     * @param predictedMultiEvent case id -> multiEvent from the analysis model; null = use the gold label. A case
     *                            without a prediction (analysis failed) falls back to the gold label.
     */
    public List<CaseResult> run(List<EvalCase> cases, Map<String, Boolean> predictedMultiEvent) {
        List<EvalCase> ordered = cases.stream().sorted(Comparator.comparing(EvalCase::publishedAt).thenComparing(EvalCase::id)).toList();
        List<SimIssue> issues = new ArrayList<>();
        Map<String, Boolean> seenGroups = new LinkedHashMap<>();
        List<CaseResult> results = new ArrayList<>();
        long nextId = 1;

        for (EvalCase c : ordered) {
            List<SimIssue> candidates = issues.stream().filter(i -> i.active || (offerPendingReviewIssues && !i.bundle))
                    .sorted(Comparator.comparing((SimIssue i) -> i.lastPublishedAt).reversed().thenComparing(i -> i.id))
                    .limit(candidateLimit).toList();
            CaseResult.Gold gold = c.multiEvent() ? CaseResult.Gold.BUNDLE
                    : seenGroups.containsKey(c.eventGroup()) ? CaseResult.Gold.SAME : CaseResult.Gold.NEW;

            boolean multiEvent = predictedMultiEvent == null || !predictedMultiEvent.containsKey(c.id())
                    ? c.multiEvent() : predictedMultiEvent.get(c.id());
            Outcome outcome;
            if (multiEvent) {
                outcome = DecisionPolicy.multiEvent();
            } else if (candidates.isEmpty()) {
                outcome = DecisionPolicy.noCandidate();
            } else {
                var views = candidates.stream().map(i -> new Judge.CandidateIssue(i.id, i.title, i.summary, List.copyOf(i.facts))).toList();
                Set<Long> ids = candidates.stream().map(i -> i.id).collect(Collectors.toSet());
                outcome = DecisionPolicy.evaluate(judge.judge(c, views), ids, thresholds);
                if (outcome.decision() == Decision.SAME_ISSUE) {
                    long matchedId = outcome.matchedIssueId();
                    SimIssue target = candidates.stream().filter(i -> i.id == matchedId).findFirst().orElseThrow();
                    outcome = DecisionPolicy.applyPeriodGuard(outcome,
                            String.join("\n", c.title(), c.summary(), String.join("\n", c.keyFacts())),
                            String.join("\n", target.title, target.summary, String.join("\n", target.facts)));
                }
            }

            boolean sameCorrect = false;
            CaseResult.Held held = CaseResult.Held.NONE;
            Decision decision = outcome.decision();
            if (decision == Decision.SAME_ISSUE) {
                final long linkedId = outcome.matchedIssueId();
                SimIssue target = issues.stream().filter(i -> i.id == linkedId).findFirst().orElseThrow();
                boolean sameEvent = target.goldGroup.equals(c.eventGroup());
                target.facts.addAll(c.keyFacts());
                target.lastPublishedAt = c.publishedAt();
                if (target.active) {
                    sameCorrect = sameEvent;
                } else {
                    // production: attached to the pending issue and kept REVIEW until an admin confirms it
                    decision = Decision.REVIEW;
                    held = sameEvent ? CaseResult.Held.SAME_EVENT : CaseResult.Held.OTHER_EVENT;
                }
            } else {
                // NEW creates an ACTIVE issue, REVIEW a pending one (bundles: a pending one that is never a candidate)
                issues.add(new SimIssue(nextId++, c, decision == Decision.NEW_ISSUE, outcome.method() == DecisionPolicy.Method.MULTI_EVENT));
            }
            if (!c.multiEvent()) {
                seenGroups.put(c.eventGroup(), true);
            }
            results.add(new CaseResult(c.id(), gold, c.ambiguous(), decision, outcome.method(), sameCorrect, held));
        }
        return results;
    }
}

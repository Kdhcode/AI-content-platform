package com.aicontent.platform.eval;

import com.aicontent.platform.issue.DecisionPolicy.RawDecision;
import java.util.List;

/** Produces the raw same-event verdict for one article; the production implementation calls the LLM prompt. */
public interface Judge {

    record CandidateIssue(long issueId, String title, String summary, List<String> keyFacts) {}

    RawDecision judge(EvalCase article, List<CandidateIssue> candidates);
}

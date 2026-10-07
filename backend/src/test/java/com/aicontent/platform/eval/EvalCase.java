package com.aicontent.platform.eval;

import java.util.List;

/**
 * One article of the fixed evaluation set. All content is FICTIONAL (invented places/companies) and exists only to
 * exercise the pipeline; it is not real news. {@code eventGroup} is the gold label: articles with the same group
 * report the same event. {@code multiEvent} marks a bundle article (expected: REVIEW); {@code ambiguous} marks a
 * case where even a human editor could go either way (REVIEW is an acceptable answer).
 */
public record EvalCase(
        String id,
        String publisher,
        String publishedAt,
        String title,
        String summary,
        List<String> keyFacts,
        List<String> entities,
        String eventGroup,
        boolean multiEvent,
        boolean ambiguous,
        String note) {}

package com.aicontent.platform.job;

/**
 * Job types handled by the Phase 1 worker. The column is plain TEXT on purpose: later phases add
 * types (content generation, publishing) without touching a CHECK constraint.
 */
public enum JobType {
    /** payload: {sourceId} */
    COLLECT_NEWS,
    /** payload: {articleId, force?} */
    ANALYZE_ARTICLE,
    /** payload: {articleId} */
    EMBED_ARTICLE,
    /** payload: {articleId} */
    CLASSIFY_ARTICLE
}

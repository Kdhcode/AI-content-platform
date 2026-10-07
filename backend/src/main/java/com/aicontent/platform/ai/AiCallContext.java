package com.aicontent.platform.ai;

/** Where an AI call belongs, for ai_job / ai_log bookkeeping. Both ids are optional (e.g. ad-hoc evaluation runs). */
public record AiCallContext(Long asyncJobId, Long articleId) {

    public static AiCallContext of(Long asyncJobId, Long articleId) {
        return new AiCallContext(asyncJobId, articleId);
    }
}

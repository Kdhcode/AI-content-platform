package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.ai.AiProviderGuard;
import org.junit.jupiter.api.Test;

/** Reads which provider made the embeddings already stored, the evidence the startup guard uses. */
class AiProviderGuardIntegrationTest extends IntegrationTestBase {

    private void log(String callType, String provider, boolean success) {
        jdbc.sql("INSERT INTO ai_log (call_type, provider, model, success) VALUES (:t, :p, 'm', :s)")
                .param("t", callType).param("p", provider).param("s", success).update();
    }

    @Test
    void onlySuccessfulEmbeddingCallsCountAsEvidence() {
        assertThat(AiProviderGuard.embeddingProviders(jdbc)).isEmpty();

        log("ARTICLE_ANALYSIS", "openai", true);    // not a vector
        log("EMBEDDING", "openai", false);          // failed: no vector stored
        assertThat(AiProviderGuard.embeddingProviders(jdbc)).isEmpty();

        log("EMBEDDING", "stub", true);
        assertThat(AiProviderGuard.embeddingProviders(jdbc)).containsExactly("stub");
        assertThat(AiProviderGuard.mixedEmbeddings("openai", AiProviderGuard.embeddingProviders(jdbc))).hasSize(1);
        assertThat(AiProviderGuard.mixedEmbeddings("stub", AiProviderGuard.embeddingProviders(jdbc))).isEmpty();
    }
}

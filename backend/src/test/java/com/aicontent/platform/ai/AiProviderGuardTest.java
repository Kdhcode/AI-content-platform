package com.aicontent.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.config.AppProperties.ModelPrice;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AiProviderGuardTest {

    @Test
    void paidAiNeedsAPriceForBothModels() {
        var priced = new AiCostCalculator(Map.of(
                "gpt-4o-mini", new ModelPrice(new BigDecimal("0.15"), new BigDecimal("0.60")),
                "text-embedding-3-small", new ModelPrice(new BigDecimal("0.02"), null)));
        assertThat(AiProviderGuard.missingPrices(priced, "gpt-4o-mini-2024-07-18", "text-embedding-3-small")).isEmpty();

        var none = new AiCostCalculator(Map.of());
        assertThat(AiProviderGuard.missingPrices(none, "gpt-4o-mini", "text-embedding-3-small")).hasSize(2);
        var chatOnly = new AiCostCalculator(Map.of("gpt-4o-mini", new ModelPrice(new BigDecimal("0.15"), new BigDecimal("0.60"))));
        assertThat(AiProviderGuard.missingPrices(chatOnly, "gpt-4o-mini", "text-embedding-3-small")).hasSize(1);
    }

    @Test
    void stubAndRealEmbeddingsNeverShareADatabase() {
        assertThat(AiProviderGuard.mixedEmbeddings("stub", Set.of("openai"))).hasSize(1);
        assertThat(AiProviderGuard.mixedEmbeddings("openai", Set.of("stub"))).hasSize(1);

        assertThat(AiProviderGuard.mixedEmbeddings("stub", Set.of("stub"))).isEmpty();
        assertThat(AiProviderGuard.mixedEmbeddings("openai", Set.of("openai"))).isEmpty();
        assertThat(AiProviderGuard.mixedEmbeddings("openai", Set.of())).isEmpty();          // new database
        assertThat(AiProviderGuard.mixedEmbeddings("none", Set.of("stub", "openai"))).isEmpty();  // makes no vectors
    }
}

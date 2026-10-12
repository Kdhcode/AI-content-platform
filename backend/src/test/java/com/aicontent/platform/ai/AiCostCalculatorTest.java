package com.aicontent.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.config.AppProperties.ModelPrice;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Prices below are made-up test values, not real provider prices. */
class AiCostCalculatorTest {

    private final AiCostCalculator calculator = new AiCostCalculator(Map.of(
            "chat-a", price("1.50", "6.00"),
            "chat-a-pro", price("10", "30"),
            "embed-a", price("0.50", null)));

    @Test
    void exactModelName() {
        // 1000 * 1.50 / 1e6 + 2000 * 6.00 / 1e6
        assertThat(calculator.estimate("chat-a", 1000, 2000)).isEqualByComparingTo("0.013500");
    }

    @Test
    void datedModelNameMatchesByPrefix() {
        assertThat(calculator.estimate("chat-a-2024-07-18", 1000, 2000)).isEqualByComparingTo("0.013500");
    }

    @Test
    void longerPrefixWins() {
        // chat-a and chat-a-pro both match; 1000 * 10 / 1e6 + 2000 * 30 / 1e6
        assertThat(calculator.estimate("chat-a-pro-2025-01-01", 1000, 2000)).isEqualByComparingTo("0.070000");
    }

    @Test
    void exactNameWinsOverALongerLookingPrefix() {
        assertThat(calculator.estimate("chat-a-pro", 1_000_000, 0)).isEqualByComparingTo("10");
    }

    @Test
    void unknownModelIsNullNotZero() {
        assertThat(calculator.estimate("other-model", 1000, 2000)).isNull();
        assertThat(calculator.estimate(null, 1000, 2000)).isNull();
        assertThat(new AiCostCalculator(Map.of()).estimate("chat-a", 1000, 2000)).isNull();
        assertThat(new AiCostCalculator(null).estimate("chat-a", 1000, 2000)).isNull();
    }

    @Test
    void unknownTokensAreNullNotZero() {
        assertThat(calculator.estimate("chat-a", null, 2000)).isNull();
        assertThat(calculator.estimate("chat-a", 1000, null)).isNull();
        assertThat(calculator.estimate("chat-a", null, null)).isNull();
    }

    @Test
    void embeddingUsesTheInputPriceOnly() {
        assertThat(calculator.estimate("embed-a", 20, null)).isEqualByComparingTo("0.000010");
        // output tokens without an output price: the cost is not known
        assertThat(calculator.estimate("embed-a", 20, 5)).isNull();
    }

    @Test
    void missingInputPriceIsNull() {
        assertThat(new AiCostCalculator(Map.of("m", price(null, "6"))).estimate("m", 10, 10)).isNull();
    }

    @Test
    void roundsHalfUpToSixDecimals() {
        // 1 * 1.50 / 1e6 = 0.0000015 -> 0.000002 ; 3 * 0.50 / 1e6 = 0.0000015 -> 0.000002 ; 1 * 0.4 / 1e6 -> 0
        assertThat(calculator.estimate("chat-a", 1, 0)).isEqualTo(new BigDecimal("0.000002"));
        assertThat(calculator.estimate("embed-a", 3, null)).isEqualTo(new BigDecimal("0.000002"));
        assertThat(new AiCostCalculator(Map.of("m", price("0.4", null))).estimate("m", 1, null))
                .isEqualTo(new BigDecimal("0.000000"));
    }

    private static ModelPrice price(String in, String out) {
        return new ModelPrice(in == null ? null : new BigDecimal(in), out == null ? null : new BigDecimal(out));
    }
}

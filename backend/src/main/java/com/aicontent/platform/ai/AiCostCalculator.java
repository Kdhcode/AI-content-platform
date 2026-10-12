package com.aicontent.platform.ai;

import com.aicontent.platform.config.AppProperties.ModelPrice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Estimates the cost of one AI call from the prices in {@code app.ai.pricing}. No price is built in: prices
 * change, so they are entered by the operator. An unknown cost is {@code null}, never 0.
 */
public class AiCostCalculator {

    private static final Logger log = LoggerFactory.getLogger(AiCostCalculator.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int SCALE = 6;   // ai_log.estimated_cost_usd is NUMERIC(12,6)

    private final Map<String, ModelPrice> pricing;

    public AiCostCalculator(Map<String, ModelPrice> pricing) {
        this.pricing = pricing == null ? Map.of() : pricing;
    }

    /**
     * @return the estimated cost in USD, or null when the model has no price or the token counts are unknown.
     *         Never throws: bookkeeping must not change the outcome of the AI call.
     */
    public BigDecimal estimate(String model, Integer inputTokens, Integer outputTokens) {
        try {
            ModelPrice price = priceFor(model);
            if (price == null || price.inputPerMillionUsd() == null || inputTokens == null) {
                return null;
            }
            BigDecimal output = outputCost(price, outputTokens);
            if (output == null) {
                return null;
            }
            return cost(inputTokens, price.inputPerMillionUsd()).add(output).setScale(SCALE, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            log.warn("ai cost estimate failed (ignored): {}", e.getMessage());
            return null;
        }
    }

    /** Embedding models have an input price only and report no output tokens; anything else unknown stays unknown. */
    private static BigDecimal outputCost(ModelPrice price, Integer outputTokens) {
        if (price.outputPerMillionUsd() == null) {
            return outputTokens == null || outputTokens == 0 ? BigDecimal.ZERO : null;
        }
        return outputTokens == null ? null : cost(outputTokens, price.outputPerMillionUsd());
    }

    private static BigDecimal cost(int tokens, BigDecimal perMillionUsd) {
        return BigDecimal.valueOf(tokens).multiply(perMillionUsd).divide(MILLION);
    }

    /** Exact name first, then the longest configured prefix (responses report dated names such as gpt-4o-mini-2024-07-18). */
    private ModelPrice priceFor(String model) {
        if (model == null) {
            return null;
        }
        ModelPrice exact = pricing.get(model);
        if (exact != null) {
            return exact;
        }
        String best = null;
        for (String name : pricing.keySet()) {
            if (!name.isEmpty() && model.startsWith(name) && (best == null || name.length() > best.length())) {
                best = name;
            }
        }
        return best == null ? null : pricing.get(best);
    }
}

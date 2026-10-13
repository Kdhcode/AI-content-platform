package com.aicontent.platform.admin;

import com.aicontent.platform.ai.AiUsageLimiter;
import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only AI usage per KST day, call type and model, plus the configured {@code app.ai.limits} and today's usage
 * against them (limits are enforced by {@link AiUsageLimiter}, not here). Raw model responses and error messages are
 * never returned. An unknown cost is null, not 0.
 */
@RestController
@RequestMapping("/api/admin/ai-usage")
public class AiUsageAdminController {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    /** {@code costUsd} is null when no call in the group has a cost; {@code unpricedCalls} had tokens but no price. */
    public record Row(LocalDate day, String callType, String provider, String model, long calls, long failures,
                      long inputTokens, long outputTokens, BigDecimal costUsd, long unpricedCalls) {}

    public record Totals(long calls, long failures, long inputTokens, long outputTokens, BigDecimal costUsd,
                         long unpricedCalls) {}

    public record Usage(int days, List<Row> rows, Totals totals, AiUsageLimiter.Status limits) {}

    private final JdbcClient jdbc;
    private final AiUsageLimiter limiter;

    public AiUsageAdminController(JdbcClient jdbc, AiUsageLimiter limiter) {
        this.jdbc = jdbc;
        this.limiter = limiter;
    }

    @GetMapping
    public ApiResponse<Usage> usage(@RequestParam(defaultValue = "7") int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "days는 " + MIN_DAYS + "~" + MAX_DAYS + " 사이여야 합니다.");
        }
        List<Row> rows = jdbc.sql("""
                SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day, call_type, provider, model,
                       count(*) AS calls,
                       count(*) FILTER (WHERE NOT success) AS failures,
                       coalesce(sum(input_tokens), 0) AS input_tokens,
                       coalesce(sum(output_tokens), 0) AS output_tokens,
                       sum(estimated_cost_usd) AS cost_usd,
                       count(*) FILTER (WHERE estimated_cost_usd IS NULL
                                        AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL)) AS unpriced_calls
                FROM ai_log
                WHERE created_at >= (date_trunc('day', now() AT TIME ZONE 'Asia/Seoul')
                                     - make_interval(days => :days - 1)) AT TIME ZONE 'Asia/Seoul'
                GROUP BY 1, 2, 3, 4
                ORDER BY 1 DESC, 2, 4
                """)
                .param("days", days)
                .query((rs, n) -> new Row(rs.getObject("day", LocalDate.class), rs.getString("call_type"),
                        rs.getString("provider"), rs.getString("model"), rs.getLong("calls"), rs.getLong("failures"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getBigDecimal("cost_usd"),
                        rs.getLong("unpriced_calls")))
                .list();
        return ApiResponse.ok(new Usage(days, rows, totals(rows), limiter.status()));
    }

    private static Totals totals(List<Row> rows) {
        long calls = 0, failures = 0, input = 0, output = 0, unpriced = 0;
        BigDecimal cost = null;
        for (Row r : rows) {
            calls += r.calls();
            failures += r.failures();
            input += r.inputTokens();
            output += r.outputTokens();
            unpriced += r.unpricedCalls();
            if (r.costUsd() != null) {
                cost = cost == null ? r.costUsd() : cost.add(r.costUsd());
            }
        }
        return new Totals(calls, failures, input, output, cost, unpriced);
    }
}

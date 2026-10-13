package com.aicontent.platform.ai;

import com.aicontent.platform.config.AppProperties;
import com.aicontent.platform.job.JobDeferredException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Enforces {@code app.ai.limits} (OPEN ITEM #10) before each AI call. A reached limit throws an uncounted
 * {@link JobDeferredException}: the job waits (until the next day, or about a minute) without using retries or
 * deferrals, and nothing is written to ai_log because no call was made. With no limit configured this costs nothing
 * (no query).
 *
 * <p>Limits are read from ai_log, so they hold across instances and restarts. They are soft: calls that are already
 * running when a limit is reached still finish, so usage can overshoot by up to {@code app.worker.concurrency} calls.
 */
@Component
public class AiUsageLimiter {

    private static final Logger log = LoggerFactory.getLogger(AiUsageLimiter.class);

    public static final String DAILY_BUDGET = "AI_DAILY_BUDGET_REACHED";
    public static final String DAILY_CALLS = "AI_DAILY_CALL_LIMIT_REACHED";
    public static final String RATE = "AI_RATE_LIMITED";
    /** Wait after the per-minute limit; one full window, so the oldest counted call has aged out. */
    static final long RATE_WAIT_SECONDS = 60;
    /** Small margin after midnight so the new day has really started for the database clock too. */
    static final long NEXT_DAY_MARGIN_SECONDS = 5;

    /** Current limits and usage for the admin screen. Limits that are off are null. */
    public record Status(BigDecimal dailyBudgetUsd, Integer dailyMaxCalls, Integer maxCallsPerMinute, String timeZone,
                         long callsToday, BigDecimal costToday, long callsLastMinute, String blockedBy) {}

    private final AiLogRepository logs;
    private final AppProperties.AiLimits limits;
    private final boolean priced;

    @Autowired
    public AiUsageLimiter(AiLogRepository logs, AppProperties props) {
        this(logs, props.ai().limits(), !props.ai().pricing().isEmpty());
    }

    AiUsageLimiter(AiLogRepository logs, AppProperties.AiLimits limits, boolean priced) {
        this.logs = logs;
        this.limits = limits;
        this.priced = priced;
        validate();
    }

    private void validate() {
        try {
            ZoneId.of(limits.timeZone());
        } catch (DateTimeException e) {
            throw new IllegalStateException("app.ai.limits.time-zone '" + limits.timeZone() + "' is not a valid time zone", e);
        }
        if (limits.dailyBudgetUsd() != null && limits.dailyBudgetUsd().signum() <= 0) {
            throw new IllegalStateException("app.ai.limits.daily-budget-usd must be greater than 0 (leave it empty for no budget)");
        }
        if (limits.dailyMaxCalls() < 0 || limits.maxCallsPerMinute() < 0) {
            throw new IllegalStateException("app.ai.limits call limits must be 0 (off) or positive");
        }
        if (limits.anyEnabled()) {
            log.info("AI limits: daily budget {} USD, {} calls/day, {} calls/min ({})",
                    limits.dailyBudgetUsd() == null ? "-" : limits.dailyBudgetUsd().toPlainString(),
                    limits.dailyMaxCalls() > 0 ? limits.dailyMaxCalls() : "-",
                    limits.maxCallsPerMinute() > 0 ? limits.maxCallsPerMinute() : "-", limits.timeZone());
        }
        if (limits.dailyBudgetUsd() != null && !priced) {
            log.warn("app.ai.limits.daily-budget-usd is set but app.ai.pricing is empty: costs stay NULL, so the budget"
                    + " can never be reached. Enter model prices or use app.ai.limits.daily-max-calls.");
        }
    }

    /** @throws JobDeferredException (not counted) when a limit is reached */
    public void checkBeforeCall(AiCallType type) {
        if (!limits.anyEnabled()) {
            return;
        }
        AiLogRepository.UsageSnapshot usage = logs.usageSnapshot(limits.timeZone());
        String blocked = blockedBy(usage);
        if (blocked == null) {
            return;
        }
        long wait = RATE.equals(blocked) ? RATE_WAIT_SECONDS : usage.secondsUntilNextDay() + NEXT_DAY_MARGIN_SECONDS;
        throw new JobDeferredException(blocked, describe(blocked, usage) + " (" + type + " call not made)", wait, false);
    }

    public Status status() {
        AiLogRepository.UsageSnapshot usage = logs.usageSnapshot(limits.timeZone());
        return new Status(limits.dailyBudgetUsd(),
                limits.dailyMaxCalls() > 0 ? limits.dailyMaxCalls() : null,
                limits.maxCallsPerMinute() > 0 ? limits.maxCallsPerMinute() : null,
                limits.timeZone(), usage.callsToday(), usage.costToday(), usage.callsLastMinute(),
                limits.anyEnabled() ? blockedBy(usage) : null);
    }

    /** Daily limits first: when both apply, the longer wait is the one that matters. */
    private String blockedBy(AiLogRepository.UsageSnapshot usage) {
        if (limits.dailyBudgetUsd() != null && usage.costToday() != null
                && usage.costToday().compareTo(limits.dailyBudgetUsd()) >= 0) {
            return DAILY_BUDGET;
        }
        if (limits.dailyMaxCalls() > 0 && usage.callsToday() >= limits.dailyMaxCalls()) {
            return DAILY_CALLS;
        }
        if (limits.maxCallsPerMinute() > 0 && usage.callsLastMinute() >= limits.maxCallsPerMinute()) {
            return RATE;
        }
        return null;
    }

    private String describe(String code, AiLogRepository.UsageSnapshot usage) {
        return switch (code) {
            case DAILY_BUDGET -> "daily AI budget reached: " + usage.costToday().toPlainString() + " / "
                    + limits.dailyBudgetUsd().toPlainString() + " USD today";
            case DAILY_CALLS -> "daily AI call limit reached: " + usage.callsToday() + " / " + limits.dailyMaxCalls() + " calls today";
            default -> "AI rate limit reached: " + usage.callsLastMinute() + " / " + limits.maxCallsPerMinute()
                    + " calls in the last minute";
        };
    }
}

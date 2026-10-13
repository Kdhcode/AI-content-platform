package com.aicontent.platform.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicontent.platform.config.AppProperties.AiLimits;
import com.aicontent.platform.job.JobDeferredException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AiUsageLimiterTest {

    /** Answers every snapshot with fixed usage and counts the queries. */
    private static final class FakeLogs extends AiLogRepository {
        int queries;
        private final UsageSnapshot usage;

        FakeLogs(long callsToday, String costToday, long callsLastMinute, long secondsUntilNextDay) {
            super(null);
            this.usage = new UsageSnapshot(callsToday, new BigDecimal(costToday), callsLastMinute, secondsUntilNextDay);
        }

        @Override
        public UsageSnapshot usageSnapshot(String timeZone) {
            queries++;
            return usage;
        }
    }

    private static AiLimits limits(String budget, int perDay, int perMinute) {
        return new AiLimits(budget == null ? null : new BigDecimal(budget), perDay, perMinute, "Asia/Seoul");
    }

    private static JobDeferredException blocked(AiUsageLimiter limiter) {
        return assertThrows(JobDeferredException.class, () -> limiter.checkBeforeCall(AiCallType.ARTICLE_ANALYSIS));
    }

    @Test
    void noLimitMeansNoQuery() {
        var logs = new FakeLogs(1_000_000, "1000", 1000, 100);
        var limiter = new AiUsageLimiter(logs, limits(null, 0, 0), true);
        assertDoesNotThrow(() -> limiter.checkBeforeCall(AiCallType.EMBEDDING));
        assertEquals(0, logs.queries);
    }

    @Test
    void belowEveryLimitPasses() {
        var limiter = new AiUsageLimiter(new FakeLogs(9, "0.99", 4, 100), limits("1.00", 10, 5), true);
        assertDoesNotThrow(() -> limiter.checkBeforeCall(AiCallType.ISSUE_CLASSIFY));
    }

    @Test
    void reachedBudgetWaitsUntilTheNextDayWithoutCounting() {
        var e = blocked(new AiUsageLimiter(new FakeLogs(3, "1.00", 0, 3600), limits("1.00", 0, 0), true));
        assertEquals(AiUsageLimiter.DAILY_BUDGET, e.errorCode());
        assertFalse(e.counted());
        assertEquals(3600 + AiUsageLimiter.NEXT_DAY_MARGIN_SECONDS, e.delaySeconds());
        assertTrue(e.getMessage().contains("1.00 / 1.00 USD"), e.getMessage());
        assertTrue(e.getMessage().contains("ARTICLE_ANALYSIS"), e.getMessage());
    }

    @Test
    void reachedDailyCallLimitWaitsUntilTheNextDay() {
        var e = blocked(new AiUsageLimiter(new FakeLogs(10, "0", 0, 50), limits(null, 10, 0), false));
        assertEquals(AiUsageLimiter.DAILY_CALLS, e.errorCode());
        assertFalse(e.counted());
        assertEquals(50 + AiUsageLimiter.NEXT_DAY_MARGIN_SECONDS, e.delaySeconds());
    }

    @Test
    void reachedRateLimitWaitsOneWindow() {
        var e = blocked(new AiUsageLimiter(new FakeLogs(1, "0", 5, 50_000), limits(null, 0, 5), false));
        assertEquals(AiUsageLimiter.RATE, e.errorCode());
        assertFalse(e.counted());
        assertEquals(AiUsageLimiter.RATE_WAIT_SECONDS, e.delaySeconds());
    }

    @Test
    void dailyLimitWinsOverTheRateLimit() {
        var e = blocked(new AiUsageLimiter(new FakeLogs(10, "0", 5, 50_000), limits(null, 10, 5), false));
        assertEquals(AiUsageLimiter.DAILY_CALLS, e.errorCode());
    }

    @Test
    void statusReportsOffLimitsAsNull() {
        var status = new AiUsageLimiter(new FakeLogs(7, "0.25", 2, 100), limits(null, 0, 3), true).status();
        assertNull(status.dailyBudgetUsd());
        assertNull(status.dailyMaxCalls());
        assertEquals(3, status.maxCallsPerMinute());
        assertEquals(7, status.callsToday());
        assertEquals(new BigDecimal("0.25"), status.costToday());
        assertEquals(2, status.callsLastMinute());
        assertNull(status.blockedBy());
        assertEquals("Asia/Seoul", status.timeZone());

        var blocked = new AiUsageLimiter(new FakeLogs(7, "0.25", 3, 100), limits(null, 0, 3), true).status();
        assertEquals(AiUsageLimiter.RATE, blocked.blockedBy());
    }

    @Test
    void invalidSettingsFailAtStartup() {
        var logs = new FakeLogs(0, "0", 0, 0);
        assertThrows(IllegalStateException.class, () -> new AiUsageLimiter(logs, limits("0", 0, 0), true));
        assertThrows(IllegalStateException.class, () -> new AiUsageLimiter(logs, limits("-1", 0, 0), true));
        assertThrows(IllegalStateException.class, () -> new AiUsageLimiter(logs, limits(null, -1, 0), true));
        assertThrows(IllegalStateException.class, () -> new AiUsageLimiter(logs, limits(null, 0, -1), true));
        assertThrows(IllegalStateException.class,
                () -> new AiUsageLimiter(logs, new AiLimits(null, 0, 0, "Not/AZone"), true));
    }
}

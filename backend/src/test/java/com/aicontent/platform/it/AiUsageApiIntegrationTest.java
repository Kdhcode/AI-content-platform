package com.aicontent.platform.it;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AiUsageApiIntegrationTest extends IntegrationTestBase {

    /** 00:00 today in Korea, as an instant. */
    private static final String KST_MIDNIGHT = "(date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul')";

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void operator() {
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES ('op', :h) ON CONFLICT (lower(username)) DO NOTHING")
                .param("h", encoder.encode("op-pw")).update();
        jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, 'OPERATOR' FROM app_user WHERE username = 'op' ON CONFLICT DO NOTHING").update();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/admin/ai-usage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void daysMustBeBetween1And90() throws Exception {
        for (String days : new String[] {"0", "91", "-1", "abc"}) {
            mvc.perform(get("/api/admin/ai-usage?days=" + days).with(httpBasic("op", "op-pw")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        for (String query : new String[] {"", "?days=1", "?days=90"}) {
            mvc.perform(get("/api/admin/ai-usage" + query).with(httpBasic("op", "op-pw")))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/admin/ai-usage").with(httpBasic("op", "op-pw")))
                .andExpect(jsonPath("$.data.days").value(7));
    }

    @Test
    void limitsAreOffByDefault() throws Exception {
        log("EMBEDDING", "stub", "stub-embedding", null, null, null, true, "now()");
        mvc.perform(get("/api/admin/ai-usage").with(httpBasic("op", "op-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limits.dailyBudgetUsd").value(nullValue()))
                .andExpect(jsonPath("$.data.limits.dailyMaxCalls").value(nullValue()))
                .andExpect(jsonPath("$.data.limits.maxCallsPerMinute").value(nullValue()))
                .andExpect(jsonPath("$.data.limits.timeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.limits.callsToday").value(1))
                .andExpect(jsonPath("$.data.limits.callsLastMinute").value(1))
                .andExpect(jsonPath("$.data.limits.blockedBy").value(nullValue()));
    }

    @Test
    void periodStartsAtKoreanMidnight() throws Exception {
        log("ARTICLE_ANALYSIS", "openai", "m", 10, 5, "0.100000", true, KST_MIDNIGHT);
        log("ARTICLE_ANALYSIS", "openai", "m", 10, 5, "0.100000", true, KST_MIDNIGHT + " - interval '1 second'");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        // the call one second before midnight belongs to yesterday (KST), whatever the server time zone is
        mvc.perform(get("/api/admin/ai-usage?days=1").with(httpBasic("op", "op-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].day").value(today.toString()))
                .andExpect(jsonPath("$.data.totals.calls").value(1));
        mvc.perform(get("/api/admin/ai-usage?days=2").with(httpBasic("op", "op-pw")))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].day").value(today.toString()))
                .andExpect(jsonPath("$.data.rows[1].day").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.data.totals.calls").value(2));
    }

    @Test
    void unknownCostStaysNullAndIsCountedAsUnpricedOnlyWhenTokensExist() throws Exception {
        log("ARTICLE_ANALYSIS", "openai", "a-priced", 100, 50, "0.250000", true, "now()");
        log("ARTICLE_ANALYSIS", "openai", "a-priced", 100, 50, "0.250000", false, "now()");
        log("ISSUE_CLASSIFY", "openai", "b-unpriced", 30, 40, null, true, "now()");
        log("EMBEDDING", "stub", "stub-embedding", null, null, null, true, "now()");

        // ORDER BY day DESC, call_type, model
        mvc.perform(get("/api/admin/ai-usage?days=1").with(httpBasic("op", "op-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rows.length()").value(3))
                .andExpect(jsonPath("$.data.rows[0].callType").value("ARTICLE_ANALYSIS"))
                .andExpect(jsonPath("$.data.rows[0].model").value("a-priced"))
                .andExpect(jsonPath("$.data.rows[0].calls").value(2))
                .andExpect(jsonPath("$.data.rows[0].failures").value(1))
                .andExpect(jsonPath("$.data.rows[0].inputTokens").value(200))
                .andExpect(jsonPath("$.data.rows[0].outputTokens").value(100))
                .andExpect(jsonPath("$.data.rows[0].costUsd").value(0.5))
                .andExpect(jsonPath("$.data.rows[0].unpricedCalls").value(0))
                .andExpect(jsonPath("$.data.rows[1].callType").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.rows[1].provider").value("stub"))
                .andExpect(jsonPath("$.data.rows[1].costUsd").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[1].inputTokens").value(0))
                .andExpect(jsonPath("$.data.rows[1].unpricedCalls").value(0))
                .andExpect(jsonPath("$.data.rows[2].model").value("b-unpriced"))
                .andExpect(jsonPath("$.data.rows[2].costUsd").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[2].unpricedCalls").value(1))
                .andExpect(jsonPath("$.data.totals.calls").value(4))
                .andExpect(jsonPath("$.data.totals.failures").value(1))
                .andExpect(jsonPath("$.data.totals.inputTokens").value(230))
                .andExpect(jsonPath("$.data.totals.outputTokens").value(140))
                .andExpect(jsonPath("$.data.totals.costUsd").value(0.5))
                .andExpect(jsonPath("$.data.totals.unpricedCalls").value(1))
                .andExpect(jsonPath("$.data.rows[0].response").doesNotExist())
                .andExpect(jsonPath("$.data.rows[0].errorMessage").doesNotExist());
    }

    @Test
    void totalCostIsNullNotZeroWhenNothingIsPriced() throws Exception {
        mvc.perform(get("/api/admin/ai-usage").with(httpBasic("op", "op-pw")))
                .andExpect(jsonPath("$.data.rows.length()").value(0))
                .andExpect(jsonPath("$.data.totals.calls").value(0))
                .andExpect(jsonPath("$.data.totals.costUsd").value(nullValue()));

        log("EMBEDDING", "stub", "stub-embedding", null, null, null, true, "now()");
        mvc.perform(get("/api/admin/ai-usage").with(httpBasic("op", "op-pw")))
                .andExpect(jsonPath("$.data.totals.calls").value(1))
                .andExpect(jsonPath("$.data.totals.costUsd").value(nullValue()))
                .andExpect(jsonPath("$.data.totals.unpricedCalls").value(0));
    }

    /** {@code createdAt} is a SQL expression written by the test itself. */
    private void log(String callType, String provider, String model, Integer in, Integer out, String cost, boolean success,
                     String createdAt) {
        jdbc.sql("""
                INSERT INTO ai_log (call_type, provider, model, input_tokens, output_tokens, estimated_cost_usd, success,
                    error_message, response, created_at)
                VALUES (:type, :provider, :model, :in, :out, CAST(:cost AS numeric), :success, 'provider error text',
                    'raw model output', %s)""".formatted(createdAt))
                .param("type", callType).param("provider", provider).param("model", model).param("in", in)
                .param("out", out).param("cost", cost).param("success", success).update();
    }
}

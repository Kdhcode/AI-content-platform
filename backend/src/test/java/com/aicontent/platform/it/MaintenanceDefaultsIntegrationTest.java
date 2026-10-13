package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.ai.AiLogRetentionService;
import com.aicontent.platform.issue.IssueAutoCloseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Retention and auto-close are policy decisions (OPEN ITEM #8, #9): with default settings they must not touch
 * anything, however old the data is.
 */
class MaintenanceDefaultsIntegrationTest extends IntegrationTestBase {

    @Autowired AiLogRetentionService retention;
    @Autowired IssueAutoCloseService autoClose;

    @Test
    void defaultSettingsKeepEverything() {
        jdbc.sql("""
                INSERT INTO ai_log (call_type, provider, model, success, response, created_at)
                VALUES ('ARTICLE_ANALYSIS', 'stub', 'm', TRUE, 'output', now() - interval '3650 days')""").update();
        jdbc.sql("""
                INSERT INTO issue (title, status, article_count, last_updated_at, created_at, updated_at)
                VALUES ('t', 'ACTIVE', 1, now() - interval '3650 days', now() - interval '3650 days',
                        now() - interval '3650 days')""").update();

        assertThat(retention.clearExpiredResponses()).isZero();
        assertThat(autoClose.closeInactive()).isZero();

        assertThat(count("SELECT count(*) FROM ai_log WHERE response = 'output'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM issue WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_audit_log")).isZero();
    }
}

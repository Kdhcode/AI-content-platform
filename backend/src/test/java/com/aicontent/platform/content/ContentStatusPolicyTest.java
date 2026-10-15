package com.aicontent.platform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.content.ContentStatusPolicy.Action;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The whole final table of docs/CONTENT_A_PLAN.md §4: every action x every status x both roles. */
class ContentStatusPolicyTest {

    private static final List<String> STATUSES = List.of("DRAFT", "REVIEW", "APPROVED", "HOLD", "EXCLUDED", "ARCHIVED");

    /** action -> (allowed source status -> result) */
    private static final Map<Action, Map<String, String>> TABLE = Map.of(
            Action.SUBMIT, Map.of("DRAFT", "REVIEW"),
            Action.APPROVE, Map.of("REVIEW", "APPROVED"),
            Action.RETURN, Map.of("REVIEW", "DRAFT"),
            Action.HOLD, Map.of("DRAFT", "HOLD", "REVIEW", "HOLD", "APPROVED", "HOLD"),
            Action.RESUME, Map.of("HOLD", "DRAFT"),
            Action.EXCLUDE, Map.of("DRAFT", "EXCLUDED", "REVIEW", "EXCLUDED", "HOLD", "EXCLUDED"),
            Action.ARCHIVE, Map.of("APPROVED", "ARCHIVED", "HOLD", "ARCHIVED"),
            Action.RESTORE, Map.of("EXCLUDED", "DRAFT", "ARCHIVED", "DRAFT"));

    private static ErrorCode errorOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (BusinessException e) {
            return e.errorCode();
        }
    }

    @Test
    void everyCombinationFollowsTheTable() {
        for (Action action : Action.values()) {
            boolean adminOnly = action == Action.APPROVE || action == Action.RESTORE;
            for (String status : STATUSES) {
                String expected = TABLE.get(action).get(status);
                // SYSTEM_ADMIN: allowed exactly where the table says
                if (expected != null) {
                    assertThat(ContentStatusPolicy.decide(action, status, true)).as(action + " from " + status).isEqualTo(expected);
                } else {
                    assertThat(errorOf(() -> ContentStatusPolicy.decide(action, status, true)))
                            .as(action + " from " + status).isEqualTo(ErrorCode.CONTENT_STATE_INVALID);
                }
                // OPERATOR: approve / restore always forbidden, everything else like SYSTEM_ADMIN
                ErrorCode operator = errorOf(() -> ContentStatusPolicy.decide(action, status, false));
                if (adminOnly) {
                    assertThat(operator).as("operator " + action + " from " + status).isEqualTo(ErrorCode.FORBIDDEN);
                } else if (expected != null) {
                    assertThat(operator).as("operator " + action + " from " + status).isNull();
                } else {
                    assertThat(operator).as("operator " + action + " from " + status).isEqualTo(ErrorCode.CONTENT_STATE_INVALID);
                }
            }
        }
    }

    @Test
    void noActionApprovesWithoutReviewOrBringsBackAnApproval() {
        for (Action action : Action.values()) {
            for (String status : STATUSES) {
                String result;
                try {
                    result = ContentStatusPolicy.decide(action, status, true);
                } catch (BusinessException e) {
                    continue;
                }
                if ("APPROVED".equals(result)) {
                    assertThat(status).as("only REVIEW leads to APPROVED").isEqualTo("REVIEW");
                }
                if (action == Action.RESUME || action == Action.RESTORE) {
                    assertThat(result).as(action + " always starts over from DRAFT").isEqualTo("DRAFT");
                }
            }
        }
        // restoring clears the review record, so an approval never comes back by itself
        assertThat(ContentStatusPolicy.rule(Action.RESTORE).approved()).isEqualTo(ContentStatusPolicy.Mark.CLEAR);
        assertThat(ContentStatusPolicy.rule(Action.RESUME).approved()).isEqualTo(ContentStatusPolicy.Mark.CLEAR);
        assertThat(ContentStatusPolicy.rule(Action.HOLD).approved()).isEqualTo(ContentStatusPolicy.Mark.CLEAR);
    }

    @Test
    void reasonsAreRequiredWhereTheTableSaysAndTrimmed() {
        for (Action a : List.of(Action.RETURN, Action.HOLD, Action.EXCLUDE, Action.RESTORE)) {
            assertThatThrownBy(() -> ContentStatusPolicy.reason(a, null)).as(a.name()).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> ContentStatusPolicy.reason(a, "   ")).as(a.name()).isInstanceOf(BusinessException.class);
            assertThat(ContentStatusPolicy.reason(a, "  사유  ")).isEqualTo("사유");
        }
        for (Action a : List.of(Action.SUBMIT, Action.APPROVE, Action.RESUME, Action.ARCHIVE)) {
            assertThat(ContentStatusPolicy.reason(a, null)).as(a.name()).isNull();
        }
        assertThatThrownBy(() -> ContentStatusPolicy.reason(Action.HOLD, "x".repeat(501))).isInstanceOf(BusinessException.class);
        assertThat(ContentStatusPolicy.reason(Action.HOLD, "x".repeat(500))).hasSize(500);
    }
}

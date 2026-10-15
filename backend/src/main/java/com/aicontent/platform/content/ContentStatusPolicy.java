package com.aicontent.platform.content;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import java.util.Map;
import java.util.Set;

/**
 * Content status actions (docs/CONTENT_A_PLAN.md §4, final table). The only way a status changes, apart from the edit
 * rule (REVIEW / APPROVED -> DRAFT when the content itself changes). Pure rules, no I/O.
 */
public final class ContentStatusPolicy {

    public enum Action { SUBMIT, APPROVE, RETURN, HOLD, RESUME, EXCLUDE, ARCHIVE, RESTORE }

    /** What happens to a review-record column. */
    enum Mark { SET, KEEP, CLEAR }

    /**
     * @param storesReason the reason becomes {@code status_reason} (null clears it); otherwise status_reason is cleared
     *                     and a given reason only goes to the audit record
     */
    record Rule(Set<String> from, String to, boolean systemAdminOnly, boolean reasonRequired, boolean storesReason,
                Mark submitted, Mark approved) {}

    private static final Map<Action, Rule> RULES = Map.of(
            Action.SUBMIT,  new Rule(Set.of("DRAFT"), "REVIEW", false, false, false, Mark.SET, Mark.CLEAR),
            Action.APPROVE, new Rule(Set.of("REVIEW"), "APPROVED", true, false, false, Mark.KEEP, Mark.SET),
            Action.RETURN,  new Rule(Set.of("REVIEW"), "DRAFT", false, true, true, Mark.CLEAR, Mark.CLEAR),
            Action.HOLD,    new Rule(Set.of("DRAFT", "REVIEW", "APPROVED"), "HOLD", false, true, true, Mark.CLEAR, Mark.CLEAR),
            Action.RESUME,  new Rule(Set.of("HOLD"), "DRAFT", false, false, false, Mark.CLEAR, Mark.CLEAR),
            Action.EXCLUDE, new Rule(Set.of("DRAFT", "REVIEW", "HOLD"), "EXCLUDED", false, true, true, Mark.CLEAR, Mark.CLEAR),
            Action.ARCHIVE, new Rule(Set.of("APPROVED", "HOLD"), "ARCHIVED", false, false, true, Mark.KEEP, Mark.KEEP),
            Action.RESTORE, new Rule(Set.of("EXCLUDED", "ARCHIVED"), "DRAFT", true, true, true, Mark.CLEAR, Mark.CLEAR));

    public static final int MAX_REASON_CHARS = 500;

    private ContentStatusPolicy() {}

    static Rule rule(Action action) {
        return RULES.get(action);
    }

    /**
     * @return the resulting status; 403 when the role may not perform the action, 409 when the current status does
     *         not allow it (e.g. approving a DRAFT, resuming an ARCHIVED content)
     */
    public static String decide(Action action, String currentStatus, boolean systemAdmin) {
        Rule rule = RULES.get(action);
        if (rule.systemAdminOnly() && !systemAdmin) {
            throw new BusinessException(ErrorCode.FORBIDDEN, name(action) + "은(는) SYSTEM_ADMIN만 할 수 있습니다.");
        }
        if (!rule.from().contains(currentStatus)) {
            throw new BusinessException(ErrorCode.CONTENT_STATE_INVALID,
                    currentStatus + " 상태에서는 " + name(action) + "을(를) 할 수 없습니다(가능한 상태: "
                            + String.join(", ", rule.from().stream().sorted().toList()) + ").",
                    Map.of("status", currentStatus, "action", action.name(), "allowedFrom", rule.from().stream().sorted().toList()));
        }
        return rule.to();
    }

    /** Trimmed reason or null; 400 when required and missing, or longer than 500 characters. */
    public static String reason(Action action, String raw) {
        String reason = raw == null || raw.isBlank() ? null : raw.strip();
        if (reason == null && RULES.get(action).reasonRequired()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, name(action) + "에는 사유가 필요합니다.");
        }
        if (reason != null && reason.length() > MAX_REASON_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "사유는 " + MAX_REASON_CHARS + "자 이하여야 합니다.");
        }
        return reason;
    }

    static String name(Action action) {
        return switch (action) {
            case SUBMIT -> "검수 요청";
            case APPROVE -> "승인";
            case RETURN -> "반려";
            case HOLD -> "보류";
            case RESUME -> "보류 해제";
            case EXCLUDE -> "제외";
            case ARCHIVE -> "보관";
            case RESTORE -> "복귀";
        };
    }
}

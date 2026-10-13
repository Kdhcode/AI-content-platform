package com.aicontent.platform.issue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aicontent.platform.issue.IssueClassificationService.LockScope;
import org.junit.jupiter.api.Test;

class ClassificationLockKeyTest {

    @Test
    void globalScopeIsOneKeyWhateverTheCategory() {
        assertEquals("issue-classification", IssueClassificationService.lockKey(LockScope.GLOBAL, "ECONOMY"));
        assertEquals("issue-classification", IssueClassificationService.lockKey(LockScope.GLOBAL, null));
    }

    @Test
    void categoryScopeIsOneKeyPerCategory() {
        assertEquals("issue-classification:ECONOMY", IssueClassificationService.lockKey(LockScope.CATEGORY, "economy"));
        assertEquals("issue-classification:NONE", IssueClassificationService.lockKey(LockScope.CATEGORY, null));
        assertEquals("issue-classification:NONE", IssueClassificationService.lockKey(LockScope.CATEGORY, " "));
    }

    @Test
    void scopeSettingIsValidated() {
        assertEquals(LockScope.GLOBAL, IssueClassificationService.parseLockScope("GLOBAL"));
        assertEquals(LockScope.CATEGORY, IssueClassificationService.parseLockScope(" category "));
        assertThrows(IllegalStateException.class, () -> IssueClassificationService.parseLockScope("cluster"));
        assertThrows(IllegalStateException.class, () -> IssueClassificationService.parseLockScope(null));
    }
}

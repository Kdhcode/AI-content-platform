package com.aicontent.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicontent.platform.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiLogRetentionServiceTest {

    /** Records every call and answers with the queued batch sizes (then 0). */
    private static final class FakeLogs extends AiLogRepository {
        final List<int[]> calls = new ArrayList<>();
        private final int[] answers;

        FakeLogs(int... answers) {
            super(null);
            this.answers = answers;
        }

        @Override
        public int clearResponsesOlderThan(int days, int batchSize) {
            int answer = calls.size() < answers.length ? answers[calls.size()] : 0;
            calls.add(new int[] {days, batchSize});
            return answer;
        }
    }

    @Test
    void offByDefaultTouchesNothing() {
        var logs = new FakeLogs(5);
        assertEquals(0, new AiLogRetentionService(logs, new AppProperties.Retention(0, 1000, 3600000)).clearExpiredResponses());
        assertTrue(logs.calls.isEmpty());
    }

    @Test
    void repeatsBatchesUntilAPartialBatch() {
        var logs = new FakeLogs(2, 2, 1);
        assertEquals(5, new AiLogRetentionService(logs, new AppProperties.Retention(30, 2, 3600000)).clearExpiredResponses());
        assertEquals(3, logs.calls.size());
        assertEquals(30, logs.calls.get(0)[0]);
        assertEquals(2, logs.calls.get(0)[1]);
    }

    @Test
    void oneRunIsBounded() {
        int[] full = new int[AiLogRetentionService.MAX_BATCHES_PER_RUN + 10];
        java.util.Arrays.fill(full, 1);
        var logs = new FakeLogs(full);
        assertEquals(AiLogRetentionService.MAX_BATCHES_PER_RUN,
                new AiLogRetentionService(logs, new AppProperties.Retention(7, 1, 3600000)).clearExpiredResponses());
        assertEquals(AiLogRetentionService.MAX_BATCHES_PER_RUN, logs.calls.size());
    }

    @Test
    void nonPositiveBatchSizeIsTreatedAsOne() {
        var logs = new FakeLogs(1, 0);
        new AiLogRetentionService(logs, new AppProperties.Retention(7, 0, 3600000)).clearExpiredResponses();
        assertEquals(1, logs.calls.get(0)[1]);
    }
}

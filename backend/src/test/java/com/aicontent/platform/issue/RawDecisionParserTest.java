package com.aicontent.platform.issue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Match;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RawDecisionParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsAV1AnswerWithoutComparison() throws Exception {
        var raw = RawDecisionParser.parse(mapper.readTree(
                "{\"decision\":\"SAME_ISSUE\",\"confidence\":0.9,\"matchedIssueId\":3,\"reason\":\"same fire\"}"));
        assertEquals(Decision.SAME_ISSUE, raw.decision());
        assertEquals(3L, raw.matchedIssueId());
        assertNull(raw.comparison());
    }

    @Test
    void readsAV3AnswerWithComparison() throws Exception {
        var raw = RawDecisionParser.parse(mapper.readTree("{\"decision\":\"NEW_ISSUE\",\"confidence\":0.8,\"matchedIssueId\":null,"
                + "\"reason\":\"other quarter\",\"comparison\":{\"actor\":\"SAME\",\"action\":\"SAME\",\"period\":\"DIFFERENT\","
                + "\"place\":\"NOT_APPLICABLE\"}}"));
        assertEquals(Decision.NEW_ISSUE, raw.decision());
        assertNull(raw.matchedIssueId());
        assertEquals(Match.DIFFERENT, raw.comparison().period());
        assertEquals(Match.NOT_APPLICABLE, raw.comparison().place());
    }
}

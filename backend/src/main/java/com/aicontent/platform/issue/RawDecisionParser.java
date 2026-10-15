package com.aicontent.platform.issue;

import com.aicontent.platform.issue.DecisionPolicy.Comparison;
import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Match;
import com.aicontent.platform.issue.DecisionPolicy.RawDecision;
import com.fasterxml.jackson.databind.JsonNode;

/** Reads a schema-validated classifier answer (any prompt version). Shared by production and the evaluation judge. */
public final class RawDecisionParser {

    private RawDecisionParser() {
    }

    public static RawDecision parse(JsonNode v) {
        Long matched = v.hasNonNull("matchedIssueId") ? v.get("matchedIssueId").asLong() : null;
        Comparison comparison = null;
        JsonNode c = v.get("comparison");
        if (c != null && c.isObject()) {
            comparison = new Comparison(match(c, "actor"), match(c, "action"), match(c, "period"), match(c, "place"));
        }
        return new RawDecision(Decision.valueOf(v.get("decision").asText()), v.get("confidence").asDouble(), matched,
                v.get("reason").asText(), comparison);
    }

    private static Match match(JsonNode c, String field) {
        return Match.valueOf(c.get(field).asText());
    }
}

package com.aicontent.platform.issue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aicontent.platform.issue.IssueContentMerger.Entity;
import com.aicontent.platform.issue.IssueContentMerger.MemberAnalysis;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IssueContentMergerTest {

    @Test
    void deduplicatesFactsCaseAndSpaceInsensitivelyKeepingFirstOrder() {
        var m = IssueContentMerger.merge(List.of(
                new MemberAnalysis(List.of("A  사실", "B 사실"), List.of()),
                new MemberAnalysis(List.of("a 사실", "C 사실"), List.of())));
        assertEquals(List.of("A  사실".trim(), "B 사실", "C 사실"), m.keyFacts());
    }

    @Test
    void entitiesRankedByNumberOfArticlesMentioningThem() {
        var m = IssueContentMerger.merge(List.of(
                new MemberAnalysis(List.of(), List.of(new Entity("갑", "ORG"), new Entity("을", "ORG"), new Entity("을", "ORG"))),
                new MemberAnalysis(List.of(), List.of(new Entity("을", "ORG")))));
        assertEquals("을", m.entities().get(0).name());
        assertEquals(2, m.entities().size());
    }

    @Test
    void sameNameDifferentTypeStaySeparate() {
        var m = IssueContentMerger.merge(List.of(
                new MemberAnalysis(List.of(), List.of(new Entity("가상", "PLACE"), new Entity("가상", "ORG")))));
        assertEquals(2, m.entities().size());
    }

    @Test
    void capsAreEnforced() {
        List<String> facts = new ArrayList<>();
        List<Entity> ents = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            facts.add("fact " + i);
            ents.add(new Entity("e" + i, "OTHER"));
        }
        var m = IssueContentMerger.merge(List.of(new MemberAnalysis(facts, ents)));
        assertEquals(IssueContentMerger.MAX_FACTS, m.keyFacts().size());
        assertEquals(IssueContentMerger.MAX_ENTITIES, m.entities().size());
    }

    @Test
    void emptyInputGivesEmptyOutput() {
        var m = IssueContentMerger.merge(List.of());
        assertEquals(0, m.keyFacts().size());
        assertEquals(0, m.entities().size());
    }
}

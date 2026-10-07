package com.aicontent.platform.issue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rebuilds an issue's key facts and entities from its member articles' analyses. Pure and order-stable: facts keep
 * the order in which they first appear (articles are passed oldest first), entities are ranked by how many articles
 * mention them. Used after every membership change so merge/split/move never leave stale content behind.
 */
public final class IssueContentMerger {

    public static final int MAX_FACTS = 15;
    public static final int MAX_ENTITIES = 20;

    public record Entity(String name, String type) {}

    public record MemberAnalysis(List<String> keyFacts, List<Entity> entities) {}

    public record Merged(List<String> keyFacts, List<Entity> entities) {}

    private IssueContentMerger() {}

    public static Merged merge(List<MemberAnalysis> members) {
        Map<String, String> facts = new LinkedHashMap<>();
        Map<String, int[]> entityCounts = new LinkedHashMap<>();
        Map<String, Entity> entityByKey = new LinkedHashMap<>();
        for (MemberAnalysis m : members) {
            if (m.keyFacts() != null) {
                for (String f : m.keyFacts()) {
                    String key = normalize(f);
                    if (!key.isEmpty()) {
                        facts.putIfAbsent(key, f.trim());
                    }
                }
            }
            if (m.entities() != null) {
                List<String> seenInThisArticle = new ArrayList<>();
                for (Entity e : m.entities()) {
                    if (e == null || e.name() == null || e.name().isBlank()) {
                        continue;
                    }
                    String key = normalize(e.name()) + "|" + e.type();
                    if (seenInThisArticle.contains(key)) {
                        continue;
                    }
                    seenInThisArticle.add(key);
                    entityCounts.computeIfAbsent(key, k -> new int[1])[0]++;
                    entityByKey.putIfAbsent(key, new Entity(e.name().trim(), e.type()));
                }
            }
        }
        List<String> keyFacts = facts.values().stream().limit(MAX_FACTS).toList();
        List<Entity> entities = entityByKey.entrySet().stream()
                .sorted((a, b) -> Integer.compare(entityCounts.get(b.getKey())[0], entityCounts.get(a.getKey())[0]))
                .limit(MAX_ENTITIES)
                .map(Map.Entry::getValue)
                .toList();
        return new Merged(keyFacts, entities);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}

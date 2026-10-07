package com.aicontent.platform.ai;

import java.util.List;

/**
 * Text that gets embedded for an article: title + summary + key facts + entity names (design doc). Pure function so
 * the exact embedding input is testable and can be versioned if it ever changes.
 */
public final class EmbeddingTextBuilder {

    private static final int MAX_CHARS = 6000;

    private EmbeddingTextBuilder() {}

    public static String build(String title, String summary, List<String> keyFacts, List<String> entityNames) {
        StringBuilder sb = new StringBuilder();
        append(sb, title);
        append(sb, summary);
        if (keyFacts != null) {
            keyFacts.forEach(f -> append(sb, f));
        }
        if (entityNames != null && !entityNames.isEmpty()) {
            append(sb, String.join(", ", entityNames));
        }
        return sb.length() <= MAX_CHARS ? sb.toString() : sb.substring(0, MAX_CHARS);
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(part.trim());
    }
}

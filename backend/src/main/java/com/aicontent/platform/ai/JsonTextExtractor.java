package com.aicontent.platform.ai;

/**
 * Pulls the JSON object out of an LLM answer that may be wrapped in a markdown fence or surrounded by prose.
 * Pure string logic (no JSON library): it only finds the first balanced {...} block, honouring string literals.
 * Whether that block is valid JSON / schema-conformant is decided afterwards by the parser and schema validator.
 */
public final class JsonTextExtractor {

    private JsonTextExtractor() {}

    /** @return the first balanced object text, or {@code null} if none exists. */
    public static String extractObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = balancedEnd(text, start);
            if (end > 0) {
                return text.substring(start, end + 1);
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    private static int balancedEnd(String s, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}

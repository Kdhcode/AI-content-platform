package com.aicontent.platform.ai;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict {@code {{name}}} substitution: a missing variable is a programming error, never silently empty. */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private TemplateRenderer() {}

    public static String render(String template, Map<String, ?> variables) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            if (!variables.containsKey(name)) {
                throw new IllegalArgumentException("prompt variable '" + name + "' has no value");
            }
            Object v = variables.get(name);
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}

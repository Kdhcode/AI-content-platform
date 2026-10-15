package com.aicontent.platform.content;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structure and size rules for a content body (docs/CONTENT_A_PLAN.md §2), checked on every save:
 * <pre>{"schemaVersion": 1, "sections": [{"id", "heading", "paragraphs": [{"id", "text"}]}]}</pre>
 * Array order is display order. Text is plain text (never interpreted as HTML). A DRAFT may be empty: rules that only
 * apply when submitting for review (non-empty headings, at least one paragraph, ...) are not checked here.
 */
public final class ContentBodyValidator {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_SECTIONS = 30;
    public static final int MAX_PARAGRAPHS_PER_SECTION = 50;
    public static final int MAX_HEADING_CHARS = 120;
    public static final int MAX_PARAGRAPH_CHARS = 5_000;
    public static final int MAX_TOTAL_CHARS = 100_000;
    public static final int MAX_TITLE_CHARS = 200;
    static final int MAX_REPORTED_ERRORS = 20;

    private static final Pattern ID = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final Set<String> BODY_KEYS = Set.of("schemaVersion", "sections");
    private static final Set<String> SECTION_KEYS = Set.of("id", "heading", "paragraphs");
    private static final Set<String> PARAGRAPH_KEYS = Set.of("id", "text");

    public record Violation(String path, String message) {}

    private ContentBodyValidator() {}

    /** Throws 400 CONTENT_BODY_INVALID listing where the body breaks the rules (up to 20 locations). */
    public static void validate(JsonNode body) {
        List<Violation> violations = violations(body);
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.CONTENT_BODY_INVALID,
                    "콘텐츠 본문 형식이 올바르지 않습니다: " + violations.get(0).path() + " " + violations.get(0).message(),
                    Map.of("violations", violations.subList(0, Math.min(MAX_REPORTED_ERRORS, violations.size()))));
        }
    }

    /** Titles: at most 200 characters, a single line. Empty is fine for a draft. */
    public static void validateTitle(String title) {
        if (title == null) {
            return;
        }
        List<Violation> violations = new ArrayList<>();
        if (title.length() > MAX_TITLE_CHARS) violations.add(new Violation("title", "최대 " + MAX_TITLE_CHARS + "자입니다."));
        if (title.indexOf('\n') >= 0 || title.indexOf('\r') >= 0) violations.add(new Violation("title", "줄바꿈을 넣을 수 없습니다."));
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "제목이 올바르지 않습니다: " + violations.get(0).message(),
                    Map.of("violations", violations));
        }
    }

    static List<Violation> violations(JsonNode body) {
        List<Violation> out = new ArrayList<>();
        if (body == null || !body.isObject()) {
            out.add(new Violation("body", "JSON 객체여야 합니다."));
            return out;
        }
        unknownKeys(body, BODY_KEYS, "body", out);
        JsonNode version = body.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || version.asInt() != SCHEMA_VERSION) {
            out.add(new Violation("body.schemaVersion", SCHEMA_VERSION + "이어야 합니다."));
        }
        JsonNode sections = body.get("sections");
        if (sections == null || !sections.isArray()) {
            out.add(new Violation("body.sections", "배열이어야 합니다."));
            return out;
        }
        if (sections.size() > MAX_SECTIONS) {
            out.add(new Violation("body.sections", "최대 " + MAX_SECTIONS + "개입니다(현재 " + sections.size() + "개)."));
        }
        Set<String> ids = new HashSet<>();
        long totalChars = 0;
        for (int s = 0; s < sections.size(); s++) {
            String sp = "body.sections[" + s + "]";
            JsonNode section = sections.get(s);
            if (!section.isObject()) {
                out.add(new Violation(sp, "객체여야 합니다."));
                continue;
            }
            unknownKeys(section, SECTION_KEYS, sp, out);
            checkId(section.get("id"), sp + ".id", ids, out);
            JsonNode heading = section.get("heading");
            if (heading == null || !heading.isTextual()) {
                out.add(new Violation(sp + ".heading", "문자열이어야 합니다(빈 문자열 가능)."));
            } else {
                String h = heading.asText();
                totalChars += h.length();
                if (h.length() > MAX_HEADING_CHARS) out.add(new Violation(sp + ".heading", "최대 " + MAX_HEADING_CHARS + "자입니다."));
                if (h.indexOf('\n') >= 0 || h.indexOf('\r') >= 0) out.add(new Violation(sp + ".heading", "줄바꿈을 넣을 수 없습니다."));
            }
            JsonNode paragraphs = section.get("paragraphs");
            if (paragraphs == null || !paragraphs.isArray()) {
                out.add(new Violation(sp + ".paragraphs", "배열이어야 합니다(빈 배열 가능)."));
                continue;
            }
            if (paragraphs.size() > MAX_PARAGRAPHS_PER_SECTION) {
                out.add(new Violation(sp + ".paragraphs", "섹션당 최대 " + MAX_PARAGRAPHS_PER_SECTION + "개입니다."));
            }
            for (int p = 0; p < paragraphs.size(); p++) {
                String pp = sp + ".paragraphs[" + p + "]";
                JsonNode paragraph = paragraphs.get(p);
                if (!paragraph.isObject()) {
                    out.add(new Violation(pp, "객체여야 합니다."));
                    continue;
                }
                unknownKeys(paragraph, PARAGRAPH_KEYS, pp, out);
                checkId(paragraph.get("id"), pp + ".id", ids, out);
                JsonNode text = paragraph.get("text");
                if (text == null || !text.isTextual()) {
                    out.add(new Violation(pp + ".text", "문자열이어야 합니다(빈 문자열 가능)."));
                } else {
                    totalChars += text.asText().length();
                    if (text.asText().length() > MAX_PARAGRAPH_CHARS) {
                        out.add(new Violation(pp + ".text", "문단은 최대 " + MAX_PARAGRAPH_CHARS + "자입니다."));
                    }
                }
            }
        }
        if (totalChars > MAX_TOTAL_CHARS) {
            out.add(new Violation("body", "소제목과 문단을 합쳐 최대 " + MAX_TOTAL_CHARS + "자입니다(현재 " + totalChars + "자)."));
        }
        return out;
    }

    private static void checkId(JsonNode id, String path, Set<String> seen, List<Violation> out) {
        if (id == null || !id.isTextual() || !ID.matcher(id.asText()).matches()) {
            out.add(new Violation(path, "영문 소문자·숫자·하이픈 1~40자여야 합니다."));
        } else if (!seen.add(id.asText())) {
            out.add(new Violation(path, "본문 안에서 중복된 id입니다: " + id.asText()));
        }
    }

    private static void unknownKeys(JsonNode node, Set<String> allowed, String path, List<Violation> out) {
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!allowed.contains(name)) out.add(new Violation(path + "." + name, "허용되지 않는 필드입니다."));
        }
    }
}

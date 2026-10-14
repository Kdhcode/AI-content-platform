package com.aicontent.platform.news;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes recurring non-article text from the copy of an RSS body that is sent to the analysis model: copyright and
 * "no reposting" notices, feed-usage notices, link labels such as "▶ 앱 다운로드", and a trailing byline that carries a
 * reporter e-mail address. The stored body ({@code news_article.analysis_text}) is never changed, so the original
 * text and its usage notice stay available. Cleaning the model input is not permission to use the content.
 *
 * <p>Rules are generic (marks and phrases, never a publisher name). A notice is only removed when it carries a
 * copyright mark or names the feed itself, so an article that merely reports on piracy keeps its sentences.
 */
public final class AnalysisInputCleaner {

    /** Cleaned text plus the fragments that were removed (for verification and logs). */
    public record Cleaned(String text, List<String> removed) {}

    private static final String NO_REPOST = "(?:무단\\s*(?:전재|복제|배포)|재배포)[^.▶\\n]{0,30}?금지(?:\\s*(?:되어|돼)\\s*있습니다)?\\.?";

    private static final List<Pattern> RULES = List.of(
            // "ⓒ 언론사 / RSS 피드는 개인 리더 이용 목적으로 허용 되어 있습니다."
            Pattern.compile("(?:[ⓒ©][^/▶\\n]{0,40}/\\s*)?(?:RSS\\s*)?피드는[^.▶\\n]{0,60}?(?:허용|이용)[^.▶\\n]{0,30}?\\.", Pattern.CASE_INSENSITIVE),
            // "피드를 이용한 게시 등의 무단 복제는 금지 되어 있습니다."
            Pattern.compile("(?:RSS\\s*)?피드를[^.▶\\n]{0,60}?" + NO_REPOST, Pattern.CASE_INSENSITIVE),
            // "ⓒ 언론사 : 무단복제 및 재배포 금지", "© 2026 Publisher. All rights reserved."
            Pattern.compile("[ⓒ©][^.▶\\n]{0,60}?" + NO_REPOST),
            Pattern.compile("(?:copyright\\s*)?(?:[ⓒ©]|\\(c\\))[^▶\\n]{0,60}?all\\s+rights\\s+reserved\\.?", Pattern.CASE_INSENSITIVE),
            // short link labels: "▶ 이 기사의 전체 내용 확인하기", "▶ 영상 시청", "▶ ... 앱 다운로드"
            Pattern.compile("▶\\s*[^▶\\n]{0,40}?(?:다운로드|바로\\s*가기|확인하기|영상\\s*시청|구독(?:하기)?)(?=\\s|▶|$)"),
            // trailing byline with an e-mail address: "◎슬로건 언론사 reporter@example.com"
            Pattern.compile("\\s[^.!?\"”’\\n]{0,40}?[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\s*$"),
            // any other e-mail address (keeps the surrounding sentence)
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));

    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private AnalysisInputCleaner() {
    }

    public static Cleaned clean(String text) {
        if (text == null || text.isBlank()) {
            return new Cleaned(text == null ? "" : text.trim(), List.of());
        }
        String out = text;
        List<String> removed = new ArrayList<>();
        for (Pattern rule : RULES) {
            Matcher m = rule.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                removed.add(m.group().trim());
                m.appendReplacement(sb, " ");
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        out = SPACES.matcher(out).replaceAll(" ").replaceAll(" *\\n *", "\n").trim();
        return new Cleaned(out, List.copyOf(removed));
    }
}

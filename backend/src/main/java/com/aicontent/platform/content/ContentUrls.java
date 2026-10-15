package com.aicontent.platform.content;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * URL rule shared by external sources and related links, matching the DB checks (V5 / V6): http or https, a host,
 * no whitespace, at most 2000 characters. Only the text is checked; the server never fetches or contacts the URL.
 */
final class ContentUrls {

    static final int MAX_URL_CHARS = 2_000;
    private static final Pattern DB_RULE = Pattern.compile("^https?://[^\\s/?#]+\\S*$");

    private ContentUrls() {}

    /** @return the trimmed URL with a lower-case scheme; 400 VALIDATION_ERROR naming {@code field} otherwise */
    static String normalize(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalid(field, "URL이 비어 있습니다.");
        }
        String url = raw.strip();
        int colon = url.indexOf(':');
        if (colon > 0) {
            url = url.substring(0, colon).toLowerCase(Locale.ROOT) + url.substring(colon);
        }
        if (url.length() > MAX_URL_CHARS) {
            throw invalid(field, "URL은 " + MAX_URL_CHARS + "자 이하여야 합니다.");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw invalid(field, "http 또는 https 주소만 쓸 수 있습니다.");
        }
        if (!DB_RULE.matcher(url).matches()) {
            throw invalid(field, "공백이 없고 호스트가 있는 주소여야 합니다.");
        }
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw invalid(field, "호스트를 알 수 없는 주소입니다.");
            }
        } catch (URISyntaxException e) {
            throw invalid(field, "주소 형식이 올바르지 않습니다.");
        }
        return url;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, field + ": " + message,
                java.util.Map.of("violations", java.util.List.of(new ContentBodyValidator.Violation(field, message))));
    }
}

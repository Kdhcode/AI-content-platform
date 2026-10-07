package com.aicontent.platform.news;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalised title used for the second de-duplication rule ("same publisher re-sent the same article under a
 * different URL"). Bracketed editorial tags such as "[속보]" / "(종합)" are ignored so a re-tagged copy still matches.
 * Titles that only differ in wording are NOT merged here; that is the job of ISSUE classification.
 */
public final class TitleNormalizer {

    private TitleNormalizer() {}

    private static final String TAG = "\\s*[\\[(<【〈《][^\\]>)】〉》]{0,20}[\\])>】〉》]";
    private static final Pattern LEADING_TAGS = Pattern.compile("^(?:" + TAG + ")+");
    private static final Pattern TRAILING_TAGS = Pattern.compile("(?:" + TAG + ")+\\s*$");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public static String normalize(String title) {
        if (title == null) {
            return "";
        }
        String s = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        String withoutTags = TRAILING_TAGS.matcher(LEADING_TAGS.matcher(s).replaceFirst("")).replaceFirst("");
        // A title that consists only of tags must not normalise to the empty string.
        String base = withoutTags.isBlank() ? s : withoutTags;
        base = NON_WORD.matcher(base).replaceAll(" ");
        return SPACES.matcher(base).replaceAll(" ").trim();
    }

    /** Hex SHA-256 of the normalised title (64 chars, matches {@code news_article.title_hash}). */
    public static String hash(String title) {
        String normalized = normalize(title);
        if (normalized.isEmpty()) {
            normalized = title == null ? "" : title.trim();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

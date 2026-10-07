package com.aicontent.platform.news;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Canonical form of an article URL, used as the de-duplication key (first rule of "same article").
 *
 * <p>Rules: http and https are treated as the same, scheme/host lower-cased, leading "www." and default
 * ports dropped, fragment dropped, dot-segments resolved, trailing slash dropped (except root), tracking
 * parameters removed and the remaining query parameters sorted. The original URL is always stored too.
 */
public final class UrlNormalizer {

    private UrlNormalizer() {}

    private static final List<String> TRACKING_PREFIXES = List.of("utm_", "mtm_", "pk_", "ga_");
    private static final List<String> TRACKING_NAMES = List.of(
            "fbclid", "gclid", "dclid", "msclkid", "yclid", "mc_cid", "mc_eid", "igshid", "_ga", "_gl", "ref_src", "ocid");

    public static String normalize(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is blank");
        }
        URI uri;
        try {
            uri = new URI(url.trim()).normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid url: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("unsupported url scheme: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("url has no host: " + url);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.") && host.length() > 4) {
            host = host.substring(4);
        }

        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        StringBuilder sb = new StringBuilder("https://").append(host);
        if (!defaultPort) {
            sb.append(':').append(port);
        }
        sb.append(path);

        String query = cleanQuery(uri.getRawQuery());
        if (!query.isEmpty()) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    private static String cleanQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String token : rawQuery.split("&")) {
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            String rawName = eq < 0 ? token : token.substring(0, eq);
            String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (isTracking(name)) {
                continue;
            }
            kept.add(token);
        }
        kept.sort(String::compareTo);
        return String.join("&", kept);
    }

    private static boolean isTracking(String name) {
        if (TRACKING_NAMES.contains(name)) {
            return true;
        }
        for (String prefix : TRACKING_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

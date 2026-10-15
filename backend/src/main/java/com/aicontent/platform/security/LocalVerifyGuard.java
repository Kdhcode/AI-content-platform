package com.aicontent.platform.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Decides whether the local-verify login bypass may run. With the bypass off it checks nothing. With it on, startup
 * fails unless every condition holds:
 * <ul>
 *   <li>the {@code local-verify} profile is active and the {@code live} profile is not;</li>
 *   <li>{@code server.address} is a loopback address (unset means every interface, so it is refused);</li>
 *   <li>the AI provider is {@code none} or {@code stub}, whatever set it ({@code live}, {@code AI_PROVIDER}, ...),
 *       and no OpenAI API key is configured.</li>
 * </ul>
 * The check runs twice: {@link LocalVerifyStartupCheck} right after the configuration is read (before the database,
 * AI clients or web server start), and again here. The security filter chain depends on this bean, so the bypass can
 * never be active without these checks.
 */
@Component
public class LocalVerifyGuard {

    public static final String PROFILE = "local-verify";
    public static final String ACTOR = "local-verify";
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost");
    private static final Set<String> NO_PAID_AI = Set.of("none", "stub");
    private static final Logger log = LoggerFactory.getLogger(LocalVerifyGuard.class);

    private final LocalVerifyProperties props;

    public LocalVerifyGuard(LocalVerifyProperties props, Environment env) {
        this.props = props;
        check(env);
        if (props.loginBypass() && !requested(Binder.get(env))) {
            throw new IllegalStateException("app.local-verify.login-bypass is on but was not checked");
        }
        if (props.loginBypass()) {
            log.warn("LOCAL VERIFY MODE: admin API runs without login as '{}' on {} (AI provider {}, data: {})",
                    ACTOR, env.getProperty("server.address"), env.getProperty("app.ai.provider"), props.dataLabel());
        }
    }

    /** Throws when the bypass is requested in an unsafe configuration; does nothing when it is off. */
    static void check(Environment env) {
        // Binder reads names the way @ConfigurationProperties does (APP_LOCALVERIFY_LOGINBYPASS, relaxed forms, ...)
        Binder binder = Binder.get(env);
        if (!requested(binder)) {
            return;
        }
        List<String> problems = problems(List.of(env.getActiveProfiles()),
                binder.bind("server.address", String.class).orElse(null),
                binder.bind("app.ai.provider", String.class).orElse("none"),
                binder.bind("app.ai.openai.api-key", String.class).orElse(""));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("app.local-verify.login-bypass=true refused: " + String.join("; ", problems));
        }
    }

    private static boolean requested(Binder binder) {
        return binder.bind("app.local-verify.login-bypass", Boolean.class).orElse(false);
    }

    /** Host (required), and Origin and X-Forwarded-Host when present, must all name this machine. */
    static boolean isLocalRequest(String host, String origin, String forwardedHost) {
        if (host == null || !LOOPBACK.contains(hostName(host))) {
            return false;
        }
        if (forwardedHost != null) {
            for (String h : forwardedHost.split(",")) {                     // one entry per proxy hop
                if (!LOOPBACK.contains(hostName(h))) return false;
            }
        }
        if (origin == null) {
            return true;
        }
        try {
            String originHost = java.net.URI.create(origin.trim()).getHost();   // "null" (sandboxed page) has no host
            return originHost != null && LOOPBACK.contains(hostName(originHost));
        } catch (IllegalArgumentException e) {
            return false;                                                   // malformed Origin
        }
    }

    /** "127.0.0.1:13091" -> "127.0.0.1", "[::1]:8080" -> "::1", "LOCALHOST" -> "localhost". */
    private static String hostName(String hostAndPort) {
        String h = hostAndPort.trim().toLowerCase();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end) : h;
        }
        int colon = h.indexOf(':');
        return colon >= 0 && h.indexOf(':', colon + 1) < 0 ? h.substring(0, colon) : h;
    }

    /** Every reason the bypass must not run; empty when it may. */
    static List<String> problems(List<String> profiles, String serverAddress, String aiProvider, String openAiKey) {
        List<String> problems = new ArrayList<>();
        if (!profiles.contains(PROFILE)) problems.add("profile '" + PROFILE + "' is not active");
        if (profiles.contains("live")) problems.add("profile 'live' is active");
        if (serverAddress == null || !LOOPBACK.contains(serverAddress.trim().toLowerCase())) {
            problems.add("server.address must be a loopback address (is " + serverAddress + ")");
        }
        String provider = aiProvider == null ? "" : aiProvider.trim().toLowerCase();
        if (!NO_PAID_AI.contains(provider)) problems.add("app.ai.provider must be none or stub (is " + aiProvider + ")");
        if (openAiKey != null && !openAiKey.isBlank()) problems.add("an OpenAI API key is configured");
        return problems;
    }

    public boolean bypassActive() {
        return props.loginBypass();
    }

    public String dataLabel() {
        return props.dataLabel();
    }
}

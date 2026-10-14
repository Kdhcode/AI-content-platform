package com.aicontent.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Local screen-check mode (profile {@code local-verify}). Off by default. When {@code loginBypass} is on, admin API
 * requests without credentials run as the fixed user {@value LocalVerifyGuard#ACTOR}; {@link LocalVerifyGuard} refuses
 * to start unless the server is loopback-only and no real AI provider can be called.
 */
@ConfigurationProperties("app.local-verify")
public record LocalVerifyProperties(
        @DefaultValue("false") boolean loginBypass,
        /** Shown on every screen so the viewer knows which data they are looking at (e.g. 시나리오 데이터). */
        @DefaultValue("") String dataLabel) {}

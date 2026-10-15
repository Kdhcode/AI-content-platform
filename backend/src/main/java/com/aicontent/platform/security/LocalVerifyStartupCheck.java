package com.aicontent.platform.security;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Runs {@link LocalVerifyGuard#check} as soon as the configuration is read, so a refused local-verify configuration
 * stops before anything connects to the database, creates an AI client or opens a port. Registered in
 * META-INF/spring.factories.
 */
public class LocalVerifyStartupCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        LocalVerifyGuard.check(event.getEnvironment());
    }
}

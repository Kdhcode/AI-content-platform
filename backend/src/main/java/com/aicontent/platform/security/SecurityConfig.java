package com.aicontent.platform.security;

import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimum admin authentication for Phase 1: HTTP Basic against app_user/user_role, stateless, JSON error bodies.
 * The final login form (session/JWT/OIDC) is an OPEN ITEM; replacing this class is the only change needed.
 * CSRF is off because no cookie/session credentials are used (Basic header per request).
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(h -> h.authenticationEntryPoint((req, res, ex) -> writeError(mapper, res, ErrorCode.UNAUTHORIZED)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(mapper, res, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) -> writeError(mapper, res, ErrorCode.FORBIDDEN)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/jobs/*/retry", "/api/admin/jobs/*/cancel",
                                "/api/admin/sources/*/collect").hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/api/admin/**").hasAnyRole("OPERATOR", "SYSTEM_ADMIN")
                        .anyRequest().denyAll());
        return http.build();
    }

    private static void writeError(ObjectMapper mapper, HttpServletResponse res, ErrorCode code) throws IOException {
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(), ApiResponse.fail(code.name(), code.defaultMessage()));
    }
}

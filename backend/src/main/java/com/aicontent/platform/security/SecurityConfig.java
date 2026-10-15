package com.aicontent.platform.security;

import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Minimum admin authentication for Phase 1: HTTP Basic against app_user/user_role, stateless, JSON error bodies.
 * The final login form (session/JWT/OIDC) is an OPEN ITEM; replacing this class is the only change needed.
 * CSRF is off because no cookie/session credentials are used (Basic header per request).
 * Local-verify mode ({@link LocalVerifyGuard}) adds one filter: a request without an Authorization header runs as the
 * fixed admin {@value LocalVerifyGuard#ACTOR}. Requests that send credentials are still checked as before.
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, LocalVerifyGuard localVerify) throws Exception {
        if (localVerify.bypassActive()) {
            http.addFilterBefore(new LocalVerifyFilter(), BasicAuthenticationFilter.class);
        }
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(h -> h.authenticationEntryPoint((req, res, ex) -> writeError(mapper, res, ErrorCode.UNAUTHORIZED)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(mapper, res, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) -> writeError(mapper, res, ErrorCode.FORBIDDEN)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/issues", "/api/public/issues/*", "/api/public/categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/local-verify/mode").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/jobs/*/retry", "/api/admin/jobs/*/cancel",
                                "/api/admin/sources/*/collect").hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/api/admin/**").hasAnyRole("OPERATOR", "SYSTEM_ADMIN")
                        .anyRequest().denyAll());
        return http.build();
    }

    /**
     * Only installed after {@link LocalVerifyGuard} accepted the configuration. The bypass also requires the request
     * itself to be local: a host name (from the Host header) naming this machine and, when the browser sends one, an Origin on this
     * machine. Behind the admin UI's proxy the original host arrives as X-Forwarded-Host and is checked too (it can
     * only take the bypass away, never grant it). A page from another site open in the same browser (cross-site POST,
     * DNS rebinding) therefore gets the normal 401 instead of admin rights.
     */
    private static final class LocalVerifyFilter extends OncePerRequestFilter {
        private static final List<SimpleGrantedAuthority> ROLES =
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"), new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));

        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest req, HttpServletResponse res,
                                        jakarta.servlet.FilterChain chain) throws jakarta.servlet.ServletException, IOException {
            if (req.getHeader("Authorization") == null && SecurityContextHolder.getContext().getAuthentication() == null
                    && LocalVerifyGuard.isLocalRequest(req.getServerName(), req.getHeader("Origin"), req.getHeader("X-Forwarded-Host"))) {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(LocalVerifyGuard.ACTOR, null, ROLES));
                SecurityContextHolder.setContext(context);
            }
            chain.doFilter(req, res);
        }
    }

    private static void writeError(ObjectMapper mapper, HttpServletResponse res, ErrorCode code) throws IOException {
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(), ApiResponse.fail(code.name(), code.defaultMessage()));
    }
}

package com.aicontent.platform.security;

import com.aicontent.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first SYSTEM_ADMIN from {@code app.admin.bootstrap-*} when that username does not exist yet.
 * No default credentials exist: with the properties empty nobody can log in (the intended safe default).
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public AdminBootstrap(JdbcClient jdbc, PasswordEncoder encoder, AppProperties props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = props.admin().bootstrapUsername();
        String password = props.admin().bootstrapPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.info("no admin bootstrap credentials configured; no admin user is created");
            return;
        }
        Long id = jdbc.sql("""
                INSERT INTO app_user (username, password_hash, display_name) VALUES (:u, :h, :u)
                ON CONFLICT (lower(username)) DO NOTHING RETURNING id""")
                .param("u", username.trim()).param("h", encoder.encode(password)).query(Long.class).optional().orElse(null);
        if (id == null) {
            return;
        }
        jdbc.sql("INSERT INTO user_role (user_id, role) VALUES (:id, 'SYSTEM_ADMIN')").param("id", id).update();
        log.info("bootstrap admin '{}' created", username.trim());
    }
}

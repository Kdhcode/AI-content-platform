package com.aicontent.platform.security;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final JdbcClient jdbc;

    public AppUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        record Row(long id, String username, String hash, String status) {}
        Row row = jdbc.sql("SELECT id, username, password_hash, status FROM app_user WHERE lower(username) = lower(:u)")
                .param("u", username)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("status")))
                .optional().orElseThrow(() -> new UsernameNotFoundException("unknown user"));
        List<SimpleGrantedAuthority> roles = jdbc.sql("SELECT role FROM user_role WHERE user_id = :id").param("id", row.id())
                .query(String.class).list().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        return User.withUsername(row.username()).password(row.hash()).authorities(roles)
                .disabled(!"ACTIVE".equals(row.status())).build();
    }
}

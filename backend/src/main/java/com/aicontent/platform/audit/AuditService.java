package com.aicontent.platform.audit;

import com.aicontent.platform.common.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Append-only history of admin changes (merge / split / move / edit). Written inside the change's transaction. */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final Json json;

    public AuditService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String actor, String action, String targetType, Object targetId, Object before, Object after,
                       String reason, String correlationId) {
        jdbc.sql("""
                INSERT INTO admin_audit_log (actor, action, target_type, target_id, before_state, after_state, reason, correlation_id)
                VALUES (:actor, :action, :tt, :tid, CAST(:before AS jsonb), CAST(:after AS jsonb), :reason, :corr)""")
                .param("actor", actor).param("action", action).param("tt", targetType).param("tid", String.valueOf(targetId))
                .param("before", before == null ? null : json.write(before))
                .param("after", after == null ? null : json.write(after))
                .param("reason", reason).param("corr", correlationId)
                .update();
    }
}

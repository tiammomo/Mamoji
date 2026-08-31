package com.mamoji.platform.audit.infrastructure;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.platform.audit.application.AuditLogRepository;
import com.mamoji.platform.audit.domain.AuditEvent;
import com.mamoji.platform.audit.domain.AuditLog;
import com.mamoji.platform.audit.domain.AuditLogSearchCriteria;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditLogRepository implements AuditLogRepository {
    private static final String COLUMNS = """
        id, company_id, entity_type, entity_id, action, summary, actor_user_id, actor_name, created_at
        """;

    private final JdbcTemplate jdbc;

    public JdbcAuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AuditLog append(AuditEvent event) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO audit_logs (
                    company_id, entity_type, entity_id, action, summary, actor_user_id, actor_name, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[] {"id"});
            statement.setLong(1, event.companyId());
            statement.setString(2, event.entityType());
            statement.setLong(3, event.entityId());
            statement.setString(4, event.action());
            statement.setString(5, event.summary());
            statement.setLong(6, event.actorUserId());
            statement.setString(7, event.actorName());
            statement.setString(8, event.createdAt());
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("Audit insert did not return an identifier");
        }
        return new AuditLog(
            id.longValue(), event.companyId(), event.entityType(), event.entityId(), event.action(), event.summary(),
            event.actorUserId(), event.actorName(), event.createdAt()
        );
    }

    @Override
    public PagedResponse<AuditLog> findPage(AuditLogSearchCriteria criteria, PageRequest pageRequest) {
        SqlAuditQuery query = auditQuery(criteria);
        Long total = jdbc.queryForObject(
            "SELECT COUNT(*)" + query.fromAndWhere(),
            Long.class,
            query.arguments().toArray()
        );
        List<Object> pageArguments = new ArrayList<>(query.arguments());
        pageArguments.add(pageRequest.size());
        pageArguments.add((long) pageRequest.page() * pageRequest.size());
        List<AuditLog> content = jdbc.query(
            "SELECT " + COLUMNS + query.fromAndWhere()
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            this::map,
            pageArguments.toArray()
        );
        long totalElements = total == null ? 0 : total;
        int totalPages = (int) Math.ceil((double) totalElements / pageRequest.size());
        return new PagedResponse<>(content, totalElements, totalPages, pageRequest.size(), pageRequest.page());
    }

    @Override
    public List<AuditLog> findByEntity(long companyId, String entityType, long entityId) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM audit_logs"
                + " WHERE company_id = ? AND entity_type = ? AND entity_id = ?"
                + " ORDER BY created_at DESC, id DESC",
            this::map,
            companyId,
            entityType,
            entityId
        );
    }

    @Override
    public boolean existsByEntityType(String entityType) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM audit_logs WHERE entity_type = ?)",
            Boolean.class,
            entityType
        );
        return Boolean.TRUE.equals(exists);
    }

    private SqlAuditQuery auditQuery(AuditLogSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder(" FROM audit_logs WHERE 1 = 1");
        List<Object> arguments = new ArrayList<>();
        addEqualFilter(sql, arguments, "company_id", criteria.companyId());
        addEqualFilter(sql, arguments, "entity_type", criteria.entityType());
        addEqualFilter(sql, arguments, "entity_id", criteria.entityId());
        addEqualFilter(sql, arguments, "action", criteria.action());
        addEqualFilter(sql, arguments, "actor_user_id", criteria.actorUserId());
        if (!criteria.keyword().isBlank()) {
            sql.append(" AND POSITION(CAST(? AS TEXT) IN LOWER(")
                .append("summary || ' ' || actor_name || ' ' || entity_type || ' ' || action")
                .append(")) > 0");
            arguments.add(criteria.keyword());
        }
        return new SqlAuditQuery(sql.toString(), arguments);
    }

    private void addEqualFilter(StringBuilder sql, List<Object> arguments, String column, Object value) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return;
        }
        sql.append(" AND ").append(column).append(" = ?");
        arguments.add(value);
    }

    private AuditLog map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditLog(
            rs.getLong("id"),
            rs.getLong("company_id"),
            rs.getString("entity_type"),
            rs.getLong("entity_id"),
            rs.getString("action"),
            rs.getString("summary"),
            rs.getLong("actor_user_id"),
            rs.getString("actor_name"),
            rs.getString("created_at")
        );
    }

    private record SqlAuditQuery(String fromAndWhere, List<Object> arguments) {
    }
}

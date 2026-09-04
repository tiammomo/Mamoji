package com.mamoji.platform.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEntityTransferRepository implements EntityTransferRepository {
    private static final String SELECT = """
        SELECT transfer.id, transfer.from_entity_id, transfer.to_entity_id,
               source.name AS from_entity_name, target.name AS to_entity_name,
               transfer.transfer_type, transfer.amount, transfer.currency, transfer.transfer_date,
               transfer.note, transfer.status, transfer.operator_user_id,
               transfer.created_at, transfer.updated_at
        FROM entity_transfers transfer
        JOIN companies source ON source.id = transfer.from_entity_id
        JOIN companies target ON target.id = transfer.to_entity_id
        """;

    private final JdbcTemplate jdbc;

    public JdbcEntityTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<EntityTransfer> findAccessible(List<Long> accessibleEntityIds, Long entityId) {
        List<Long> ids = accessibleEntityIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        StringBuilder sql = new StringBuilder(SELECT)
            .append(" WHERE (transfer.from_entity_id IN (").append(placeholders)
            .append(") OR transfer.to_entity_id IN (").append(placeholders).append("))");
        List<Object> arguments = new ArrayList<>();
        arguments.addAll(ids);
        arguments.addAll(ids);
        if (entityId != null) {
            sql.append(" AND (transfer.from_entity_id = ? OR transfer.to_entity_id = ?)");
            arguments.add(entityId);
            arguments.add(entityId);
        }
        sql.append(" ORDER BY transfer.transfer_date DESC, transfer.id");
        return jdbc.query(sql.toString(), this::map, arguments.toArray());
    }

    @Override
    public boolean existsBetween(long firstEntityId, long secondEntityId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS(
                SELECT 1 FROM entity_transfers
                WHERE (from_entity_id = ? AND to_entity_id = ?)
                   OR (from_entity_id = ? AND to_entity_id = ?)
            )
            """, Boolean.class, firstEntityId, secondEntityId, secondEntityId, firstEntityId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public EntityTransfer append(EntityTransfer transfer) {
        EntityTransferPolicy.normalizeAndValidate(transfer);
        String now = OffsetDateTime.now().toString();
        transfer.createdAt = now;
        transfer.updatedAt = now;
        Long id = jdbc.queryForObject("""
            INSERT INTO entity_transfers (
                from_entity_id, to_entity_id, transfer_type, amount, currency, transfer_date,
                note, status, operator_user_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, Long.class, transfer.fromEntityId, transfer.toEntityId, transfer.transferType,
            transfer.amount, transfer.currency, LocalDate.parse(transfer.transferDate), transfer.note,
            transfer.status, transfer.operatorUserId, OffsetDateTime.parse(transfer.createdAt),
            OffsetDateTime.parse(transfer.updatedAt));
        if (id == null) throw new IllegalStateException("Database did not return a generated entity transfer id");
        transfer.id = id;
        return findNames(transfer);
    }

    private EntityTransfer findNames(EntityTransfer transfer) {
        Map<String, Object> names = jdbc.queryForMap("""
            SELECT source.name AS from_entity_name, target.name AS to_entity_name
            FROM companies source CROSS JOIN companies target
            WHERE source.id = ? AND target.id = ?
            """, transfer.fromEntityId, transfer.toEntityId);
        transfer.fromEntityName = String.valueOf(names.get("from_entity_name"));
        transfer.toEntityName = String.valueOf(names.get("to_entity_name"));
        return transfer;
    }

    private EntityTransfer map(ResultSet result, int rowNumber) throws SQLException {
        EntityTransfer transfer = new EntityTransfer();
        transfer.id = result.getLong("id");
        transfer.fromEntityId = result.getLong("from_entity_id");
        transfer.toEntityId = result.getLong("to_entity_id");
        transfer.fromEntityName = result.getString("from_entity_name");
        transfer.toEntityName = result.getString("to_entity_name");
        transfer.transferType = result.getString("transfer_type");
        transfer.amount = result.getBigDecimal("amount");
        transfer.currency = result.getString("currency");
        transfer.transferDate = result.getObject("transfer_date", LocalDate.class).toString();
        transfer.note = result.getString("note");
        transfer.status = result.getString("status");
        transfer.operatorUserId = result.getLong("operator_user_id");
        transfer.createdAt = result.getObject("created_at", OffsetDateTime.class).toString();
        transfer.updatedAt = result.getObject("updated_at", OffsetDateTime.class).toString();
        return transfer;
    }
}

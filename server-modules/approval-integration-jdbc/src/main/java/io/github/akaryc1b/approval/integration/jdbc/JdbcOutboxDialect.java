package io.github.akaryc1b.approval.integration.jdbc;

import org.springframework.dao.DuplicateKeyException;

/** Vendor-specific SQL for Outbox JSON storage and bounded worker claims. */
enum JdbcOutboxDialect {
    POSTGRESQL(
        """
        insert into ap_outbox (
            id, tenant_id, connector_key, request_id, trace_id,
            event_id, event_type, aggregate_type, aggregate_id,
            occurred_at, idempotency_key, payload_json, status,
            attempts, available_at, created_at, updated_at
        ) values (
            :id, :tenantId, :connectorKey, :requestId, :traceId,
            :eventId, :eventType, :aggregateType, :aggregateId,
            :occurredAt, :idempotencyKey, cast(:payloadJson as jsonb), 'PENDING',
            0, :availableAt, :createdAt, :createdAt
        )
        on conflict (tenant_id, connector_key, idempotency_key) do nothing
        """,
        """
        with due as (
            select id
            from ap_outbox
            where (
                status = 'PENDING' and available_at <= :now
            ) or (
                status = 'IN_FLIGHT' and locked_until <= :now
            )
            order by available_at, created_at, id
            for update skip locked
            limit :limit
        )
        update ap_outbox target
        set status = 'IN_FLIGHT',
            locked_by = :workerId,
            locked_until = :lockedUntil,
            updated_at = :now
        from due
        where target.id = due.id
        returning target.*
        """,
        null,
        null,
        null,
        true,
        false
    ),
    MYSQL(
        """
        insert into ap_outbox (
            id, tenant_id, connector_key, request_id, trace_id,
            event_id, event_type, aggregate_type, aggregate_id,
            occurred_at, idempotency_key, payload_json, status,
            attempts, available_at, created_at, updated_at
        ) values (
            :id, :tenantId, :connectorKey, :requestId, :traceId,
            :eventId, :eventType, :aggregateType, :aggregateId,
            :occurredAt, :idempotencyKey, json_object(
                'encoding', 'CANONICAL_JSON_TEXT_V1',
                'payload', :payloadJson
            ), 'PENDING',
            0, :availableAt, :createdAt, :createdAt
        )
        """,
        null,
        """
        select id
        from ap_outbox
        where (
            status = 'PENDING' and available_at <= :now
        ) or (
            status = 'IN_FLIGHT' and locked_until <= :now
        )
        order by available_at, created_at, id
        limit :limit
        for update skip locked
        """,
        """
        update ap_outbox
        set status = 'IN_FLIGHT',
            locked_by = :workerId,
            locked_until = :lockedUntil,
            updated_at = :now
        where id in (:ids)
          and (
              (status = 'PENDING' and available_at <= :now)
              or (status = 'IN_FLIGHT' and locked_until <= :now)
          )
        """,
        """
        select
            ap_outbox.*,
            case
                when json_type(payload_json) = 'OBJECT'
                 and json_unquote(json_extract(payload_json, '$.encoding'))
                     = 'CANONICAL_JSON_TEXT_V1'
                 and json_type(json_extract(payload_json, '$.payload')) = 'STRING'
                then json_unquote(json_extract(payload_json, '$.payload'))
                else null
            end as payload_json_text
        from ap_outbox
        where id in (:ids)
          and status = 'IN_FLIGHT'
          and locked_by = :workerId
        order by available_at, created_at, id
        """,
        false,
        true
    );

    private final String appendSql;
    private final String postgreSqlClaimSql;
    private final String mySqlSelectDueSql;
    private final String mySqlClaimSql;
    private final String mySqlReadClaimsSql;
    private final boolean zeroRowAppendIsDuplicate;
    private final boolean duplicateKeyRequiresBusinessKeyVerification;

    JdbcOutboxDialect(
        String appendSql,
        String postgreSqlClaimSql,
        String mySqlSelectDueSql,
        String mySqlClaimSql,
        String mySqlReadClaimsSql,
        boolean zeroRowAppendIsDuplicate,
        boolean duplicateKeyRequiresBusinessKeyVerification
    ) {
        this.appendSql = appendSql;
        this.postgreSqlClaimSql = postgreSqlClaimSql;
        this.mySqlSelectDueSql = mySqlSelectDueSql;
        this.mySqlClaimSql = mySqlClaimSql;
        this.mySqlReadClaimsSql = mySqlReadClaimsSql;
        this.zeroRowAppendIsDuplicate = zeroRowAppendIsDuplicate;
        this.duplicateKeyRequiresBusinessKeyVerification =
            duplicateKeyRequiresBusinessKeyVerification;
    }

    static JdbcOutboxDialect forDatabase(JdbcIntegrationDatabaseDialect database) {
        return switch (database) {
            case POSTGRESQL -> POSTGRESQL;
            case MYSQL -> MYSQL;
        };
    }

    String appendSql() {
        return appendSql;
    }

    String postgreSqlClaimSql() {
        return postgreSqlClaimSql;
    }

    String mySqlSelectDueSql() {
        return mySqlSelectDueSql;
    }

    String mySqlClaimSql() {
        return mySqlClaimSql;
    }

    String mySqlReadClaimsSql() {
        return mySqlReadClaimsSql;
    }

    boolean isExpectedNoOpDuplicate(int rowCount) {
        return zeroRowAppendIsDuplicate && rowCount == 0;
    }

    boolean requiresBusinessKeyVerification(RuntimeException exception) {
        return duplicateKeyRequiresBusinessKeyVerification
            && exception instanceof DuplicateKeyException;
    }
}

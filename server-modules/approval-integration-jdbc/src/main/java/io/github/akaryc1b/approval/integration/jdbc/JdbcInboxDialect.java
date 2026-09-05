package io.github.akaryc1b.approval.integration.jdbc;

import org.springframework.dao.DuplicateKeyException;

/** Vendor-specific SQL for replay-safe Inbox admission and lease reacquisition. */
enum JdbcInboxDialect {
    POSTGRESQL(
        """
        insert into ap_inbox (
            tenant_id, consumer_key, message_id, payload_hash,
            status, attempts, locked_by, locked_until,
            received_at, updated_at
        ) values (
            :tenantId, :consumerKey, :messageId, :payloadHash,
            'PROCESSING', 1, :workerId, :lockedUntil,
            :now, :now
        )
        on conflict (tenant_id, consumer_key, message_id) do nothing
        """,
        """
        update ap_inbox
        set status = 'PROCESSING',
            attempts = attempts + 1,
            locked_by = :workerId,
            locked_until = :lockedUntil,
            updated_at = :now,
            last_error = null
        where tenant_id = :tenantId
          and consumer_key = :consumerKey
          and message_id = :messageId
          and payload_hash = :payloadHash
          and status <> 'COMPLETED'
          and (
              status = 'FAILED'
              or locked_until is null
              or locked_until <= :now
          )
        returning attempts
        """,
        true,
        false
    ),
    MYSQL(
        """
        insert into ap_inbox (
            tenant_id, consumer_key, message_id, payload_hash,
            status, attempts, locked_by, locked_until,
            received_at, updated_at
        ) values (
            :tenantId, :consumerKey, :messageId, :payloadHash,
            'PROCESSING', 1, :workerId, :lockedUntil,
            :now, :now
        )
        """,
        """
        update ap_inbox
        set status = 'PROCESSING',
            attempts = attempts + 1,
            locked_by = :workerId,
            locked_until = :lockedUntil,
            updated_at = :now,
            last_error = null
        where tenant_id = :tenantId
          and consumer_key = :consumerKey
          and message_id = :messageId
          and payload_hash = :payloadHash
          and status <> 'COMPLETED'
          and (
              status = 'FAILED'
              or locked_until is null
              or locked_until <= :now
          )
        """,
        false,
        true
    );

    private final String admissionSql;
    private final String reacquisitionSql;
    private final boolean reacquisitionReturnsAttempts;
    private final boolean duplicateAdmissionIsReplay;

    JdbcInboxDialect(
        String admissionSql,
        String reacquisitionSql,
        boolean reacquisitionReturnsAttempts,
        boolean duplicateAdmissionIsReplay
    ) {
        this.admissionSql = admissionSql;
        this.reacquisitionSql = reacquisitionSql;
        this.reacquisitionReturnsAttempts = reacquisitionReturnsAttempts;
        this.duplicateAdmissionIsReplay = duplicateAdmissionIsReplay;
    }

    static JdbcInboxDialect forDatabase(JdbcIntegrationDatabaseDialect database) {
        return switch (database) {
            case POSTGRESQL -> POSTGRESQL;
            case MYSQL -> MYSQL;
        };
    }

    String admissionSql() {
        return admissionSql;
    }

    String reacquisitionSql() {
        return reacquisitionSql;
    }

    boolean reacquisitionReturnsAttempts() {
        return reacquisitionReturnsAttempts;
    }

    boolean isExpectedDuplicateAdmission(RuntimeException exception) {
        return duplicateAdmissionIsReplay && exception instanceof DuplicateKeyException;
    }
}

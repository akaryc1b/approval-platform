package io.github.akaryc1b.approval.persistence.jdbc;

import org.springframework.dao.DuplicateKeyException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Objects;

/** Vendor-specific SQL for the command-idempotency persistence boundary. */
enum JdbcIdempotencyDialect {
    POSTGRESQL(
        """
        insert into ap_command_idempotency (
            tenant_id, operation, idempotency_key, request_hash,
            request_id, trace_id, status, created_at
        ) values (
            :tenantId, :operation, :idempotencyKey, :requestHash,
            :requestId, :traceId, 'IN_PROGRESS', :createdAt
        )
        on conflict (tenant_id, operation, idempotency_key) do nothing
        """,
        """
        update ap_command_idempotency
        set result_type = :resultType,
            result_json = cast(:resultJson as jsonb),
            status = 'COMPLETED',
            completed_at = :completedAt
        where tenant_id = :tenantId
          and operation = :operation
          and idempotency_key = :idempotencyKey
          and status = 'IN_PROGRESS'
        """,
        """
        select request_hash, result_type, result_json::text as result_json, status
        from ap_command_idempotency
        where tenant_id = :tenantId
          and operation = :operation
          and idempotency_key = :idempotencyKey
        for update
        """,
        false
    ),
    MYSQL(
        """
        insert into ap_command_idempotency (
            tenant_id, operation, idempotency_key, request_hash,
            request_id, trace_id, status, created_at
        ) values (
            :tenantId, :operation, :idempotencyKey, :requestHash,
            :requestId, :traceId, 'IN_PROGRESS', :createdAt
        )
        """,
        """
        update ap_command_idempotency
        set result_type = :resultType,
            result_json = cast(:resultJson as json),
            status = 'COMPLETED',
            completed_at = :completedAt
        where tenant_id = :tenantId
          and operation = :operation
          and idempotency_key = :idempotencyKey
          and status = 'IN_PROGRESS'
        """,
        """
        select
            request_hash,
            result_type,
            cast(result_json as char character set utf8mb4) as result_json,
            status
        from ap_command_idempotency
        where tenant_id = :tenantId
          and operation = :operation
          and idempotency_key = :idempotencyKey
        for update
        """,
        true
    );

    private final String admissionSql;
    private final String completionSql;
    private final String replaySql;
    private final boolean duplicateAdmissionIsReplay;

    JdbcIdempotencyDialect(
        String admissionSql,
        String completionSql,
        String replaySql,
        boolean duplicateAdmissionIsReplay
    ) {
        this.admissionSql = admissionSql;
        this.completionSql = completionSql;
        this.replaySql = replaySql;
        this.duplicateAdmissionIsReplay = duplicateAdmissionIsReplay;
    }

    String admissionSql() {
        return admissionSql;
    }

    String completionSql() {
        return completionSql;
    }

    String replaySql() {
        return replaySql;
    }

    boolean isExpectedDuplicateAdmission(RuntimeException exception) {
        return duplicateAdmissionIsReplay && exception instanceof DuplicateKeyException;
    }

    static JdbcIdempotencyDialect resolve(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        try (Connection connection = source.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            ApprovalDatabaseVendor vendor = ApprovalDatabaseVendor.fromProductName(
                metadata.getDatabaseProductName()
            );
            vendor.requireSupportedVersion(
                metadata.getDatabaseMajorVersion(),
                metadata.getDatabaseMinorVersion()
            );
            return switch (vendor) {
                case POSTGRESQL -> POSTGRESQL;
                case MYSQL -> MYSQL;
            };
        } catch (SQLException exception) {
            throw new ApprovalDatabaseVendorResolver.DatabaseVendorResolutionException(
                exception
            );
        }
    }
}

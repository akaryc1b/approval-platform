package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** MySQL 8.4 platform-owned Release Package deployment projection. */
public final class JdbcMySqlApprovalReleaseDeploymentStore
    implements ApprovalReleaseDeploymentStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;

    public JdbcMySqlApprovalReleaseDeploymentStore(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalReleaseDeploymentStore requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.locks = new JdbcMySqlTransactionLockManager(source);
    }

    @Override
    public void lock(String tenantId, String definitionKey, int releaseVersion) {
        String exactTenant = requireText(tenantId, "tenantId");
        String exactDefinitionKey = requireText(definitionKey, "definitionKey");
        int exactReleaseVersion = requirePositive(releaseVersion, "releaseVersion");
        locks.acquire(
            "approval-release-deployment:"
                + exactTenant
                + ':'
                + exactDefinitionKey
                + ':'
                + exactReleaseVersion
        );
    }

    @Override
    public Optional<ApprovalReleaseDeployment> find(
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        return jdbc.query(
            selectDeployment()
                + " where tenant_id = :tenantId"
                + " and definition_key = :definitionKey"
                + " and release_version = :releaseVersion",
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("definitionKey", requireText(definitionKey, "definitionKey"))
                .addValue("releaseVersion", requirePositive(releaseVersion, "releaseVersion")),
            (resultSet, rowNumber) -> deployment(resultSet)
        ).stream().findFirst();
    }

    @Override
    public List<ApprovalReleaseDeployment> findByDefinition(
        String tenantId,
        String definitionKey
    ) {
        return jdbc.query(
            selectDeployment()
                + " where tenant_id = :tenantId"
                + " and definition_key = :definitionKey"
                + " order by release_version desc",
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("definitionKey", requireText(definitionKey, "definitionKey")),
            (resultSet, rowNumber) -> deployment(resultSet)
        );
    }

    @Override
    public void save(ApprovalReleaseDeployment deployment) {
        ApprovalReleaseDeployment exact = Objects.requireNonNull(
            deployment,
            "deployment must not be null"
        );
        int inserted = jdbc.update(
            """
            insert into ap_approval_release_deployment (
                deployment_record_id, tenant_id, definition_key, release_version,
                release_package_hash, status, attempt_count,
                engine_deployment_id, engine_definition_id, engine_version,
                last_error_code, last_error_message, requested_by,
                created_at, updated_at, deployed_at
            ) values (
                :deploymentRecordId, :tenantId, :definitionKey, :releaseVersion,
                :releasePackageHash, :status, :attemptCount,
                :engineDeploymentId, :engineDefinitionId, :engineVersion,
                :lastErrorCode, :lastErrorMessage, :requestedBy,
                :createdAt, :updatedAt, :deployedAt
            )
            """,
            parameters(exact)
        );
        if (inserted != 1) {
            throw new IllegalStateException("Release deployment was not inserted");
        }
    }

    @Override
    public boolean update(
        ApprovalReleaseDeployment deployment,
        int expectedAttemptCount
    ) {
        ApprovalReleaseDeployment exact = Objects.requireNonNull(
            deployment,
            "deployment must not be null"
        );
        int updated = jdbc.update(
            """
            update ap_approval_release_deployment
            set release_package_hash = :releasePackageHash,
                status = :status,
                attempt_count = :attemptCount,
                engine_deployment_id = :engineDeploymentId,
                engine_definition_id = :engineDefinitionId,
                engine_version = :engineVersion,
                last_error_code = :lastErrorCode,
                last_error_message = :lastErrorMessage,
                requested_by = :requestedBy,
                updated_at = :updatedAt,
                deployed_at = :deployedAt
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and release_version = :releaseVersion
              and attempt_count = :expectedAttemptCount
            """,
            parameters(exact).addValue("expectedAttemptCount", expectedAttemptCount)
        );
        if (updated == 0) {
            return false;
        }
        if (updated != 1) {
            throw new IllegalStateException("Release deployment update was not singular");
        }
        return true;
    }

    private static String selectDeployment() {
        return """
            select deployment_record_id, tenant_id, definition_key, release_version,
                   release_package_hash, status, attempt_count,
                   engine_deployment_id, engine_definition_id, engine_version,
                   last_error_code, last_error_message, requested_by,
                   created_at, updated_at, deployed_at
            from ap_approval_release_deployment
            """;
    }

    private MapSqlParameterSource parameters(ApprovalReleaseDeployment deployment) {
        return new MapSqlParameterSource()
            .addValue("deploymentRecordId", values.bindUuid(deployment.deploymentRecordId()))
            .addValue("tenantId", deployment.tenantId())
            .addValue("definitionKey", deployment.definitionKey())
            .addValue("releaseVersion", deployment.releaseVersion())
            .addValue("releasePackageHash", deployment.releasePackageHash())
            .addValue("status", deployment.status().name())
            .addValue("attemptCount", deployment.attemptCount())
            .addValue("engineDeploymentId", deployment.engineDeploymentId())
            .addValue("engineDefinitionId", deployment.engineDefinitionId())
            .addValue("engineVersion", deployment.engineVersion())
            .addValue("lastErrorCode", deployment.lastErrorCode())
            .addValue("lastErrorMessage", deployment.lastErrorMessage())
            .addValue("requestedBy", deployment.requestedBy())
            .addValue(
                "createdAt",
                values.bindInstant(canonicalInstant(deployment.createdAt()))
            )
            .addValue(
                "updatedAt",
                values.bindInstant(canonicalInstant(deployment.updatedAt()))
            )
            .addValue(
                "deployedAt",
                deployment.deployedAt() == null
                    ? null
                    : values.bindInstant(canonicalInstant(deployment.deployedAt()))
            );
    }

    private ApprovalReleaseDeployment deployment(ResultSet resultSet)
        throws SQLException {
        return new ApprovalReleaseDeployment(
            values.uuid(resultSet, "deployment_record_id"),
            resultSet.getString("tenant_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("release_version"),
            resultSet.getString("release_package_hash"),
            ApprovalReleaseDeployment.Status.valueOf(resultSet.getString("status")),
            resultSet.getInt("attempt_count"),
            resultSet.getString("engine_deployment_id"),
            resultSet.getString("engine_definition_id"),
            integer(resultSet, "engine_version"),
            resultSet.getString("last_error_code"),
            resultSet.getString("last_error_message"),
            resultSet.getString("requested_by"),
            values.instant(resultSet, "created_at"),
            values.instant(resultSet, "updated_at"),
            values.nullableInstant(resultSet, "deployed_at")
        );
    }

    private static Integer integer(ResultSet resultSet, String column)
        throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(value, "instant must not be null")
        );
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null");
        if (exact.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}

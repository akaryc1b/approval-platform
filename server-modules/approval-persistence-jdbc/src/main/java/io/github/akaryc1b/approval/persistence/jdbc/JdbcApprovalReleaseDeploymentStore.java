package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL platform-owned Release Package deployment projection. */
public final class JdbcApprovalReleaseDeploymentStore
    implements ApprovalReleaseDeploymentStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalReleaseDeploymentStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public void lock(String tenantId, String definitionKey, int releaseVersion) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource().addValue(
                "lockKey",
                "approval-release-deployment:"
                    + tenantId
                    + ':'
                    + definitionKey
                    + ':'
                    + releaseVersion
            ),
            resultSet -> null
        );
    }

    @Override
    public Optional<ApprovalReleaseDeployment> find(
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        return jdbc.query(
            """
            select deployment_record_id, tenant_id, definition_key, release_version,
                   release_package_hash, status, attempt_count,
                   engine_deployment_id, engine_definition_id, engine_version,
                   last_error_code, last_error_message, requested_by,
                   created_at, updated_at, deployed_at
            from ap_approval_release_deployment
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and release_version = :releaseVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey)
                .addValue("releaseVersion", releaseVersion),
            (resultSet, rowNumber) -> deployment(resultSet)
        ).stream().findFirst();
    }

    @Override
    public void save(ApprovalReleaseDeployment deployment) {
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
            parameters(deployment)
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
        return jdbc.update(
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
            parameters(deployment).addValue("expectedAttemptCount", expectedAttemptCount)
        ) == 1;
    }

    private static MapSqlParameterSource parameters(
        ApprovalReleaseDeployment deployment
    ) {
        return new MapSqlParameterSource()
            .addValue("deploymentRecordId", deployment.deploymentRecordId())
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
            .addValue("createdAt", offset(deployment.createdAt()))
            .addValue("updatedAt", offset(deployment.updatedAt()))
            .addValue("deployedAt", offset(deployment.deployedAt()));
    }

    private static ApprovalReleaseDeployment deployment(ResultSet resultSet)
        throws SQLException {
        return new ApprovalReleaseDeployment(
            resultSet.getObject("deployment_record_id", UUID.class),
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
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            optionalInstant(resultSet, "deployed_at")
        );
    }

    private static Integer integer(ResultSet resultSet, String column)
        throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column)
        throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant optionalInstant(ResultSet resultSet, String column)
        throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

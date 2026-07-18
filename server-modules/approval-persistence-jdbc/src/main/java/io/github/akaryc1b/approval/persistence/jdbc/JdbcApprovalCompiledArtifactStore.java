package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalCompiledArtifactStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalCompiledArtifact;
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

/** PostgreSQL immutable compiled Approval artifact store. */
public final class JdbcApprovalCompiledArtifactStore
    implements ApprovalCompiledArtifactStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalCompiledArtifactStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource));
    }

    @Override
    public void lockArtifact(
        String tenantId,
        String definitionKey,
        int definitionVersion,
        String compilerVersion
    ) {
        jdbc.query(
            "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource().addValue(
                "lockKey",
                String.join(
                    ":",
                    "approval-artifact",
                    tenantId,
                    definitionKey,
                    Integer.toString(definitionVersion),
                    compilerVersion
                )
            ),
            resultSet -> null
        );
    }

    @Override
    public Optional<ApprovalCompiledArtifact> find(
        String tenantId,
        String definitionKey,
        int definitionVersion,
        String compilerVersion
    ) {
        return jdbc.query(
            """
            select tenant_id, definition_key, definition_version, definition_hash,
                   form_version, form_hash, compiler_version, resource_name,
                   bpmn_xml, compiled_artifact_hash, bpmn_hash, created_at
            from ap_approval_compiled_artifact
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and definition_version = :definitionVersion
              and compiler_version = :compilerVersion
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("definitionKey", definitionKey)
                .addValue("definitionVersion", definitionVersion)
                .addValue("compilerVersion", compilerVersion),
            (resultSet, rowNumber) -> item(resultSet)
        ).stream().findFirst();
    }

    @Override
    public void save(ApprovalCompiledArtifact artifact) {
        int inserted = jdbc.update(
            """
            insert into ap_approval_compiled_artifact (
                tenant_id, definition_key, definition_version, definition_hash,
                form_version, form_hash, compiler_version, resource_name,
                bpmn_xml, compiled_artifact_hash, bpmn_hash, created_at
            ) values (
                :tenantId, :definitionKey, :definitionVersion, :definitionHash,
                :formVersion, :formHash, :compilerVersion, :resourceName,
                :bpmnXml, :compiledArtifactHash, :bpmnHash, :createdAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", artifact.tenantId())
                .addValue("definitionKey", artifact.definitionKey())
                .addValue("definitionVersion", artifact.definitionVersion())
                .addValue("definitionHash", artifact.definitionHash())
                .addValue("formVersion", artifact.formVersion())
                .addValue("formHash", artifact.formHash())
                .addValue("compilerVersion", artifact.compilerVersion())
                .addValue("resourceName", artifact.resourceName())
                .addValue("bpmnXml", artifact.bpmnXml())
                .addValue("compiledArtifactHash", artifact.compiledArtifactHash())
                .addValue("bpmnHash", artifact.bpmnHash())
                .addValue("createdAt", offset(artifact.createdAt()))
        );
        if (inserted != 1) {
            throw new IllegalStateException("compiled Approval artifact was not inserted");
        }
    }

    private static ApprovalCompiledArtifact item(ResultSet resultSet) throws SQLException {
        return new ApprovalCompiledArtifact(
            resultSet.getString("tenant_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("definition_version"),
            resultSet.getString("definition_hash"),
            resultSet.getInt("form_version"),
            resultSet.getString("form_hash"),
            resultSet.getString("compiler_version"),
            resultSet.getString("resource_name"),
            resultSet.getString("bpmn_xml"),
            resultSet.getString("compiled_artifact_hash"),
            resultSet.getString("bpmn_hash"),
            instant(resultSet, "created_at")
        );
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}

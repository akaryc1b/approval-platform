package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL delegation policy store with principal-scoped advisory serialization.
 */
public final class JdbcApprovalDelegationStore implements ApprovalDelegationStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApprovalDelegationStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public DelegationRule create(DelegationRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        lockPrincipal(rule.tenantId(), rule.principalId());
        if (hasOverlap(rule)) {
            throw new DelegationConflictException(
                "an active delegation already overlaps the requested scope and time window"
            );
        }
        int inserted = jdbc.update(
            """
            insert into ap_delegation_rule (
                tenant_id, rule_id, principal_id, delegate_id,
                scope, definition_key, valid_from, valid_until,
                status, reason, created_by, created_at,
                revoked_by, revoked_at, revoke_reason, version
            ) values (
                :tenantId, :ruleId, :principalId, :delegateId,
                :scope, :definitionKey, :validFrom, :validUntil,
                :status, :reason, :createdBy, :createdAt,
                null, null, null, :version
            )
            """,
            parameters(rule)
        );
        if (inserted != 1) {
            throw new IllegalStateException("delegation rule was not inserted");
        }
        return rule;
    }

    @Override
    public Optional<DelegationRule> findById(String tenantId, UUID ruleId) {
        List<DelegationRule> rules = jdbc.query(
            """
            select *
            from ap_delegation_rule
            where tenant_id = :tenantId
              and rule_id = :ruleId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("ruleId", Objects.requireNonNull(ruleId, "ruleId must not be null")),
            ruleMapper()
        );
        return rules.stream().findFirst();
    }

    @Override
    public List<DelegationRule> findByPrincipal(
        String tenantId,
        String principalId,
        boolean includeRevoked
    ) {
        return List.copyOf(jdbc.query(
            """
            select *
            from ap_delegation_rule
            where tenant_id = :tenantId
              and principal_id = :principalId
              and (:includeRevoked = true or status = 'ACTIVE')
            order by created_at desc, rule_id desc
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("principalId", requireText(principalId, "principalId"))
                .addValue("includeRevoked", includeRevoked),
            ruleMapper()
        ));
    }

    @Override
    public DelegationRule revoke(
        String tenantId,
        UUID ruleId,
        String principalId,
        String revokedBy,
        String revokeReason,
        Instant revokedAt
    ) {
        String normalizedTenant = requireText(tenantId, "tenantId");
        String normalizedPrincipal = requireText(principalId, "principalId");
        String normalizedRevokedBy = requireText(revokedBy, "revokedBy");
        String normalizedReason = requireText(revokeReason, "revokeReason");
        Objects.requireNonNull(ruleId, "ruleId must not be null");
        Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        lockPrincipal(normalizedTenant, normalizedPrincipal);
        DelegationRule current = findById(normalizedTenant, ruleId)
            .orElseThrow(() -> new DelegationNotFoundException(
                "delegation rule was not found"
            ));
        if (!current.principalId().equals(normalizedPrincipal)) {
            throw new DelegationConflictException(
                "only the delegation principal can revoke the rule"
            );
        }
        if (current.status() != DelegationStatus.ACTIVE) {
            throw new DelegationConflictException(
                "only an active delegation rule can be revoked"
            );
        }
        int updated = jdbc.update(
            """
            update ap_delegation_rule
            set status = 'REVOKED',
                revoked_by = :revokedBy,
                revoked_at = :revokedAt,
                revoke_reason = :revokeReason,
                version = version + 1
            where tenant_id = :tenantId
              and rule_id = :ruleId
              and principal_id = :principalId
              and status = 'ACTIVE'
              and version = :version
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", normalizedTenant)
                .addValue("ruleId", ruleId)
                .addValue("principalId", normalizedPrincipal)
                .addValue("revokedBy", normalizedRevokedBy)
                .addValue("revokedAt", offset(revokedAt))
                .addValue("revokeReason", normalizedReason)
                .addValue("version", current.version())
        );
        if (updated != 1) {
            throw new DelegationConflictException(
                "delegation rule changed concurrently"
            );
        }
        return findById(normalizedTenant, ruleId)
            .orElseThrow(() -> new IllegalStateException(
                "revoked delegation rule disappeared"
            ));
    }

    @Override
    public Optional<DelegationRule> resolveEffective(
        String tenantId,
        String principalId,
        String definitionKey,
        Instant effectiveAt
    ) {
        String normalizedDefinition = requireText(definitionKey, "definitionKey");
        List<DelegationRule> matches = jdbc.query(
            """
            select *
            from ap_delegation_rule
            where tenant_id = :tenantId
              and principal_id = :principalId
              and status = 'ACTIVE'
              and valid_from <= :effectiveAt
              and valid_until > :effectiveAt
              and (
                  scope = 'ALL'
                  or (scope = 'DEFINITION' and definition_key = :definitionKey)
              )
            order by
                case when scope = 'DEFINITION' then 0 else 1 end,
                valid_from desc,
                created_at desc,
                rule_id desc
            limit 1
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("principalId", requireText(principalId, "principalId"))
                .addValue("definitionKey", normalizedDefinition)
                .addValue(
                    "effectiveAt",
                    offset(Objects.requireNonNull(effectiveAt, "effectiveAt must not be null"))
                ),
            ruleMapper()
        );
        return matches.stream().findFirst();
    }

    private void lockPrincipal(String tenantId, String principalId) {
        String lockKey = requireText(tenantId, "tenantId")
            + '\u001f'
            + requireText(principalId, "principalId");
        jdbc.query(
            """
            select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))
            """,
            new MapSqlParameterSource("lockKey", lockKey),
            resultSet -> null
        );
    }

    private boolean hasOverlap(DelegationRule rule) {
        Boolean overlap = jdbc.queryForObject(
            """
            select exists (
                select 1
                from ap_delegation_rule
                where tenant_id = :tenantId
                  and principal_id = :principalId
                  and status = 'ACTIVE'
                  and scope = :scope
                  and coalesce(definition_key, '') =
                      coalesce(cast(:definitionKey as varchar), '')
                  and valid_from < :validUntil
                  and valid_until > :validFrom
            )
            """,
            parameters(rule),
            Boolean.class
        );
        return Boolean.TRUE.equals(overlap);
    }

    private static MapSqlParameterSource parameters(DelegationRule rule) {
        return new MapSqlParameterSource()
            .addValue("tenantId", rule.tenantId())
            .addValue("ruleId", rule.ruleId())
            .addValue("principalId", rule.principalId())
            .addValue("delegateId", rule.delegateId())
            .addValue("scope", rule.scope().name())
            .addValue("definitionKey", rule.definitionKey())
            .addValue("validFrom", offset(rule.validFrom()))
            .addValue("validUntil", offset(rule.validUntil()))
            .addValue("status", rule.status().name())
            .addValue("reason", rule.reason())
            .addValue("createdBy", rule.createdBy())
            .addValue("createdAt", offset(rule.createdAt()))
            .addValue("version", rule.version());
    }

    private static RowMapper<DelegationRule> ruleMapper() {
        return (resultSet, rowNumber) -> new DelegationRule(
            resultSet.getObject("rule_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getString("principal_id"),
            resultSet.getString("delegate_id"),
            DelegationScope.valueOf(resultSet.getString("scope")),
            resultSet.getString("definition_key"),
            instant(resultSet, "valid_from"),
            instant(resultSet, "valid_until"),
            DelegationStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("reason"),
            resultSet.getString("created_by"),
            instant(resultSet, "created_at"),
            resultSet.getString("revoked_by"),
            nullableInstant(resultSet, "revoked_at"),
            resultSet.getString("revoke_reason"),
            resultSet.getLong("version")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column)
        throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}

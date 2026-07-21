package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DayOverride;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingInterval;
import io.github.akaryc1b.approval.application.port.ApprovalParticipantSlaQuery;
import io.github.akaryc1b.approval.application.port.ApprovalSlaManagementQuery;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.*;

final class JdbcApprovalSlaPolicyStore extends JdbcApprovalSlaStoreSupport {

    JdbcApprovalSlaPolicyStore(DataSource dataSource, PlatformTransactionManager transactionManager) {
        super(dataSource, transactionManager);
    }

    public Optional<SlaPolicyIdentity> findPolicy(String tenantId, UUID policyId) {
        return jdbc.query(
            "select * from ap_sla_policy where tenant_id=:tenantId and policy_id=:policyId",
            params("tenantId", tenantId, "policyId", policyId),
            policyIdentityMapper()
        ).stream().findFirst();
    }
    public Optional<SlaPolicyIdentity> findPolicyByKey(String tenantId, String policyKey) {
        return jdbc.query(
            "select * from ap_sla_policy where tenant_id=:tenantId and policy_key=:policyKey",
            params("tenantId", tenantId, "policyKey", policyKey),
            policyIdentityMapper()
        ).stream().findFirst();
    }
    public SlaPolicyIdentity createPolicy(SlaPolicyIdentity policy) {
        int inserted = jdbc.update(
            """
            insert into ap_sla_policy (
                policy_id,tenant_id,policy_key,display_name,status,active_version,
                created_by,created_at,updated_at,version
            ) values (
                :policyId,:tenantId,:policyKey,:displayName,:status,:activeVersion,
                :createdBy,:createdAt,:updatedAt,:version
            ) on conflict (tenant_id,policy_key) do nothing
            """,
            policyIdentityParameters(policy)
        );
        if (inserted != 1) {
            throw conflict("APPROVAL_SLA_POLICY_VERSION_CONFLICT", "policy key already exists");
        }
        return findPolicy(policy.tenantId(), policy.policyId())
            .orElseThrow(() -> new IllegalStateException("created policy disappeared"));
    }
    public SlaPolicyVersion savePolicyVersion(SlaPolicyVersion version, long expectedPolicyVersion) {
        return required(transactions.execute(status -> {
            SlaPolicyIdentity identity = lockPolicy(version.tenantId(), version.policyId());
            if (identity.version() != expectedPolicyVersion) {
                throw conflict("APPROVAL_SLA_POLICY_VERSION_CONFLICT", "policy identity changed concurrently");
            }
            Optional<SlaPolicyVersion> existing = findPolicyVersion(
                version.tenantId(), version.policyId(), version.policyVersion()
            );
            if (existing.filter(SlaPolicyVersion::immutable).isPresent()) {
                throw conflict("APPROVAL_SLA_POLICY_ALREADY_PUBLISHED", "published policy version is immutable");
            }
            MapSqlParameterSource parameters = policyVersionParameters(version);
            if (existing.isPresent()) {
                int changed = jdbc.update(
                    """
                    update ap_sla_policy_version set
                        definition_key=:definitionKey,release_version=:releaseVersion,
                        task_definition_key=:taskDefinitionKey,target_type=:targetType,
                        duration_mode=:durationMode,duration_millis=:durationMillis,
                        calendar_id=:calendarId,calendar_version=:calendarVersion,
                        calendar_content_hash=:calendarContentHash,time_zone=:timeZone,
                        first_reminder_offset_millis=:firstReminderOffsetMillis,
                        repeat_reminder_interval_millis=:repeatReminderIntervalMillis,
                        maximum_reminder_count=:maximumReminderCount,
                        overdue_offset_millis=:overdueOffsetMillis,
                        escalation_strategy=:escalationStrategy,escalation_target=:escalationTarget,
                        automatic_action_policy=:automaticActionPolicy,
                        pause_rules_json=cast(:pauseRulesJson as jsonb),content_hash=:contentHash,
                        status='DRAFT',immutable=false,published_by=null,published_at=null,
                        updated_at=:updatedAt
                    where tenant_id=:tenantId and policy_id=:policyId
                      and policy_version=:policyVersion and immutable=false
                    """,
                    parameters
                );
                if (changed != 1) {
                    throw conflict("APPROVAL_SLA_POLICY_VERSION_CONFLICT", "policy draft changed concurrently");
                }
            } else {
                int inserted = jdbc.update(
                    """
                    insert into ap_sla_policy_version (
                        policy_id,tenant_id,policy_version,definition_key,release_version,
                        task_definition_key,target_type,duration_mode,duration_millis,
                        calendar_id,calendar_version,calendar_content_hash,time_zone,
                        first_reminder_offset_millis,repeat_reminder_interval_millis,
                        maximum_reminder_count,overdue_offset_millis,escalation_strategy,
                        escalation_target,automatic_action_policy,pause_rules_json,content_hash,
                        status,immutable,published_by,published_at,created_at,updated_at
                    ) values (
                        :policyId,:tenantId,:policyVersion,:definitionKey,:releaseVersion,
                        :taskDefinitionKey,:targetType,:durationMode,:durationMillis,
                        :calendarId,:calendarVersion,:calendarContentHash,:timeZone,
                        :firstReminderOffsetMillis,:repeatReminderIntervalMillis,
                        :maximumReminderCount,:overdueOffsetMillis,:escalationStrategy,
                        :escalationTarget,:automaticActionPolicy,cast(:pauseRulesJson as jsonb),:contentHash,
                        'DRAFT',false,null,null,:createdAt,:updatedAt
                    )
                    """,
                    parameters
                );
                if (inserted != 1) {
                    throw new IllegalStateException("policy draft was not inserted");
                }
            }
            updatePolicyVersion(identity, version.updatedAt());
            return findPolicyVersion(version.tenantId(), version.policyId(), version.policyVersion())
                .orElseThrow(() -> new IllegalStateException("saved policy version disappeared"));
        }));
    }
    public Optional<SlaPolicyVersion> findPolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion
    ) {
        return jdbc.query(
            """
            select * from ap_sla_policy_version
            where tenant_id=:tenantId and policy_id=:policyId and policy_version=:policyVersion
            """,
            params("tenantId", tenantId, "policyId", policyId, "policyVersion", policyVersion),
            policyVersionMapper()
        ).stream().findFirst();
    }
    public SlaPolicyVersion publishPolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion,
        String publishedBy,
        Instant publishedAt,
        long expectedPolicyVersion
    ) {
        return required(transactions.execute(status -> {
            SlaPolicyIdentity identity = lockPolicy(tenantId, policyId);
            SlaPolicyVersion existing = findPolicyVersion(tenantId, policyId, policyVersion)
                .orElseThrow(() -> notFound("APPROVAL_SLA_POLICY_NOT_FOUND", "policy version was not found"));
            if (existing.immutable()) {
                return existing;
            }
            if (identity.version() != expectedPolicyVersion) {
                throw conflict("APPROVAL_SLA_POLICY_VERSION_CONFLICT", "policy identity changed concurrently");
            }
            int changed = jdbc.update(
                """
                update ap_sla_policy_version set status='PUBLISHED',immutable=true,
                    published_by=:publishedBy,published_at=:publishedAt,updated_at=:publishedAt
                where tenant_id=:tenantId and policy_id=:policyId and policy_version=:policyVersion
                  and status='DRAFT' and immutable=false
                """,
                params(
                    "tenantId", tenantId,
                    "policyId", policyId,
                    "policyVersion", policyVersion,
                    "publishedBy", publishedBy,
                    "publishedAt", offset(publishedAt)
                )
            );
            if (changed != 1) {
                throw conflict("APPROVAL_SLA_POLICY_VERSION_CONFLICT", "policy version cannot be published");
            }
            updatePolicyVersion(identity, publishedAt);
            return findPolicyVersion(tenantId, policyId, policyVersion).orElseThrow();
        }));
    }
    public SlaPolicyIdentity activatePolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion,
        String activatedBy,
        Instant activatedAt,
        long expectedPolicyVersion
    ) {
        return required(transactions.execute(status -> {
            SlaPolicyVersion candidate = findPolicyVersion(tenantId, policyId, policyVersion)
                .orElseThrow(() -> notFound("APPROVAL_SLA_POLICY_NOT_FOUND", "policy version was not found"));
            advisoryLock(policyTargetLock(candidate));
            SlaPolicyIdentity identity = lockPolicy(tenantId, policyId);
            if (Objects.equals(identity.activeVersion(), policyVersion)
                && identity.status() == PolicyStatus.ACTIVE) {
                return identity;
            }
            if (identity.version() != expectedPolicyVersion) {
                throw conflict("APPROVAL_SLA_POLICY_ACTIVATION_CONFLICT", "policy identity changed concurrently");
            }
            if (!candidate.immutable()) {
                throw conflict("APPROVAL_SLA_POLICY_ACTIVATION_CONFLICT", "policy version must be published");
            }
            List<Map<String, Object>> active = jdbc.queryForList(
                """
                select policy_id,policy_version from ap_sla_policy_version
                where tenant_id=:tenantId and definition_key=:definitionKey
                  and release_version is not distinct from :releaseVersion
                  and task_definition_key is not distinct from :taskDefinitionKey
                  and target_type=:targetType and status='ACTIVE'
                """,
                params(
                    "tenantId", tenantId,
                    "definitionKey", candidate.definitionKey(),
                    "releaseVersion", candidate.releaseVersion(),
                    "taskDefinitionKey", candidate.taskDefinitionKey(),
                    "targetType", candidate.targetType().name()
                )
            );
            for (Map<String, Object> row : active) {
                UUID previousPolicyId = uuid(row.get("policy_id"));
                int previousVersion = ((Number) row.get("policy_version")).intValue();
                jdbc.update(
                    """
                    update ap_sla_policy_version set status='INACTIVE',updated_at=:activatedAt
                    where tenant_id=:tenantId and policy_id=:policyId and policy_version=:policyVersion
                    """,
                    params(
                        "tenantId", tenantId,
                        "policyId", previousPolicyId,
                        "policyVersion", previousVersion,
                        "activatedAt", offset(activatedAt)
                    )
                );
                jdbc.update(
                    """
                    update ap_sla_policy set status='INACTIVE',active_version=null,
                        updated_at=:activatedAt,version=version+1
                    where tenant_id=:tenantId and policy_id=:policyId
                    """,
                    params(
                        "tenantId", tenantId,
                        "policyId", previousPolicyId,
                        "activatedAt", offset(activatedAt)
                    )
                );
            }
            int versionChanged = jdbc.update(
                """
                update ap_sla_policy_version set status='ACTIVE',updated_at=:activatedAt
                where tenant_id=:tenantId and policy_id=:policyId and policy_version=:policyVersion
                  and immutable=true and status in ('PUBLISHED','INACTIVE','ACTIVE')
                """,
                params(
                    "tenantId", tenantId,
                    "policyId", policyId,
                    "policyVersion", policyVersion,
                    "activatedAt", offset(activatedAt)
                )
            );
            if (versionChanged != 1) {
                throw conflict("APPROVAL_SLA_POLICY_ACTIVATION_CONFLICT", "policy version cannot be activated");
            }
            int changed = jdbc.update(
                """
                update ap_sla_policy set status='ACTIVE',active_version=:policyVersion,
                    updated_at=:activatedAt,version=version+1
                where tenant_id=:tenantId and policy_id=:policyId and version=:expectedVersion
                """,
                params(
                    "tenantId", tenantId,
                    "policyId", policyId,
                    "policyVersion", policyVersion,
                    "activatedAt", offset(activatedAt),
                    "expectedVersion", expectedPolicyVersion
                )
            );
            if (changed != 1) {
                throw conflict("APPROVAL_SLA_POLICY_ACTIVATION_CONFLICT", "policy activation changed concurrently");
            }
            return findPolicy(tenantId, policyId).orElseThrow();
        }));
    }
    public SlaPolicyPage findPolicies(String tenantId, int limit, int offsetValue) {
        page(limit, offsetValue);
        long total = count(
            "select count(*) from ap_sla_policy where tenant_id=:tenantId",
            params("tenantId", tenantId)
        );
        List<SlaPolicyIdentity> items = jdbc.query(
            """
            select * from ap_sla_policy where tenant_id=:tenantId
            order by updated_at desc,policy_id limit :limit offset :offset
            """,
            params("tenantId", tenantId, "limit", limit, "offset", offsetValue),
            policyIdentityMapper()
        );
        return new SlaPolicyPage(items, total, limit, offsetValue);
    }
    public Optional<SlaPolicyVersion> findEffectivePolicy(
        String tenantId,
        String definitionKey,
        Integer releaseVersion,
        String taskDefinitionKey,
        SlaTargetType targetType
    ) {
        return jdbc.query(
            """
            select * from ap_sla_policy_version
            where tenant_id=:tenantId and definition_key=:definitionKey and target_type=:targetType
              and status='ACTIVE'
              and (release_version=:releaseVersion or release_version is null)
              and task_definition_key is not distinct from :taskDefinitionKey
            order by case when release_version is not distinct from :releaseVersion then 0 else 1 end,
                     policy_version desc,policy_id
            limit 1
            """,
            params(
                "tenantId", tenantId,
                "definitionKey", definitionKey,
                "releaseVersion", releaseVersion,
                "taskDefinitionKey", taskDefinitionKey,
                "targetType", targetType.name()
            ),
            policyVersionMapper()
        ).stream().findFirst();
    }
}

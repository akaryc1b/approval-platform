package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.MigrationAttemptProvisioningConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore.ProvisioningResult;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** MySQL 8.4 short transaction that materializes initial attempts from sealed selections. */
public final class JdbcMySqlApprovalMigrationAttemptProvisioningStore
    implements ApprovalMigrationAttemptProvisioningStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final JdbcDatabaseValueAdapter values;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcMySqlApprovalMigrationAttemptProvisioningStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        ObjectMapper mapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalMigrationAttemptProvisioningStore requires MySQL 8.4"
            );
        }
        jdbc = new NamedParameterJdbcTemplate(source);
        json = new JdbcApprovalMigrationJson(mapper);
        transactions = new TransactionTemplate(manager);
        this.auditEvents = Objects.requireNonNull(
            auditEvents,
            "auditEvents must not be null"
        );
        this.identifiers = Objects.requireNonNull(
            identifiers,
            "identifiers must not be null"
        );
    }

    @Override
    public ProvisioningResult ensureInitialAttempts(ProvisioningRequest request) {
        ProvisioningRequest exact = Objects.requireNonNull(
            request,
            "request must not be null"
        );
        Instant happenedAt = AuditHashCanonicalizer.canonicalInstant(exact.happenedAt());
        try {
            return transactions.execute(status -> provisionOnce(exact, happenedAt));
        } catch (MigrationAttemptProvisioningConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new MigrationAttemptProvisioningConflictException(
                "migration attempt provisioning persistence conflict",
                exception
            );
        }
    }

    private ProvisioningResult provisionOnce(
        ProvisioningRequest request,
        Instant happenedAt
    ) {
        IntentAuthority intent = lockIntent(request.tenantId(), request.intentId());
        if (intent.status() != IntentStatus.PENDING
            && intent.status() != IntentStatus.RUNNING) {
            throw conflict("migration intent is not current for attempt provisioning");
        }
        PlanAuthority plan = lockConsumedPlan(intent, happenedAt);
        requireExactPlanIntentIdentity(plan, intent);
        List<ProvisioningCandidate> candidates = loadCandidates(plan);
        List<ApprovalMigrationAttempt> existing = findAttempts(
            intent.tenantId(),
            intent.intentId()
        );
        requireNoUnexpectedAttempts(candidates, existing);

        Map<UUID, ApprovalMigrationAttempt> initialByInstance = initialAttempts(existing);
        List<ApprovalMigrationAttempt> result = new ArrayList<>();
        int created = 0;
        for (ProvisioningCandidate candidate : candidates) {
            ApprovalMigrationAttempt current = initialByInstance.get(candidate.instanceId());
            if (current == null) {
                current = createInitialAttempt(
                    intent,
                    plan,
                    candidate,
                    request,
                    happenedAt
                );
                created++;
            } else {
                requireExactInitialAttempt(current, intent, plan, candidate);
            }
            result.add(current);
        }
        if (created > 0) {
            appendAudit(intent, plan, request, happenedAt, created);
        }
        return new ProvisioningResult(result, created);
    }

    private IntentAuthority lockIntent(String tenantId, UUID intentId) {
        return jdbc.query("""
            select tenant_id,intent_id,plan_id,plan_hash,definition_key,
              source_release_version,source_package_hash,target_release_version,
              target_package_hash,status
            from ap_process_migration_intent
            where tenant_id=:tenantId and intent_id=:intentId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("intentId", values.bindUuid(intentId)),
            (row, number) -> new IntentAuthority(
                row.getString("tenant_id"),
                values.uuid(row, "intent_id"),
                values.uuid(row, "plan_id"),
                row.getString("plan_hash"),
                row.getString("definition_key"),
                row.getInt("source_release_version"),
                row.getString("source_package_hash"),
                row.getInt("target_release_version"),
                row.getString("target_package_hash"),
                IntentStatus.valueOf(row.getString("status"))
            )).stream().findFirst().orElseThrow(() ->
                conflict("migration intent was not found for provisioning")
            );
    }

    private PlanAuthority lockConsumedPlan(IntentAuthority intent, Instant happenedAt) {
        return jdbc.query("""
            select p.tenant_id,p.plan_id,p.plan_hash,p.definition_key,
              p.source_release_version,p.source_package_hash,p.target_release_version,
              p.target_package_hash,p.target_engine_definition_id,
              p.selected_instance_count,p.status,p.expires_at
            from ap_process_migration_plan p
            join ap_process_migration_plan_consumption c
              on c.tenant_id=p.tenant_id
             and c.plan_id=p.plan_id
             and c.plan_hash=p.plan_hash
            where p.tenant_id=:tenantId and p.plan_id=:planId and p.plan_hash=:planHash
              and c.intent_id=:intentId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", intent.tenantId())
                .addValue("planId", values.bindUuid(intent.planId()))
                .addValue("planHash", intent.planHash())
                .addValue("intentId", values.bindUuid(intent.intentId())),
            (row, number) -> new PlanAuthority(
                row.getString("tenant_id"),
                values.uuid(row, "plan_id"),
                row.getString("plan_hash"),
                row.getString("definition_key"),
                row.getInt("source_release_version"),
                row.getString("source_package_hash"),
                row.getInt("target_release_version"),
                row.getString("target_package_hash"),
                row.getString("target_engine_definition_id"),
                row.getInt("selected_instance_count"),
                row.getString("status"),
                values.instant(row, "expires_at")
            )).stream().findFirst()
            .filter(plan -> "CONSUMED".equals(plan.status()))
            .filter(plan -> happenedAt.isBefore(plan.expiresAt()))
            .orElseThrow(() ->
                conflict("consumed migration plan was not found or is expired for intent")
            );
    }

    private List<ProvisioningCandidate> loadCandidates(PlanAuthority plan) {
        List<ProvisioningCandidate> rows = jdbc.query("""
            select selection.approval_instance_id,selection.sequence_no,
              selection.expected_instance_status,selection.expected_binding_evidence_hash,
              binding.engine_instance_id,binding.definition_key,binding.release_version,
              binding.release_package_hash,binding.engine_definition_id,
              binding.binding_evidence_hash,instance.status
            from ap_process_migration_plan_instance selection
            join ap_process_runtime_binding binding
              on binding.tenant_id=selection.tenant_id
             and binding.approval_instance_id=selection.approval_instance_id
            join ap_approval_instance instance
              on instance.tenant_id=selection.tenant_id
             and instance.instance_id=selection.approval_instance_id
            where selection.tenant_id=:tenantId and selection.plan_id=:planId
            order by selection.sequence_no
            """, new MapSqlParameterSource()
                .addValue("tenantId", plan.tenantId())
                .addValue("planId", values.bindUuid(plan.planId())),
            (row, number) -> new ProvisioningCandidate(
                values.uuid(row, "approval_instance_id"),
                row.getInt("sequence_no"),
                row.getString("expected_instance_status"),
                row.getString("expected_binding_evidence_hash"),
                row.getString("engine_instance_id"),
                row.getString("definition_key"),
                row.getInt("release_version"),
                row.getString("release_package_hash"),
                row.getString("engine_definition_id"),
                row.getString("binding_evidence_hash"),
                row.getString("status")
            ));
        if (rows.size() != plan.selectedInstanceCount()) {
            throw conflict("sealed migration selection has missing runtime binding evidence");
        }
        for (int index = 0; index < rows.size(); index++) {
            ProvisioningCandidate candidate = rows.get(index);
            if (candidate.sequence() != index + 1
                || !"RUNNING".equals(candidate.expectedStatus())
                || !"RUNNING".equals(candidate.instanceStatus())
                || !candidate.bindingEvidenceHash().equals(candidate.expectedBindingHash())
                || !candidate.definitionKey().equals(plan.definitionKey())
                || candidate.releaseVersion() != plan.sourceReleaseVersion()
                || !candidate.releasePackageHash().equals(plan.sourcePackageHash())) {
                throw conflict("runtime binding no longer matches sealed migration selection");
            }
        }
        return List.copyOf(rows);
    }

    private List<ApprovalMigrationAttempt> findAttempts(String tenantId, UUID intentId) {
        return List.copyOf(jdbc.query("""
            select payload_json
            from ap_process_migration_attempt
            where tenant_id=:tenantId and intent_id=:intentId
            order by approval_instance_id,attempt_number
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("intentId", values.bindUuid(intentId)),
            (row, number) -> json.read(
                row.getString("payload_json"),
                ApprovalMigrationAttempt.class
            )));
    }

    private ApprovalMigrationAttempt createInitialAttempt(
        IntentAuthority intent,
        PlanAuthority plan,
        ProvisioningCandidate candidate,
        ProvisioningRequest request,
        Instant happenedAt
    ) {
        ApprovalMigrationAttempt attempt = new ApprovalMigrationAttempt(
            nextIdentifier("attemptId"),
            intent.tenantId(),
            intent.intentId(),
            candidate.instanceId(),
            candidate.engineInstanceId(),
            1,
            null,
            candidate.expectedBindingHash(),
            candidate.sourceEngineDefinitionId(),
            plan.targetEngineDefinitionId(),
            AttemptStatus.PENDING,
            EngineOutcome.NOT_REQUESTED,
            1,
            null,
            null,
            null,
            FailureClass.NONE,
            null,
            happenedAt,
            happenedAt,
            request.requestId(),
            request.traceId()
        );
        ApprovalMigrationAttemptEvent event = new ApprovalMigrationAttemptEvent(
            nextIdentifier("attemptEventId"),
            attempt.tenantId(),
            attempt.attemptId(),
            1,
            null,
            AttemptStatus.PENDING,
            EngineOutcome.NOT_REQUESTED,
            FailureClass.NONE,
            null,
            happenedAt,
            request.requestId(),
            request.traceId()
        ).withDurableEvidence(attempt, null);
        insertAttempt(attempt);
        appendAttemptEvent(event);
        return attempt;
    }

    private void insertAttempt(ApprovalMigrationAttempt attempt) {
        jdbc.update("""
            insert into ap_process_migration_attempt (
              tenant_id,attempt_id,intent_id,approval_instance_id,attempt_number,
              parent_attempt_id,status,revision,engine_outcome,lease_actor,
              lease_owner,lease_until,engine_request_reference,failure_class,
              error_summary,expected_binding_evidence_hash,payload_json,
              created_at,updated_at
            ) values (
              :tenantId,:attemptId,:intentId,:instanceId,:attemptNumber,
              :parentAttemptId,:status,:revision,:engineOutcome,null,
              :leaseOwner,:leaseUntil,:engineRequestReference,:failureClass,
              :errorSummary,:bindingHash,:payload,:createdAt,:updatedAt
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("attemptId", values.bindUuid(attempt.attemptId()))
                .addValue("intentId", values.bindUuid(attempt.intentId()))
                .addValue("instanceId", values.bindUuid(attempt.approvalInstanceId()))
                .addValue("attemptNumber", attempt.attemptNumber())
                .addValue("parentAttemptId", values.bindNullableUuid(attempt.parentAttemptId()))
                .addValue("status", attempt.status().name())
                .addValue("revision", attempt.revision())
                .addValue("engineOutcome", attempt.engineOutcome().name())
                .addValue("leaseOwner", attempt.leaseOwner())
                .addValue("leaseUntil", values.bindNullableInstant(attempt.leaseUntil()))
                .addValue("engineRequestReference", attempt.engineRequestReference())
                .addValue("failureClass", attempt.failureClass().name())
                .addValue("errorSummary", attempt.errorSummary())
                .addValue("bindingHash", attempt.expectedBindingEvidenceHash())
                .addValue("payload", json.write(attempt))
                .addValue("createdAt", values.bindInstant(attempt.createdAt()))
                .addValue("updatedAt", values.bindInstant(attempt.updatedAt())));
    }

    private void appendAttemptEvent(ApprovalMigrationAttemptEvent event) {
        jdbc.update("""
            insert into ap_process_migration_attempt_event (
              tenant_id,event_id,attempt_id,revision,from_status,to_status,
              engine_outcome,lease_actor,lease_owner,lease_until,
              engine_request_reference,failure_class,error_summary,payload_json,
              happened_at
            ) values (
              :tenantId,:eventId,:attemptId,:revision,:fromStatus,:toStatus,
              :engineOutcome,:leaseActor,:leaseOwner,:leaseUntil,
              :engineRequestReference,:failureClass,:errorSummary,:payload,
              :happenedAt
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", values.bindUuid(event.eventId()))
                .addValue("attemptId", values.bindUuid(event.attemptId()))
                .addValue("revision", event.revision())
                .addValue("fromStatus", event.fromStatus() == null
                    ? null
                    : event.fromStatus().name())
                .addValue("toStatus", event.toStatus().name())
                .addValue("engineOutcome", event.engineOutcome().name())
                .addValue("leaseActor", event.leaseActor())
                .addValue("leaseOwner", event.leaseOwner())
                .addValue("leaseUntil", values.bindNullableInstant(event.leaseUntil()))
                .addValue("engineRequestReference", event.engineRequestReference())
                .addValue("failureClass", event.failureClass().name())
                .addValue("errorSummary", event.errorSummary())
                .addValue("payload", json.write(event))
                .addValue("happenedAt", values.bindInstant(event.happenedAt())));
    }

    private void appendAudit(
        IntentAuthority intent,
        PlanAuthority plan,
        ProvisioningRequest request,
        Instant happenedAt,
        int created
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("planId", plan.planId().toString());
        attributes.put("planHash", plan.planHash());
        attributes.put("selectedInstanceCount", Integer.toString(plan.selectedInstanceCount()));
        attributes.put("createdAttemptCount", Integer.toString(created));
        attributes.put("workerId", request.workerId());
        attributes.put("requestHash", request.requestHash());
        auditEvents.append(new AuditEvent(
            nextIdentifier("auditEventId"),
            intent.tenantId(),
            request.workerId(),
            "PROCESS_MIGRATION_ATTEMPTS_PROVISIONED",
            "APPROVAL_MIGRATION_INTENT",
            intent.intentId().toString(),
            request.requestId(),
            request.traceId(),
            happenedAt,
            Map.copyOf(attributes)
        ));
    }

    private static void requireExactPlanIntentIdentity(
        PlanAuthority plan,
        IntentAuthority intent
    ) {
        if (!plan.tenantId().equals(intent.tenantId())
            || !plan.planId().equals(intent.planId())
            || !plan.planHash().equals(intent.planHash())
            || !plan.definitionKey().equals(intent.definitionKey())
            || plan.sourceReleaseVersion() != intent.sourceReleaseVersion()
            || !plan.sourcePackageHash().equals(intent.sourcePackageHash())
            || plan.targetReleaseVersion() != intent.targetReleaseVersion()
            || !plan.targetPackageHash().equals(intent.targetPackageHash())) {
            throw conflict("consumed plan and migration intent identity do not match");
        }
    }

    private static void requireNoUnexpectedAttempts(
        List<ProvisioningCandidate> candidates,
        List<ApprovalMigrationAttempt> attempts
    ) {
        Set<UUID> selected = new LinkedHashSet<>();
        for (ProvisioningCandidate candidate : candidates) {
            selected.add(candidate.instanceId());
        }
        if (attempts.stream().anyMatch(attempt ->
            !selected.contains(attempt.approvalInstanceId())
        )) {
            throw conflict("migration intent contains an attempt outside sealed plan selection");
        }
    }

    private static Map<UUID, ApprovalMigrationAttempt> initialAttempts(
        List<ApprovalMigrationAttempt> attempts
    ) {
        Map<UUID, ApprovalMigrationAttempt> result = new LinkedHashMap<>();
        for (ApprovalMigrationAttempt attempt : attempts) {
            if (attempt.attemptNumber() == 1) {
                ApprovalMigrationAttempt previous = result.put(
                    attempt.approvalInstanceId(),
                    attempt
                );
                if (previous != null) {
                    throw conflict("migration instance has duplicate initial attempts");
                }
            }
        }
        return result;
    }

    private static void requireExactInitialAttempt(
        ApprovalMigrationAttempt attempt,
        IntentAuthority intent,
        PlanAuthority plan,
        ProvisioningCandidate candidate
    ) {
        if (!attempt.tenantId().equals(intent.tenantId())
            || !attempt.intentId().equals(intent.intentId())
            || !attempt.approvalInstanceId().equals(candidate.instanceId())
            || !attempt.engineInstanceId().equals(candidate.engineInstanceId())
            || attempt.attemptNumber() != 1
            || attempt.parentAttemptId() != null
            || !attempt.expectedBindingEvidenceHash().equals(candidate.expectedBindingHash())
            || !attempt.sourceEngineDefinitionId().equals(candidate.sourceEngineDefinitionId())
            || !attempt.targetEngineDefinitionId().equals(plan.targetEngineDefinitionId())) {
            throw conflict("initial migration attempt does not match sealed selection evidence");
        }
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            "generated " + name + " must not be null"
        );
    }

    private static MigrationAttemptProvisioningConflictException conflict(String message) {
        return new MigrationAttemptProvisioningConflictException(message);
    }

    private record IntentAuthority(
        String tenantId,
        UUID intentId,
        UUID planId,
        String planHash,
        String definitionKey,
        int sourceReleaseVersion,
        String sourcePackageHash,
        int targetReleaseVersion,
        String targetPackageHash,
        IntentStatus status
    ) {
    }

    private record PlanAuthority(
        String tenantId,
        UUID planId,
        String planHash,
        String definitionKey,
        int sourceReleaseVersion,
        String sourcePackageHash,
        int targetReleaseVersion,
        String targetPackageHash,
        String targetEngineDefinitionId,
        int selectedInstanceCount,
        String status,
        Instant expiresAt
    ) {
    }

    private record ProvisioningCandidate(
        UUID instanceId,
        int sequence,
        String expectedStatus,
        String expectedBindingHash,
        String engineInstanceId,
        String definitionKey,
        int releaseVersion,
        String releasePackageHash,
        String sourceEngineDefinitionId,
        String bindingEvidenceHash,
        String instanceStatus
    ) {
    }
}

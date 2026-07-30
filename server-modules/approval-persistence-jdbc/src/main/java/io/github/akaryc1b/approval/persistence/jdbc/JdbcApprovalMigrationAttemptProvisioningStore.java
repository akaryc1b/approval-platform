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
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL short transaction that materializes one initial attempt per sealed selection. */
public final class JdbcApprovalMigrationAttemptProvisioningStore
    implements ApprovalMigrationAttemptProvisioningStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final JdbcApprovalMigrationProtocolStore protocol;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcApprovalMigrationAttemptProvisioningStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        DataSource source = Objects.requireNonNull(dataSource, "dataSource must not be null");
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        jdbc = new NamedParameterJdbcTemplate(source);
        json = new JdbcApprovalMigrationJson(mapper);
        protocol = new JdbcApprovalMigrationProtocolStore(source, mapper, manager);
        transactions = new TransactionTemplate(manager);
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    @Override
    public ProvisioningResult ensureInitialAttempts(ProvisioningRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return transactions.execute(status -> provisionOnce(request));
        } catch (DataAccessException exception) {
            throw new MigrationAttemptProvisioningConflictException(
                "migration attempt provisioning persistence conflict",
                exception
            );
        }
    }

    private ProvisioningResult provisionOnce(ProvisioningRequest request) {
        ApprovalMigrationIntent intent = lockIntent(request.tenantId(), request.intentId());
        if ((intent.status() != IntentStatus.PENDING && intent.status() != IntentStatus.RUNNING)
            || !request.happenedAt().isBefore(intent.expiresAt())) {
            throw conflict("migration intent is not current for attempt provisioning");
        }
        ApprovalMigrationPlan plan = lockConsumedPlan(intent);
        requireExactPlanIntentIdentity(plan, intent);
        List<ProvisioningCandidate> candidates = loadCandidates(plan);
        List<ApprovalMigrationAttempt> existing = protocol.findAttempts(
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
                current = createInitialAttempt(intent, plan, candidate, request);
                created++;
            } else {
                requireExactInitialAttempt(current, intent, plan, candidate);
            }
            result.add(current);
        }
        if (created > 0) {
            appendAudit(intent, plan, request, created);
        }
        return new ProvisioningResult(result, created);
    }

    private ApprovalMigrationIntent lockIntent(String tenantId, UUID intentId) {
        return jdbc.query("""
            select payload_json::text
            from ap_process_migration_intent
            where tenant_id=:tenantId and intent_id=:intentId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("intentId", intentId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationIntent.class))
            .stream()
            .findFirst()
            .orElseThrow(() -> conflict("migration intent was not found for provisioning"));
    }

    private ApprovalMigrationPlan lockConsumedPlan(ApprovalMigrationIntent intent) {
        return jdbc.query("""
            select payload_json::text
            from ap_process_migration_plan
            where tenant_id=:tenantId and plan_id=:planId and plan_hash=:planHash
            for share
            """, new MapSqlParameterSource()
                .addValue("tenantId", intent.tenantId())
                .addValue("planId", intent.planId())
                .addValue("planHash", intent.planHash()),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationPlan.class))
            .stream()
            .findFirst()
            .filter(plan -> plan.status() == PlanStatus.CONSUMED)
            .orElseThrow(() -> conflict("consumed migration plan was not found for intent"));
    }

    private List<ProvisioningCandidate> loadCandidates(ApprovalMigrationPlan plan) {
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
                .addValue("planId", plan.planId()),
            (row, number) -> new ProvisioningCandidate(
                row.getObject("approval_instance_id", UUID.class),
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
            ApprovalMigrationPlan.SelectedInstance selected = plan.selectedInstances().get(index);
            if (candidate.sequence() != index + 1
                || !candidate.instanceId().equals(selected.approvalInstanceId())
                || !candidate.expectedBindingHash().equals(
                    selected.expectedBindingEvidenceHash()
                )
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

    private ApprovalMigrationAttempt createInitialAttempt(
        ApprovalMigrationIntent intent,
        ApprovalMigrationPlan plan,
        ProvisioningCandidate candidate,
        ProvisioningRequest request
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
            request.happenedAt(),
            request.happenedAt(),
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
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
        return protocol.createAttempt(attempt, event).attempt();
    }

    private void appendAudit(
        ApprovalMigrationIntent intent,
        ApprovalMigrationPlan plan,
        ProvisioningRequest request,
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
            request.happenedAt(),
            Map.copyOf(attributes)
        ));
    }

    private static void requireExactPlanIntentIdentity(
        ApprovalMigrationPlan plan,
        ApprovalMigrationIntent intent
    ) {
        if (!plan.tenantId().equals(intent.tenantId())
            || !plan.planId().equals(intent.planId())
            || !plan.planHash().equals(intent.planHash())
            || !plan.definitionKey().equals(intent.definitionKey())
            || plan.sourceReleaseVersion() != intent.sourceReleaseVersion()
            || !plan.sourcePackageHash().equals(intent.sourcePackageHash())
            || plan.targetReleaseVersion() != intent.targetReleaseVersion()
            || !plan.targetPackageHash().equals(intent.targetPackageHash())
            || plan.selectedInstanceCount() != intent.selectedInstanceCount()) {
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
        if (attempts.stream().anyMatch(attempt -> !selected.contains(
            attempt.approvalInstanceId()
        ))) {
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
        ApprovalMigrationIntent intent,
        ApprovalMigrationPlan plan,
        ProvisioningCandidate candidate
    ) {
        if (!attempt.tenantId().equals(intent.tenantId())
            || !attempt.intentId().equals(intent.intentId())
            || !attempt.approvalInstanceId().equals(candidate.instanceId())
            || !attempt.engineInstanceId().equals(candidate.engineInstanceId())
            || attempt.attemptNumber() != 1
            || attempt.parentAttemptId() != null
            || !attempt.expectedBindingEvidenceHash().equals(
                candidate.expectedBindingHash()
            )
            || !attempt.sourceEngineDefinitionId().equals(
                candidate.sourceEngineDefinitionId()
            )
            || !attempt.targetEngineDefinitionId().equals(
                plan.targetEngineDefinitionId()
            )) {
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

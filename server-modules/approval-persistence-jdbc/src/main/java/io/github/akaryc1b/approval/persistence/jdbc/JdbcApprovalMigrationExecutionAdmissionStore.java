package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.AdmissionResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExecutionAdmissionStore.MigrationExecutionAdmissionConflictException;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanConsumption;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL short transaction for exact authorized-plan consumption and intent admission. */
public final class JdbcApprovalMigrationExecutionAdmissionStore
    implements ApprovalMigrationExecutionAdmissionStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final JdbcApprovalMigrationPlanRepository plans;
    private final JdbcApprovalMigrationIntentRepository intents;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;

    public JdbcApprovalMigrationExecutionAdmissionStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents
    ) {
        DataSource source = Objects.requireNonNull(dataSource, "dataSource must not be null");
        json = new JdbcApprovalMigrationJson(
            Objects.requireNonNull(objectMapper, "objectMapper must not be null")
        );
        jdbc = new NamedParameterJdbcTemplate(source);
        plans = new JdbcApprovalMigrationPlanRepository(source, json);
        intents = new JdbcApprovalMigrationIntentRepository(source, json);
        transactions = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
    }

    @Override
    public AdmissionResult admit(AdmissionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireEvidence(request);
        Optional<AdmissionResult> replay = findReplay(
            request.consumption().tenantId(),
            request.consumption().idempotencyKey(),
            request.consumption().requestHash()
        );
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return transactions.execute(status -> admitOnce(request));
        } catch (DataAccessException exception) {
            Optional<AdmissionResult> concurrentReplay = findReplay(
                request.consumption().tenantId(),
                request.consumption().idempotencyKey(),
                request.consumption().requestHash()
            );
            if (concurrentReplay.isPresent()) {
                return concurrentReplay.get();
            }
            throw new MigrationExecutionAdmissionConflictException(
                "migration execution admission persistence conflict",
                exception
            );
        }
    }

    @Override
    public Optional<ApprovalMigrationPlanConsumption> findConsumption(
        String tenantId,
        UUID planId
    ) {
        return queryConsumption(
            "tenant_id=:tenantId and plan_id=:planId",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("planId", planId)
        );
    }

    private AdmissionResult admitOnce(AdmissionRequest request) {
        ApprovalMigrationPlanConsumption requestedConsumption = request.consumption();
        Optional<AdmissionResult> replay = findReplay(
            requestedConsumption.tenantId(),
            requestedConsumption.idempotencyKey(),
            requestedConsumption.requestHash()
        );
        if (replay.isPresent()) {
            return replay.get();
        }
        ApprovalMigrationPlan current = lockPlan(
            requestedConsumption.tenantId(),
            requestedConsumption.planId(),
            requestedConsumption.planHash()
        );
        if (current.status() == PlanStatus.CONSUMED) {
            return replayByPlan(current, requestedConsumption.requestHash());
        }
        Instant consumedAt = requestedConsumption.consumedAt();
        if (current.status() != PlanStatus.AUTHORIZED
            || current.revision() != request.expectedRevision()
            || !current.authorizedAt(consumedAt)
            || !current.authorizationId().equals(requestedConsumption.authorizationId())
            || !current.authorizationEvidenceHash().equals(
                requestedConsumption.authorizationEvidenceHash()
            )) {
            throw conflict("migration plan is not current and authorized for exact admission");
        }
        if (intents.insert(request.intent()) != 1) {
            throw conflict("migration intent identity or idempotency key is already in use");
        }
        intents.appendEvent(request.intentEvent());
        insertConsumption(requestedConsumption);
        if (plans.updatePlan(
            request.consumedPlan(),
            request.expectedRevision(),
            request.planEvent()
        ) != 1) {
            throw conflict("migration plan consumption lost revision compare-and-set");
        }
        plans.appendEvent(request.planEvent());
        auditEvents.append(request.auditEvent());
        return new AdmissionResult(
            request.consumedPlan(),
            request.intent(),
            requestedConsumption,
            false
        );
    }

    private ApprovalMigrationPlan lockPlan(String tenantId, UUID planId, String planHash) {
        return jdbc.query("""
            select payload_json::text
            from ap_process_migration_plan
            where tenant_id=:tenantId and plan_id=:planId and plan_hash=:planHash
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("planId", planId)
                .addValue("planHash", planHash),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationPlan.class))
            .stream()
            .findFirst()
            .orElseThrow(() -> conflict("migration plan was not found for exact admission"));
    }

    private void insertConsumption(ApprovalMigrationPlanConsumption value) {
        int inserted = jdbc.update("""
            insert into ap_process_migration_plan_consumption (
              tenant_id,consumption_id,plan_id,plan_hash,authorization_id,
              authorization_evidence_hash,intent_id,intent_evidence_hash,idempotency_key,
              request_hash,consumed_by,reason,consumed_at,request_id,trace_id,
              audit_chain_reference,payload_json
            ) values (
              :tenantId,:consumptionId,:planId,:planHash,:authorizationId,
              :authorizationEvidenceHash,:intentId,:intentEvidenceHash,:idempotencyKey,
              :requestHash,:consumedBy,:reason,:consumedAt,:requestId,:traceId,
              :auditChainReference,cast(:payload as jsonb)
            ) on conflict (tenant_id,idempotency_key) do nothing
            """, consumptionParameters(value));
        if (inserted != 1) {
            throw conflict("migration plan consumption idempotency key is already in use");
        }
    }

    private Optional<AdmissionResult> findReplay(
        String tenantId,
        String idempotencyKey,
        String requestHash
    ) {
        Optional<ApprovalMigrationPlanConsumption> existing = queryConsumption(
            "tenant_id=:tenantId and idempotency_key=:idempotencyKey",
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("idempotencyKey", idempotencyKey)
        );
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        ApprovalMigrationPlanConsumption consumption = existing.get();
        if (!consumption.requestHash().equals(requestHash)) {
            throw conflict("migration admission idempotency key was reused with different payload");
        }
        return Optional.of(authoritativeReplay(consumption));
    }

    private AdmissionResult replayByPlan(ApprovalMigrationPlan plan, String requestHash) {
        ApprovalMigrationPlanConsumption consumption = findConsumption(
            plan.tenantId(),
            plan.planId()
        ).orElseThrow(() -> conflict("consumed migration plan has no admission evidence"));
        if (!consumption.requestHash().equals(requestHash)) {
            throw conflict("migration plan was already consumed by a different request");
        }
        return authoritativeReplay(consumption);
    }

    private AdmissionResult authoritativeReplay(ApprovalMigrationPlanConsumption consumption) {
        ApprovalMigrationPlan plan = plans.findPlan(
            consumption.tenantId(),
            consumption.planId()
        ).orElseThrow(() -> conflict("migration admission plan replay disappeared"));
        ApprovalMigrationIntent intent = intents.find(
            consumption.tenantId(),
            consumption.intentId()
        ).orElseThrow(() -> conflict("migration admission intent replay disappeared"));
        if (plan.status() != PlanStatus.CONSUMED
            || !plan.planHash().equals(consumption.planHash())
            || !intent.planId().equals(plan.planId())
            || !intent.planHash().equals(plan.planHash())
            || !intent.intentEvidenceHash().equals(consumption.intentEvidenceHash())) {
            throw conflict("migration admission replay evidence is inconsistent");
        }
        return new AdmissionResult(plan, intent, consumption, true);
    }

    private Optional<ApprovalMigrationPlanConsumption> queryConsumption(
        String predicate,
        MapSqlParameterSource parameters
    ) {
        return jdbc.query(
            "select payload_json::text from ap_process_migration_plan_consumption where "
                + predicate,
            parameters,
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationPlanConsumption.class
            )
        ).stream().findFirst();
    }

    private MapSqlParameterSource consumptionParameters(ApprovalMigrationPlanConsumption value) {
        return new MapSqlParameterSource()
            .addValue("tenantId", value.tenantId())
            .addValue("consumptionId", value.consumptionId())
            .addValue("planId", value.planId())
            .addValue("planHash", value.planHash())
            .addValue("authorizationId", value.authorizationId())
            .addValue("authorizationEvidenceHash", value.authorizationEvidenceHash())
            .addValue("intentId", value.intentId())
            .addValue("intentEvidenceHash", value.intentEvidenceHash())
            .addValue("idempotencyKey", value.idempotencyKey())
            .addValue("requestHash", value.requestHash())
            .addValue("consumedBy", value.consumedBy())
            .addValue("reason", value.reason())
            .addValue("consumedAt", JdbcApprovalMigrationJson.offset(value.consumedAt()))
            .addValue("requestId", value.requestId())
            .addValue("traceId", value.traceId())
            .addValue("auditChainReference", value.auditChainReference())
            .addValue("payload", json.write(value));
    }

    private static void requireEvidence(AdmissionRequest request) {
        if (request.consumedPlan().status() != PlanStatus.CONSUMED
            || request.planEvent().fromStatus() != PlanStatus.AUTHORIZED
            || request.planEvent().toStatus() != PlanStatus.CONSUMED
            || request.intent().revision() != 1
            || request.intentEvent().fromStatus() != null) {
            throw new IllegalArgumentException("migration admission transition evidence is invalid");
        }
    }

    private static MigrationExecutionAdmissionConflictException conflict(String message) {
        return new MigrationExecutionAdmissionConflictException(message);
    }
}

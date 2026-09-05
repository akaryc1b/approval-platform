package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceFact;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregateEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanCompletion;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanSignals;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.Summary;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationRules;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** MySQL 8.4 D8 aggregation from immutable D1-D7 server evidence only. */
public final class JdbcMySqlApprovalMigrationPlanAggregationStore
    extends JdbcMySqlApprovalMigrationPlanAggregationPersistenceSupport
    implements ApprovalMigrationPlanAggregationStore {

    public JdbcMySqlApprovalMigrationPlanAggregationStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        super(dataSource, objectMapper, transactionManager, auditEvents, identifiers);
    }

    @Override
    public AggregationResult aggregate(AggregationRequest request) {
        AggregationRequest exact = Objects.requireNonNull(
            request,
            "request must not be null"
        );
        return execute("migration plan aggregation conflict", () -> {
            try {
                locks.acquire(planLockScope(exact.tenantId(), exact.planId()));
            } catch (RuntimeException exception) {
                throw conflict(
                    "plan aggregation serialization failed; exact replay is required",
                    exception
                );
            }
            return aggregateOnce(exact);
        });
    }

    private AggregationResult aggregateOnce(AggregationRequest request) {
        String requestHash = requestHash(request);
        Optional<PlanAggregate> replay = findAggregateByIdempotency(
            request.tenantId(),
            request.idempotencyKey()
        );
        if (replay.isPresent()) {
            PlanAggregate aggregate = replay.orElseThrow();
            requireReplay(aggregate, request, requestHash);
            return result(aggregate, true);
        }

        PlanContext plan = lockPlan(request.tenantId(), request.planId());
        long currentRevision = currentAggregateRevision(plan.tenantId(), plan.planId());
        if (request.expectedAggregateRevision() != currentRevision + 1) {
            throw conflict("plan aggregate revision is stale");
        }

        List<InstanceFact> facts = loadFacts(plan);
        if (facts.size() != plan.selectedCount()) {
            throw conflict(
                "sealed plan selected count does not match canonical instances"
            );
        }
        PlanSignals signals = loadSignals(plan);
        Summary summary = ApprovalMigrationPlanAggregationRules.summarize(facts, signals);
        String inputHash = inputEvidenceHash(plan, summary);
        if (currentRevision > 0) {
            PlanAggregate latest = latestAggregate(plan.tenantId(), plan.planId());
            if (latest.inputEvidenceHash().equals(inputHash)) {
                throw conflict(
                    "authoritative aggregation input is unchanged; exact replay must reuse "
                        + "the existing idempotency key"
                );
            }
        }
        String predecessor = currentRevision == 0
            ? ZERO_HASH
            : latestAggregateHash(plan.tenantId(), plan.planId());
        String aggregateHash = aggregateHash(
            plan,
            request.expectedAggregateRevision(),
            summary,
            inputHash,
            predecessor
        );

        UUID auditEventId = nextIdentifier("auditEventId");
        String auditReference = "audit-event:" + auditEventId;
        PlanAggregate aggregate = new PlanAggregate(
            nextIdentifier("planAggregateId"),
            plan.tenantId(),
            request.operatorId(),
            plan.planId(),
            plan.intentId(),
            plan.planHash(),
            request.expectedAggregateRevision(),
            summary.status(),
            summary.terminalOutcome(),
            summary.counts(),
            summary.signals().canaryStatus(),
            summary.signals().orchestrationStatus(),
            summary.signals().paused(),
            summary.signals().pauseReason(),
            summary.signals().killSwitchObserved(),
            inputHash,
            predecessor,
            request.idempotencyKey(),
            requestHash,
            aggregateHash,
            request.happenedAt(),
            request.reason(),
            request.requestId(),
            request.traceId(),
            auditReference
        );
        PlanAggregateEvent event = event(aggregate, request);
        PlanCompletion completion = completion(aggregate, request);

        insertAggregate(aggregate);
        insertEvent(event);
        if (completion != null) {
            insertCompletion(completion);
        }
        appendAudit(auditEventId, aggregate, completion != null);
        return new AggregationResult(aggregate, event, completion, false);
    }
}

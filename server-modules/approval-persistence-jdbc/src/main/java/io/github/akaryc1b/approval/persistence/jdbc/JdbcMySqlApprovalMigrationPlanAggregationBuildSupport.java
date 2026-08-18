package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationRequest;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceFact;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregateEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanCompletion;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.StateCounts;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.Summary;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

abstract class JdbcMySqlApprovalMigrationPlanAggregationBuildSupport
    extends JdbcMySqlApprovalMigrationPlanAggregationFactSupport {

    JdbcMySqlApprovalMigrationPlanAggregationBuildSupport(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        super(dataSource, objectMapper, transactionManager, auditEvents, identifiers);
    }

    protected String inputEvidenceHash(PlanContext plan, Summary summary) {
        List<Object> inputs = new ArrayList<>();
        inputs.add("M5-D8-INPUT-EVIDENCE-V1");
        inputs.add(plan.tenantId());
        inputs.add(plan.planId());
        inputs.add(plan.planHash());
        inputs.add(plan.intentId());
        inputs.add(plan.intentEvidenceHash());
        inputs.add(plan.selectedCount());
        inputs.add(summary.signals().canaryStatus());
        inputs.add(summary.signals().orchestrationStatus());
        inputs.add(summary.signals().pauseReason());
        inputs.add(summary.signals().killSwitchObserved());
        inputs.add(summary.signals().incompleteEvidence());
        inputs.add(summary.signals().evidenceHash());
        for (InstanceFact fact : summary.canonicalFacts()) {
            inputs.add(fact.sequenceNo());
            inputs.add(fact.approvalInstanceId());
            inputs.add(fact.canary());
            inputs.add(fact.attemptId());
            inputs.add(fact.status());
            inputs.add(fact.selectedInstanceEvidenceHash());
            inputs.add(fact.evidenceHash());
        }
        return hashValues(inputs.toArray());
    }

    protected String aggregateHash(
        PlanContext plan,
        long revision,
        Summary summary,
        String inputHash,
        String predecessor
    ) {
        StateCounts counts = summary.counts();
        return hashValues(
            "M5-D8-PLAN-AGGREGATE-V1",
            plan.tenantId(),
            plan.planId(),
            plan.intentId(),
            plan.planHash(),
            revision,
            summary.status(),
            summary.terminalOutcome(),
            counts.selectedCount(),
            counts.provisionedAttemptCount(),
            counts.pendingCount(),
            counts.claimedCount(),
            counts.engineRequestedCount(),
            counts.verifyingCount(),
            counts.reconcilingCount(),
            counts.unknownCount(),
            counts.manualReviewCount(),
            counts.bindingConflictCount(),
            counts.blockedStaleCount(),
            counts.terminalFailedCount(),
            counts.exactSuccessCount(),
            counts.unresolvedCount(),
            summary.signals().canaryStatus(),
            summary.signals().orchestrationStatus(),
            summary.signals().pauseReason(),
            summary.signals().killSwitchObserved(),
            inputHash,
            predecessor
        );
    }

    protected PlanAggregateEvent event(
        PlanAggregate aggregate,
        AggregationRequest request
    ) {
        String eventHash = hashValues(
            "M5-D8-PLAN-AGGREGATE-EVENT-V1",
            aggregate.tenantId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.terminalOutcome(),
            aggregate.pauseReason(),
            aggregate.aggregateHash(),
            aggregate.predecessorHash()
        );
        return new PlanAggregateEvent(
            nextIdentifier("planAggregateEventId"),
            aggregate.tenantId(),
            aggregate.aggregateId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.terminalOutcome(),
            aggregate.pauseReason(),
            aggregate.predecessorHash(),
            aggregate.aggregateHash(),
            eventHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId(),
            aggregate.auditReference()
        );
    }

    protected PlanCompletion completion(
        PlanAggregate aggregate,
        AggregationRequest request
    ) {
        if (aggregate.status() != AggregateStatus.COMPLETED_SUCCEEDED
            && aggregate.status()
                != AggregateStatus.COMPLETED_WITH_TERMINAL_FAILURE) {
            return null;
        }
        String completionHash = hashValues(
            "M5-D8-PLAN-COMPLETION-V1",
            aggregate.tenantId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.terminalOutcome(),
            aggregate.counts().selectedCount(),
            aggregate.counts().exactSuccessCount(),
            aggregate.counts().terminalFailedCount(),
            aggregate.counts().unresolvedCount(),
            aggregate.inputEvidenceHash(),
            aggregate.aggregateHash()
        );
        return new PlanCompletion(
            nextIdentifier("planCompletionId"),
            aggregate.tenantId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.terminalOutcome(),
            aggregate.counts(),
            aggregate.inputEvidenceHash(),
            aggregate.aggregateHash(),
            completionHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId(),
            aggregate.auditReference()
        );
    }
}

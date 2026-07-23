package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionPlanner;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Coordinates SLA lifecycle and durable execution evidence in one database transaction. */
public final class TransactionalApprovalSlaStore implements ApprovalSlaStore {

    private final ApprovalSlaStore delegate;
    private final ApprovalSlaExecutionStore executions;
    private final ApprovalSlaExecutionPlanner planner;
    private final TransactionTemplate transactions;

    public TransactionalApprovalSlaStore(
        ApprovalSlaStore delegate,
        ApprovalSlaExecutionStore executions,
        ApprovalSlaExecutionPlanner planner,
        PlatformTransactionManager transactionManager
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.executions = Objects.requireNonNull(executions, "executions must not be null");
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.transactions = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    @Override
    public Optional<CalendarIdentity> findCalendar(String tenantId, UUID calendarId) {
        return delegate.findCalendar(tenantId, calendarId);
    }

    @Override
    public Optional<CalendarIdentity> findCalendarByKey(String tenantId, String calendarKey) {
        return delegate.findCalendarByKey(tenantId, calendarKey);
    }

    @Override
    public CalendarIdentity createCalendar(CalendarIdentity calendar) {
        return delegate.createCalendar(calendar);
    }

    @Override
    public CalendarVersion saveCalendarVersion(
        CalendarVersion calendarVersion,
        long expectedCalendarVersion
    ) {
        return delegate.saveCalendarVersion(calendarVersion, expectedCalendarVersion);
    }

    @Override
    public Optional<CalendarVersion> findCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion
    ) {
        return delegate.findCalendarVersion(tenantId, calendarId, calendarVersion);
    }

    @Override
    public CalendarVersion publishCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion,
        String publishedBy,
        Instant publishedAt,
        long expectedCalendarVersion
    ) {
        return delegate.publishCalendarVersion(
            tenantId,
            calendarId,
            calendarVersion,
            publishedBy,
            publishedAt,
            expectedCalendarVersion
        );
    }

    @Override
    public CalendarIdentity activateCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion,
        String activatedBy,
        Instant activatedAt,
        long expectedCalendarVersion
    ) {
        return delegate.activateCalendarVersion(
            tenantId,
            calendarId,
            calendarVersion,
            activatedBy,
            activatedAt,
            expectedCalendarVersion
        );
    }

    @Override
    public CalendarPage findCalendars(String tenantId, int limit, int offset) {
        return delegate.findCalendars(tenantId, limit, offset);
    }

    @Override
    public Optional<SlaPolicyIdentity> findPolicy(String tenantId, UUID policyId) {
        return delegate.findPolicy(tenantId, policyId);
    }

    @Override
    public Optional<SlaPolicyIdentity> findPolicyByKey(String tenantId, String policyKey) {
        return delegate.findPolicyByKey(tenantId, policyKey);
    }

    @Override
    public SlaPolicyIdentity createPolicy(SlaPolicyIdentity policy) {
        return delegate.createPolicy(policy);
    }

    @Override
    public SlaPolicyVersion savePolicyVersion(
        SlaPolicyVersion policyVersion,
        long expectedPolicyVersion
    ) {
        return delegate.savePolicyVersion(policyVersion, expectedPolicyVersion);
    }

    @Override
    public Optional<SlaPolicyVersion> findPolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion
    ) {
        return delegate.findPolicyVersion(tenantId, policyId, policyVersion);
    }

    @Override
    public SlaPolicyVersion publishPolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion,
        String publishedBy,
        Instant publishedAt,
        long expectedPolicyVersion
    ) {
        return delegate.publishPolicyVersion(
            tenantId,
            policyId,
            policyVersion,
            publishedBy,
            publishedAt,
            expectedPolicyVersion
        );
    }

    @Override
    public SlaPolicyIdentity activatePolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion,
        String activatedBy,
        Instant activatedAt,
        long expectedPolicyVersion
    ) {
        return delegate.activatePolicyVersion(
            tenantId,
            policyId,
            policyVersion,
            activatedBy,
            activatedAt,
            expectedPolicyVersion
        );
    }

    @Override
    public SlaPolicyPage findPolicies(String tenantId, int limit, int offset) {
        return delegate.findPolicies(tenantId, limit, offset);
    }

    @Override
    public Optional<SlaPolicyVersion> findEffectivePolicy(
        String tenantId,
        String definitionKey,
        Integer releaseVersion,
        String taskDefinitionKey,
        SlaTargetType targetType
    ) {
        return delegate.findEffectivePolicy(
            tenantId,
            definitionKey,
            releaseVersion,
            taskDefinitionKey,
            targetType
        );
    }

    @Override
    public int createInstances(List<SlaInstance> instances) {
        return required(() -> {
            int inserted = delegate.createInstances(instances);
            if (instances != null) {
                for (SlaInstance requested : instances) {
                    if (requested == null) {
                        continue;
                    }
                    delegate.findInstance(
                        requested.tenantId(),
                        requested.slaInstanceId()
                    ).ifPresent(this::enqueue);
                }
            }
            return inserted;
        });
    }

    @Override
    public Optional<SlaInstance> findInstance(String tenantId, UUID slaInstanceId) {
        return delegate.findInstance(tenantId, slaInstanceId);
    }

    @Override
    public Optional<SlaInstance> findActiveProcessInstance(
        String tenantId,
        UUID approvalInstanceId
    ) {
        return delegate.findActiveProcessInstance(tenantId, approvalInstanceId);
    }

    @Override
    public Optional<SlaInstance> findActiveTaskInstance(String tenantId, UUID taskId) {
        return delegate.findActiveTaskInstance(tenantId, taskId);
    }

    @Override
    public Optional<SlaInstance> findActiveCollaborationInstance(
        String tenantId,
        UUID collaborationParticipantId
    ) {
        return delegate.findActiveCollaborationInstance(tenantId, collaborationParticipantId);
    }

    @Override
    public List<SlaInstance> findActiveByApprovalInstance(
        String tenantId,
        UUID approvalInstanceId
    ) {
        return delegate.findActiveByApprovalInstance(tenantId, approvalInstanceId);
    }

    @Override
    public SlaInstance pause(
        String tenantId,
        UUID slaInstanceId,
        long expectedVersion,
        Instant pausedAt,
        String reason
    ) {
        return required(() -> {
            SlaInstance paused = delegate.pause(
                tenantId,
                slaInstanceId,
                expectedVersion,
                pausedAt,
                reason
            );
            executions.cancelActiveForSla(
                tenantId,
                slaInstanceId,
                pausedAt,
                "SLA_PAUSED: " + reason
            );
            return paused;
        });
    }

    @Override
    public SlaInstance resume(
        String tenantId,
        UUID slaInstanceId,
        long expectedVersion,
        Instant dueAt,
        Instant nextReminderAt,
        Instant overdueAt,
        Duration accumulatedPausedDuration,
        Instant resumedAt
    ) {
        return required(() -> {
            SlaInstance resumed = delegate.resume(
                tenantId,
                slaInstanceId,
                expectedVersion,
                dueAt,
                nextReminderAt,
                overdueAt,
                accumulatedPausedDuration,
                resumedAt
            );
            executions.cancelActiveForSla(
                tenantId,
                slaInstanceId,
                resumedAt,
                "SLA_RESCHEDULED_ON_RESUME"
            );
            enqueue(resumed);
            return resumed;
        });
    }

    @Override
    public int terminalTask(
        String tenantId,
        UUID taskId,
        SlaTerminalReason reason,
        Instant terminalAt
    ) {
        return required(() -> {
            List<SlaInstance> affected = activeByTask(tenantId, taskId);
            int changed = delegate.terminalTask(tenantId, taskId, reason, terminalAt);
            cancel(affected, terminalAt, reason);
            return changed;
        });
    }

    @Override
    public int terminalCollaborationParticipant(
        String tenantId,
        UUID collaborationParticipantId,
        SlaTerminalReason reason,
        Instant terminalAt
    ) {
        return required(() -> {
            List<SlaInstance> affected = delegate.findActiveCollaborationInstance(
                tenantId,
                collaborationParticipantId
            ).map(List::of).orElseGet(List::of);
            int changed = delegate.terminalCollaborationParticipant(
                tenantId,
                collaborationParticipantId,
                reason,
                terminalAt
            );
            cancel(affected, terminalAt, reason);
            return changed;
        });
    }

    @Override
    public int terminalCollaborationParticipantsByTask(
        String tenantId,
        UUID taskId,
        SlaTerminalReason reason,
        Instant terminalAt
    ) {
        return required(() -> {
            List<SlaInstance> affected = activeByTask(tenantId, taskId).stream()
                .filter(instance -> instance.targetType()
                    == SlaTargetType.COLLABORATION_PARTICIPANT)
                .toList();
            int changed = delegate.terminalCollaborationParticipantsByTask(
                tenantId,
                taskId,
                reason,
                terminalAt
            );
            cancel(affected, terminalAt, reason);
            return changed;
        });
    }

    @Override
    public int terminalApprovalInstance(
        String tenantId,
        UUID approvalInstanceId,
        SlaTerminalReason reason,
        Instant terminalAt
    ) {
        return required(() -> {
            List<SlaInstance> affected = delegate.findActiveByApprovalInstance(
                tenantId,
                approvalInstanceId
            );
            int changed = delegate.terminalApprovalInstance(
                tenantId,
                approvalInstanceId,
                reason,
                terminalAt
            );
            cancel(affected, terminalAt, reason);
            return changed;
        });
    }

    @Override
    public SlaInstance changeResponsibility(
        ResponsibilityChange change,
        long expectedVersion
    ) {
        return required(() -> {
            SlaInstance changed = delegate.changeResponsibility(change, expectedVersion);
            executions.updateFutureResponsibleUser(
                changed.tenantId(),
                changed.slaInstanceId(),
                changed.responsibleUserId(),
                changed.updatedAt()
            );
            return changed;
        });
    }

    @Override
    public SlaInstancePage findInstances(SlaInstanceCriteria criteria) {
        return delegate.findInstances(criteria);
    }

    private void enqueue(SlaInstance instance) {
        SlaPolicyVersion policy = delegate.findPolicyVersion(
            instance.tenantId(),
            instance.policyId(),
            instance.policyVersion()
        ).orElseThrow(() -> new IllegalStateException(
            "immutable SLA policy snapshot was not found for execution planning"
        ));
        executions.enqueue(planner.plan(instance, policy));
    }

    private List<SlaInstance> activeByTask(String tenantId, UUID taskId) {
        Optional<SlaInstance> anchor = delegate.findActiveTaskInstance(tenantId, taskId);
        if (anchor.isEmpty()) {
            return List.of();
        }
        return delegate.findActiveByApprovalInstance(
            tenantId,
            anchor.get().approvalInstanceId()
        ).stream().filter(instance -> taskId.equals(instance.taskId())).toList();
    }

    private void cancel(
        List<SlaInstance> affected,
        Instant terminalAt,
        SlaTerminalReason reason
    ) {
        for (SlaInstance instance : affected) {
            executions.cancelActiveForSla(
                instance.tenantId(),
                instance.slaInstanceId(),
                terminalAt,
                "SLA_TERMINAL: " + reason.name()
            );
        }
    }

    private <T> T required(Supplier<T> operation) {
        T result = transactions.execute(status -> operation.get());
        return Objects.requireNonNull(result, "transaction result must not be null");
    }
}

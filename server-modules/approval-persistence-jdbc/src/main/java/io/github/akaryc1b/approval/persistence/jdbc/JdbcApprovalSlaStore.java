package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalParticipantSlaQuery;
import io.github.akaryc1b.approval.application.port.ApprovalSlaManagementQuery;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.*;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL versioned calendar, policy, SLA instance and responsibility evidence store. */
public final class JdbcApprovalSlaStore implements ApprovalSlaStore,
    ApprovalSlaManagementQuery, ApprovalParticipantSlaQuery {

    private final JdbcApprovalCalendarStore calendars;
    private final JdbcApprovalSlaPolicyStore policies;
    private final JdbcApprovalSlaInstanceStore instances;

    public JdbcApprovalSlaStore(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.calendars = new JdbcApprovalCalendarStore(dataSource, transactionManager);
        this.policies = new JdbcApprovalSlaPolicyStore(dataSource, transactionManager);
        this.instances = new JdbcApprovalSlaInstanceStore(dataSource, transactionManager);
    }

    @Override
    public Optional<CalendarIdentity> findCalendar(String tenantId, UUID calendarId) {
        return calendars.findCalendar(tenantId, calendarId);
    }

    @Override
    public Optional<CalendarIdentity> findCalendarByKey(String tenantId, String calendarKey) {
        return calendars.findCalendarByKey(tenantId, calendarKey);
    }

    @Override
    public CalendarIdentity createCalendar(CalendarIdentity calendar) {
        return calendars.createCalendar(calendar);
    }

    @Override
    public CalendarVersion saveCalendarVersion(CalendarVersion version, long expectedCalendarVersion) {
        return calendars.saveCalendarVersion(version, expectedCalendarVersion);
    }

    @Override
    public Optional<CalendarVersion> findCalendarVersion( String tenantId, UUID calendarId, int calendarVersion ) {
        return calendars.findCalendarVersion(tenantId, calendarId, calendarVersion);
    }

    @Override
    public CalendarVersion publishCalendarVersion( String tenantId, UUID calendarId, int calendarVersion, String publishedBy, Instant publishedAt, long expectedCalendarVersion ) {
        return calendars.publishCalendarVersion(tenantId, calendarId, calendarVersion, publishedBy, publishedAt, expectedCalendarVersion);
    }

    @Override
    public CalendarIdentity activateCalendarVersion( String tenantId, UUID calendarId, int calendarVersion, String activatedBy, Instant activatedAt, long expectedCalendarVersion ) {
        return calendars.activateCalendarVersion(tenantId, calendarId, calendarVersion, activatedBy, activatedAt, expectedCalendarVersion);
    }

    @Override
    public CalendarPage findCalendars(String tenantId, int limit, int offsetValue) {
        return calendars.findCalendars(tenantId, limit, offsetValue);
    }

    @Override
    public Optional<SlaPolicyIdentity> findPolicy(String tenantId, UUID policyId) {
        return policies.findPolicy(tenantId, policyId);
    }

    @Override
    public Optional<SlaPolicyIdentity> findPolicyByKey(String tenantId, String policyKey) {
        return policies.findPolicyByKey(tenantId, policyKey);
    }

    @Override
    public SlaPolicyIdentity createPolicy(SlaPolicyIdentity policy) {
        return policies.createPolicy(policy);
    }

    @Override
    public SlaPolicyVersion savePolicyVersion(SlaPolicyVersion version, long expectedPolicyVersion) {
        return policies.savePolicyVersion(version, expectedPolicyVersion);
    }

    @Override
    public Optional<SlaPolicyVersion> findPolicyVersion( String tenantId, UUID policyId, int policyVersion ) {
        return policies.findPolicyVersion(tenantId, policyId, policyVersion);
    }

    @Override
    public SlaPolicyVersion publishPolicyVersion( String tenantId, UUID policyId, int policyVersion, String publishedBy, Instant publishedAt, long expectedPolicyVersion ) {
        return policies.publishPolicyVersion(tenantId, policyId, policyVersion, publishedBy, publishedAt, expectedPolicyVersion);
    }

    @Override
    public SlaPolicyIdentity activatePolicyVersion( String tenantId, UUID policyId, int policyVersion, String activatedBy, Instant activatedAt, long expectedPolicyVersion ) {
        return policies.activatePolicyVersion(tenantId, policyId, policyVersion, activatedBy, activatedAt, expectedPolicyVersion);
    }

    @Override
    public SlaPolicyPage findPolicies(String tenantId, int limit, int offsetValue) {
        return policies.findPolicies(tenantId, limit, offsetValue);
    }

    @Override
    public Optional<SlaPolicyVersion> findEffectivePolicy( String tenantId, String definitionKey, Integer releaseVersion, String taskDefinitionKey, SlaTargetType targetType ) {
        return policies.findEffectivePolicy(tenantId, definitionKey, releaseVersion, taskDefinitionKey, targetType);
    }

    @Override
    public int createInstances(List<SlaInstance> instances) {
        return this.instances.createInstances(instances);
    }

    @Override
    public Optional<SlaInstance> findInstance(String tenantId, UUID slaInstanceId) {
        return instances.findInstance(tenantId, slaInstanceId);
    }

    @Override
    public Optional<SlaInstance> findActiveProcessInstance(String tenantId, UUID approvalInstanceId) {
        return instances.findActiveProcessInstance(tenantId, approvalInstanceId);
    }

    @Override
    public Optional<SlaInstance> findActiveTaskInstance(String tenantId, UUID taskId) {
        return instances.findActiveTaskInstance(tenantId, taskId);
    }

    @Override
    public Optional<SlaInstance> findActiveCollaborationInstance( String tenantId, UUID collaborationParticipantId ) {
        return instances.findActiveCollaborationInstance(tenantId, collaborationParticipantId);
    }

    @Override
    public List<SlaInstance> findActiveByApprovalInstance(String tenantId, UUID approvalInstanceId) {
        return instances.findActiveByApprovalInstance(tenantId, approvalInstanceId);
    }

    @Override
    public SlaInstance pause( String tenantId, UUID slaInstanceId, long expectedVersion, Instant pausedAt, String reason ) {
        return instances.pause(tenantId, slaInstanceId, expectedVersion, pausedAt, reason);
    }

    @Override
    public SlaInstance resume( String tenantId, UUID slaInstanceId, long expectedVersion, Instant dueAt, Instant nextReminderAt, Instant overdueAt, Duration accumulatedPausedDuration, Instant resumedAt ) {
        return instances.resume(tenantId, slaInstanceId, expectedVersion, dueAt, nextReminderAt, overdueAt, accumulatedPausedDuration, resumedAt);
    }

    @Override
    public int terminalTask(String tenantId, UUID taskId, SlaTerminalReason reason, Instant terminalAt) {
        return instances.terminalTask(tenantId, taskId, reason, terminalAt);
    }

    @Override
    public int terminalCollaborationParticipant( String tenantId, UUID collaborationParticipantId, SlaTerminalReason reason, Instant terminalAt ) {
        return instances.terminalCollaborationParticipant(tenantId, collaborationParticipantId, reason, terminalAt);
    }

    @Override
    public int terminalCollaborationParticipantsByTask( String tenantId, UUID taskId, SlaTerminalReason reason, Instant terminalAt ) {
        return instances.terminalCollaborationParticipantsByTask(tenantId, taskId, reason, terminalAt);
    }

    @Override
    public int terminalApprovalInstance( String tenantId, UUID approvalInstanceId, SlaTerminalReason reason, Instant terminalAt ) {
        return instances.terminalApprovalInstance(tenantId, approvalInstanceId, reason, terminalAt);
    }

    @Override
    public SlaInstance changeResponsibility(ResponsibilityChange change, long expectedVersion) {
        return instances.changeResponsibility(change, expectedVersion);
    }

    @Override
    public SlaInstancePage findInstances(SlaInstanceCriteria criteria) {
        return instances.findInstances(criteria);
    }

    @Override
    public SlaInstancePage findUpcoming( String tenantId, Instant observedAt, Instant dueBefore, int limit, int offsetValue ) {
        return instances.findUpcoming(tenantId, observedAt, dueBefore, limit, offsetValue);
    }

    @Override
    public SlaInstancePage findOverdue( String tenantId, Instant observedAt, int limit, int offsetValue ) {
        return instances.findOverdue(tenantId, observedAt, limit, offsetValue);
    }

    @Override
    public SlaInstancePage findByRequestId( String tenantId, String requestId, int limit, int offsetValue ) {
        return instances.findByRequestId(tenantId, requestId, limit, offsetValue);
    }

    @Override
    public List<ResponsibilityChange> findResponsibilityChanges( String tenantId, UUID slaInstanceId, int limit ) {
        return instances.findResponsibilityChanges(tenantId, slaInstanceId, limit);
    }

    @Override
    public Optional<SlaInstance> findVisibleTaskSla(String tenantId, UUID taskId, String userId) {
        return instances.findVisibleTaskSla(tenantId, taskId, userId);
    }

}

package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Platform-owned versioned work-calendar, SLA-policy and SLA-instance persistence. */
public interface ApprovalSlaStore {

    Optional<CalendarIdentity> findCalendar(String tenantId, UUID calendarId);

    Optional<CalendarIdentity> findCalendarByKey(String tenantId, String calendarKey);

    CalendarIdentity createCalendar(CalendarIdentity calendar);

    CalendarVersion saveCalendarVersion(CalendarVersion calendarVersion, long expectedCalendarVersion);

    Optional<CalendarVersion> findCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion
    );

    CalendarVersion publishCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion,
        String publishedBy,
        Instant publishedAt,
        long expectedCalendarVersion
    );

    CalendarIdentity activateCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion,
        String activatedBy,
        Instant activatedAt,
        long expectedCalendarVersion
    );

    CalendarPage findCalendars(String tenantId, int limit, int offset);

    Optional<SlaPolicyIdentity> findPolicy(String tenantId, UUID policyId);

    Optional<SlaPolicyIdentity> findPolicyByKey(String tenantId, String policyKey);

    SlaPolicyIdentity createPolicy(SlaPolicyIdentity policy);

    SlaPolicyVersion savePolicyVersion(SlaPolicyVersion policyVersion, long expectedPolicyVersion);

    Optional<SlaPolicyVersion> findPolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion
    );

    SlaPolicyVersion publishPolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion,
        String publishedBy,
        Instant publishedAt,
        long expectedPolicyVersion
    );

    SlaPolicyIdentity activatePolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion,
        String activatedBy,
        Instant activatedAt,
        long expectedPolicyVersion
    );

    SlaPolicyPage findPolicies(String tenantId, int limit, int offset);

    Optional<SlaPolicyVersion> findEffectivePolicy(
        String tenantId,
        String definitionKey,
        Integer releaseVersion,
        String taskDefinitionKey,
        SlaTargetType targetType
    );

    int createInstances(List<SlaInstance> instances);

    Optional<SlaInstance> findInstance(String tenantId, UUID slaInstanceId);

    Optional<SlaInstance> findActiveProcessInstance(
        String tenantId,
        UUID approvalInstanceId
    );

    Optional<SlaInstance> findActiveTaskInstance(String tenantId, UUID taskId);

    Optional<SlaInstance> findActiveCollaborationInstance(
        String tenantId,
        UUID collaborationParticipantId
    );

    List<SlaInstance> findActiveByApprovalInstance(String tenantId, UUID approvalInstanceId);

    SlaInstance pause(
        String tenantId,
        UUID slaInstanceId,
        long expectedVersion,
        Instant pausedAt,
        String reason
    );

    SlaInstance resume(
        String tenantId,
        UUID slaInstanceId,
        long expectedVersion,
        Instant dueAt,
        Instant nextReminderAt,
        Instant overdueAt,
        Duration accumulatedPausedDuration,
        Instant resumedAt
    );

    int terminalTask(
        String tenantId,
        UUID taskId,
        SlaTerminalReason reason,
        Instant terminalAt
    );

    int terminalCollaborationParticipant(
        String tenantId,
        UUID collaborationParticipantId,
        SlaTerminalReason reason,
        Instant terminalAt
    );

    int terminalCollaborationParticipantsByTask(
        String tenantId,
        UUID taskId,
        SlaTerminalReason reason,
        Instant terminalAt
    );

    int terminalApprovalInstance(
        String tenantId,
        UUID approvalInstanceId,
        SlaTerminalReason reason,
        Instant terminalAt
    );

    SlaInstance changeResponsibility(
        ResponsibilityChange change,
        long expectedVersion
    );

    SlaInstancePage findInstances(SlaInstanceCriteria criteria);

    record CalendarIdentity(
        UUID calendarId,
        String tenantId,
        String calendarKey,
        String displayName,
        String timeZone,
        CalendarStatus status,
        Integer activeVersion,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version
    ) {
        public CalendarIdentity {
            calendarId = Objects.requireNonNull(calendarId, "calendarId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            calendarKey = requireKey(calendarKey, "calendarKey");
            displayName = requireBoundedText(displayName, "displayName", 200);
            timeZone = requireBoundedText(timeZone, "timeZone", 100);
            try {
                java.time.ZoneId.of(timeZone);
            } catch (java.time.DateTimeException exception) {
                throw new IllegalArgumentException("timeZone must be a valid IANA zone", exception);
            }
            status = Objects.requireNonNull(status, "status must not be null");
            createdBy = requireText(createdBy, "createdBy");
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            if (activeVersion != null && activeVersion < 1) {
                throw new IllegalArgumentException("activeVersion must be positive");
            }
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }
    }

    record CalendarVersion(
        UUID calendarId,
        String tenantId,
        int calendarVersion,
        Instant effectiveFrom,
        Instant effectiveTo,
        CalendarSnapshot snapshot,
        CalendarStatus status,
        boolean immutable,
        String publishedBy,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        public CalendarVersion {
            calendarId = Objects.requireNonNull(calendarId, "calendarId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            if (calendarVersion < 1) {
                throw new IllegalArgumentException("calendarVersion must be positive");
            }
            if (effectiveFrom != null && effectiveTo != null && !effectiveFrom.isBefore(effectiveTo)) {
                throw new IllegalArgumentException("effectiveFrom must be before effectiveTo");
            }
            snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
            if (!calendarId.equals(snapshot.calendarId())
                || !tenantId.equals(snapshot.tenantId())
                || calendarVersion != snapshot.calendarVersion()) {
                throw new IllegalArgumentException("calendar snapshot identity must match its version");
            }
            status = Objects.requireNonNull(status, "status must not be null");
            publishedBy = normalizeOptional(publishedBy);
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            if (immutable && (publishedBy == null || publishedAt == null)) {
                throw new IllegalArgumentException("immutable calendar version requires publication evidence");
            }
        }
    }

    record CalendarPage(List<CalendarIdentity> items, long total, int limit, int offset) {
        public CalendarPage {
            items = items == null ? List.of() : List.copyOf(items);
            validatePage(total, limit, offset);
        }
    }

    record SlaPolicyIdentity(
        UUID policyId,
        String tenantId,
        String policyKey,
        String displayName,
        PolicyStatus status,
        Integer activeVersion,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version
    ) {
        public SlaPolicyIdentity {
            policyId = Objects.requireNonNull(policyId, "policyId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            policyKey = requireKey(policyKey, "policyKey");
            displayName = requireBoundedText(displayName, "displayName", 200);
            status = Objects.requireNonNull(status, "status must not be null");
            createdBy = requireText(createdBy, "createdBy");
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            if (activeVersion != null && activeVersion < 1) {
                throw new IllegalArgumentException("activeVersion must be positive");
            }
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }
    }

    record SlaPolicyVersion(
        UUID policyId,
        String tenantId,
        int policyVersion,
        String definitionKey,
        Integer releaseVersion,
        String taskDefinitionKey,
        SlaTargetType targetType,
        SlaDurationMode durationMode,
        Duration duration,
        UUID calendarId,
        Integer calendarVersion,
        Duration firstReminderOffset,
        Duration repeatReminderInterval,
        int maximumReminderCount,
        Duration overdueOffset,
        EscalationTargetType escalationTargetType,
        String escalationTarget,
        AutomaticAction automaticAction,
        boolean naturalTimePauses,
        String contentHash,
        PolicyStatus status,
        boolean immutable,
        String publishedBy,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        public SlaPolicyVersion {
            policyId = Objects.requireNonNull(policyId, "policyId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            if (policyVersion < 1) {
                throw new IllegalArgumentException("policyVersion must be positive");
            }
            definitionKey = requireKey(definitionKey, "definitionKey");
            taskDefinitionKey = normalizeOptional(taskDefinitionKey);
            targetType = Objects.requireNonNull(targetType, "targetType must not be null");
            durationMode = Objects.requireNonNull(durationMode, "durationMode must not be null");
            duration = requirePositiveDuration(duration, "duration");
            firstReminderOffset = normalizeNonNegativeDuration(
                firstReminderOffset,
                "firstReminderOffset"
            );
            repeatReminderInterval = normalizeNonNegativeDuration(
                repeatReminderInterval,
                "repeatReminderInterval"
            );
            overdueOffset = normalizeNonNegativeDuration(overdueOffset, "overdueOffset");
            if (maximumReminderCount < 0 || maximumReminderCount > 100) {
                throw new IllegalArgumentException("maximumReminderCount must be between 0 and 100");
            }
            if (maximumReminderCount > 1
                && (repeatReminderInterval == null || repeatReminderInterval.isZero())) {
                throw new IllegalArgumentException(
                    "repeatReminderInterval is required for repeated reminders"
                );
            }
            if (durationMode == SlaDurationMode.WORKING_TIME
                && (calendarId == null || calendarVersion == null)) {
                throw new IllegalArgumentException("working-time policy requires calendar snapshot");
            }
            if (durationMode == SlaDurationMode.NATURAL_TIME
                && (calendarId != null || calendarVersion != null)) {
                throw new IllegalArgumentException("natural-time policy must not bind a calendar");
            }
            if (calendarVersion != null && calendarVersion < 1) {
                throw new IllegalArgumentException("calendarVersion must be positive");
            }
            escalationTarget = normalizeOptional(escalationTarget);
            if (escalationTargetType == null && escalationTarget != null) {
                throw new IllegalArgumentException("escalation target type is required");
            }
            if (escalationTargetType != null && escalationTarget == null
                && escalationTargetType != EscalationTargetType.MANAGER
                && escalationTargetType != EscalationTargetType.DEPARTMENT_ADMIN) {
                throw new IllegalArgumentException("escalation target is required");
            }
            if ((escalationTargetType == EscalationTargetType.MANAGER
                || escalationTargetType == EscalationTargetType.DEPARTMENT_ADMIN)
                && escalationTarget != null) {
                throw new IllegalArgumentException(
                    "manager and department-admin escalation must not contain a target identifier"
                );
            }
            automaticAction = automaticAction == null ? AutomaticAction.NONE : automaticAction;
            contentHash = requireBoundedText(contentHash, "contentHash", 128);
            status = Objects.requireNonNull(status, "status must not be null");
            publishedBy = normalizeOptional(publishedBy);
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            if (immutable && (publishedBy == null || publishedAt == null)) {
                throw new IllegalArgumentException("immutable policy version requires publication evidence");
            }
        }

        public boolean appliesToTask(String candidateTaskDefinitionKey) {
            return taskDefinitionKey == null || taskDefinitionKey.equals(candidateTaskDefinitionKey);
        }
    }

    record SlaPolicyPage(List<SlaPolicyIdentity> items, long total, int limit, int offset) {
        public SlaPolicyPage {
            items = items == null ? List.of() : List.copyOf(items);
            validatePage(total, limit, offset);
        }
    }

    record SlaInstance(
        UUID slaInstanceId,
        String tenantId,
        UUID approvalInstanceId,
        UUID taskId,
        UUID collaborationParticipantId,
        String definitionKey,
        String taskDefinitionKey,
        SlaTargetType targetType,
        UUID policyId,
        int policyVersion,
        UUID calendarId,
        Integer calendarVersion,
        String timeZone,
        String responsibleUserId,
        String originalResponsibleUserId,
        Instant startedAt,
        Instant dueAt,
        Instant nextReminderAt,
        Instant overdueAt,
        Instant pausedAt,
        String pauseReason,
        Duration accumulatedPausedDuration,
        Instant terminalAt,
        SlaTerminalReason terminalReason,
        SlaStatus status,
        long lastActionSequence,
        String requestId,
        String traceId,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        public SlaInstance {
            slaInstanceId = Objects.requireNonNull(slaInstanceId, "slaInstanceId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            definitionKey = requireKey(definitionKey, "definitionKey");
            taskDefinitionKey = normalizeOptional(taskDefinitionKey);
            targetType = Objects.requireNonNull(targetType, "targetType must not be null");
            policyId = Objects.requireNonNull(policyId, "policyId must not be null");
            if (policyVersion < 1) {
                throw new IllegalArgumentException("policyVersion must be positive");
            }
            if (calendarVersion != null && calendarVersion < 1) {
                throw new IllegalArgumentException("calendarVersion must be positive");
            }
            timeZone = requireBoundedText(timeZone, "timeZone", 100);
            try {
                java.time.ZoneId.of(timeZone);
            } catch (java.time.DateTimeException exception) {
                throw new IllegalArgumentException("timeZone must be a valid IANA zone", exception);
            }
            responsibleUserId = requireText(responsibleUserId, "responsibleUserId");
            originalResponsibleUserId = requireText(
                originalResponsibleUserId,
                "originalResponsibleUserId"
            );
            startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
            dueAt = Objects.requireNonNull(dueAt, "dueAt must not be null");
            overdueAt = Objects.requireNonNull(overdueAt, "overdueAt must not be null");
            if (dueAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("dueAt must not be before startedAt");
            }
            pauseReason = normalizeOptional(pauseReason);
            accumulatedPausedDuration = accumulatedPausedDuration == null
                ? Duration.ZERO : accumulatedPausedDuration;
            if (accumulatedPausedDuration.isNegative()) {
                throw new IllegalArgumentException("accumulatedPausedDuration must not be negative");
            }
            status = Objects.requireNonNull(status, "status must not be null");
            if (status == SlaStatus.PAUSED && (pausedAt == null || pauseReason == null)) {
                throw new IllegalArgumentException("paused SLA requires pause evidence");
            }
            if (status != SlaStatus.PAUSED && (pausedAt != null || pauseReason != null)) {
                throw new IllegalArgumentException("non-paused SLA must not contain pause evidence");
            }
            if (status == SlaStatus.TERMINAL && (terminalAt == null || terminalReason == null)) {
                throw new IllegalArgumentException("terminal SLA requires terminal evidence");
            }
            if (status != SlaStatus.TERMINAL && (terminalAt != null || terminalReason != null)) {
                throw new IllegalArgumentException("non-terminal SLA must not contain terminal evidence");
            }
            if (lastActionSequence < 0 || version < 1) {
                throw new IllegalArgumentException("SLA sequence and version values are invalid");
            }
            requestId = requireBoundedText(requestId, "requestId", 128);
            traceId = normalizeOptional(traceId);
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            validateTargetIdentity(targetType, taskId, collaborationParticipantId);
        }

        public boolean overdueAt(Instant now) {
            return status != SlaStatus.TERMINAL && !Objects.requireNonNull(now).isBefore(overdueAt);
        }
    }

    record ResponsibilityChange(
        UUID responsibilityChangeId,
        UUID slaInstanceId,
        String tenantId,
        String previousResponsibleUserId,
        String newResponsibleUserId,
        ResponsibilityChangeSource source,
        String reason,
        String changedBy,
        Instant changedAt,
        String requestId,
        String traceId
    ) {
        public ResponsibilityChange {
            responsibilityChangeId = Objects.requireNonNull(
                responsibilityChangeId,
                "responsibilityChangeId must not be null"
            );
            slaInstanceId = Objects.requireNonNull(slaInstanceId, "slaInstanceId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            previousResponsibleUserId = requireText(
                previousResponsibleUserId,
                "previousResponsibleUserId"
            );
            newResponsibleUserId = requireText(newResponsibleUserId, "newResponsibleUserId");
            source = Objects.requireNonNull(source, "source must not be null");
            reason = requireBoundedText(reason, "reason", 512);
            changedBy = requireText(changedBy, "changedBy");
            changedAt = Objects.requireNonNull(changedAt, "changedAt must not be null");
            requestId = requireBoundedText(requestId, "requestId", 128);
            traceId = normalizeOptional(traceId);
        }
    }

    record SlaInstanceCriteria(
        String tenantId,
        SlaStatus status,
        String responsibleUserId,
        Instant dueBefore,
        Instant dueAfter,
        String requestId,
        int limit,
        int offset
    ) {
        public SlaInstanceCriteria {
            tenantId = requireText(tenantId, "tenantId");
            responsibleUserId = normalizeOptional(responsibleUserId);
            requestId = normalizeOptional(requestId);
            if (dueBefore != null && dueAfter != null && dueBefore.isBefore(dueAfter)) {
                throw new IllegalArgumentException("dueBefore must not be before dueAfter");
            }
            validatePage(0, limit, offset);
        }
    }

    record SlaInstancePage(List<SlaInstance> items, long total, int limit, int offset) {
        public SlaInstancePage {
            items = items == null ? List.of() : List.copyOf(items);
            validatePage(total, limit, offset);
        }
    }

    enum CalendarStatus {
        DRAFT,
        PUBLISHED,
        ACTIVE,
        INACTIVE,
        ARCHIVED
    }

    enum PolicyStatus {
        DRAFT,
        PUBLISHED,
        ACTIVE,
        INACTIVE,
        ARCHIVED
    }

    enum SlaTargetType {
        PROCESS,
        TASK,
        COLLABORATION_PARTICIPANT
    }

    enum SlaDurationMode {
        NATURAL_TIME,
        WORKING_TIME
    }

    enum SlaStatus {
        ACTIVE,
        PAUSED,
        TERMINAL
    }

    enum SlaTerminalReason {
        TASK_COMPLETED,
        TASK_CANCELED,
        INSTANCE_COMPLETED,
        INSTANCE_REJECTED,
        INSTANCE_WITHDRAWN,
        COLLABORATION_DECIDED,
        COLLABORATION_REMOVED,
        COLLABORATION_CANCELED
    }

    enum ResponsibilityChangeSource {
        DELEGATION,
        HANDOVER,
        MANUAL_TRANSFER,
        COLLABORATION_ADD,
        COLLABORATION_REMOVE,
        ADMIN_CORRECTION
    }

    enum EscalationTargetType {
        MANAGER,
        USER,
        ROLE,
        DEPARTMENT_ADMIN
    }

    enum AutomaticAction {
        NONE,
        AUTO_TRANSFER,
        AUTO_APPROVE,
        AUTO_REJECT
    }

    final class SlaConflictException extends RuntimeException {
        private final String code;

        public SlaConflictException(String message) {
            this("APPROVAL_SLA_INSTANCE_STATE_CONFLICT", message);
        }

        public SlaConflictException(String code, String message) {
            super(message);
            this.code = requireText(code, "code");
        }

        public String code() {
            return code;
        }
    }

    final class SlaNotFoundException extends RuntimeException {
        private final String code;

        public SlaNotFoundException(String message) {
            this("APPROVAL_SLA_INSTANCE_NOT_FOUND", message);
        }

        public SlaNotFoundException(String code, String message) {
            super(message);
            this.code = requireText(code, "code");
        }

        public String code() {
            return code;
        }
    }

    private static void validateTargetIdentity(
        SlaTargetType targetType,
        UUID taskId,
        UUID collaborationParticipantId
    ) {
        switch (targetType) {
            case PROCESS -> {
                if (taskId != null || collaborationParticipantId != null) {
                    throw new IllegalArgumentException("process SLA must not bind task identifiers");
                }
            }
            case TASK -> {
                if (taskId == null || collaborationParticipantId != null) {
                    throw new IllegalArgumentException("task SLA requires only taskId");
                }
            }
            case COLLABORATION_PARTICIPANT -> {
                if (taskId == null || collaborationParticipantId == null) {
                    throw new IllegalArgumentException(
                        "collaboration SLA requires task and participant identifiers"
                    );
                }
            }
        }
    }

    private static Duration requirePositiveDuration(Duration duration, String name) {
        Duration value = Objects.requireNonNull(duration, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofDays(36_600)) > 0) {
            throw new IllegalArgumentException(name + " must be positive and bounded");
        }
        return value;
    }

    private static Duration normalizeNonNegativeDuration(Duration duration, String name) {
        if (duration == null) {
            return null;
        }
        if (duration.isNegative() || duration.compareTo(Duration.ofDays(36_600)) > 0) {
            throw new IllegalArgumentException(name + " must be non-negative and bounded");
        }
        return duration;
    }

    private static void validatePage(long total, int limit, int offset) {
        if (total < 0 || limit < 1 || limit > 500 || offset < 0) {
            throw new IllegalArgumentException("pagination values are invalid");
        }
    }

    private static String requireKey(String value, String name) {
        String normalized = requireBoundedText(value, name, 100);
        if (!normalized.matches("[A-Za-z][A-Za-z0-9._:-]{1,99}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        return requireBoundedText(value, name, 200);
    }

    private static String requireBoundedText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

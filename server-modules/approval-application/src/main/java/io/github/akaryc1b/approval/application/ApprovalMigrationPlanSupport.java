package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationPlanService.AuthorizePlanCommand;
import io.github.akaryc1b.approval.application.ApprovalMigrationPlanService.CreatePlanCommand;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentResult;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan.SelectedInstance;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class ApprovalMigrationPlanSupport {

    private static final int MIN_REASON_CODE_POINTS = 8;
    private static final int MAX_REASON_CODE_POINTS = 512;

    private final ApprovalReleasePackageHasher hasher;
    private final Duration planTtl;
    private final Duration assessmentMaximumAge;

    ApprovalMigrationPlanSupport(
        ApprovalReleasePackageHasher hasher,
        Duration planTtl,
        Duration assessmentMaximumAge
    ) {
        this.hasher = hasher;
        this.planTtl = planTtl;
        this.assessmentMaximumAge = assessmentMaximumAge;
    }

    Instant planExpiry(AssessmentResult assessment, Instant now) {
        Instant expiresAt = earlier(
            assessment.assessedAt().plus(planTtl),
            assessment.assessedAt().plus(assessmentMaximumAge)
        );
        if (!expiresAt.isAfter(now)) {
            throw new ApprovalMigrationPlanService.StaleMigrationAssessmentException(
                "Migration assessment validity has elapsed"
            );
        }
        return expiresAt;
    }

    String planHash(
        AssessmentResult assessment,
        ApprovalReleaseDeployment targetDeployment,
        List<SelectedInstance> selected,
        String requestedBy,
        String reason,
        Instant expiresAt
    ) {
        List<Object> values = new ArrayList<>();
        values.add("M5-C-IMMUTABLE-PLAN-V1");
        values.add(assessment.tenantId());
        values.add(assessment.assessmentId());
        values.add(assessment.reportHash());
        values.add(assessment.definitionKey());
        values.add(assessment.sourceReleaseVersion());
        values.add(assessment.sourceReleasePackageHash());
        values.add(assessment.targetReleaseVersion());
        values.add(assessment.targetReleasePackageHash());
        values.add(targetDeployment.deploymentRecordId());
        values.add(targetDeployment.engineDeploymentId());
        values.add(targetDeployment.engineDefinitionId());
        values.add(targetDeployment.engineVersion());
        values.add(requestedBy);
        values.add(reason);
        values.add(assessment.assessedAt());
        values.add(expiresAt);
        for (SelectedInstance item : selected) {
            values.add(item.approvalInstanceId());
            values.add(item.expectedInstanceStatus());
            values.add(item.expectedActiveTaskDefinitionKeys().size());
            values.addAll(item.expectedActiveTaskDefinitionKeys());
            values.add(item.expectedBindingEvidenceHash());
            values.add(item.instanceEvidenceHash());
        }
        return hasher.hashValues(values.toArray());
    }

    String createRequestHash(CreatePlanCommand command) {
        List<String> ids = command.selectedInstanceIds().stream()
            .map(UUID::toString)
            .sorted()
            .toList();
        return hasher.hashValues(
            command.context().operatorId(),
            command.assessment().assessmentId(),
            command.assessment().reportHash(),
            String.join(",", ids),
            command.reason()
        );
    }

    String authorizeRequestHash(AuthorizePlanCommand command, String planHash) {
        return hasher.hashValues(
            command.context().operatorId(),
            command.planId(),
            planHash,
            command.expectedRevision(),
            command.reason()
        );
    }

    Map<String, String> createAuditAttributes(
        AssessmentResult assessment,
        ApprovalReleaseDeployment targetDeployment,
        List<SelectedInstance> selected,
        String planHash,
        String reason,
        Instant expiresAt
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("assessmentId", assessment.assessmentId().toString());
        values.put("assessmentReportHash", assessment.reportHash());
        values.put("definitionKey", assessment.definitionKey());
        values.put("sourceReleaseVersion", Integer.toString(assessment.sourceReleaseVersion()));
        values.put("sourcePackageHash", assessment.sourceReleasePackageHash());
        values.put("targetReleaseVersion", Integer.toString(assessment.targetReleaseVersion()));
        values.put("targetPackageHash", assessment.targetReleasePackageHash());
        values.put("targetDeploymentRecordId", targetDeployment.deploymentRecordId().toString());
        values.put("targetEngineDefinitionId", targetDeployment.engineDefinitionId());
        values.put("targetEngineVersion", Integer.toString(targetDeployment.engineVersion()));
        values.put("selectedInstanceCount", Integer.toString(selected.size()));
        values.put("planHash", planHash);
        values.put("expiresAt", expiresAt.toString());
        values.put("reason", reason);
        return Map.copyOf(values);
    }

    Map<String, String> authorizationAuditAttributes(
        ApprovalMigrationPlan plan,
        ApprovalMigrationPlanAuthorization authorization
    ) {
        return Map.of(
            "planHash", plan.planHash(),
            "selectedInstanceCount", Integer.toString(plan.selectedInstanceCount()),
            "authorizationPolicy", authorization.authorizationPolicy(),
            "authorizationPolicyVersion", authorization.authorizationPolicyVersion(),
            "authorizationEvidenceHash", authorization.authorizationEvidenceHash(),
            "authorizationExpiresAt", authorization.expiresAt().toString(),
            "reason", authorization.reason()
        );
    }

    static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    static String normalizeReason(String supplied) {
        Objects.requireNonNull(supplied, "reason must not be null");
        String normalized = Normalizer.normalize(supplied.trim(), Normalizer.Form.NFKC);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_REASON_CODE_POINTS || length > MAX_REASON_CODE_POINTS) {
            throw new IllegalArgumentException("reason must contain between 8 and 512 characters");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            int type = Character.getType(value);
            if (Character.isISOControl(value)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE) {
                throw new IllegalArgumentException("reason contains unsupported characters");
            }
        }
        return normalized;
    }
}

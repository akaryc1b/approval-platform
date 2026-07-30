package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentResult;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentStatus;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.FindingSeverity;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.InstanceAssessment;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.InstanceDecision;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan.SelectedInstance;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.ExpectedInstanceStatus;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class ApprovalMigrationPlanEvidenceValidator {

    private final ApprovalProcessReleaseStore releases;
    private final ApprovalReleasePackageStore packages;
    private final ApprovalReleaseDeploymentStore deployments;
    private final ApprovalReleasePackageHasher hasher;
    private final Duration assessmentMaximumAge;

    ApprovalMigrationPlanEvidenceValidator(
        ApprovalProcessReleaseStore releases,
        ApprovalReleasePackageStore packages,
        ApprovalReleaseDeploymentStore deployments,
        ApprovalReleasePackageHasher hasher,
        Duration assessmentMaximumAge
    ) {
        this.releases = releases;
        this.packages = packages;
        this.deployments = deployments;
        this.hasher = hasher;
        this.assessmentMaximumAge = assessmentMaximumAge;
    }

    void requireAssessment(RequestContext context, AssessmentResult assessment, Instant now) {
        if (!context.tenantId().equals(assessment.tenantId())) {
            throw stale("Migration assessment tenant does not match request context");
        }
        if (!assessment.detectOnly()
            || !assessment.complete()
            || assessment.status() != AssessmentStatus.READY
            || assessment.offset() != 0
            || assessment.hasMore()) {
            throw stale("Migration plan requires one complete READY detect-only assessment");
        }
        if (assessment.assessedAt().isAfter(now)
            || !now.isBefore(assessment.assessedAt().plus(assessmentMaximumAge))) {
            throw stale("Migration assessment is expired or future-dated");
        }
        long running = assessment.instances().stream()
            .filter(item -> item.instanceStatus() != null
                && "RUNNING".equals(item.instanceStatus().name()))
            .count();
        long eligible = count(assessment, InstanceDecision.ELIGIBLE);
        long blocked = count(assessment, InstanceDecision.BLOCKED);
        long terminal = count(assessment, InstanceDecision.TERMINAL_SKIPPED);
        boolean blocker = assessment.globalFindings().stream()
            .anyMatch(item -> item.severity() == FindingSeverity.BLOCKER);
        if (assessment.totalBindingCount() != assessment.instances().size()
            || assessment.runningCount() != running
            || assessment.eligibleCount() != eligible
            || assessment.blockedCount() != blocked
            || assessment.terminalCount() != terminal
            || blocker) {
            throw stale("Migration assessment counts or global findings are inconsistent");
        }
    }

    ApprovalReleaseDeployment currentReleaseEvidence(AssessmentResult assessment) {
        ApprovalProcessRelease source = release(
            assessment.tenantId(), assessment.definitionKey(),
            assessment.sourceReleaseVersion(), "source"
        );
        ApprovalProcessRelease target = release(
            assessment.tenantId(), assessment.definitionKey(),
            assessment.targetReleaseVersion(), "target"
        );
        ApprovalReleasePackage sourcePackage = releasePackage(
            assessment.tenantId(), assessment.definitionKey(),
            assessment.sourceReleaseVersion(), "source"
        );
        ApprovalReleasePackage targetPackage = releasePackage(
            assessment.tenantId(), assessment.definitionKey(),
            assessment.targetReleaseVersion(), "target"
        );
        ApprovalReleaseDeployment targetDeployment = deployment(
            assessment.tenantId(), assessment.definitionKey(),
            assessment.targetReleaseVersion()
        );
        if (!source.releasePackageHash().equals(assessment.sourceReleasePackageHash())
            || source.lifecycleState() != assessment.sourceLifecycleState()
            || !sourcePackage.packageHash().equals(assessment.sourceReleasePackageHash())
            || !target.releasePackageHash().equals(assessment.targetReleasePackageHash())
            || target.lifecycleState() != assessment.targetLifecycleState()
            || !targetPackage.packageHash().equals(assessment.targetReleasePackageHash())
            || targetDeployment.status() != ApprovalReleaseDeployment.Status.DEPLOYED
            || !targetDeployment.releasePackageHash().equals(
                assessment.targetReleasePackageHash()
            )) {
            throw stale("Release evidence changed after migration assessment");
        }
        return targetDeployment;
    }

    void requireCurrentPlanReleaseEvidence(ApprovalMigrationPlan plan) {
        ApprovalProcessRelease source = release(
            plan.tenantId(), plan.definitionKey(), plan.sourceReleaseVersion(), "source"
        );
        ApprovalProcessRelease target = release(
            plan.tenantId(), plan.definitionKey(), plan.targetReleaseVersion(), "target"
        );
        ApprovalReleasePackage sourcePackage = releasePackage(
            plan.tenantId(), plan.definitionKey(), plan.sourceReleaseVersion(), "source"
        );
        ApprovalReleasePackage targetPackage = releasePackage(
            plan.tenantId(), plan.definitionKey(), plan.targetReleaseVersion(), "target"
        );
        ApprovalReleaseDeployment targetDeployment = deployment(
            plan.tenantId(), plan.definitionKey(), plan.targetReleaseVersion()
        );
        if (!source.releasePackageHash().equals(plan.sourcePackageHash())
            || !sourcePackage.packageHash().equals(plan.sourcePackageHash())
            || source.lifecycleState() == State.ACTIVE
            || source.lifecycleState() == State.PUBLISHED
            || !target.releasePackageHash().equals(plan.targetPackageHash())
            || !targetPackage.packageHash().equals(plan.targetPackageHash())
            || target.lifecycleState() != State.ACTIVE
            || targetDeployment.status() != ApprovalReleaseDeployment.Status.DEPLOYED
            || !targetDeployment.deploymentRecordId().equals(plan.targetDeploymentRecordId())
            || !targetDeployment.releasePackageHash().equals(plan.targetPackageHash())
            || !targetDeployment.engineDeploymentId().equals(plan.targetEngineDeploymentId())
            || !targetDeployment.engineDefinitionId().equals(plan.targetEngineDefinitionId())
            || targetDeployment.engineVersion() != plan.targetEngineVersion()) {
            throw new ApprovalMigrationPlanService.MigrationPlanNotAuthorizedException(
                "Release identity changed after migration plan creation"
            );
        }
    }

    List<SelectedInstance> selectInstances(
        AssessmentResult assessment,
        List<UUID> selectedInstanceIds
    ) {
        if (selectedInstanceIds == null
            || selectedInstanceIds.isEmpty()
            || selectedInstanceIds.size() > 1000) {
            throw new IllegalArgumentException(
                "selectedInstanceIds must contain between 1 and 1000 items"
            );
        }
        if (selectedInstanceIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("selectedInstanceIds must not contain null");
        }
        Set<UUID> requested = new HashSet<>(selectedInstanceIds);
        if (requested.size() != selectedInstanceIds.size()) {
            throw new IllegalArgumentException("selectedInstanceIds contains duplicates");
        }
        Map<UUID, InstanceAssessment> assessed = new LinkedHashMap<>();
        for (InstanceAssessment item : assessment.instances()) {
            Objects.requireNonNull(item, "assessment instances must not contain null");
            if (assessed.putIfAbsent(item.approvalInstanceId(), item) != null) {
                throw stale("Migration assessment contains duplicate instance evidence");
            }
        }
        List<SelectedInstance> result = new ArrayList<>(requested.size());
        for (UUID instanceId : requested) {
            InstanceAssessment item = assessed.get(instanceId);
            if (item == null || item.decision() != InstanceDecision.ELIGIBLE
                || item.instanceStatus() == null
                || !"RUNNING".equals(item.instanceStatus().name())) {
                throw stale("Selected instance is not explicitly ELIGIBLE in the assessment");
            }
            List<String> taskKeys = canonicalTaskKeys(item.activeTaskDefinitionKeys());
            List<Object> values = new ArrayList<>();
            values.add(instanceId);
            values.add(item.instanceStatus());
            values.add(taskKeys.size());
            values.addAll(taskKeys);
            values.add(item.bindingEvidenceHash());
            result.add(new SelectedInstance(
                instanceId,
                ExpectedInstanceStatus.RUNNING,
                taskKeys,
                item.bindingEvidenceHash(),
                hasher.hashValues(values.toArray())
            ));
        }
        result.sort(Comparator.comparing(item -> item.approvalInstanceId().toString()));
        return List.copyOf(result);
    }

    private ApprovalProcessRelease release(
        String tenantId,
        String definitionKey,
        int releaseVersion,
        String role
    ) {
        return releases.find(tenantId, definitionKey, releaseVersion)
            .orElseThrow(() -> missing(role + " release lifecycle"));
    }

    private ApprovalReleaseDeployment deployment(
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        return deployments.find(tenantId, definitionKey, releaseVersion)
            .orElseThrow(() -> missing("target release deployment"));
    }

    private ApprovalReleasePackage releasePackage(
        String tenantId,
        String definitionKey,
        int releaseVersion,
        String role
    ) {
        return packages.find(tenantId, definitionKey, releaseVersion)
            .orElseThrow(() -> missing(role + " release package"));
    }

    private static List<String> canonicalTaskKeys(List<String> supplied) {
        if (supplied == null || supplied.isEmpty()) {
            throw stale("Selected instance has no active task-definition evidence");
        }
        Set<String> unique = new HashSet<>();
        List<String> canonical = new ArrayList<>(supplied.size());
        for (String value : supplied) {
            if (value == null) {
                throw stale("Selected instance task-definition evidence contains null");
            }
            String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC);
            if (normalized.isEmpty() || normalized.length() > 128 || !value.equals(normalized)) {
                throw stale("Selected instance task-definition evidence is not canonical");
            }
            if (!unique.add(normalized)) {
                throw stale("Selected instance task-definition evidence contains duplicates");
            }
            canonical.add(normalized);
        }
        canonical.sort(String::compareTo);
        return List.copyOf(canonical);
    }

    private static long count(AssessmentResult assessment, InstanceDecision decision) {
        return assessment.instances().stream().filter(item -> item.decision() == decision).count();
    }

    private static ApprovalMigrationPlanService.StaleMigrationAssessmentException stale(
        String message
    ) {
        return new ApprovalMigrationPlanService.StaleMigrationAssessmentException(message);
    }

    private static ApprovalMigrationPlanService.MigrationPlanEvidenceNotFoundException missing(
        String evidence
    ) {
        return new ApprovalMigrationPlanService.MigrationPlanEvidenceNotFoundException(
            evidence + " was not found for the tenant"
        );
    }
}

package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read model for the PC Approval DSL and Release Package version-management center. */
public final class ApprovalVersionManagementService {

    private final ApprovalDefinitionVersionStore definitions;
    private final ApprovalReleasePackageStore releases;
    private final ApprovalReleaseDeploymentStore deployments;
    private final ApprovalReleaseStructuralDiff structuralDiff;

    public ApprovalVersionManagementService(
        ApprovalDefinitionVersionStore definitions,
        ApprovalReleasePackageStore releases,
        ApprovalReleaseDeploymentStore deployments,
        ApprovalReleaseStructuralDiff structuralDiff
    ) {
        this.definitions = Objects.requireNonNull(definitions);
        this.releases = Objects.requireNonNull(releases);
        this.deployments = Objects.requireNonNull(deployments);
        this.structuralDiff = Objects.requireNonNull(structuralDiff);
    }

    public VersionCenter findVersionCenter(
        String tenantId,
        String definitionKey,
        int limit,
        int offset
    ) {
        String normalizedTenant = requireText(tenantId, "tenantId");
        String normalizedKey = requireText(definitionKey, "definitionKey");
        ApprovalDefinitionVersionStore.VersionPage definitionPage = definitions.findVersions(
            new ApprovalDefinitionVersionStore.VersionCriteria(
                normalizedTenant,
                normalizedKey,
                limit,
                offset
            )
        );
        ApprovalReleasePackageStore.ReleasePage releasePage = releases.findReleases(
            new ApprovalReleasePackageStore.ReleaseCriteria(
                normalizedTenant,
                normalizedKey,
                limit,
                offset
            )
        );
        List<ApprovalReleaseDeployment> deploymentItems = deployments.findByDefinition(
            normalizedTenant,
            normalizedKey
        );
        Map<Integer, ApprovalReleaseDeployment> deploymentByVersion = new HashMap<>();
        for (ApprovalReleaseDeployment deployment : deploymentItems) {
            deploymentByVersion.put(deployment.releaseVersion(), deployment);
        }
        Integer currentDeployedReleaseVersion = deploymentItems.stream()
            .filter(value -> value.status() == ApprovalReleaseDeployment.Status.DEPLOYED)
            .max(Comparator.comparingInt(ApprovalReleaseDeployment::releaseVersion))
            .map(ApprovalReleaseDeployment::releaseVersion)
            .orElse(null);
        Integer latestDefinitionVersion = definitions.findLatest(normalizedTenant, normalizedKey)
            .map(ApprovalDefinitionVersion::version)
            .orElse(null);
        Integer latestPublishedReleaseVersion = releases.findLatest(normalizedTenant, normalizedKey)
            .map(ApprovalReleasePackage::releaseVersion)
            .orElse(null);

        List<DefinitionVersionSummary> definitionVersions = definitionPage.items().stream()
            .map(DefinitionVersionSummary::from)
            .toList();
        List<ReleaseVersionSummary> releaseVersions = releasePage.items().stream()
            .map(value -> ReleaseVersionSummary.from(
                value,
                deploymentByVersion.get(value.releaseVersion()),
                Objects.equals(value.releaseVersion(), currentDeployedReleaseVersion)
            ))
            .toList();
        return new VersionCenter(
            normalizedTenant,
            normalizedKey,
            latestDefinitionVersion,
            latestPublishedReleaseVersion,
            currentDeployedReleaseVersion,
            null,
            definitionVersions,
            page(definitionPage),
            releaseVersions,
            page(releasePage)
        );
    }

    public Optional<ApprovalReleaseStructuralDiff.Result> diffDefinitions(
        String tenantId,
        String definitionKey,
        int fromVersion,
        int toVersion
    ) {
        String normalizedTenant = requireText(tenantId, "tenantId");
        String normalizedKey = requireText(definitionKey, "definitionKey");
        requirePositive(fromVersion, "fromVersion");
        requirePositive(toVersion, "toVersion");
        Optional<ApprovalDefinitionVersion> before = definitions.find(
            normalizedTenant,
            normalizedKey,
            fromVersion
        );
        Optional<ApprovalDefinitionVersion> after = definitions.find(
            normalizedTenant,
            normalizedKey,
            toVersion
        );
        if (before.isEmpty() || after.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(structuralDiff.diff(before.orElseThrow(), after.orElseThrow()));
    }

    public Optional<ApprovalReleaseStructuralDiff.Result> diffReleases(
        String tenantId,
        String definitionKey,
        int fromReleaseVersion,
        int toReleaseVersion
    ) {
        String normalizedTenant = requireText(tenantId, "tenantId");
        String normalizedKey = requireText(definitionKey, "definitionKey");
        requirePositive(fromReleaseVersion, "fromReleaseVersion");
        requirePositive(toReleaseVersion, "toReleaseVersion");
        Optional<ApprovalReleasePackage> beforeRelease = releases.find(
            normalizedTenant,
            normalizedKey,
            fromReleaseVersion
        );
        Optional<ApprovalReleasePackage> afterRelease = releases.find(
            normalizedTenant,
            normalizedKey,
            toReleaseVersion
        );
        if (beforeRelease.isEmpty() || afterRelease.isEmpty()) {
            return Optional.empty();
        }
        ApprovalReleasePackage leftPackage = beforeRelease.orElseThrow();
        ApprovalReleasePackage rightPackage = afterRelease.orElseThrow();
        Optional<ApprovalDefinitionVersion> beforeDefinition = definitions.find(
            normalizedTenant,
            normalizedKey,
            leftPackage.definitionVersion()
        );
        Optional<ApprovalDefinitionVersion> afterDefinition = definitions.find(
            normalizedTenant,
            normalizedKey,
            rightPackage.definitionVersion()
        );
        if (beforeDefinition.isEmpty() || afterDefinition.isEmpty()) {
            throw new IllegalStateException(
                "Release Package references a missing immutable Approval DSL version"
            );
        }
        return Optional.of(structuralDiff.diff(
            beforeDefinition.orElseThrow(),
            afterDefinition.orElseThrow(),
            leftPackage,
            rightPackage
        ));
    }

    private static Page page(ApprovalDefinitionVersionStore.VersionPage value) {
        return new Page(value.total(), value.limit(), value.offset(), value.hasMore());
    }

    private static Page page(ApprovalReleasePackageStore.ReleasePage value) {
        return new Page(value.total(), value.limit(), value.offset(), value.hasMore());
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public record VersionCenter(
        String tenantId,
        String definitionKey,
        Integer latestDefinitionVersion,
        Integer latestPublishedReleaseVersion,
        Integer currentDeployedReleaseVersion,
        Integer currentEffectiveReleaseVersion,
        List<DefinitionVersionSummary> definitionVersions,
        Page definitionPage,
        List<ReleaseVersionSummary> releaseVersions,
        Page releasePage
    ) {
        public VersionCenter {
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = requireText(definitionKey, "definitionKey");
            definitionVersions = definitionVersions == null
                ? List.of()
                : List.copyOf(definitionVersions);
            definitionPage = Objects.requireNonNull(
                definitionPage,
                "definitionPage must not be null"
            );
            releaseVersions = releaseVersions == null
                ? List.of()
                : List.copyOf(releaseVersions);
            releasePage = Objects.requireNonNull(releasePage, "releasePage must not be null");
        }
    }

    public record Page(long total, int limit, int offset, boolean hasMore) {
        public Page {
            if (total < 0 || limit < 1 || limit > 100 || offset < 0) {
                throw new IllegalArgumentException("invalid version-management page");
            }
        }
    }

    public record DefinitionVersionSummary(
        int definitionVersion,
        String definitionHash,
        int formPackageVersion,
        String formPackageHash,
        UUID sourceDraftId,
        String publishedBy,
        Instant publishedAt
    ) {
        private static DefinitionVersionSummary from(ApprovalDefinitionVersion value) {
            return new DefinitionVersionSummary(
                value.version(),
                value.contentHash(),
                value.formPackageVersion(),
                value.formPackageHash(),
                value.sourceDraftId(),
                value.publishedBy(),
                value.publishedAt()
            );
        }
    }

    public record ReleaseVersionSummary(
        int releaseVersion,
        int definitionVersion,
        String definitionHash,
        int formPackageVersion,
        String formPackageHash,
        int formSchemaVersion,
        String formSchemaHash,
        int uiSchemaVersion,
        String uiSchemaHash,
        String compilerVersion,
        String compiledArtifactHash,
        String bpmnHash,
        String deploymentMetadataHash,
        String packageHash,
        UUID sourceDraftId,
        String publishedBy,
        Instant publishedAt,
        DeploymentSummary deployment,
        boolean currentDeployed,
        boolean currentEffective
    ) {
        private static ReleaseVersionSummary from(
            ApprovalReleasePackage value,
            ApprovalReleaseDeployment deployment,
            boolean currentDeployed
        ) {
            return new ReleaseVersionSummary(
                value.releaseVersion(),
                value.definitionVersion(),
                value.definitionHash(),
                value.formPackageVersion(),
                value.formPackageHash(),
                value.formVersion(),
                value.formHash(),
                value.uiSchemaVersion(),
                value.uiSchemaHash(),
                value.compilerVersion(),
                value.compiledArtifactHash(),
                value.bpmnHash(),
                value.deploymentMetadataHash(),
                value.packageHash(),
                value.sourceDraftId(),
                value.publishedBy(),
                value.publishedAt(),
                DeploymentSummary.from(deployment),
                currentDeployed,
                false
            );
        }
    }

    public record DeploymentSummary(
        UUID deploymentRecordId,
        ApprovalReleaseDeployment.Status status,
        int attemptCount,
        String engineDeploymentId,
        String engineDefinitionId,
        Integer engineVersion,
        String lastErrorCode,
        String lastErrorMessage,
        String requestedBy,
        Instant updatedAt,
        Instant deployedAt
    ) {
        private static DeploymentSummary from(ApprovalReleaseDeployment value) {
            if (value == null) {
                return null;
            }
            return new DeploymentSummary(
                value.deploymentRecordId(),
                value.status(),
                value.attemptCount(),
                value.engineDeploymentId(),
                value.engineDefinitionId(),
                value.engineVersion(),
                value.lastErrorCode(),
                value.lastErrorMessage(),
                value.requestedBy(),
                value.updatedAt(),
                value.deployedAt()
            );
        }
    }
}

package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolProfile;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic non-applying deployment change evidence with mandatory human review. */
public record AiProviderDeploymentChangeSet(
    String sourceSnapshotId,
    String sourceSnapshotVersion,
    String sourceSnapshotHash,
    String targetSnapshotId,
    String targetSnapshotVersion,
    String targetSnapshotHash,
    List<Change> changes,
    RiskLevel highestRisk,
    String changeSetHash,
    boolean humanApprovalRequired,
    boolean applyAuthorized,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    public AiProviderDeploymentChangeSet {
        sourceSnapshotId = requireText(sourceSnapshotId, "sourceSnapshotId", 160);
        sourceSnapshotVersion = requireText(sourceSnapshotVersion, "sourceSnapshotVersion", 120);
        sourceSnapshotHash = requireText(sourceSnapshotHash, "sourceSnapshotHash", 64);
        targetSnapshotId = requireText(targetSnapshotId, "targetSnapshotId", 160);
        targetSnapshotVersion = requireText(targetSnapshotVersion, "targetSnapshotVersion", 120);
        targetSnapshotHash = requireText(targetSnapshotHash, "targetSnapshotHash", 64);
        changes = changes == null ? List.of() : List.copyOf(changes);
        if (changes.size() > 1_000) {
            throw new IllegalArgumentException("changes must be bounded");
        }
        highestRisk = Objects.requireNonNull(highestRisk, "highestRisk must not be null");
        changeSetHash = requireText(changeSetHash, "changeSetHash", 64);
        if (humanApprovalRequired != !changes.isEmpty()) {
            throw new IllegalArgumentException(
                "humanApprovalRequired must reflect whether the change set is non-empty"
            );
        }
        if (applyAuthorized) {
            throw new IllegalArgumentException("M6-D change sets cannot authorize apply");
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "M6-D change sets cannot authorize production enablement"
            );
        }
        if (approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "M6-D change sets cannot authorize approval automation"
            );
        }
        RiskLevel calculated = changes.stream()
            .map(Change::riskLevel)
            .max(Comparator.naturalOrder())
            .orElse(RiskLevel.NONE);
        if (highestRisk != calculated) {
            throw new IllegalArgumentException("highestRisk must match the change list");
        }
    }

    public static AiProviderDeploymentChangeSet compare(
        AiProviderDeploymentSnapshot source,
        AiProviderDeploymentSnapshot target
    ) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        List<Change> changes = new ArrayList<>();

        if (!source.contentHashMatches()) {
            changes.add(change(
                ChangeType.SOURCE_SNAPSHOT_HASH_INVALID,
                source.snapshotId(),
                source.declaredContentHash(),
                source.computedContentHash(),
                RiskLevel.CRITICAL
            ));
        }
        if (!target.contentHashMatches()) {
            changes.add(change(
                ChangeType.TARGET_SNAPSHOT_HASH_INVALID,
                target.snapshotId(),
                target.declaredContentHash(),
                target.computedContentHash(),
                RiskLevel.CRITICAL
            ));
        }
        if (!source.advisoryConfigurationHash().equals(target.advisoryConfigurationHash())) {
            changes.add(change(
                ChangeType.ADVISORY_CONFIGURATION_CHANGED,
                "advisory-configuration",
                source.advisoryConfigurationHash(),
                target.advisoryConfigurationHash(),
                RiskLevel.CRITICAL
            ));
        }

        compareBindings(source.bindings(), target.bindings(), changes);
        compareEndpoints(source.endpoints(), target.endpoints(), changes);
        compareEgress(source.egressPolicies(), target.egressPolicies(), changes);
        compareSecrets(source.secretReferences(), target.secretReferences(), changes);
        compareProfiles(source.validationProfiles(), target.validationProfiles(), changes);

        changes = changes.stream()
            .sorted(Comparator
                .comparing((Change value) -> value.changeType().name())
                .thenComparing(Change::subjectKey)
                .thenComparing(value -> value.beforeFingerprint() == null
                    ? ""
                    : value.beforeFingerprint())
                .thenComparing(value -> value.afterFingerprint() == null
                    ? ""
                    : value.afterFingerprint()))
            .toList();
        RiskLevel highestRisk = changes.stream()
            .map(Change::riskLevel)
            .max(Comparator.naturalOrder())
            .orElse(RiskLevel.NONE);
        String hash = hash(source, target, changes);
        return new AiProviderDeploymentChangeSet(
            source.snapshotId(),
            source.snapshotVersion(),
            source.declaredContentHash(),
            target.snapshotId(),
            target.snapshotVersion(),
            target.declaredContentHash(),
            changes,
            highestRisk,
            hash,
            !changes.isEmpty(),
            false,
            false,
            false
        );
    }

    public enum RiskLevel {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum ChangeType {
        SOURCE_SNAPSHOT_HASH_INVALID,
        TARGET_SNAPSHOT_HASH_INVALID,
        ADVISORY_CONFIGURATION_CHANGED,
        PROVIDER_BINDING_ADDED,
        PROVIDER_BINDING_REMOVED,
        ENDPOINT_SELECTION_CHANGED,
        EGRESS_POLICY_SELECTION_CHANGED,
        SECRET_REFERENCE_SET_CHANGED,
        VALIDATION_PROFILE_SELECTION_CHANGED,
        OPERATIONAL_STAGE_CHANGED,
        ENDPOINT_METADATA_ADDED,
        ENDPOINT_METADATA_REMOVED,
        ENDPOINT_METADATA_CHANGED,
        EGRESS_POLICY_ADDED,
        EGRESS_POLICY_REMOVED,
        EGRESS_POLICY_CHANGED,
        SECRET_REFERENCE_ADDED,
        SECRET_REFERENCE_REMOVED,
        SECRET_ROTATION_STATE_CHANGED,
        SECRET_METADATA_CHANGED,
        VALIDATION_PROFILE_ADDED,
        VALIDATION_PROFILE_REMOVED,
        VALIDATION_SCHEMA_CHANGED,
        VALIDATION_LIMIT_CHANGED
    }

    public record Change(
        ChangeType changeType,
        String subjectKey,
        String beforeFingerprint,
        String afterFingerprint,
        RiskLevel riskLevel
    ) {
        public Change {
            changeType = Objects.requireNonNull(changeType, "changeType must not be null");
            subjectKey = requireText(subjectKey, "subjectKey", 500);
            beforeFingerprint = normalizeOptional(beforeFingerprint, 1_000);
            afterFingerprint = normalizeOptional(afterFingerprint, 1_000);
            riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
            if (riskLevel == RiskLevel.NONE) {
                throw new IllegalArgumentException("a change cannot have NONE risk");
            }
        }
    }

    private static void compareBindings(
        Map<AiVersionReferences.ProviderVersion, AiProviderDeploymentSnapshot.DeploymentBinding>
            source,
        Map<AiVersionReferences.ProviderVersion, AiProviderDeploymentSnapshot.DeploymentBinding>
            target,
        List<Change> changes
    ) {
        Set<AiVersionReferences.ProviderVersion> keys = new TreeSet<>(Comparator.comparing(
            AiProviderDeploymentChangeSet::providerKey
        ));
        keys.addAll(source.keySet());
        keys.addAll(target.keySet());
        for (AiVersionReferences.ProviderVersion key : keys) {
            AiProviderDeploymentSnapshot.DeploymentBinding before = source.get(key);
            AiProviderDeploymentSnapshot.DeploymentBinding after = target.get(key);
            String subject = providerKey(key);
            if (before == null) {
                changes.add(change(
                    ChangeType.PROVIDER_BINDING_ADDED,
                    subject,
                    null,
                    bindingFingerprint(after),
                    RiskLevel.CRITICAL
                ));
                continue;
            }
            if (after == null) {
                changes.add(change(
                    ChangeType.PROVIDER_BINDING_REMOVED,
                    subject,
                    bindingFingerprint(before),
                    null,
                    RiskLevel.CRITICAL
                ));
                continue;
            }
            if (!before.endpointId().equals(after.endpointId())) {
                changes.add(change(
                    ChangeType.ENDPOINT_SELECTION_CHANGED,
                    subject,
                    before.endpointId(),
                    after.endpointId(),
                    RiskLevel.HIGH
                ));
            }
            if (!before.egressPolicyAuthorizationKey()
                .equals(after.egressPolicyAuthorizationKey())) {
                changes.add(change(
                    ChangeType.EGRESS_POLICY_SELECTION_CHANGED,
                    subject,
                    before.egressPolicyAuthorizationKey(),
                    after.egressPolicyAuthorizationKey(),
                    RiskLevel.HIGH
                ));
            }
            if (!before.secretReferenceAuthorizationKeys()
                .equals(after.secretReferenceAuthorizationKeys())) {
                changes.add(change(
                    ChangeType.SECRET_REFERENCE_SET_CHANGED,
                    subject,
                    sortedSet(before.secretReferenceAuthorizationKeys()),
                    sortedSet(after.secretReferenceAuthorizationKeys()),
                    RiskLevel.CRITICAL
                ));
            }
            if (!before.validationProfileAuthorizationKey()
                .equals(after.validationProfileAuthorizationKey())) {
                changes.add(change(
                    ChangeType.VALIDATION_PROFILE_SELECTION_CHANGED,
                    subject,
                    before.validationProfileAuthorizationKey(),
                    after.validationProfileAuthorizationKey(),
                    RiskLevel.HIGH
                ));
            }
            if (before.stage() != after.stage()) {
                changes.add(change(
                    ChangeType.OPERATIONAL_STAGE_CHANGED,
                    subject,
                    before.stage().name(),
                    after.stage().name(),
                    RiskLevel.HIGH
                ));
            }
        }
    }

    private static void compareEndpoints(
        Map<String, AiProviderEndpointDescriptor> source,
        Map<String, AiProviderEndpointDescriptor> target,
        List<Change> changes
    ) {
        for (String key : union(source.keySet(), target.keySet())) {
            AiProviderEndpointDescriptor before = source.get(key);
            AiProviderEndpointDescriptor after = target.get(key);
            if (before == null) {
                changes.add(change(
                    ChangeType.ENDPOINT_METADATA_ADDED,
                    key,
                    null,
                    endpointFingerprint(after),
                    RiskLevel.HIGH
                ));
            } else if (after == null) {
                changes.add(change(
                    ChangeType.ENDPOINT_METADATA_REMOVED,
                    key,
                    endpointFingerprint(before),
                    null,
                    RiskLevel.HIGH
                ));
            } else if (!endpointFingerprint(before).equals(endpointFingerprint(after))) {
                changes.add(change(
                    ChangeType.ENDPOINT_METADATA_CHANGED,
                    key,
                    endpointFingerprint(before),
                    endpointFingerprint(after),
                    RiskLevel.CRITICAL
                ));
            }
        }
    }

    private static void compareEgress(
        Map<String, AiProviderEgressPolicy> source,
        Map<String, AiProviderEgressPolicy> target,
        List<Change> changes
    ) {
        for (String key : union(source.keySet(), target.keySet())) {
            AiProviderEgressPolicy before = source.get(key);
            AiProviderEgressPolicy after = target.get(key);
            if (before == null) {
                changes.add(change(
                    ChangeType.EGRESS_POLICY_ADDED,
                    key,
                    null,
                    after.declaredContentHash(),
                    RiskLevel.HIGH
                ));
            } else if (after == null) {
                changes.add(change(
                    ChangeType.EGRESS_POLICY_REMOVED,
                    key,
                    before.declaredContentHash(),
                    null,
                    RiskLevel.HIGH
                ));
            } else if (!before.declaredContentHash().equals(after.declaredContentHash())
                || before.contentHashMatches() != after.contentHashMatches()) {
                changes.add(change(
                    ChangeType.EGRESS_POLICY_CHANGED,
                    key,
                    before.declaredContentHash(),
                    after.declaredContentHash(),
                    RiskLevel.CRITICAL
                ));
            }
        }
    }

    private static void compareSecrets(
        Map<String, AiExternalSecretReference> source,
        Map<String, AiExternalSecretReference> target,
        List<Change> changes
    ) {
        for (String key : union(source.keySet(), target.keySet())) {
            AiExternalSecretReference before = source.get(key);
            AiExternalSecretReference after = target.get(key);
            if (before == null) {
                changes.add(change(
                    ChangeType.SECRET_REFERENCE_ADDED,
                    key,
                    null,
                    secretFingerprint(after),
                    RiskLevel.CRITICAL
                ));
            } else if (after == null) {
                changes.add(change(
                    ChangeType.SECRET_REFERENCE_REMOVED,
                    key,
                    secretFingerprint(before),
                    null,
                    RiskLevel.CRITICAL
                ));
            } else {
                if (before.rotationState() != after.rotationState()) {
                    changes.add(change(
                        ChangeType.SECRET_ROTATION_STATE_CHANGED,
                        key,
                        before.rotationState().name(),
                        after.rotationState().name(),
                        RiskLevel.CRITICAL
                    ));
                }
                if (!secretMetadataFingerprint(before)
                    .equals(secretMetadataFingerprint(after))) {
                    changes.add(change(
                        ChangeType.SECRET_METADATA_CHANGED,
                        key,
                        secretMetadataFingerprint(before),
                        secretMetadataFingerprint(after),
                        RiskLevel.HIGH
                    ));
                }
            }
        }
    }

    private static void compareProfiles(
        Map<String, AiProviderProtocolProfile> source,
        Map<String, AiProviderProtocolProfile> target,
        List<Change> changes
    ) {
        for (String key : union(source.keySet(), target.keySet())) {
            AiProviderProtocolProfile before = source.get(key);
            AiProviderProtocolProfile after = target.get(key);
            if (before == null) {
                changes.add(change(
                    ChangeType.VALIDATION_PROFILE_ADDED,
                    key,
                    null,
                    profileFingerprint(after),
                    RiskLevel.HIGH
                ));
            } else if (after == null) {
                changes.add(change(
                    ChangeType.VALIDATION_PROFILE_REMOVED,
                    key,
                    profileFingerprint(before),
                    null,
                    RiskLevel.HIGH
                ));
            } else {
                if (!before.requestSchemaHash().equals(after.requestSchemaHash())
                    || !before.responseSchemaHash().equals(after.responseSchemaHash())) {
                    changes.add(change(
                        ChangeType.VALIDATION_SCHEMA_CHANGED,
                        key,
                        before.requestSchemaHash() + "/" + before.responseSchemaHash(),
                        after.requestSchemaHash() + "/" + after.responseSchemaHash(),
                        RiskLevel.CRITICAL
                    ));
                }
                if (before.maximumRequestBytes() != after.maximumRequestBytes()
                    || before.maximumResponseBytes() != after.maximumResponseBytes()) {
                    changes.add(change(
                        ChangeType.VALIDATION_LIMIT_CHANGED,
                        key,
                        before.maximumRequestBytes() + "/" + before.maximumResponseBytes(),
                        after.maximumRequestBytes() + "/" + after.maximumResponseBytes(),
                        RiskLevel.HIGH
                    ));
                }
            }
        }
    }

    private static Change change(
        ChangeType type,
        String subject,
        String before,
        String after,
        RiskLevel risk
    ) {
        return new Change(type, subject, before, after, risk);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>();
        result.addAll(left);
        result.addAll(right);
        return result;
    }

    private static String hash(
        AiProviderDeploymentSnapshot source,
        AiProviderDeploymentSnapshot target,
        List<Change> changes
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "sourceId", source.snapshotId());
        append(canonical, "sourceVersion", source.snapshotVersion());
        append(canonical, "sourceHash", source.declaredContentHash());
        append(canonical, "targetId", target.snapshotId());
        append(canonical, "targetVersion", target.snapshotVersion());
        append(canonical, "targetHash", target.declaredContentHash());
        for (Change change : changes) {
            append(canonical, "type", change.changeType().name());
            append(canonical, "subject", change.subjectKey());
            append(canonical, "before", change.beforeFingerprint());
            append(canonical, "after", change.afterFingerprint());
            append(canonical, "risk", change.riskLevel().name());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String bindingFingerprint(
        AiProviderDeploymentSnapshot.DeploymentBinding value
    ) {
        return value.endpointId()
            + "|"
            + value.egressPolicyAuthorizationKey()
            + "|"
            + sortedSet(value.secretReferenceAuthorizationKeys())
            + "|"
            + value.validationProfileAuthorizationKey()
            + "|"
            + value.stage().name();
    }

    private static String endpointFingerprint(AiProviderEndpointDescriptor value) {
        return providerKey(value.providerVersion()) + "|" + value.authorizationKey();
    }

    private static String secretFingerprint(AiExternalSecretReference value) {
        return secretMetadataFingerprint(value) + "|" + value.rotationState().name();
    }

    private static String secretMetadataFingerprint(AiExternalSecretReference value) {
        return value.storeKind().name()
            + "|"
            + providerKey(value.providerVersion())
            + "|"
            + value.purposes().stream().map(Enum::name).sorted().toList();
    }

    private static String profileFingerprint(AiProviderProtocolProfile value) {
        return providerKey(value.providerVersion())
            + "|"
            + value.requestSchemaHash()
            + "|"
            + value.responseSchemaHash()
            + "|"
            + value.maximumRequestBytes()
            + "|"
            + value.maximumResponseBytes();
    }

    private static String providerKey(AiVersionReferences.ProviderVersion value) {
        return value.providerId() + "/" + value.version();
    }

    private static String sortedSet(Set<String> values) {
        return values.stream().sorted().toList().toString();
    }

    private static void append(StringBuilder target, String key, Object value) {
        String text = String.valueOf(value);
        target.append(key.length()).append(':').append(key)
            .append('=').append(text.length()).append(':').append(text).append(';');
    }

    private static String normalizeOptional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("optional value must be bounded");
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}

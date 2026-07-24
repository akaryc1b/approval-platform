package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
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

/**
 * Immutable server-owned configuration snapshot for startup preflight and zero-call dry runs.
 *
 * <p>The snapshot contains metadata and policy only. It carries no credential, endpoint, prompt
 * body, customer knowledge, network client or production-enablement authority.</p>
 */
public record AiAdvisoryConfigurationSnapshot(
    String snapshotId,
    String snapshotVersion,
    String declaredContentHash,
    AiProviderRoutingPolicy routingPolicy,
    Map<AiVersionReferences.PolicyVersion, AiDataMinimizationPolicy> dataPolicies,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    public AiAdvisoryConfigurationSnapshot {
        snapshotId = requireText(snapshotId, "snapshotId", 160);
        snapshotVersion = requireText(snapshotVersion, "snapshotVersion", 120);
        declaredContentHash = requireSha256(declaredContentHash, "declaredContentHash");
        routingPolicy = Objects.requireNonNull(routingPolicy, "routingPolicy must not be null");
        dataPolicies = dataPolicies == null ? Map.of() : Map.copyOf(dataPolicies);
        for (Map.Entry<AiVersionReferences.PolicyVersion, AiDataMinimizationPolicy> entry
            : dataPolicies.entrySet()) {
            AiVersionReferences.PolicyVersion key = Objects.requireNonNull(
                entry.getKey(),
                "data policy key must not be null"
            );
            AiDataMinimizationPolicy policy = Objects.requireNonNull(
                entry.getValue(),
                "data policy must not be null"
            );
            if (!key.equals(policy.version())) {
                throw new IllegalArgumentException(
                    "data policy map key must match the exact policy version"
                );
            }
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "M6-D configuration snapshots cannot authorize production enablement"
            );
        }
        if (approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "M6-D configuration snapshots cannot authorize approval automation"
            );
        }
    }

    public static AiAdvisoryConfigurationSnapshot create(
        String snapshotId,
        String snapshotVersion,
        AiProviderRoutingPolicy routingPolicy,
        Map<AiVersionReferences.PolicyVersion, AiDataMinimizationPolicy> dataPolicies
    ) {
        String hash = computeContentHash(
            snapshotId,
            snapshotVersion,
            routingPolicy,
            dataPolicies
        );
        return new AiAdvisoryConfigurationSnapshot(
            snapshotId,
            snapshotVersion,
            hash,
            routingPolicy,
            dataPolicies,
            false,
            false
        );
    }

    public String computedContentHash() {
        return computeContentHash(snapshotId, snapshotVersion, routingPolicy, dataPolicies);
    }

    public boolean contentHashMatches() {
        return declaredContentHash.equals(computedContentHash());
    }

    public AiDataMinimizationPolicy dataPolicy(
        AiVersionReferences.PolicyVersion version
    ) {
        return dataPolicies.get(version);
    }

    public Stage stage() {
        return Stage.DRY_RUN_ONLY;
    }

    public enum Stage {
        DRY_RUN_ONLY
    }

    private static String computeContentHash(
        String snapshotId,
        String snapshotVersion,
        AiProviderRoutingPolicy routingPolicy,
        Map<AiVersionReferences.PolicyVersion, AiDataMinimizationPolicy> dataPolicies
    ) {
        String normalizedSnapshotId = requireText(snapshotId, "snapshotId", 160);
        String normalizedSnapshotVersion = requireText(
            snapshotVersion,
            "snapshotVersion",
            120
        );
        AiProviderRoutingPolicy normalizedRouting = Objects.requireNonNull(
            routingPolicy,
            "routingPolicy must not be null"
        );
        Map<AiVersionReferences.PolicyVersion, AiDataMinimizationPolicy> normalizedPolicies =
            dataPolicies == null ? Map.of() : Map.copyOf(dataPolicies);

        StringBuilder canonical = new StringBuilder();
        append(canonical, "snapshotId", normalizedSnapshotId);
        append(canonical, "snapshotVersion", normalizedSnapshotVersion);
        append(canonical, "routing.enabled", normalizedRouting.enabled());
        append(
            canonical,
            "routing.allowPreInvocationCandidateFallback",
            normalizedRouting.allowPreInvocationCandidateFallback()
        );
        append(
            canonical,
            "routing.allowPostInvocationFallback",
            normalizedRouting.allowPostInvocationFallback()
        );

        List<AiProviderRoute> routes = new ArrayList<>(normalizedRouting.routes());
        routes.sort(Comparator.comparing(AiProviderRoute::routeId));
        for (AiProviderRoute route : routes) {
            append(canonical, "route.id", route.routeId());
            append(canonical, "route.priority", route.priority());
            append(canonical, "route.enabled", route.enabled());
            route.capabilities().stream()
                .map(AiCapability::name)
                .sorted()
                .forEach(value -> append(canonical, "route.capability", value));
            appendVersions(canonical, route.versions());
            append(canonical, "route.timeout", route.budget().timeout().toString());
            append(
                canonical,
                "route.maximumInputCharacters",
                route.budget().maximumInputCharacters()
            );
            append(
                canonical,
                "route.maximumInputFields",
                route.budget().maximumInputFields()
            );
            append(
                canonical,
                "route.minimumConfidence",
                Double.toHexString(route.budget().minimumConfidence())
            );
        }

        normalizedPolicies.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(
                AiAdvisoryConfigurationSnapshot::policyAuthorizationKey
            )))
            .forEach(entry -> appendPolicy(canonical, entry.getKey(), entry.getValue()));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void appendVersions(
        StringBuilder canonical,
        AiVersionReferences versions
    ) {
        append(canonical, "provider.id", versions.provider().providerId());
        append(canonical, "provider.version", versions.provider().version());
        append(canonical, "model.providerId", versions.model().providerId());
        append(canonical, "model.id", versions.model().modelId());
        append(canonical, "model.version", versions.model().version());
        append(canonical, "prompt.id", versions.promptTemplate().templateId());
        append(canonical, "prompt.version", versions.promptTemplate().version());
        append(canonical, "prompt.hash", versions.promptTemplate().contentHash());
        append(canonical, "knowledge.id", versions.knowledgeSource().sourceId());
        append(canonical, "knowledge.version", versions.knowledgeSource().version());
        append(canonical, "knowledge.hash", versions.knowledgeSource().contentHash());
        append(
            canonical,
            "knowledge.containsCustomerData",
            versions.knowledgeSource().containsCustomerData()
        );
        append(canonical, "policy.id", versions.policy().policyId());
        append(canonical, "policy.version", versions.policy().version());
        append(canonical, "policy.hash", versions.policy().contentHash());
        append(canonical, "output.id", versions.outputSchema().schemaId());
        append(canonical, "output.version", versions.outputSchema().version());
    }

    private static void appendPolicy(
        StringBuilder canonical,
        AiVersionReferences.PolicyVersion key,
        AiDataMinimizationPolicy policy
    ) {
        if (!key.equals(policy.version())) {
            throw new IllegalArgumentException(
                "data policy map key must match the exact policy version"
            );
        }
        append(canonical, "dataPolicy.id", key.policyId());
        append(canonical, "dataPolicy.version", key.version());
        append(canonical, "dataPolicy.hash", key.contentHash());
        policy.fieldRules().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                append(canonical, "dataPolicy.field", entry.getKey());
                append(canonical, "dataPolicy.rule", entry.getValue().name());
            });
        append(
            canonical,
            "dataPolicy.maximumFields",
            policy.limits().maximumFields()
        );
        append(
            canonical,
            "dataPolicy.maximumTextCharactersPerValue",
            policy.limits().maximumTextCharactersPerValue()
        );
        append(
            canonical,
            "dataPolicy.maximumTotalTextCharacters",
            policy.limits().maximumTotalTextCharacters()
        );
        append(
            canonical,
            "dataPolicy.maximumCollectionSize",
            policy.limits().maximumCollectionSize()
        );
        append(canonical, "dataPolicy.maximumDepth", policy.limits().maximumDepth());
        append(
            canonical,
            "dataPolicy.blockPromptInjectionMarkers",
            policy.blockPromptInjectionMarkers()
        );
    }

    private static String policyAuthorizationKey(
        AiVersionReferences.PolicyVersion version
    ) {
        return version.policyId() + "/" + version.version() + "/" + version.contentHash();
    }

    private static void append(StringBuilder canonical, String name, Object value) {
        String text = String.valueOf(value);
        canonical.append(name.length()).append(':').append(name)
            .append('=').append(text.length()).append(':').append(text).append('\n');
    }

    private static String requireSha256(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
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

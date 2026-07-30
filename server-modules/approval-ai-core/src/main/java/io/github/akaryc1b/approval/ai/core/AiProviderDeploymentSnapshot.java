package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolProfile;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable deployment metadata for readiness and fault-drill validation.
 *
 * <p>The snapshot contains external references and exact allowlist metadata only. It cannot resolve
 * a secret, connect to an endpoint, invoke a Provider or authorize production.</p>
 */
public record AiProviderDeploymentSnapshot(
    String snapshotId,
    String snapshotVersion,
    String declaredContentHash,
    String advisoryConfigurationHash,
    Map<AiVersionReferences.ProviderVersion, DeploymentBinding> bindings,
    Map<String, AiProviderEndpointDescriptor> endpoints,
    Map<String, AiProviderEgressPolicy> egressPolicies,
    Map<String, AiExternalSecretReference> secretReferences,
    Map<String, AiProviderProtocolProfile> validationProfiles,
    boolean providerInvocationAuthorized,
    boolean secretResolutionAuthorized,
    boolean networkEgressAuthorized,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderDeploymentSnapshot {
        snapshotId = requireText(snapshotId, "snapshotId", 160);
        snapshotVersion = requireText(snapshotVersion, "snapshotVersion", 120);
        declaredContentHash = requireSha256(declaredContentHash, "declaredContentHash");
        advisoryConfigurationHash = requireSha256(
            advisoryConfigurationHash,
            "advisoryConfigurationHash"
        );
        bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
        endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
        egressPolicies = egressPolicies == null ? Map.of() : Map.copyOf(egressPolicies);
        secretReferences = secretReferences == null ? Map.of() : Map.copyOf(secretReferences);
        validationProfiles = validationProfiles == null ? Map.of() : Map.copyOf(validationProfiles);
        validateMapKeys(bindings, endpoints, egressPolicies, secretReferences, validationProfiles);
        if (bindings.size() > 100
            || endpoints.size() > 100
            || egressPolicies.size() > 100
            || secretReferences.size() > 500
            || validationProfiles.size() > 100) {
            throw new IllegalArgumentException("deployment snapshot collections must be bounded");
        }
        if (providerInvocationAuthorized
            || secretResolutionAuthorized
            || networkEgressAuthorized) {
            throw new IllegalArgumentException(
                "M6-D deployment snapshots must remain zero-call and secret-free"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "M6-D deployment snapshots cannot authorize production enablement"
            );
        }
        if (approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "M6-D deployment snapshots cannot authorize approval automation"
            );
        }
    }

    public static AiProviderDeploymentSnapshot create(
        String snapshotId,
        String snapshotVersion,
        String advisoryConfigurationHash,
        Map<AiVersionReferences.ProviderVersion, DeploymentBinding> bindings,
        Map<String, AiProviderEndpointDescriptor> endpoints,
        Map<String, AiProviderEgressPolicy> egressPolicies,
        Map<String, AiExternalSecretReference> secretReferences,
        Map<String, AiProviderProtocolProfile> validationProfiles
    ) {
        String hash = computeContentHash(
            snapshotId,
            snapshotVersion,
            advisoryConfigurationHash,
            bindings,
            endpoints,
            egressPolicies,
            secretReferences,
            validationProfiles
        );
        return new AiProviderDeploymentSnapshot(
            snapshotId,
            snapshotVersion,
            hash,
            advisoryConfigurationHash,
            bindings,
            endpoints,
            egressPolicies,
            secretReferences,
            validationProfiles,
            false,
            false,
            false,
            false,
            false
        );
    }

    public String computedContentHash() {
        return computeContentHash(
            snapshotId,
            snapshotVersion,
            advisoryConfigurationHash,
            bindings,
            endpoints,
            egressPolicies,
            secretReferences,
            validationProfiles
        );
    }

    public boolean contentHashMatches() {
        return declaredContentHash.equals(computedContentHash());
    }

    public Stage stage() {
        return Stage.FAULT_DRILL_ONLY;
    }

    public enum Stage {
        FAULT_DRILL_ONLY
    }

    public enum OperationalStage {
        DISABLED,
        FAULT_DRILL_ONLY
    }

    public record DeploymentBinding(
        AiVersionReferences.ProviderVersion providerVersion,
        String endpointId,
        String egressPolicyAuthorizationKey,
        Set<String> secretReferenceAuthorizationKeys,
        String validationProfileAuthorizationKey,
        OperationalStage stage
    ) {
        public DeploymentBinding {
            providerVersion = Objects.requireNonNull(
                providerVersion,
                "providerVersion must not be null"
            );
            endpointId = requireText(endpointId, "endpointId", 160);
            egressPolicyAuthorizationKey = requireText(
                egressPolicyAuthorizationKey,
                "egressPolicyAuthorizationKey",
                240
            );
            secretReferenceAuthorizationKeys = secretReferenceAuthorizationKeys == null
                ? Set.of()
                : Set.copyOf(secretReferenceAuthorizationKeys);
            if (secretReferenceAuthorizationKeys.isEmpty()
                || secretReferenceAuthorizationKeys.size() > 20) {
                throw new IllegalArgumentException(
                    "secretReferenceAuthorizationKeys must be non-empty and bounded"
                );
            }
            secretReferenceAuthorizationKeys = secretReferenceAuthorizationKeys.stream()
                .map(value -> requireText(value, "secretReferenceAuthorizationKey", 360))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            validationProfileAuthorizationKey = requireText(
                validationProfileAuthorizationKey,
                "validationProfileAuthorizationKey",
                360
            );
            stage = Objects.requireNonNull(stage, "stage must not be null");
        }
    }

    private static void validateMapKeys(
        Map<AiVersionReferences.ProviderVersion, DeploymentBinding> bindings,
        Map<String, AiProviderEndpointDescriptor> endpoints,
        Map<String, AiProviderEgressPolicy> egressPolicies,
        Map<String, AiExternalSecretReference> secretReferences,
        Map<String, AiProviderProtocolProfile> validationProfiles
    ) {
        for (Map.Entry<AiVersionReferences.ProviderVersion, DeploymentBinding> entry
            : bindings.entrySet()) {
            if (!Objects.requireNonNull(entry.getKey(), "binding key must not be null")
                .equals(Objects.requireNonNull(entry.getValue(), "binding must not be null")
                    .providerVersion())) {
                throw new IllegalArgumentException(
                    "binding map key must match the exact Provider version"
                );
            }
        }
        for (Map.Entry<String, AiProviderEndpointDescriptor> entry : endpoints.entrySet()) {
            if (!requireText(entry.getKey(), "endpoint key", 160)
                .equals(Objects.requireNonNull(entry.getValue(), "endpoint must not be null")
                    .endpointId())) {
                throw new IllegalArgumentException("endpoint map key must match endpointId");
            }
        }
        for (Map.Entry<String, AiProviderEgressPolicy> entry : egressPolicies.entrySet()) {
            if (!requireText(entry.getKey(), "egress policy key", 240)
                .equals(Objects.requireNonNull(entry.getValue(), "egress policy must not be null")
                    .authorizationKey())) {
                throw new IllegalArgumentException(
                    "egress policy map key must match authorizationKey"
                );
            }
        }
        for (Map.Entry<String, AiExternalSecretReference> entry : secretReferences.entrySet()) {
            if (!requireText(entry.getKey(), "secret reference key", 360)
                .equals(Objects.requireNonNull(
                    entry.getValue(),
                    "secret reference must not be null"
                ).authorizationKey())) {
                throw new IllegalArgumentException(
                    "secret reference map key must match authorizationKey"
                );
            }
        }
        for (Map.Entry<String, AiProviderProtocolProfile> entry : validationProfiles.entrySet()) {
            if (!requireText(entry.getKey(), "validation profile key", 360)
                .equals(Objects.requireNonNull(
                    entry.getValue(),
                    "validation profile must not be null"
                ).authorizationKey())) {
                throw new IllegalArgumentException(
                    "validation profile map key must match authorizationKey"
                );
            }
        }
    }

    private static String computeContentHash(
        String snapshotId,
        String snapshotVersion,
        String advisoryConfigurationHash,
        Map<AiVersionReferences.ProviderVersion, DeploymentBinding> bindings,
        Map<String, AiProviderEndpointDescriptor> endpoints,
        Map<String, AiProviderEgressPolicy> egressPolicies,
        Map<String, AiExternalSecretReference> secretReferences,
        Map<String, AiProviderProtocolProfile> validationProfiles
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "snapshotId", requireText(snapshotId, "snapshotId", 160));
        append(canonical, "snapshotVersion", requireText(snapshotVersion, "snapshotVersion", 120));
        append(
            canonical,
            "advisoryConfigurationHash",
            requireSha256(advisoryConfigurationHash, "advisoryConfigurationHash")
        );

        (bindings == null ? Map.<AiVersionReferences.ProviderVersion, DeploymentBinding>of()
            : Map.copyOf(bindings)).entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(
                AiProviderDeploymentSnapshot::providerAuthorizationKey
            )))
            .forEach(entry -> appendBinding(canonical, entry.getKey(), entry.getValue()));
        (endpoints == null ? Map.<String, AiProviderEndpointDescriptor>of()
            : Map.copyOf(endpoints)).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> appendEndpoint(canonical, entry.getKey(), entry.getValue()));
        (egressPolicies == null ? Map.<String, AiProviderEgressPolicy>of()
            : Map.copyOf(egressPolicies)).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> appendEgress(canonical, entry.getKey(), entry.getValue()));
        (secretReferences == null ? Map.<String, AiExternalSecretReference>of()
            : Map.copyOf(secretReferences)).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> appendSecret(canonical, entry.getKey(), entry.getValue()));
        (validationProfiles == null ? Map.<String, AiProviderProtocolProfile>of()
            : Map.copyOf(validationProfiles)).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> appendProfile(canonical, entry.getKey(), entry.getValue()));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void appendBinding(
        StringBuilder canonical,
        AiVersionReferences.ProviderVersion key,
        DeploymentBinding binding
    ) {
        if (!key.equals(binding.providerVersion())) {
            throw new IllegalArgumentException(
                "binding map key must match the exact Provider version"
            );
        }
        append(canonical, "binding.provider", providerAuthorizationKey(key));
        append(canonical, "binding.endpointId", binding.endpointId());
        append(canonical, "binding.egressPolicy", binding.egressPolicyAuthorizationKey());
        binding.secretReferenceAuthorizationKeys().stream().sorted()
            .forEach(value -> append(canonical, "binding.secretReference", value));
        append(
            canonical,
            "binding.validationProfile",
            binding.validationProfileAuthorizationKey()
        );
        append(canonical, "binding.stage", binding.stage().name());
    }

    private static void appendEndpoint(
        StringBuilder canonical,
        String key,
        AiProviderEndpointDescriptor endpoint
    ) {
        if (!key.equals(endpoint.endpointId())) {
            throw new IllegalArgumentException("endpoint map key must match endpointId");
        }
        append(canonical, "endpoint.id", key);
        append(canonical, "endpoint.provider", providerAuthorizationKey(endpoint.providerVersion()));
        append(canonical, "endpoint.authorizationKey", endpoint.authorizationKey());
    }

    private static void appendEgress(
        StringBuilder canonical,
        String key,
        AiProviderEgressPolicy policy
    ) {
        if (!key.equals(policy.authorizationKey())) {
            throw new IllegalArgumentException(
                "egress policy map key must match authorizationKey"
            );
        }
        append(canonical, "egress.key", key);
        append(canonical, "egress.declaredHash", policy.declaredContentHash());
        append(canonical, "egress.computedHash", policy.contentHashMatches());
        policy.allowedEndpoints().stream()
            .sorted(Comparator.comparing(AiProviderEndpointDescriptor::endpointId))
            .forEach(value -> append(
                canonical,
                "egress.endpoint",
                value.authorizationKey()
            ));
    }

    private static void appendSecret(
        StringBuilder canonical,
        String key,
        AiExternalSecretReference reference
    ) {
        if (!key.equals(reference.authorizationKey())) {
            throw new IllegalArgumentException(
                "secret reference map key must match authorizationKey"
            );
        }
        append(canonical, "secret.key", key);
        append(canonical, "secret.storeKind", reference.storeKind().name());
        append(canonical, "secret.provider", providerAuthorizationKey(reference.providerVersion()));
        reference.purposes().stream().map(Enum::name).sorted()
            .forEach(value -> append(canonical, "secret.purpose", value));
        append(canonical, "secret.rotationState", reference.rotationState().name());
    }

    private static void appendProfile(
        StringBuilder canonical,
        String key,
        AiProviderProtocolProfile profile
    ) {
        if (!key.equals(profile.authorizationKey())) {
            throw new IllegalArgumentException(
                "validation profile map key must match authorizationKey"
            );
        }
        append(canonical, "profile.key", key);
        append(canonical, "profile.provider", providerAuthorizationKey(profile.providerVersion()));
        profile.capabilities().stream().map(Enum::name).sorted()
            .forEach(value -> append(canonical, "profile.capability", value));
        append(canonical, "profile.requestSchemaHash", profile.requestSchemaHash());
        append(canonical, "profile.responseSchemaHash", profile.responseSchemaHash());
        append(canonical, "profile.maximumRequestBytes", profile.maximumRequestBytes());
        append(canonical, "profile.maximumResponseBytes", profile.maximumResponseBytes());
    }

    private static String providerAuthorizationKey(
        AiVersionReferences.ProviderVersion version
    ) {
        return version.providerId() + "/" + version.version();
    }

    private static void append(StringBuilder target, String key, Object value) {
        String text = String.valueOf(value);
        target.append(key.length()).append(':').append(key)
            .append('=').append(text.length()).append(':').append(text).append(';');
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
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

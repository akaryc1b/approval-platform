package io.github.akaryc1b.approval.connector.routing;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable and secret-free contracts for server-owned tenant connector routing.
 */
public final class TenantConnectorRouteContracts {

    static final String DINGTALK_PROVIDER = "dingtalk";
    static final int MAX_IDENTIFIER_LENGTH = 128;
    static final int MAX_VERSION_LENGTH = 64;
    static final int MAX_CORRELATION_LENGTH = 256;
    static final int MAX_BUSINESS_REFERENCE_LENGTH = 256;
    private static final Pattern SAFE_TEXT = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:/-]*"
    );
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private TenantConnectorRouteContracts() {
    }

    public enum RouteIntent {
        ORGANIZATION_READ_USER_BY_ID(
            ConnectorProvider.Capability.ORGANIZATION,
            ConnectorOperation.ORGANIZATION_READ,
            "USER_BY_ID"
        ),
        IDENTITY_RESOLVE_DINGTALK_USERID(
            ConnectorProvider.Capability.AUTHENTICATION,
            ConnectorOperation.IDENTITY_RESOLVE,
            "dingtalk-userid"
        );

        private final ConnectorProvider.Capability capability;
        private final ConnectorOperation connectorOperation;
        private final String providerOperation;

        RouteIntent(
            ConnectorProvider.Capability capability,
            ConnectorOperation connectorOperation,
            String providerOperation
        ) {
            this.capability = capability;
            this.connectorOperation = connectorOperation;
            this.providerOperation = providerOperation;
        }

        public ConnectorProvider.Capability capability() {
            return capability;
        }

        public ConnectorOperation connectorOperation() {
            return connectorOperation;
        }

        public String providerOperation() {
            return providerOperation;
        }
    }

    public enum ProviderApiFamily {
        OPEN_API_V1,
        LEGACY_OAPI
    }

    public enum TransportProfile {
        DINGTALK_JAVA21_FIXED_HTTPS_V1
    }

    public enum ResolutionStatus {
        RESOLVED,
        DISABLED,
        MISSING,
        AMBIGUOUS,
        EXPIRED,
        NOT_YET_VALID,
        UNSUPPORTED,
        INCOMPATIBLE,
        INVALID_CONFIGURATION,
        SOURCE_UNAVAILABLE
    }

    public enum RevalidationStatus {
        VALID,
        TENANT_MISMATCH,
        INVALID_PLAN,
        STALE,
        DISABLED,
        EXPIRED,
        NOT_YET_VALID,
        UNSUPPORTED,
        INCOMPATIBLE,
        INVALID_CONFIGURATION,
        SOURCE_UNAVAILABLE
    }

    public record RouteRequest(
        ConnectorProvider.Capability capability,
        RouteIntent intent,
        String businessReference,
        String correlationReference
    ) {
        public RouteRequest {
            capability = Objects.requireNonNull(capability, "capability must not be null");
            intent = Objects.requireNonNull(intent, "intent must not be null");
            if (capability != intent.capability()) {
                throw new IllegalArgumentException("capability does not match the closed route intent");
            }
            businessReference = optionalText(
                businessReference,
                "businessReference",
                MAX_BUSINESS_REFERENCE_LENGTH
            );
            correlationReference = requiredText(
                correlationReference,
                "correlationReference",
                MAX_CORRELATION_LENGTH
            );
        }

        public String evidenceHash() {
            return hash(canonical(
                capability.name(),
                intent.name(),
                optionalHash(businessReference),
                hash(correlationReference)
            ));
        }
    }

    public record RouteDefinition(
        String tenantId,
        String providerKey,
        ConnectorProvider.Capability capability,
        RouteIntent intent,
        ProviderApiFamily apiFamily,
        TransportProfile transportProfile,
        CredentialReference credentialReference,
        CredentialMaterialType credentialMaterialType,
        String routeVersion,
        String routePolicyVersion,
        String credentialPolicyVersion,
        String credentialDescriptorFingerprint,
        boolean enabled,
        Instant validFrom,
        Instant validUntil,
        String definitionHash
    ) {
        public RouteDefinition {
            tenantId = boundedIdentifier(tenantId, "tenantId", MAX_IDENTIFIER_LENGTH);
            providerKey = boundedIdentifier(providerKey, "providerKey", 32);
            capability = Objects.requireNonNull(capability, "capability must not be null");
            intent = Objects.requireNonNull(intent, "intent must not be null");
            if (capability != intent.capability()) {
                throw new IllegalArgumentException("route capability does not match route intent");
            }
            apiFamily = Objects.requireNonNull(apiFamily, "apiFamily must not be null");
            transportProfile = Objects.requireNonNull(
                transportProfile,
                "transportProfile must not be null"
            );
            credentialReference = Objects.requireNonNull(
                credentialReference,
                "credentialReference must not be null"
            );
            if (!providerKey.equals(credentialReference.providerKey())) {
                throw new IllegalArgumentException("credential reference belongs to another provider");
            }
            credentialMaterialType = Objects.requireNonNull(
                credentialMaterialType,
                "credentialMaterialType must not be null"
            );
            routeVersion = version(routeVersion, "routeVersion");
            routePolicyVersion = version(routePolicyVersion, "routePolicyVersion");
            credentialPolicyVersion = version(
                credentialPolicyVersion,
                "credentialPolicyVersion"
            );
            credentialDescriptorFingerprint = sha256(
                credentialDescriptorFingerprint,
                "credentialDescriptorFingerprint"
            );
            if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
                throw new IllegalArgumentException("validUntil must be after validFrom");
            }
            definitionHash = sha256(definitionHash, "definitionHash");
        }

        public static RouteDefinition create(
            String tenantId,
            String providerKey,
            ConnectorProvider.Capability capability,
            RouteIntent intent,
            ProviderApiFamily apiFamily,
            TransportProfile transportProfile,
            CredentialReference credentialReference,
            CredentialMaterialType credentialMaterialType,
            String routeVersion,
            String routePolicyVersion,
            String credentialPolicyVersion,
            String credentialDescriptorFingerprint,
            boolean enabled,
            Instant validFrom,
            Instant validUntil
        ) {
            String normalizedTenant = boundedIdentifier(
                tenantId,
                "tenantId",
                MAX_IDENTIFIER_LENGTH
            );
            String normalizedProvider = boundedIdentifier(providerKey, "providerKey", 32);
            String normalizedRouteVersion = version(routeVersion, "routeVersion");
            String normalizedRoutePolicy = version(routePolicyVersion, "routePolicyVersion");
            String normalizedCredentialPolicy = version(
                credentialPolicyVersion,
                "credentialPolicyVersion"
            );
            String normalizedFingerprint = sha256(
                credentialDescriptorFingerprint,
                "credentialDescriptorFingerprint"
            );
            String computed = computeDefinitionHash(
                normalizedTenant,
                normalizedProvider,
                capability,
                intent,
                apiFamily,
                transportProfile,
                credentialReference,
                credentialMaterialType,
                normalizedRouteVersion,
                normalizedRoutePolicy,
                normalizedCredentialPolicy,
                normalizedFingerprint,
                enabled,
                validFrom,
                validUntil
            );
            return new RouteDefinition(
                normalizedTenant,
                normalizedProvider,
                capability,
                intent,
                apiFamily,
                transportProfile,
                credentialReference,
                credentialMaterialType,
                normalizedRouteVersion,
                normalizedRoutePolicy,
                normalizedCredentialPolicy,
                normalizedFingerprint,
                enabled,
                validFrom,
                validUntil,
                computed
            );
        }

        public String computedDefinitionHash() {
            return computeDefinitionHash(
                tenantId,
                providerKey,
                capability,
                intent,
                apiFamily,
                transportProfile,
                credentialReference,
                credentialMaterialType,
                routeVersion,
                routePolicyVersion,
                credentialPolicyVersion,
                credentialDescriptorFingerprint,
                enabled,
                validFrom,
                validUntil
            );
        }

        public boolean hashMatches() {
            return definitionHash.equals(computedDefinitionHash());
        }

        public boolean validAt(Instant evaluatedAt) {
            Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
            return (validFrom == null || !evaluatedAt.isBefore(validFrom))
                && (validUntil == null || evaluatedAt.isBefore(validUntil));
        }

        public boolean notYetValidAt(Instant evaluatedAt) {
            return validFrom != null && evaluatedAt.isBefore(validFrom);
        }

        public boolean expiredAt(Instant evaluatedAt) {
            return validUntil != null && !evaluatedAt.isBefore(validUntil);
        }

        public boolean supportedByP4() {
            if (!DINGTALK_PROVIDER.equals(providerKey)
                || transportProfile != TransportProfile.DINGTALK_JAVA21_FIXED_HTTPS_V1
                || credentialMaterialType != CredentialMaterialType.ACCESS_TOKEN) {
                return false;
            }
            return switch (intent) {
                case ORGANIZATION_READ_USER_BY_ID -> apiFamily == ProviderApiFamily.OPEN_API_V1
                    || apiFamily == ProviderApiFamily.LEGACY_OAPI;
                case IDENTITY_RESOLVE_DINGTALK_USERID ->
                    apiFamily == ProviderApiFamily.LEGACY_OAPI;
            };
        }
    }

    public record RoutePlan(
        String tenantEvidenceHash,
        String providerKey,
        ConnectorProvider.Capability capability,
        RouteIntent intent,
        ConnectorOperation connectorOperation,
        ProviderApiFamily apiFamily,
        TransportProfile transportProfile,
        String credentialReferenceHash,
        CredentialMaterialType credentialMaterialType,
        String routeVersion,
        String routePolicyVersion,
        String credentialPolicyVersion,
        String configurationSnapshotHash,
        String routeDefinitionHash,
        String credentialDescriptorFingerprint,
        String requestEvidenceHash,
        String businessReferenceHash,
        String correlationEvidenceHash,
        Instant createdAtEvidence,
        String planHash
    ) {
        public RoutePlan {
            tenantEvidenceHash = sha256(tenantEvidenceHash, "tenantEvidenceHash");
            providerKey = boundedIdentifier(providerKey, "providerKey", 32);
            capability = Objects.requireNonNull(capability, "capability must not be null");
            intent = Objects.requireNonNull(intent, "intent must not be null");
            connectorOperation = Objects.requireNonNull(
                connectorOperation,
                "connectorOperation must not be null"
            );
            if (capability != intent.capability()
                || connectorOperation != intent.connectorOperation()) {
                throw new IllegalArgumentException("plan operation evidence is inconsistent");
            }
            apiFamily = Objects.requireNonNull(apiFamily, "apiFamily must not be null");
            transportProfile = Objects.requireNonNull(
                transportProfile,
                "transportProfile must not be null"
            );
            credentialReferenceHash = sha256(
                credentialReferenceHash,
                "credentialReferenceHash"
            );
            credentialMaterialType = Objects.requireNonNull(
                credentialMaterialType,
                "credentialMaterialType must not be null"
            );
            routeVersion = version(routeVersion, "routeVersion");
            routePolicyVersion = version(routePolicyVersion, "routePolicyVersion");
            credentialPolicyVersion = version(
                credentialPolicyVersion,
                "credentialPolicyVersion"
            );
            configurationSnapshotHash = sha256(
                configurationSnapshotHash,
                "configurationSnapshotHash"
            );
            routeDefinitionHash = sha256(routeDefinitionHash, "routeDefinitionHash");
            credentialDescriptorFingerprint = sha256(
                credentialDescriptorFingerprint,
                "credentialDescriptorFingerprint"
            );
            requestEvidenceHash = sha256(requestEvidenceHash, "requestEvidenceHash");
            businessReferenceHash = optionalSha256(
                businessReferenceHash,
                "businessReferenceHash"
            );
            correlationEvidenceHash = sha256(
                correlationEvidenceHash,
                "correlationEvidenceHash"
            );
            createdAtEvidence = Objects.requireNonNull(
                createdAtEvidence,
                "createdAtEvidence must not be null"
            );
            planHash = sha256(planHash, "planHash");
        }

        public static RoutePlan create(
            String trustedTenantId,
            RouteDefinition definition,
            String configurationSnapshotHash,
            RouteRequest request,
            String credentialReferenceHash,
            Instant createdAtEvidence
        ) {
            Objects.requireNonNull(definition, "definition must not be null");
            Objects.requireNonNull(request, "request must not be null");
            String tenantHash = TenantConnectorRouteContracts.tenantEvidenceHash(trustedTenantId);
            String businessHash = optionalHash(request.businessReference());
            String correlationHash = hash(request.correlationReference());
            String requestHash = request.evidenceHash();
            String computedPlanHash = computePlanHash(
                tenantHash,
                definition.providerKey(),
                definition.capability(),
                definition.intent(),
                definition.intent().connectorOperation(),
                definition.apiFamily(),
                definition.transportProfile(),
                credentialReferenceHash,
                definition.credentialMaterialType(),
                definition.routeVersion(),
                definition.routePolicyVersion(),
                definition.credentialPolicyVersion(),
                configurationSnapshotHash,
                definition.definitionHash(),
                definition.credentialDescriptorFingerprint(),
                requestHash,
                businessHash,
                correlationHash,
                createdAtEvidence
            );
            return new RoutePlan(
                tenantHash,
                definition.providerKey(),
                definition.capability(),
                definition.intent(),
                definition.intent().connectorOperation(),
                definition.apiFamily(),
                definition.transportProfile(),
                credentialReferenceHash,
                definition.credentialMaterialType(),
                definition.routeVersion(),
                definition.routePolicyVersion(),
                definition.credentialPolicyVersion(),
                configurationSnapshotHash,
                definition.definitionHash(),
                definition.credentialDescriptorFingerprint(),
                requestHash,
                businessHash,
                correlationHash,
                createdAtEvidence,
                computedPlanHash
            );
        }

        public String computedPlanHash() {
            return computePlanHash(
                tenantEvidenceHash,
                providerKey,
                capability,
                intent,
                connectorOperation,
                apiFamily,
                transportProfile,
                credentialReferenceHash,
                credentialMaterialType,
                routeVersion,
                routePolicyVersion,
                credentialPolicyVersion,
                configurationSnapshotHash,
                routeDefinitionHash,
                credentialDescriptorFingerprint,
                requestEvidenceHash,
                businessReferenceHash,
                correlationEvidenceHash,
                createdAtEvidence
            );
        }

        public boolean hashMatches() {
            return planHash.equals(computedPlanHash());
        }
    }

    public record RouteEvidence(
        ResolutionStatus status,
        String tenantEvidenceHash,
        String requestEvidenceHash,
        String configurationSnapshotHash,
        String routeDefinitionHash,
        String planHash,
        String evidenceHash
    ) {
        public RouteEvidence {
            status = Objects.requireNonNull(status, "status must not be null");
            tenantEvidenceHash = sha256(tenantEvidenceHash, "tenantEvidenceHash");
            requestEvidenceHash = sha256(requestEvidenceHash, "requestEvidenceHash");
            configurationSnapshotHash = optionalSha256(
                configurationSnapshotHash,
                "configurationSnapshotHash"
            );
            routeDefinitionHash = optionalSha256(
                routeDefinitionHash,
                "routeDefinitionHash"
            );
            planHash = optionalSha256(planHash, "planHash");
            evidenceHash = sha256(evidenceHash, "evidenceHash");
        }

        public static RouteEvidence create(
            ResolutionStatus status,
            String tenantEvidenceHash,
            String requestEvidenceHash,
            String configurationSnapshotHash,
            String routeDefinitionHash,
            String planHash
        ) {
            String computed = hash(canonical(
                status.name(),
                tenantEvidenceHash,
                requestEvidenceHash,
                nullable(configurationSnapshotHash),
                nullable(routeDefinitionHash),
                nullable(planHash)
            ));
            return new RouteEvidence(
                status,
                tenantEvidenceHash,
                requestEvidenceHash,
                configurationSnapshotHash,
                routeDefinitionHash,
                planHash,
                computed
            );
        }
    }

    public record RouteResolution(
        ResolutionStatus status,
        Optional<RoutePlan> plan,
        RouteEvidence evidence
    ) {
        public RouteResolution {
            status = Objects.requireNonNull(status, "status must not be null");
            plan = plan == null ? Optional.empty() : plan;
            evidence = Objects.requireNonNull(evidence, "evidence must not be null");
            if ((status == ResolutionStatus.RESOLVED) != plan.isPresent()) {
                throw new IllegalArgumentException("only RESOLVED may carry a route plan");
            }
            if (evidence.status() != status) {
                throw new IllegalArgumentException("resolution and evidence status differ");
            }
        }

        public boolean executablePlanPresent() {
            return status == ResolutionStatus.RESOLVED && plan.isPresent();
        }
    }

    public record RouteRevalidation(
        RevalidationStatus status,
        String tenantEvidenceHash,
        String planHash,
        String configurationSnapshotHash,
        String evidenceHash
    ) {
        public RouteRevalidation {
            status = Objects.requireNonNull(status, "status must not be null");
            tenantEvidenceHash = sha256(tenantEvidenceHash, "tenantEvidenceHash");
            planHash = sha256(planHash, "planHash");
            configurationSnapshotHash = optionalSha256(
                configurationSnapshotHash,
                "configurationSnapshotHash"
            );
            evidenceHash = sha256(evidenceHash, "evidenceHash");
        }

        public static RouteRevalidation create(
            RevalidationStatus status,
            String tenantEvidenceHash,
            String planHash,
            String configurationSnapshotHash
        ) {
            String computed = hash(canonical(
                status.name(),
                tenantEvidenceHash,
                planHash,
                nullable(configurationSnapshotHash)
            ));
            return new RouteRevalidation(
                status,
                tenantEvidenceHash,
                planHash,
                configurationSnapshotHash,
                computed
            );
        }

        public boolean validForDispatch() {
            return status == RevalidationStatus.VALID;
        }
    }

    static String tenantEvidenceHash(String tenantId) {
        return hash("tenant-evidence\n" + boundedIdentifier(
            tenantId,
            "trustedTenantId",
            MAX_IDENTIFIER_LENGTH
        ));
    }

    static String computeDefinitionHash(
        String tenantId,
        String providerKey,
        ConnectorProvider.Capability capability,
        RouteIntent intent,
        ProviderApiFamily apiFamily,
        TransportProfile transportProfile,
        CredentialReference credentialReference,
        CredentialMaterialType credentialMaterialType,
        String routeVersion,
        String routePolicyVersion,
        String credentialPolicyVersion,
        String credentialDescriptorFingerprint,
        boolean enabled,
        Instant validFrom,
        Instant validUntil
    ) {
        return hash(canonical(
            tenantId,
            providerKey,
            capability.name(),
            intent.name(),
            intent.connectorOperation().name(),
            intent.providerOperation(),
            apiFamily.name(),
            transportProfile.name(),
            credentialReference.providerKey(),
            credentialReference.referenceId(),
            credentialMaterialType.name(),
            routeVersion,
            routePolicyVersion,
            credentialPolicyVersion,
            credentialDescriptorFingerprint,
            Boolean.toString(enabled),
            instant(validFrom),
            instant(validUntil)
        ));
    }

    private static String computePlanHash(
        String tenantEvidenceHash,
        String providerKey,
        ConnectorProvider.Capability capability,
        RouteIntent intent,
        ConnectorOperation connectorOperation,
        ProviderApiFamily apiFamily,
        TransportProfile transportProfile,
        String credentialReferenceHash,
        CredentialMaterialType credentialMaterialType,
        String routeVersion,
        String routePolicyVersion,
        String credentialPolicyVersion,
        String configurationSnapshotHash,
        String routeDefinitionHash,
        String credentialDescriptorFingerprint,
        String requestEvidenceHash,
        String businessReferenceHash,
        String correlationEvidenceHash,
        Instant createdAtEvidence
    ) {
        return hash(canonical(
            tenantEvidenceHash,
            providerKey,
            capability.name(),
            intent.name(),
            connectorOperation.name(),
            apiFamily.name(),
            transportProfile.name(),
            credentialReferenceHash,
            credentialMaterialType.name(),
            routeVersion,
            routePolicyVersion,
            credentialPolicyVersion,
            configurationSnapshotHash,
            routeDefinitionHash,
            credentialDescriptorFingerprint,
            requestEvidenceHash,
            nullable(businessReferenceHash),
            correlationEvidenceHash,
            createdAtEvidence.toString()
        ));
    }

    static String canonical(String... values) {
        StringBuilder canonical = new StringBuilder(values.length * 48);
        for (String value : values) {
            String safe = Objects.requireNonNull(value, "canonical value must not be null");
            byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
            canonical.append(bytes.length).append(':').append(safe).append('|');
        }
        return canonical.toString();
    }

    static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    static String optionalHash(String value) {
        return value == null ? null : hash(value);
    }

    static String nullable(String value) {
        return value == null ? "<null>" : value;
    }

    static String instant(Instant value) {
        return value == null ? "<null>" : value.toString();
    }

    static String boundedIdentifier(String value, String name, int maximumLength) {
        String normalized = requiredText(value, name, maximumLength);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!SAFE_TEXT.matcher(normalized).matches()
            || normalized.contains("*")
            || lower.equals("all")
            || lower.equals("any")
            || lower.equals("default")
            || lower.equals("catch-all")
            || lower.contains("://")) {
            throw new IllegalArgumentException(name + " is not an exact safe identifier");
        }
        return normalized;
    }

    static String version(String value, String name) {
        return boundedIdentifier(value, name, MAX_VERSION_LENGTH);
    }

    static String sha256(String value, String name) {
        String normalized = requiredText(value, name, 64).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hexadecimal digest");
        }
        return normalized;
    }

    private static String optionalSha256(String value, String name) {
        return value == null ? null : sha256(value, name);
    }

    private static String requiredText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                name + " exceeds " + maximumLength + " characters"
            );
        }
        return normalized;
    }

    private static String optionalText(String value, String name, int maximumLength) {
        return value == null || value.isBlank()
            ? null
            : requiredText(value, name, maximumLength);
    }
}

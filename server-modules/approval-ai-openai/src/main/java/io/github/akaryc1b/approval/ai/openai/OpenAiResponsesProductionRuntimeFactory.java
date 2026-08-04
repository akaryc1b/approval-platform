package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialEnvironment;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialRequest;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialVersion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Framework-free P6-E runtime factory for exact tenant-bound production senders.
 *
 * <p>The factory reads no Secret. A cached tenant binding owns one callback-scoped Secret source,
 * while circuit/rate/cost controls are shared and bounded. The API key is read only inside the
 * accepted sender callback after admission, DNS and verified TLS.</p>
 */
public final class OpenAiResponsesProductionRuntimeFactory {

    private static final int MAXIMUM_TENANT_BINDINGS = 10_000;

    private final RuntimeProfile profile;
    private final Clock clock;
    private final OpenAiResponsesTransportControls.RateLimiter rateLimiter;
    private final OpenAiResponsesTransportControls.CircuitBreaker circuitBreaker;
    private final OpenAiResponsesTransportControls.CostPolicy costPolicy;
    private final OpenAiResponsesTransportControls.KillSwitchSnapshot killSwitch;
    private final ConcurrentHashMap<String, Binding> bindings = new ConcurrentHashMap<>();

    public OpenAiResponsesProductionRuntimeFactory(RuntimeProfile profile, Clock clock) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.rateLimiter = new OpenAiResponsesTransportControls.RateLimiter(
            profile.perTenantRateLimit(),
            profile.globalRateLimit(),
            MAXIMUM_TENANT_BINDINGS,
            profile.rateWindow()
        );
        this.circuitBreaker = new OpenAiResponsesTransportControls.CircuitBreaker(
            profile.circuitFailureThreshold(),
            profile.circuitOpenDuration()
        );
        this.costPolicy = new OpenAiResponsesTransportControls.CostPolicy(
            profile.costPolicyVersion(),
            OpenAiResponsesProtocol.MODEL_SNAPSHOT,
            profile.inputMicrosPerConservativeToken(),
            profile.outputMicrosPerToken(),
            profile.maximumRequestMicros(),
            profile.costPolicyEffectiveFrom(),
            profile.costPolicyExpiresAt()
        );
        this.killSwitch = new OpenAiResponsesTransportControls.KillSwitchSnapshot(
            OpenAiResponsesProtocol.PROVIDER_ID,
            OpenAiResponsesProtocol.PROVIDER_VERSION,
            profile.killSwitchGeneration(),
            true,
            profile.killSwitchPolicyRevision()
        );
        costPolicy.requireCurrent(clock.instant());
    }

    public Binding bind(String trustedTenantId) {
        String tenantId = requireText(trustedTenantId, "trustedTenantId", 128);
        Binding existing = bindings.get(tenantId);
        if (existing != null) {
            return existing;
        }
        if (bindings.size() >= MAXIMUM_TENANT_BINDINGS) {
            throw new IllegalStateException("AI production tenant binding capacity exceeded");
        }
        return bindings.computeIfAbsent(tenantId, this::newBinding);
    }

    private Binding newBinding(String tenantId) {
        Instant now = clock.instant();
        String tenantHash = CanonicalPayloadHash.sha256Utf8(tenantId);
        CredentialMaterialVersion version = new CredentialMaterialVersion(
            profile.secretVersionReference(),
            profile.secretVersionEffectiveFrom(),
            profile.secretVersionExpiresAt(),
            CanonicalPayloadHash.sha256Utf8(profile.secretVersionReference())
        );
        CredentialMaterialRequest credentialRequest = new CredentialMaterialRequest(
            new CredentialReference(
                OpenAiEnvironmentCredentialMaterialSource.PROVIDER_KEY,
                OpenAiEnvironmentCredentialMaterialSource.CREDENTIAL_REFERENCE_ID
            ),
            tenantId,
            OpenAiEnvironmentCredentialMaterialSource.PROVIDER_KEY,
            CanonicalPayloadHash.sha256Utf8(
                "openai-responses-route/" + OpenAiResponsesProtocol.MODEL_SNAPSHOT
            ),
            CanonicalPayloadHash.sha256Utf8(
                "openai-responses-secret-binding/" + profile.secretVersionReference()
            ),
            version,
            CredentialMaterialType.API_KEY,
            ConnectorOperation.AI_ADVISORY_GENERATE,
            OpenAiEnvironmentCredentialMaterialSource.PROTOCOL_PROFILE,
            OpenAiEnvironmentCredentialMaterialSource.CAPABILITY,
            CredentialMaterialEnvironment.PRODUCTION,
            profile.secretPolicyRevision()
        );
        OpenAiEnvironmentCredentialMaterialSource credentialSource =
            OpenAiEnvironmentCredentialMaterialSource.systemEnvironment(
                credentialRequest,
                clock
            );
        OpenAiResponsesTransportAdmission admission = new OpenAiResponsesTransportAdmission(
            tenantHash,
            () -> killSwitch,
            killSwitch.generation(),
            killSwitch.evidenceHash(),
            circuitBreaker,
            rateLimiter,
            costPolicy,
            clock
        );
        OpenAiResponsesSecureHttpSender sender = OpenAiResponsesSecureHttpSender.production(
            credentialSource,
            credentialRequest,
            admission,
            clock
        );
        return new Binding(
            OpenAiResponsesAdvisoryProvider.production(sender),
            tenantHash,
            killSwitch.generation(),
            killSwitch.evidenceHash(),
            costPolicy.evidenceHash(),
            version.versionEvidenceHash(),
            credentialSource.bindingEvidenceHash(),
            now
        );
    }

    public RuntimeProfile profile() {
        return profile;
    }

    public record Binding(
        OpenAiResponsesAdvisoryProvider provider,
        String tenantHash,
        long killSwitchGeneration,
        String killSwitchEvidenceHash,
        String costPolicyEvidenceHash,
        String secretVersionEvidenceHash,
        String secretBindingEvidenceHash,
        Instant boundAt
    ) {
        public Binding {
            provider = Objects.requireNonNull(provider, "provider must not be null");
            tenantHash = requireHash(tenantHash, "tenantHash");
            if (killSwitchGeneration < 1) {
                throw new IllegalArgumentException("killSwitchGeneration must be positive");
            }
            killSwitchEvidenceHash = requireHash(
                killSwitchEvidenceHash,
                "killSwitchEvidenceHash"
            );
            costPolicyEvidenceHash = requireHash(
                costPolicyEvidenceHash,
                "costPolicyEvidenceHash"
            );
            secretVersionEvidenceHash = requireHash(
                secretVersionEvidenceHash,
                "secretVersionEvidenceHash"
            );
            secretBindingEvidenceHash = requireHash(
                secretBindingEvidenceHash,
                "secretBindingEvidenceHash"
            );
            boundAt = Objects.requireNonNull(boundAt, "boundAt must not be null");
        }
    }

    public record RuntimeProfile(
        String secretVersionReference,
        Instant secretVersionEffectiveFrom,
        Instant secretVersionExpiresAt,
        String secretPolicyRevision,
        long killSwitchGeneration,
        String killSwitchPolicyRevision,
        String costPolicyVersion,
        Instant costPolicyEffectiveFrom,
        Instant costPolicyExpiresAt,
        long inputMicrosPerConservativeToken,
        long outputMicrosPerToken,
        long maximumRequestMicros,
        int perTenantRateLimit,
        int globalRateLimit,
        Duration rateWindow,
        int circuitFailureThreshold,
        Duration circuitOpenDuration
    ) {
        public RuntimeProfile {
            secretVersionReference = requireText(
                secretVersionReference,
                "secretVersionReference",
                128
            );
            secretVersionEffectiveFrom = Objects.requireNonNull(
                secretVersionEffectiveFrom,
                "secretVersionEffectiveFrom must not be null"
            );
            secretVersionExpiresAt = Objects.requireNonNull(
                secretVersionExpiresAt,
                "secretVersionExpiresAt must not be null"
            );
            if (!secretVersionEffectiveFrom.isBefore(secretVersionExpiresAt)) {
                throw new IllegalArgumentException("Secret version interval must be positive");
            }
            secretPolicyRevision = requireText(
                secretPolicyRevision,
                "secretPolicyRevision",
                160
            );
            if (killSwitchGeneration < 1) {
                throw new IllegalArgumentException("killSwitchGeneration must be positive");
            }
            killSwitchPolicyRevision = requireText(
                killSwitchPolicyRevision,
                "killSwitchPolicyRevision",
                160
            );
            costPolicyVersion = requireText(costPolicyVersion, "costPolicyVersion", 120);
            costPolicyEffectiveFrom = Objects.requireNonNull(
                costPolicyEffectiveFrom,
                "costPolicyEffectiveFrom must not be null"
            );
            costPolicyExpiresAt = Objects.requireNonNull(
                costPolicyExpiresAt,
                "costPolicyExpiresAt must not be null"
            );
            rateWindow = Objects.requireNonNull(rateWindow, "rateWindow must not be null");
            circuitOpenDuration = Objects.requireNonNull(
                circuitOpenDuration,
                "circuitOpenDuration must not be null"
            );
            if (perTenantRateLimit < 1 || globalRateLimit < perTenantRateLimit
                || circuitFailureThreshold < 1) {
                throw new IllegalArgumentException("runtime limits must be positive and coherent");
            }
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return normalized;
    }
}

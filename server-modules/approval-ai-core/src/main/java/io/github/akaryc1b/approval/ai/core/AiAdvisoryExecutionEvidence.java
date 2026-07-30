package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Hash-only routing, circuit and usage evidence for one coordinated advisory request. */
public record AiAdvisoryExecutionEvidence(
    String requestEvidenceHash,
    String subjectEvidenceHash,
    String resourceEvidenceHash,
    AiCapability capability,
    String routeEvidenceHash,
    AiVersionReferences versions,
    AiOutcomeClassification resultClassification,
    AiUsageEvidence usageEvidence,
    AiProviderCircuitBreaker.State circuitStateBefore,
    AiProviderCircuitBreaker.State circuitStateAfter,
    int skippedCandidates,
    boolean providerInvocationStarted,
    boolean postInvocationFallbackAttempted,
    String evidenceHash
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiAdvisoryExecutionEvidence {
        requestEvidenceHash = requireSha256(requestEvidenceHash, "requestEvidenceHash");
        subjectEvidenceHash = requireSha256(subjectEvidenceHash, "subjectEvidenceHash");
        resourceEvidenceHash = requireSha256(resourceEvidenceHash, "resourceEvidenceHash");
        capability = Objects.requireNonNull(capability, "capability must not be null");
        routeEvidenceHash = normalizeOptionalSha256(routeEvidenceHash, "routeEvidenceHash");
        resultClassification = Objects.requireNonNull(
            resultClassification,
            "resultClassification must not be null"
        );
        usageEvidence = Objects.requireNonNull(
            usageEvidence,
            "usageEvidence must not be null"
        );
        circuitStateBefore = Objects.requireNonNull(
            circuitStateBefore,
            "circuitStateBefore must not be null"
        );
        circuitStateAfter = Objects.requireNonNull(
            circuitStateAfter,
            "circuitStateAfter must not be null"
        );
        if (skippedCandidates < 0) {
            throw new IllegalArgumentException("skippedCandidates must not be negative");
        }
        if ((routeEvidenceHash == null) != (versions == null)) {
            throw new IllegalArgumentException(
                "route evidence and exact versions must be present or absent together"
            );
        }
        if (providerInvocationStarted && routeEvidenceHash == null) {
            throw new IllegalArgumentException(
                "provider invocation evidence requires exact route and versions"
            );
        }
        if (postInvocationFallbackAttempted) {
            throw new IllegalArgumentException(
                "post-invocation fallback is prohibited in the M6-D safe foundation"
            );
        }
        evidenceHash = requireSha256(evidenceHash, "evidenceHash");
        String expected = computeEvidenceHash(
            requestEvidenceHash,
            subjectEvidenceHash,
            resourceEvidenceHash,
            capability,
            routeEvidenceHash,
            versions,
            resultClassification,
            usageEvidence,
            circuitStateBefore,
            circuitStateAfter,
            skippedCandidates,
            providerInvocationStarted,
            postInvocationFallbackAttempted
        );
        if (!evidenceHash.equals(expected)) {
            throw new IllegalArgumentException("evidenceHash must match canonical evidence");
        }
    }

    public static AiAdvisoryExecutionEvidence create(
        AiServerRequestContext context,
        AiAuthorizedResource resource,
        AiCapability capability,
        AiCoordinatedAdvisoryOutcome coordinated
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(coordinated, "coordinated must not be null");
        String requestHash = hash(
            "request",
            context.tenantId(),
            context.requestId(),
            nullable(context.traceId())
        );
        String subjectHash = hash(
            "subject",
            context.tenantId(),
            context.operatorId()
        );
        String resourceHash = hash(
            "resource",
            resource.tenantId(),
            resource.resourceType().name(),
            resource.resourceId(),
            resource.authorizationReference()
        );
        AiProviderRoute route = coordinated.selectedRoute();
        String routeHash = route == null
            ? null
            : hash("route", route.routeId(), versionKey(route.versions()));
        AiVersionReferences exactVersions = route == null ? null : route.versions();
        String canonicalHash = computeEvidenceHash(
            requestHash,
            subjectHash,
            resourceHash,
            capability,
            routeHash,
            exactVersions,
            coordinated.outcome().classification(),
            coordinated.usageEvidence(),
            coordinated.circuitStateBefore(),
            coordinated.circuitStateAfter(),
            coordinated.skippedCandidates(),
            coordinated.providerInvocationStarted(),
            coordinated.postInvocationFallbackAttempted()
        );
        return new AiAdvisoryExecutionEvidence(
            requestHash,
            subjectHash,
            resourceHash,
            capability,
            routeHash,
            exactVersions,
            coordinated.outcome().classification(),
            coordinated.usageEvidence(),
            coordinated.circuitStateBefore(),
            coordinated.circuitStateAfter(),
            coordinated.skippedCandidates(),
            coordinated.providerInvocationStarted(),
            coordinated.postInvocationFallbackAttempted(),
            canonicalHash
        );
    }

    private static String computeEvidenceHash(
        String requestHash,
        String subjectHash,
        String resourceHash,
        AiCapability capability,
        String routeHash,
        AiVersionReferences versions,
        AiOutcomeClassification classification,
        AiUsageEvidence usage,
        AiProviderCircuitBreaker.State circuitBefore,
        AiProviderCircuitBreaker.State circuitAfter,
        int skippedCandidates,
        boolean invocationStarted,
        boolean fallbackAttempted
    ) {
        return hash(
            "execution-evidence",
            requestHash,
            subjectHash,
            resourceHash,
            capability.name(),
            nullable(routeHash),
            versionKey(versions),
            classification.name(),
            usageKey(usage),
            circuitBefore.name(),
            circuitAfter.name(),
            Integer.toString(skippedCandidates),
            Boolean.toString(invocationStarted),
            Boolean.toString(fallbackAttempted)
        );
    }

    private static String usageKey(AiUsageEvidence usage) {
        return String.join(
            "/",
            Integer.toString(usage.inputCharacters()),
            nullable(usage.inputTokens()),
            nullable(usage.outputTokens()),
            nullable(usage.totalTokens()),
            nullable(usage.providerLatencyMillis()),
            Long.toString(usage.observedLatencyMillis()),
            usage.estimatedCost() == null ? "" : usage.estimatedCost().toPlainString(),
            nullable(usage.currency()),
            usage.source().name()
        );
    }

    private static String versionKey(AiVersionReferences versions) {
        if (versions == null) {
            return "none";
        }
        return String.join(
            "/",
            versions.provider().providerId(),
            versions.provider().version(),
            versions.model().providerId(),
            versions.model().modelId(),
            versions.model().version(),
            versions.promptTemplate().templateId(),
            versions.promptTemplate().version(),
            versions.promptTemplate().contentHash(),
            versions.knowledgeSource().sourceId(),
            versions.knowledgeSource().version(),
            versions.knowledgeSource().contentHash(),
            Boolean.toString(versions.knowledgeSource().containsCustomerData()),
            versions.policy().policyId(),
            versions.policy().version(),
            versions.policy().contentHash(),
            versions.outputSchema().schemaId(),
            Integer.toString(versions.outputSchema().version())
        );
    }

    private static String hash(String... values) {
        String canonical = String.join("|", values);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String requireSha256(String value, String name) {
        String normalized = Objects.requireNonNull(value, name + " must not be null")
            .trim()
            .toLowerCase();
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
        return normalized;
    }

    private static String normalizeOptionalSha256(String value, String name) {
        return value == null || value.isBlank() ? null : requireSha256(value, name);
    }

    private static String nullable(Object value) {
        return value == null ? "" : value.toString();
    }
}

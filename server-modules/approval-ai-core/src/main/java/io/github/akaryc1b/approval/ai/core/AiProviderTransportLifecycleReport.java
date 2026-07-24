package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderTransportFixtureObservation;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingResult;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Offline transport lifecycle evidence with no dispatch or retry authority. */
public record AiProviderTransportLifecycleReport(
    AiVersionReferences.ProviderVersion providerVersion,
    AiProviderTransportMappingResult.Status mappingStatus,
    AiProviderTransportFixtureObservation.Shape observationShape,
    Status status,
    FailureClass failureClass,
    String requestEvidenceHash,
    String transportEnvelopeHash,
    String responseEvidenceHash,
    String expectedResponseSchemaHash,
    String observedResponseSchemaHash,
    String reportHash,
    boolean networkDispatchAttempted,
    boolean providerInvocationAttempted,
    boolean secretMaterialAccessed,
    boolean retryAuthorized,
    boolean postInvocationFallbackAuthorized,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderTransportLifecycleReport {
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        mappingStatus = Objects.requireNonNull(
            mappingStatus,
            "mappingStatus must not be null"
        );
        observationShape = Objects.requireNonNull(
            observationShape,
            "observationShape must not be null"
        );
        status = Objects.requireNonNull(status, "status must not be null");
        failureClass = Objects.requireNonNull(
            failureClass,
            "failureClass must not be null"
        );
        requestEvidenceHash = requireSha256(requestEvidenceHash, "requestEvidenceHash");
        transportEnvelopeHash = normalizeOptionalSha256(
            transportEnvelopeHash,
            "transportEnvelopeHash"
        );
        responseEvidenceHash = requireSha256(
            responseEvidenceHash,
            "responseEvidenceHash"
        );
        expectedResponseSchemaHash = requireSha256(
            expectedResponseSchemaHash,
            "expectedResponseSchemaHash"
        );
        observedResponseSchemaHash = normalizeOptionalSha256(
            observedResponseSchemaHash,
            "observedResponseSchemaHash"
        );
        reportHash = requireSha256(reportHash, "reportHash");
        if (networkDispatchAttempted
            || providerInvocationAttempted
            || secretMaterialAccessed) {
            throw new IllegalArgumentException(
                "transport lifecycle evidence must remain offline and zero-call"
            );
        }
        if (retryAuthorized || postInvocationFallbackAuthorized) {
            throw new IllegalArgumentException(
                "transport lifecycle evidence cannot authorize retry or fallback"
            );
        }
        if (productionEnablementAuthorized || approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "transport lifecycle evidence cannot authorize production or automation"
            );
        }
        if (status == Status.READY_FOR_OFFLINE_ASSERTION
            && failureClass != FailureClass.NONE) {
            throw new IllegalArgumentException(
                "ready lifecycle evidence cannot contain a failure classification"
            );
        }
        if (status != Status.READY_FOR_OFFLINE_ASSERTION
            && failureClass == FailureClass.NONE) {
            throw new IllegalArgumentException(
                "non-ready lifecycle evidence requires a failure classification"
            );
        }
        if (!reportHash.equals(computeHash(
            providerVersion,
            mappingStatus,
            observationShape,
            status,
            failureClass,
            requestEvidenceHash,
            transportEnvelopeHash,
            responseEvidenceHash,
            expectedResponseSchemaHash,
            observedResponseSchemaHash
        ))) {
            throw new IllegalArgumentException(
                "reportHash must match exact transport lifecycle evidence"
            );
        }
    }

    static AiProviderTransportLifecycleReport create(
        AiVersionReferences.ProviderVersion providerVersion,
        AiProviderTransportMappingResult.Status mappingStatus,
        AiProviderTransportFixtureObservation.Shape observationShape,
        Status status,
        FailureClass failureClass,
        String requestEvidenceHash,
        String transportEnvelopeHash,
        String responseEvidenceHash,
        String expectedResponseSchemaHash,
        String observedResponseSchemaHash
    ) {
        String hash = computeHash(
            providerVersion,
            mappingStatus,
            observationShape,
            status,
            failureClass,
            requestEvidenceHash,
            transportEnvelopeHash,
            responseEvidenceHash,
            expectedResponseSchemaHash,
            observedResponseSchemaHash
        );
        return new AiProviderTransportLifecycleReport(
            providerVersion,
            mappingStatus,
            observationShape,
            status,
            failureClass,
            requestEvidenceHash,
            transportEnvelopeHash,
            responseEvidenceHash,
            expectedResponseSchemaHash,
            observedResponseSchemaHash,
            hash,
            false,
            false,
            false,
            false,
            false,
            false,
            false
        );
    }

    public enum Status {
        READY_FOR_OFFLINE_ASSERTION,
        CANCELLED_BEFORE_DISPATCH,
        TIMED_OUT_BEFORE_DISPATCH,
        RESPONSE_REJECTED,
        BLOCKED
    }

    public enum FailureClass {
        NONE,
        MAPPING_REJECTED,
        MAPPING_UNSUPPORTED,
        MAPPING_UNKNOWN,
        CANCELLATION_REQUESTED,
        TIMEOUT_OBSERVED,
        MALFORMED_JSON,
        SCHEMA_DRIFT,
        UNKNOWN_FIELDS,
        BODY_TOO_LARGE,
        CONNECTION_ERROR,
        EMPTY_BODY,
        UNKNOWN_RESPONSE,
        RESPONSE_SCHEMA_MISMATCH
    }

    private static String computeHash(
        AiVersionReferences.ProviderVersion providerVersion,
        AiProviderTransportMappingResult.Status mappingStatus,
        AiProviderTransportFixtureObservation.Shape observationShape,
        Status status,
        FailureClass failureClass,
        String requestEvidenceHash,
        String transportEnvelopeHash,
        String responseEvidenceHash,
        String expectedResponseSchemaHash,
        String observedResponseSchemaHash
    ) {
        String canonical = String.join(
            "\n",
            providerVersion.providerId(),
            providerVersion.version(),
            mappingStatus.name(),
            observationShape.name(),
            status.name(),
            failureClass.name(),
            requireSha256(requestEvidenceHash, "requestEvidenceHash"),
            transportEnvelopeHash == null ? "none" : transportEnvelopeHash,
            requireSha256(responseEvidenceHash, "responseEvidenceHash"),
            requireSha256(expectedResponseSchemaHash, "expectedResponseSchemaHash"),
            observedResponseSchemaHash == null ? "none" : observedResponseSchemaHash
        );
        return sha256(canonical);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String normalizeOptionalSha256(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireSha256(value, name);
    }

    private static String requireSha256(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
        }
        return normalized;
    }
}

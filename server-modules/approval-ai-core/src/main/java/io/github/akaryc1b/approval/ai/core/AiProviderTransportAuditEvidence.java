package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportFixtureObservation;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingResult;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Redaction-safe transport audit evidence containing hashes and stable codes only. */
public record AiProviderTransportAuditEvidence(
    AiVersionReferences.ProviderVersion providerVersion,
    AiCapability capability,
    String endpointAuthorizationKey,
    String mapperAuthorizationKey,
    String canonicalPayloadHash,
    String signingInputHash,
    String requestEvidenceHash,
    String transportEnvelopeHash,
    String lifecycleReportHash,
    AiProviderTransportMappingResult.Status mappingStatus,
    AiProviderTransportLifecycleReport.Status lifecycleStatus,
    AiProviderTransportFixtureObservation.Shape responseShape,
    List<String> codes,
    String auditHash,
    boolean rawRequestStored,
    boolean rawResponseStored,
    boolean headerValuesStored,
    boolean secretMaterialStored,
    boolean networkPayloadStored,
    boolean productionEnablementAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderTransportAuditEvidence {
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        capability = Objects.requireNonNull(capability, "capability must not be null");
        endpointAuthorizationKey = requireText(
            endpointAuthorizationKey,
            "endpointAuthorizationKey",
            500
        );
        mapperAuthorizationKey = requireText(
            mapperAuthorizationKey,
            "mapperAuthorizationKey",
            300
        );
        canonicalPayloadHash = requireSha256(
            canonicalPayloadHash,
            "canonicalPayloadHash"
        );
        signingInputHash = requireSha256(signingInputHash, "signingInputHash");
        requestEvidenceHash = requireSha256(requestEvidenceHash, "requestEvidenceHash");
        transportEnvelopeHash = normalizeOptionalSha256(
            transportEnvelopeHash,
            "transportEnvelopeHash"
        );
        lifecycleReportHash = requireSha256(
            lifecycleReportHash,
            "lifecycleReportHash"
        );
        mappingStatus = Objects.requireNonNull(
            mappingStatus,
            "mappingStatus must not be null"
        );
        lifecycleStatus = Objects.requireNonNull(
            lifecycleStatus,
            "lifecycleStatus must not be null"
        );
        responseShape = Objects.requireNonNull(
            responseShape,
            "responseShape must not be null"
        );
        codes = codes == null ? List.of() : List.copyOf(codes);
        if (codes.size() > 100) {
            throw new IllegalArgumentException("codes must be bounded");
        }
        auditHash = requireSha256(auditHash, "auditHash");
        if (rawRequestStored
            || rawResponseStored
            || headerValuesStored
            || secretMaterialStored
            || networkPayloadStored) {
            throw new IllegalArgumentException(
                "transport audit evidence must remain redaction-safe and hash-only"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "transport audit evidence cannot authorize production enablement"
            );
        }
        if (!auditHash.equals(computeHash(
            providerVersion,
            capability,
            endpointAuthorizationKey,
            mapperAuthorizationKey,
            canonicalPayloadHash,
            signingInputHash,
            requestEvidenceHash,
            transportEnvelopeHash,
            lifecycleReportHash,
            mappingStatus,
            lifecycleStatus,
            responseShape,
            codes
        ))) {
            throw new IllegalArgumentException("auditHash must match exact transport evidence");
        }
    }

    public static AiProviderTransportAuditEvidence create(
        AiProviderTransportMappingRequest request,
        AiProviderTransportMappingResult mapping,
        AiProviderTransportLifecycleReport lifecycle,
        AiProviderTransportFixtureObservation observation,
        List<String> codes
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(mapping, "mapping must not be null");
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(observation, "observation must not be null");
        List<String> copy = codes == null ? List.of() : List.copyOf(codes);
        String hash = computeHash(
            request.providerVersion(),
            request.capability(),
            request.endpointAuthorizationKey(),
            request.mapperAuthorizationKey(),
            request.canonicalPayloadHash(),
            request.signingInputHash(),
            mapping.requestEvidenceHash(),
            mapping.transportEnvelopeHash(),
            lifecycle.reportHash(),
            mapping.status(),
            lifecycle.status(),
            observation.shape(),
            copy
        );
        return new AiProviderTransportAuditEvidence(
            request.providerVersion(),
            request.capability(),
            request.endpointAuthorizationKey(),
            request.mapperAuthorizationKey(),
            request.canonicalPayloadHash(),
            request.signingInputHash(),
            mapping.requestEvidenceHash(),
            mapping.transportEnvelopeHash(),
            lifecycle.reportHash(),
            mapping.status(),
            lifecycle.status(),
            observation.shape(),
            copy,
            hash,
            false,
            false,
            false,
            false,
            false,
            false
        );
    }

    private static String computeHash(
        AiVersionReferences.ProviderVersion providerVersion,
        AiCapability capability,
        String endpointAuthorizationKey,
        String mapperAuthorizationKey,
        String canonicalPayloadHash,
        String signingInputHash,
        String requestEvidenceHash,
        String transportEnvelopeHash,
        String lifecycleReportHash,
        AiProviderTransportMappingResult.Status mappingStatus,
        AiProviderTransportLifecycleReport.Status lifecycleStatus,
        AiProviderTransportFixtureObservation.Shape responseShape,
        List<String> codes
    ) {
        List<String> parts = new ArrayList<>();
        parts.add(providerVersion.providerId());
        parts.add(providerVersion.version());
        parts.add(capability.name());
        parts.add(endpointAuthorizationKey);
        parts.add(mapperAuthorizationKey);
        parts.add(canonicalPayloadHash);
        parts.add(signingInputHash);
        parts.add(requestEvidenceHash);
        parts.add(transportEnvelopeHash == null ? "none" : transportEnvelopeHash);
        parts.add(lifecycleReportHash);
        parts.add(mappingStatus.name());
        parts.add(lifecycleStatus.name());
        parts.add(responseShape.name());
        codes.stream().sorted().forEach(parts::add);
        return sha256(String.join("\n", parts));
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

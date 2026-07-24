package io.github.akaryc1b.approval.ai.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Offline endpoint trust assessment over precomputed DNS and TLS evidence. */
public record AiEndpointTrustAssessment(
    String policyHash,
    Status status,
    List<Fault> faults,
    String assessmentHash,
    boolean dnsLookupAttempted,
    boolean tlsHandshakeAttempted,
    boolean networkCallAttempted,
    boolean productionEnablementAuthorized
) {

    public AiEndpointTrustAssessment {
        policyHash = requireText(policyHash, "policyHash", 64);
        status = Objects.requireNonNull(status, "status must not be null");
        faults = faults == null ? List.of() : List.copyOf(faults);
        if (faults.size() > 50) {
            throw new IllegalArgumentException("faults must be bounded");
        }
        assessmentHash = requireText(assessmentHash, "assessmentHash", 64);
        if (dnsLookupAttempted || tlsHandshakeAttempted || networkCallAttempted) {
            throw new IllegalArgumentException(
                "endpoint trust assessment must remain offline and zero-call"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "endpoint trust assessment cannot authorize production enablement"
            );
        }
        if (status == Status.TRUSTED_FOR_REVIEW && !faults.isEmpty()) {
            throw new IllegalArgumentException(
                "TRUSTED_FOR_REVIEW cannot contain faults"
            );
        }
        if (status == Status.BLOCKED && faults.isEmpty()) {
            throw new IllegalArgumentException("BLOCKED assessment requires faults");
        }
    }

    public static AiEndpointTrustAssessment assess(
        AiEndpointTrustPolicy policy,
        AiDnsResolutionEvidence dns,
        AiTlsPeerEvidence tls
    ) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(dns, "dns must not be null");
        Objects.requireNonNull(tls, "tls must not be null");
        List<Fault> faults = new ArrayList<>();
        if (!policy.endpointAuthorizationKey().equals(dns.endpointAuthorizationKey())
            || !policy.endpointAuthorizationKey().equals(tls.endpointAuthorizationKey())) {
            faults.add(Fault.ENDPOINT_IDENTITY_MISMATCH);
        }
        if (!policy.exactHost().equals(dns.host()) || !policy.exactHost().equals(tls.host())) {
            faults.add(Fault.HOST_MISMATCH);
        }
        if (dns.status() != AiDnsResolutionEvidence.Status.PUBLIC_SET_MATCHED) {
            faults.add(switch (dns.status()) {
                case PRIVATE_OR_LOCAL_ADDRESS -> Fault.PRIVATE_OR_LOCAL_ADDRESS;
                case REBINDING_DETECTED -> Fault.DNS_REBINDING_DETECTED;
                case EMPTY_RESULT -> Fault.EMPTY_DNS_RESULT;
                case UNKNOWN -> Fault.DNS_UNKNOWN;
                case PUBLIC_SET_MATCHED -> throw new IllegalStateException("unreachable");
            });
        }
        if (!policy.allowedAddressEvidenceHashes().containsAll(dns.addressEvidenceHashes())) {
            faults.add(Fault.ADDRESS_PIN_MISMATCH);
        }
        if (dns.observedTtlSeconds() > policy.maximumTtlSeconds()) {
            faults.add(Fault.DNS_TTL_EXCEEDED);
        }
        if (tls.status() != AiTlsPeerEvidence.Status.CHAIN_HOST_AND_PIN_MATCHED) {
            faults.add(switch (tls.status()) {
                case HOSTNAME_MISMATCH -> Fault.TLS_HOSTNAME_MISMATCH;
                case CHAIN_INVALID -> Fault.TLS_CHAIN_INVALID;
                case CERTIFICATE_EXPIRED -> Fault.TLS_CERTIFICATE_EXPIRED;
                case PIN_MISMATCH -> Fault.TLS_PIN_MISMATCH;
                case REDIRECT_OBSERVED -> Fault.REDIRECT_OBSERVED;
                case UNKNOWN -> Fault.TLS_UNKNOWN;
                case CHAIN_HOST_AND_PIN_MATCHED -> throw new IllegalStateException("unreachable");
            });
        }
        if (tls.certificateSpkiSha256() == null
            || !policy.allowedCertificateSpkiHashes().contains(tls.certificateSpkiSha256())) {
            faults.add(Fault.TLS_PIN_MISMATCH);
        }
        if (tls.redirectObserved()) {
            faults.add(Fault.REDIRECT_OBSERVED);
        }
        List<Fault> normalized = faults.stream().distinct().sorted().toList();
        Status status = normalized.isEmpty() ? Status.TRUSTED_FOR_REVIEW : Status.BLOCKED;
        String hash = hash(
            policy.contentHash(),
            status,
            normalized,
            dns.evidenceHash(),
            tls.evidenceHash()
        );
        return new AiEndpointTrustAssessment(
            policy.contentHash(),
            status,
            normalized,
            hash,
            false,
            false,
            false,
            false
        );
    }

    public enum Status {
        TRUSTED_FOR_REVIEW,
        BLOCKED
    }

    public enum Fault {
        ENDPOINT_IDENTITY_MISMATCH,
        HOST_MISMATCH,
        PRIVATE_OR_LOCAL_ADDRESS,
        DNS_REBINDING_DETECTED,
        EMPTY_DNS_RESULT,
        DNS_UNKNOWN,
        ADDRESS_PIN_MISMATCH,
        DNS_TTL_EXCEEDED,
        TLS_HOSTNAME_MISMATCH,
        TLS_CHAIN_INVALID,
        TLS_CERTIFICATE_EXPIRED,
        TLS_PIN_MISMATCH,
        REDIRECT_OBSERVED,
        TLS_UNKNOWN
    }

    private static String hash(
        String policyHash,
        Status status,
        List<Fault> faults,
        String dnsHash,
        String tlsHash
    ) {
        String canonical = policyHash + '|' + status.name() + '|'
            + faults.stream().map(Enum::name).sorted().toList() + '|'
            + dnsHash + '|' + tlsHash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
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
}

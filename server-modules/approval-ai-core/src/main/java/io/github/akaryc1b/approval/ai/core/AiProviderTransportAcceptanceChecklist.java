package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Non-executable transport acceptance checklist for later production review. */
public record AiProviderTransportAcceptanceChecklist(
    String checklistId,
    String checklistVersion,
    AiVersionReferences.ProviderVersion providerVersion,
    String deploymentSnapshotHash,
    String activationReviewHash,
    String mapperContractHash,
    String canonicalizationPolicyHash,
    String lifecycleDrillHash,
    String malformedResponseDrillHash,
    String schemaDriftDrillHash,
    String cancellationDrillHash,
    String transportAuditHash,
    List<GateEvidence> gates,
    Status status,
    String checklistHash,
    boolean applyAuthorized,
    boolean networkAccessAuthorized,
    boolean providerInvocationAuthorized,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> REQUIRED_GATES = Set.of(
        "CANONICAL_PAYLOAD_DETERMINISTIC",
        "SECRET_MATERIAL_ABSENT",
        "NETWORK_DISPATCH_PROHIBITED",
        "MALFORMED_RESPONSE_FAILS_CLOSED",
        "SCHEMA_DRIFT_FAILS_CLOSED",
        "TIMEOUT_CANCELLATION_FAILS_CLOSED",
        "REDACTION_AUDIT_COMPLETE",
        "TWO_PERSON_REVIEW_BOUND"
    );

    public AiProviderTransportAcceptanceChecklist {
        checklistId = requireText(checklistId, "checklistId", 160);
        checklistVersion = requireText(checklistVersion, "checklistVersion", 120);
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        deploymentSnapshotHash = requireSha256(
            deploymentSnapshotHash,
            "deploymentSnapshotHash"
        );
        activationReviewHash = requireSha256(
            activationReviewHash,
            "activationReviewHash"
        );
        mapperContractHash = requireSha256(mapperContractHash, "mapperContractHash");
        canonicalizationPolicyHash = requireSha256(
            canonicalizationPolicyHash,
            "canonicalizationPolicyHash"
        );
        lifecycleDrillHash = requireSha256(lifecycleDrillHash, "lifecycleDrillHash");
        malformedResponseDrillHash = requireSha256(
            malformedResponseDrillHash,
            "malformedResponseDrillHash"
        );
        schemaDriftDrillHash = requireSha256(
            schemaDriftDrillHash,
            "schemaDriftDrillHash"
        );
        cancellationDrillHash = requireSha256(
            cancellationDrillHash,
            "cancellationDrillHash"
        );
        transportAuditHash = requireSha256(transportAuditHash, "transportAuditHash");
        gates = gates == null ? List.of() : List.copyOf(gates);
        if (gates.size() > 100) {
            throw new IllegalArgumentException("gates must be bounded");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        checklistHash = requireSha256(checklistHash, "checklistHash");
        if (applyAuthorized
            || networkAccessAuthorized
            || providerInvocationAuthorized
            || productionEnablementAuthorized
            || approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "transport acceptance checklists cannot authorize execution or production"
            );
        }
        Status computedStatus = computeStatus(gates);
        if (status != computedStatus) {
            throw new IllegalArgumentException(
                "checklist status must match exact required gate evidence"
            );
        }
        if (!checklistHash.equals(computeHash(
            checklistId,
            checklistVersion,
            providerVersion,
            deploymentSnapshotHash,
            activationReviewHash,
            mapperContractHash,
            canonicalizationPolicyHash,
            lifecycleDrillHash,
            malformedResponseDrillHash,
            schemaDriftDrillHash,
            cancellationDrillHash,
            transportAuditHash,
            gates,
            status
        ))) {
            throw new IllegalArgumentException(
                "checklistHash must match exact acceptance evidence"
            );
        }
    }

    public static AiProviderTransportAcceptanceChecklist create(
        String checklistId,
        String checklistVersion,
        AiVersionReferences.ProviderVersion providerVersion,
        String deploymentSnapshotHash,
        String activationReviewHash,
        String mapperContractHash,
        String canonicalizationPolicyHash,
        String lifecycleDrillHash,
        String malformedResponseDrillHash,
        String schemaDriftDrillHash,
        String cancellationDrillHash,
        String transportAuditHash,
        List<GateEvidence> gates
    ) {
        List<GateEvidence> copy = gates == null ? List.of() : List.copyOf(gates);
        Status status = computeStatus(copy);
        String hash = computeHash(
            checklistId,
            checklistVersion,
            providerVersion,
            deploymentSnapshotHash,
            activationReviewHash,
            mapperContractHash,
            canonicalizationPolicyHash,
            lifecycleDrillHash,
            malformedResponseDrillHash,
            schemaDriftDrillHash,
            cancellationDrillHash,
            transportAuditHash,
            copy,
            status
        );
        return new AiProviderTransportAcceptanceChecklist(
            checklistId,
            checklistVersion,
            providerVersion,
            deploymentSnapshotHash,
            activationReviewHash,
            mapperContractHash,
            canonicalizationPolicyHash,
            lifecycleDrillHash,
            malformedResponseDrillHash,
            schemaDriftDrillHash,
            cancellationDrillHash,
            transportAuditHash,
            copy,
            status,
            hash,
            false,
            false,
            false,
            false,
            false
        );
    }

    public Stage stage() {
        return Stage.NON_EXECUTABLE_TRANSPORT_ACCEPTANCE;
    }

    public enum Stage {
        NON_EXECUTABLE_TRANSPORT_ACCEPTANCE
    }

    public enum Status {
        REVIEW_COMPLETE,
        BLOCKED
    }

    public enum Category {
        SECURITY,
        PLATFORM,
        OPERATIONS
    }

    public enum Decision {
        PASSED,
        FAILED,
        MISSING
    }

    public record GateEvidence(
        String gateId,
        Category category,
        Decision decision,
        String evidenceHash
    ) {
        public GateEvidence {
            gateId = requireText(gateId, "gateId", 120);
            if (!gateId.matches("[A-Z0-9_]+")) {
                throw new IllegalArgumentException(
                    "gateId must use a stable uppercase identifier"
                );
            }
            category = Objects.requireNonNull(category, "category must not be null");
            decision = Objects.requireNonNull(decision, "decision must not be null");
            evidenceHash = requireSha256(evidenceHash, "evidenceHash");
        }
    }

    private static Status computeStatus(List<GateEvidence> gates) {
        Set<String> seenGates = new HashSet<>();
        Set<Category> passedCategories = new HashSet<>();
        boolean allPassed = true;
        for (GateEvidence gate : gates) {
            Objects.requireNonNull(gate, "gate must not be null");
            if (!seenGates.add(gate.gateId())) {
                throw new IllegalArgumentException("gate IDs must be unique");
            }
            if (gate.decision() != Decision.PASSED) {
                allPassed = false;
            } else {
                passedCategories.add(gate.category());
            }
        }
        return allPassed
            && seenGates.containsAll(REQUIRED_GATES)
            && passedCategories.containsAll(Set.of(Category.values()))
            ? Status.REVIEW_COMPLETE
            : Status.BLOCKED;
    }

    private static String computeHash(
        String checklistId,
        String checklistVersion,
        AiVersionReferences.ProviderVersion providerVersion,
        String deploymentSnapshotHash,
        String activationReviewHash,
        String mapperContractHash,
        String canonicalizationPolicyHash,
        String lifecycleDrillHash,
        String malformedResponseDrillHash,
        String schemaDriftDrillHash,
        String cancellationDrillHash,
        String transportAuditHash,
        List<GateEvidence> gates,
        Status status
    ) {
        List<String> parts = new ArrayList<>();
        parts.add(checklistId);
        parts.add(checklistVersion);
        parts.add(providerVersion.providerId());
        parts.add(providerVersion.version());
        parts.add(deploymentSnapshotHash);
        parts.add(activationReviewHash);
        parts.add(mapperContractHash);
        parts.add(canonicalizationPolicyHash);
        parts.add(lifecycleDrillHash);
        parts.add(malformedResponseDrillHash);
        parts.add(schemaDriftDrillHash);
        parts.add(cancellationDrillHash);
        parts.add(transportAuditHash);
        gates.stream()
            .sorted(java.util.Comparator.comparing(GateEvidence::gateId))
            .forEach(gate -> {
                parts.add(gate.gateId());
                parts.add(gate.category().name());
                parts.add(gate.decision().name());
                parts.add(gate.evidenceHash());
            });
        parts.add(status.name());
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

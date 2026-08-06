package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Closed, typed and permanently non-executable controlled-automation Proposal.
 *
 * <p>The Proposal carries evidence and human-readable risk metadata only. It is not a command,
 * credential, permission token, confirmation token, operator identity or execution capability.</p>
 */
public final class ControlledAutomationProposal {

    static final int MAXIMUM_PARAMETERS = 16;
    private static final int MAXIMUM_TEXT_PARAMETER_LENGTH = 500;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ACTION_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{2,95}");
    private static final Pattern RESOURCE_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");
    private static final Pattern PARAMETER_NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");
    private static final Pattern ENUM_VALUE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}");
    private static final Pattern FORBIDDEN_PARAMETER_NAME = Pattern.compile(
        ".*(url|uri|http|sql|script|expression|class|module|secret|token|credential|password|key).*",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXECUTABLE_TEXT = Pattern.compile(
        "(?is).*(://|<script|javascript:|\\$\\{|#\\{|"
            + "\\b(select|insert|update|delete|drop|alter|exec|execute)\\b).*"
    );

    private final UUID proposalId;
    private final String tenantEvidenceHash;
    private final String operatorEvidenceHash;
    private final SourceAdvisoryEvidence sourceAdvisory;
    private final String canonicalActionType;
    private final Map<String, ParameterBinding> parameters;
    private final TargetResourceEvidence targetResource;
    private final String whitelistVersion;
    private final PolicyEvidence policy;
    private final ControlledAutomationActionWhitelist.RiskClassification riskClassification;
    private final String sideEffectSummary;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final ProposalStatus status;
    private final boolean requiresHumanConfirmation;
    private final ControlledAutomationActionWhitelist.ReauthenticationRequirement
        reauthenticationRequirement;
    private final Authority authority;
    private final String lineageHash;

    private ControlledAutomationProposal(
        UUID proposalId,
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        SourceAdvisoryEvidence sourceAdvisory,
        String canonicalActionType,
        Map<String, ParameterBinding> parameters,
        TargetResourceEvidence targetResource,
        String whitelistVersion,
        PolicyEvidence policy,
        ControlledAutomationActionWhitelist.RiskClassification riskClassification,
        String sideEffectSummary,
        Instant createdAt,
        Instant expiresAt,
        ProposalStatus status,
        boolean requiresHumanConfirmation,
        ControlledAutomationActionWhitelist.ReauthenticationRequirement
            reauthenticationRequirement,
        Authority authority,
        String lineageHash
    ) {
        this.proposalId = Objects.requireNonNull(proposalId, "proposalId must not be null");
        this.tenantEvidenceHash = requireSha256(
            tenantEvidenceHash,
            "tenantEvidenceHash"
        );
        this.operatorEvidenceHash = requireSha256(
            operatorEvidenceHash,
            "operatorEvidenceHash"
        );
        this.sourceAdvisory = Objects.requireNonNull(
            sourceAdvisory,
            "sourceAdvisory must not be null"
        );
        this.canonicalActionType = requireCanonicalActionType(canonicalActionType);
        this.parameters = immutableParameters(parameters);
        this.targetResource = Objects.requireNonNull(
            targetResource,
            "targetResource must not be null"
        );
        this.whitelistVersion = requireVersion(whitelistVersion, "whitelistVersion");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.riskClassification = Objects.requireNonNull(
            riskClassification,
            "riskClassification must not be null"
        );
        this.sideEffectSummary = requireHumanSummary(
            sideEffectSummary,
            "sideEffectSummary"
        );
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.requiresHumanConfirmation = requiresHumanConfirmation;
        this.reauthenticationRequirement = Objects.requireNonNull(
            reauthenticationRequirement,
            "reauthenticationRequirement must not be null"
        );
        this.authority = Objects.requireNonNull(authority, "authority must not be null");
        this.lineageHash = requireSha256(lineageHash, "lineageHash");

        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (status != ProposalStatus.PROPOSED
            || !requiresHumanConfirmation
            || reauthenticationRequirement
                != ControlledAutomationActionWhitelist.ReauthenticationRequirement.REQUIRED
            || authority != Authority.NON_EXECUTABLE_PROPOSAL) {
            throw new IllegalArgumentException(
                "P1 Proposal must be proposed, non-executable and require human confirmation"
            );
        }
        String expectedLineageHash = computeLineageHash(
            proposalId,
            tenantEvidenceHash,
            operatorEvidenceHash,
            sourceAdvisory,
            canonicalActionType,
            this.parameters,
            targetResource,
            whitelistVersion,
            policy,
            riskClassification,
            sideEffectSummary,
            createdAt,
            expiresAt,
            status,
            requiresHumanConfirmation,
            reauthenticationRequirement,
            authority
        );
        if (!this.lineageHash.equals(expectedLineageHash)) {
            throw new IllegalArgumentException(
                "lineageHash must match the exact canonical Proposal"
            );
        }
    }

    static ControlledAutomationProposal create(
        UUID proposalId,
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        SourceAdvisoryEvidence sourceAdvisory,
        String canonicalActionType,
        Map<String, ParameterBinding> parameters,
        TargetResourceEvidence targetResource,
        String whitelistVersion,
        PolicyEvidence policy,
        ControlledAutomationActionWhitelist.RiskClassification riskClassification,
        String sideEffectSummary,
        Instant createdAt,
        Instant expiresAt,
        ControlledAutomationActionWhitelist.ReauthenticationRequirement
            reauthenticationRequirement
    ) {
        Map<String, ParameterBinding> immutableParameters = immutableParameters(parameters);
        String lineageHash = computeLineageHash(
            proposalId,
            tenantEvidenceHash,
            operatorEvidenceHash,
            sourceAdvisory,
            canonicalActionType,
            immutableParameters,
            targetResource,
            whitelistVersion,
            policy,
            riskClassification,
            sideEffectSummary,
            createdAt,
            expiresAt,
            ProposalStatus.PROPOSED,
            true,
            reauthenticationRequirement,
            Authority.NON_EXECUTABLE_PROPOSAL
        );
        return new ControlledAutomationProposal(
            proposalId,
            tenantEvidenceHash,
            operatorEvidenceHash,
            sourceAdvisory,
            canonicalActionType,
            immutableParameters,
            targetResource,
            whitelistVersion,
            policy,
            riskClassification,
            sideEffectSummary,
            createdAt,
            expiresAt,
            ProposalStatus.PROPOSED,
            true,
            reauthenticationRequirement,
            Authority.NON_EXECUTABLE_PROPOSAL,
            lineageHash
        );
    }

    public UUID proposalId() {
        return proposalId;
    }

    public String tenantEvidenceHash() {
        return tenantEvidenceHash;
    }

    public String operatorEvidenceHash() {
        return operatorEvidenceHash;
    }

    public SourceAdvisoryEvidence sourceAdvisory() {
        return sourceAdvisory;
    }

    public String canonicalActionType() {
        return canonicalActionType;
    }

    public Map<String, ParameterBinding> parameters() {
        return parameters;
    }

    public TargetResourceEvidence targetResource() {
        return targetResource;
    }

    public String whitelistVersion() {
        return whitelistVersion;
    }

    public PolicyEvidence policy() {
        return policy;
    }

    public ControlledAutomationActionWhitelist.RiskClassification riskClassification() {
        return riskClassification;
    }

    public String sideEffectSummary() {
        return sideEffectSummary;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public ProposalStatus status() {
        return status;
    }

    public boolean requiresHumanConfirmation() {
        return requiresHumanConfirmation;
    }

    public ControlledAutomationActionWhitelist.ReauthenticationRequirement
        reauthenticationRequirement() {
        return reauthenticationRequirement;
    }

    public Authority authority() {
        return authority;
    }

    public String lineageHash() {
        return lineageHash;
    }

    public enum Authority {
        NON_EXECUTABLE_PROPOSAL
    }

    public enum ProposalStatus {
        PROPOSED,
        INELIGIBLE,
        ELIGIBLE,
        EXPIRED,
        STALE,
        CANCELLED,
        CONFIRMED
    }

    public enum CreationTrigger {
        EXPLICIT_USER_ACTION,
        ADVISORY_CALLBACK,
        PAGE_LOAD,
        LISTENER,
        POLLING,
        PROVIDER_CALLBACK,
        SCHEDULED,
        WEBHOOK
    }

    public enum ParameterSource {
        SERVER_RESOURCE_STATE,
        EXPLICIT_USER_INPUT,
        CLOSED_SERVER_MAPPING
    }

    public sealed interface TypedParameter permits BooleanParameter, EnumParameter,
        IdentifierParameter, InstantParameter, IntegerParameter, TextParameter {

        ControlledAutomationActionWhitelist.ParameterType type();

        String canonicalValue();
    }

    public record BooleanParameter(boolean value) implements TypedParameter {
        @Override
        public ControlledAutomationActionWhitelist.ParameterType type() {
            return ControlledAutomationActionWhitelist.ParameterType.BOOLEAN;
        }

        @Override
        public String canonicalValue() {
            return Boolean.toString(value);
        }
    }

    public record EnumParameter(String value) implements TypedParameter {
        public EnumParameter {
            value = requireEnumValue(value);
        }

        @Override
        public ControlledAutomationActionWhitelist.ParameterType type() {
            return ControlledAutomationActionWhitelist.ParameterType.ENUM;
        }

        @Override
        public String canonicalValue() {
            return value;
        }
    }

    public record IdentifierParameter(String value) implements TypedParameter {
        public IdentifierParameter {
            value = requireIdentifier(value);
        }

        @Override
        public ControlledAutomationActionWhitelist.ParameterType type() {
            return ControlledAutomationActionWhitelist.ParameterType.IDENTIFIER;
        }

        @Override
        public String canonicalValue() {
            return value;
        }
    }

    public record InstantParameter(Instant value) implements TypedParameter {
        public InstantParameter {
            value = Objects.requireNonNull(value, "value must not be null");
        }

        @Override
        public ControlledAutomationActionWhitelist.ParameterType type() {
            return ControlledAutomationActionWhitelist.ParameterType.INSTANT;
        }

        @Override
        public String canonicalValue() {
            return value.toString();
        }
    }

    public record IntegerParameter(long value) implements TypedParameter {
        @Override
        public ControlledAutomationActionWhitelist.ParameterType type() {
            return ControlledAutomationActionWhitelist.ParameterType.INTEGER;
        }

        @Override
        public String canonicalValue() {
            return Long.toString(value);
        }
    }

    public record TextParameter(String value) implements TypedParameter {
        public TextParameter {
            value = requireTextParameter(value);
        }

        @Override
        public ControlledAutomationActionWhitelist.ParameterType type() {
            return ControlledAutomationActionWhitelist.ParameterType.TEXT;
        }

        @Override
        public String canonicalValue() {
            return value;
        }
    }

    public record ParameterBinding(
        String name,
        TypedParameter value,
        ParameterSource source
    ) {
        public ParameterBinding {
            name = requireParameterName(name);
            value = Objects.requireNonNull(value, "value must not be null");
            source = Objects.requireNonNull(source, "source must not be null");
        }
    }

    public record SourceAdvisoryEvidence(
        UUID evidenceId,
        String evidenceHash,
        AiVersionReferences versionReferences
    ) {
        public SourceAdvisoryEvidence {
            evidenceId = Objects.requireNonNull(evidenceId, "evidenceId must not be null");
            evidenceHash = requireSha256(evidenceHash, "sourceEvidenceHash");
            versionReferences = Objects.requireNonNull(
                versionReferences,
                "versionReferences must not be null"
            );
        }
    }

    public record TargetResourceEvidence(
        String resourceType,
        String resourceIdEvidenceHash,
        String expectedState,
        long expectedVersion,
        Instant observedAt,
        String evidenceHash
    ) {
        public TargetResourceEvidence {
            resourceType = requireResourceType(resourceType);
            resourceIdEvidenceHash = requireSha256(
                resourceIdEvidenceHash,
                "resourceIdEvidenceHash"
            );
            expectedState = requireExpectedState(expectedState);
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            evidenceHash = requireSha256(evidenceHash, "resourceEvidenceHash");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
            String expectedHash = hashTuple(
                "controlled-automation-resource-v1",
                resourceType,
                resourceIdEvidenceHash,
                expectedState,
                Long.toString(expectedVersion),
                observedAt.toString()
            );
            if (!evidenceHash.equals(expectedHash)) {
                throw new IllegalArgumentException(
                    "resource evidence hash must match the exact expected state"
                );
            }
        }

        public static TargetResourceEvidence create(
            String resourceType,
            String resourceIdEvidenceHash,
            String expectedState,
            long expectedVersion,
            Instant observedAt
        ) {
            String normalizedResourceType = requireResourceType(resourceType);
            String normalizedResourceIdHash = requireSha256(
                resourceIdEvidenceHash,
                "resourceIdEvidenceHash"
            );
            String normalizedState = requireExpectedState(expectedState);
            Instant exactObservedAt = Objects.requireNonNull(
                observedAt,
                "observedAt must not be null"
            );
            return new TargetResourceEvidence(
                normalizedResourceType,
                normalizedResourceIdHash,
                normalizedState,
                expectedVersion,
                exactObservedAt,
                hashTuple(
                    "controlled-automation-resource-v1",
                    normalizedResourceType,
                    normalizedResourceIdHash,
                    normalizedState,
                    Long.toString(expectedVersion),
                    exactObservedAt.toString()
                )
            );
        }
    }

    public record PolicyEvidence(String version, String evidenceHash) {
        public PolicyEvidence {
            version = requireVersion(version, "policyVersion");
            evidenceHash = requireSha256(evidenceHash, "policyEvidenceHash");
        }
    }

    static String requireCanonicalActionType(String value) {
        return requirePattern(value, "canonicalActionType", ACTION_TYPE, 96);
    }

    static String requireResourceType(String value) {
        return requirePattern(value, "resourceType", RESOURCE_TYPE, 64);
    }

    static String requireParameterName(String value) {
        String normalized = requirePattern(value, "parameterName", PARAMETER_NAME, 64);
        if (FORBIDDEN_PARAMETER_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "parameter names cannot carry executable or credential material"
            );
        }
        return normalized;
    }

    static String requireEnumValue(String value) {
        return requirePattern(value, "enumValue", ENUM_VALUE, 64);
    }

    static String requireVersion(String value, String name) {
        return requireText(value, name, 160);
    }

    static String requireHumanSummary(String value, String name) {
        String normalized = requireText(value, name, 1_000);
        if (EXECUTABLE_TEXT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                name + " cannot contain URL, SQL, script or executable expression material"
            );
        }
        return normalized;
    }

    static String requireSha256(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                name + " must be a canonical lowercase SHA-256 hex digest"
            );
        }
        return value;
    }

    static String hashTuple(String domain, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthFramed(digest, domain);
            for (String value : values) {
                updateLengthFramed(digest, Objects.requireNonNull(value));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String computeLineageHash(
        UUID proposalId,
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        SourceAdvisoryEvidence sourceAdvisory,
        String canonicalActionType,
        Map<String, ParameterBinding> parameters,
        TargetResourceEvidence targetResource,
        String whitelistVersion,
        PolicyEvidence policy,
        ControlledAutomationActionWhitelist.RiskClassification riskClassification,
        String sideEffectSummary,
        Instant createdAt,
        Instant expiresAt,
        ProposalStatus status,
        boolean requiresHumanConfirmation,
        ControlledAutomationActionWhitelist.ReauthenticationRequirement
            reauthenticationRequirement,
        Authority authority
    ) {
        List<String> values = new ArrayList<>();
        values.add(proposalId.toString());
        values.add(tenantEvidenceHash);
        values.add(operatorEvidenceHash);
        values.add(sourceAdvisory.evidenceId().toString());
        values.add(sourceAdvisory.evidenceHash());
        values.add(versionHash(sourceAdvisory.versionReferences()));
        values.add(canonicalActionType);
        parameters.values().stream()
            .sorted(Comparator.comparing(ParameterBinding::name))
            .forEach(parameter -> {
                values.add(parameter.name());
                values.add(parameter.value().type().name());
                values.add(parameter.value().canonicalValue());
                values.add(parameter.source().name());
            });
        values.add(targetResource.evidenceHash());
        values.add(whitelistVersion);
        values.add(policy.version());
        values.add(policy.evidenceHash());
        values.add(riskClassification.name());
        values.add(sideEffectSummary);
        values.add(createdAt.toString());
        values.add(expiresAt.toString());
        values.add(status.name());
        values.add(Boolean.toString(requiresHumanConfirmation));
        values.add(reauthenticationRequirement.name());
        values.add(authority.name());
        return hashTuple("controlled-automation-proposal-v1", values.toArray(String[]::new));
    }

    private static String versionHash(AiVersionReferences versions) {
        return hashTuple(
            "controlled-automation-source-versions-v1",
            versions.provider().providerId(),
            versions.provider().version(),
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

    private static Map<String, ParameterBinding> immutableParameters(
        Map<String, ParameterBinding> values
    ) {
        if (values == null || values.size() > MAXIMUM_PARAMETERS) {
            throw new IllegalArgumentException("parameters must remain within the closed limit");
        }
        Map<String, ParameterBinding> normalized = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                String key = requireParameterName(entry.getKey());
                ParameterBinding binding = Objects.requireNonNull(
                    entry.getValue(),
                    "parameter binding must not be null"
                );
                if (!key.equals(binding.name())) {
                    throw new IllegalArgumentException(
                        "parameter key must match the exact binding name"
                    );
                }
                normalized.put(key, binding);
            });
        return Map.copyOf(normalized);
    }

    private static String requireTextParameter(String value) {
        String normalized = requireText(value, "textParameter", MAXIMUM_TEXT_PARAMETER_LENGTH);
        if (EXECUTABLE_TEXT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "text parameters cannot contain URL, SQL, script or executable expression material"
            );
        }
        return normalized;
    }

    private static String requireIdentifier(String value) {
        return requirePattern(value, "identifier", IDENTIFIER, 160);
    }

    private static String requireExpectedState(String value) {
        return requirePattern(value, "expectedState", ENUM_VALUE, 64);
    }

    private static String requirePattern(
        String value,
        String name,
        Pattern pattern,
        int maximumLength
    ) {
        String normalized = requireText(value, name, maximumLength);
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " has an invalid closed format");
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

    private static void updateLengthFramed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }
}

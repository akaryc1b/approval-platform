package io.github.akaryc1b.approval.connector.invocation;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenOutcome;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenRequest;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteIntent;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.TransportProfile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Closed, secret-free contracts for one synchronous governed read-only invocation.
 */
public final class GovernedConnectorInvocationContracts {

    private static final int MAX_IDENTIFIER_LENGTH = 256;
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_]{0,127}");
    private static final Pattern SAFE_ATTRIBUTE = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private GovernedConnectorInvocationContracts() {
    }

    public record InvocationPolicy(
        String policyVersion,
        int maximumRequestBytes,
        int maximumResponseBytes,
        Duration timeout,
        String killSwitchRevision,
        String tokenPolicyVersion
    ) {
        public InvocationPolicy {
            policyVersion = identifier(policyVersion, "policyVersion");
            if (maximumRequestBytes < 1 || maximumRequestBytes > 65_536) {
                throw new IllegalArgumentException(
                    "maximumRequestBytes must be between 1 and 65536"
                );
            }
            if (maximumResponseBytes < 1 || maximumResponseBytes > 262_144) {
                throw new IllegalArgumentException(
                    "maximumResponseBytes must be between 1 and 262144"
                );
            }
            timeout = Objects.requireNonNull(timeout, "timeout must not be null");
            if (timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException(
                    "timeout must be between 1 nanosecond and 30 seconds"
                );
            }
            killSwitchRevision = identifier(killSwitchRevision, "killSwitchRevision");
            tokenPolicyVersion = identifier(tokenPolicyVersion, "tokenPolicyVersion");
        }
    }

    /**
     * Client-neutral request. Tenant, Provider, credential, Token, host and path are absent.
     */
    public record InvocationRequest(
        RouteIntent intent,
        String subjectId,
        String correlationReference
    ) {
        public InvocationRequest {
            intent = Objects.requireNonNull(intent, "intent must not be null");
            subjectId = requiredText(subjectId, "subjectId", MAX_IDENTIFIER_LENGTH);
            correlationReference = requiredText(
                correlationReference,
                "correlationReference",
                MAX_IDENTIFIER_LENGTH
            );
        }

        public int canonicalByteCount() {
            return canonical(intent.name(), subjectId, correlationReference)
                .getBytes(StandardCharsets.UTF_8).length;
        }

        public String requestHash() {
            return hash(canonical(intent.name(), hash(subjectId), hash(correlationReference)));
        }

        @Override
        public String toString() {
            return "InvocationRequest[intent=" + intent
                + ", requestHash=" + requestHash() + "]";
        }
    }

    /** Server-owned source for the exact P6 request bound to a resolved P4 plan. */
    @FunctionalInterface
    public interface DingTalkTokenRequestSource {
        DingTalkTokenRequest create(String trustedTenantId, RoutePlan routePlan);
    }

    /**
     * P7 transport seam. The repository provides no production implementation.
     */
    @FunctionalInterface
    public interface DingTalkReadOnlyDispatchPort {
        DispatchResponse dispatch(DispatchRequest request, byte[] accessToken);
    }

    /**
     * Closed dispatch request. It cannot carry a host, path, header, Cookie or arbitrary operation.
     */
    public record DispatchRequest(
        String trustedTenantId,
        RoutePlan routePlan,
        InvocationRequest invocationRequest,
        Duration timeout,
        int maximumResponseBytes
    ) {
        public DispatchRequest {
            trustedTenantId = identifier(trustedTenantId, "trustedTenantId");
            routePlan = Objects.requireNonNull(routePlan, "routePlan must not be null");
            invocationRequest = Objects.requireNonNull(
                invocationRequest,
                "invocationRequest must not be null"
            );
            timeout = Objects.requireNonNull(timeout, "timeout must not be null");
            if (timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("dispatch timeout is outside the closed bound");
            }
            if (maximumResponseBytes < 1 || maximumResponseBytes > 262_144) {
                throw new IllegalArgumentException("maximumResponseBytes is outside the closed bound");
            }
            if (!routePlan.hashMatches()
                || routePlan.intent() != invocationRequest.intent()
                || !closedMatrixAllows(routePlan)) {
                throw new IllegalArgumentException(
                    "dispatch request is outside the closed read-only matrix"
                );
            }
        }

        @Override
        public String toString() {
            return "DispatchRequest[planHash=" + routePlan.planHash()
                + ", requestHash=" + invocationRequest.requestHash() + "]";
        }
    }

    public enum DispatchState {
        SUCCEEDED,
        PROVIDER_REJECTED,
        TIMEOUT,
        UNKNOWN
    }

    /** Bounded typed provider result. It is returned to the caller but never copied into evidence. */
    public record ReadOnlyProviderResult(
        String providerSubjectId,
        Map<String, String> attributes,
        String resultHash
    ) {
        public ReadOnlyProviderResult {
            providerSubjectId = requiredText(
                providerSubjectId,
                "providerSubjectId",
                MAX_IDENTIFIER_LENGTH
            );
            attributes = boundedAttributes(attributes);
            resultHash = sha256(resultHash, "resultHash");
            if (!resultHash.equals(computeResultHash(providerSubjectId, attributes))) {
                throw new IllegalArgumentException("provider result hash does not match content");
            }
        }

        public static ReadOnlyProviderResult create(
            String providerSubjectId,
            Map<String, String> attributes
        ) {
            String subject = requiredText(
                providerSubjectId,
                "providerSubjectId",
                MAX_IDENTIFIER_LENGTH
            );
            Map<String, String> bounded = boundedAttributes(attributes);
            return new ReadOnlyProviderResult(
                subject,
                bounded,
                computeResultHash(subject, bounded)
            );
        }

        @Override
        public String toString() {
            return "ReadOnlyProviderResult[resultHash=" + resultHash + "]";
        }
    }

    public record DispatchResponse(
        DispatchState state,
        Optional<ReadOnlyProviderResult> providerResult,
        int responseBytes,
        String stableProviderCode
    ) {
        public DispatchResponse {
            state = Objects.requireNonNull(state, "state must not be null");
            providerResult = providerResult == null ? Optional.empty() : providerResult;
            if (responseBytes < 0 || responseBytes > 262_144) {
                throw new IllegalArgumentException("responseBytes is outside the absolute bound");
            }
            stableProviderCode = stableCode(stableProviderCode, "stableProviderCode");
            if ((state == DispatchState.SUCCEEDED) != providerResult.isPresent()) {
                throw new IllegalArgumentException("only SUCCEEDED may carry a Provider result");
            }
            if (state != DispatchState.SUCCEEDED && responseBytes != 0) {
                throw new IllegalArgumentException(
                    "non-success dispatch evidence must not retain response bytes"
                );
            }
        }

        public static DispatchResponse succeeded(
            ReadOnlyProviderResult result,
            int responseBytes
        ) {
            return new DispatchResponse(
                DispatchState.SUCCEEDED,
                Optional.of(Objects.requireNonNull(result, "result must not be null")),
                responseBytes,
                "NONE"
            );
        }

        public static DispatchResponse providerRejected(String stableProviderCode) {
            return new DispatchResponse(
                DispatchState.PROVIDER_REJECTED,
                Optional.empty(),
                0,
                stableProviderCode
            );
        }

        public static DispatchResponse timeout() {
            return new DispatchResponse(
                DispatchState.TIMEOUT,
                Optional.empty(),
                0,
                "TRANSPORT_TIMEOUT"
            );
        }

        public static DispatchResponse unknown() {
            return new DispatchResponse(
                DispatchState.UNKNOWN,
                Optional.empty(),
                0,
                "TRANSPORT_UNKNOWN"
            );
        }
    }

    public enum GateResult {
        NOT_EVALUATED,
        ALLOWED,
        BLOCKED,
        REVISION_DRIFT,
        EVALUATION_FAILED
    }

    public enum CompletionClassification {
        SUCCEEDED,
        REJECTED_BEFORE_DISPATCH,
        PROVIDER_REJECTED,
        UNKNOWN_AFTER_DISPATCH
    }

    public enum DurationBucket {
        LT_10_MS,
        LT_100_MS,
        LT_1_S,
        LT_5_S,
        GE_5_S;

        public static DurationBucket from(Duration duration) {
            Objects.requireNonNull(duration, "duration must not be null");
            Duration safe = duration.isNegative() ? Duration.ZERO : duration;
            if (safe.compareTo(Duration.ofMillis(10)) < 0) {
                return LT_10_MS;
            }
            if (safe.compareTo(Duration.ofMillis(100)) < 0) {
                return LT_100_MS;
            }
            if (safe.compareTo(Duration.ofSeconds(1)) < 0) {
                return LT_1_S;
            }
            if (safe.compareTo(Duration.ofSeconds(5)) < 0) {
                return LT_5_S;
            }
            return GE_5_S;
        }
    }

    public enum StableFailureCode {
        NONE,
        COORDINATOR_CLOSED,
        INVALID_REQUEST,
        UNSUPPORTED_OPERATION,
        ROUTE_MISSING,
        ROUTE_DISABLED,
        ROUTE_STALE,
        ROUTE_REJECTED,
        KILL_SWITCH_BLOCKED,
        KILL_SWITCH_REVISION_DRIFT,
        KILL_SWITCH_UNAVAILABLE,
        CREDENTIAL_REVALIDATION_FAILED,
        TOKEN_REQUEST_INVALID,
        TOKEN_ACQUISITION_FAILED,
        TOKEN_POLICY_DRIFT,
        TOKEN_ROUTE_DRIFT,
        POST_TOKEN_ROUTE_DRIFT,
        PROVIDER_REJECTED,
        RESPONSE_TOO_LARGE,
        RESPONSE_INVALID,
        TRANSPORT_TIMEOUT,
        TRANSPORT_EXCEPTION,
        TRANSPORT_UNKNOWN
    }

    /**
     * Hash-only bounded evidence. It intentionally excludes raw tenant, subject, credential and
     * response material.
     */
    public record InvocationEvidence(
        String tenantHash,
        String requestHash,
        String routePlanHash,
        String routeDefinitionHash,
        String credentialReferenceHash,
        String credentialBindingFingerprint,
        String credentialVersionReference,
        String credentialVersionHash,
        String tokenEvidenceHash,
        DingTalkTokenOutcome tokenOutcome,
        TransportProfile transportProfile,
        ProviderApiFamily apiFamily,
        ConnectorOperation connectorOperation,
        String providerOperation,
        GateResult preDispatchGateResult,
        boolean dispatchAttempted,
        int dispatchCount,
        CompletionClassification completionClassification,
        DurationBucket durationBucket,
        StableFailureCode stableFailureCode,
        String evidenceHash
    ) {
        public InvocationEvidence {
            tenantHash = sha256(tenantHash, "tenantHash");
            requestHash = sha256(requestHash, "requestHash");
            routePlanHash = optionalSha256(routePlanHash, "routePlanHash");
            routeDefinitionHash = optionalSha256(
                routeDefinitionHash,
                "routeDefinitionHash"
            );
            credentialReferenceHash = optionalSha256(
                credentialReferenceHash,
                "credentialReferenceHash"
            );
            credentialBindingFingerprint = optionalSha256(
                credentialBindingFingerprint,
                "credentialBindingFingerprint"
            );
            credentialVersionReference = optionalIdentifier(
                credentialVersionReference,
                "credentialVersionReference"
            );
            credentialVersionHash = optionalSha256(
                credentialVersionHash,
                "credentialVersionHash"
            );
            tokenEvidenceHash = optionalSha256(tokenEvidenceHash, "tokenEvidenceHash");
            connectorOperation = Objects.requireNonNull(
                connectorOperation,
                "connectorOperation must not be null"
            );
            providerOperation = requiredText(
                providerOperation,
                "providerOperation",
                MAX_IDENTIFIER_LENGTH
            );
            preDispatchGateResult = Objects.requireNonNull(
                preDispatchGateResult,
                "preDispatchGateResult must not be null"
            );
            if (dispatchCount < 0 || dispatchCount > 1
                || dispatchAttempted != (dispatchCount == 1)) {
                throw new IllegalArgumentException("dispatch evidence must be exactly zero or one");
            }
            completionClassification = Objects.requireNonNull(
                completionClassification,
                "completionClassification must not be null"
            );
            durationBucket = Objects.requireNonNull(
                durationBucket,
                "durationBucket must not be null"
            );
            stableFailureCode = Objects.requireNonNull(
                stableFailureCode,
                "stableFailureCode must not be null"
            );
            if ((completionClassification == CompletionClassification.SUCCEEDED)
                != (stableFailureCode == StableFailureCode.NONE)) {
                throw new IllegalArgumentException(
                    "only successful completion may have NONE failure"
                );
            }
            if (dispatchCount == 0
                && completionClassification == CompletionClassification.UNKNOWN_AFTER_DISPATCH) {
                throw new IllegalArgumentException(
                    "UNKNOWN_AFTER_DISPATCH requires one dispatch attempt"
                );
            }
            evidenceHash = sha256(evidenceHash, "evidenceHash");
        }

        public static InvocationEvidence create(
            EvidenceInput input
        ) {
            Objects.requireNonNull(input, "input must not be null");
            String computed = hash(canonical(
                input.tenantHash(),
                input.requestHash(),
                nullable(input.routePlanHash()),
                nullable(input.routeDefinitionHash()),
                nullable(input.credentialReferenceHash()),
                nullable(input.credentialBindingFingerprint()),
                nullable(input.credentialVersionReference()),
                nullable(input.credentialVersionHash()),
                nullable(input.tokenEvidenceHash()),
                input.tokenOutcome() == null ? "<null>" : input.tokenOutcome().name(),
                input.transportProfile() == null ? "<null>" : input.transportProfile().name(),
                input.apiFamily() == null ? "<null>" : input.apiFamily().name(),
                input.connectorOperation().name(),
                input.providerOperation(),
                input.preDispatchGateResult().name(),
                Boolean.toString(input.dispatchAttempted()),
                Integer.toString(input.dispatchCount()),
                input.completionClassification().name(),
                input.durationBucket().name(),
                input.stableFailureCode().name()
            ));
            return new InvocationEvidence(
                input.tenantHash(),
                input.requestHash(),
                input.routePlanHash(),
                input.routeDefinitionHash(),
                input.credentialReferenceHash(),
                input.credentialBindingFingerprint(),
                input.credentialVersionReference(),
                input.credentialVersionHash(),
                input.tokenEvidenceHash(),
                input.tokenOutcome(),
                input.transportProfile(),
                input.apiFamily(),
                input.connectorOperation(),
                input.providerOperation(),
                input.preDispatchGateResult(),
                input.dispatchAttempted(),
                input.dispatchCount(),
                input.completionClassification(),
                input.durationBucket(),
                input.stableFailureCode(),
                computed
            );
        }

        public String canonicalJson() {
            return new StringBuilder(1_536)
                .append('{')
                .append("\"tenantHash\":").append(json(tenantHash))
                .append(",\"requestHash\":").append(json(requestHash))
                .append(",\"routePlanHash\":").append(jsonNullable(routePlanHash))
                .append(",\"routeDefinitionHash\":")
                .append(jsonNullable(routeDefinitionHash))
                .append(",\"credentialReferenceHash\":")
                .append(jsonNullable(credentialReferenceHash))
                .append(",\"credentialBindingFingerprint\":")
                .append(jsonNullable(credentialBindingFingerprint))
                .append(",\"credentialVersionReference\":")
                .append(jsonNullable(credentialVersionReference))
                .append(",\"credentialVersionHash\":")
                .append(jsonNullable(credentialVersionHash))
                .append(",\"tokenEvidenceHash\":")
                .append(jsonNullable(tokenEvidenceHash))
                .append(",\"tokenOutcome\":")
                .append(tokenOutcome == null ? "null" : json(tokenOutcome.name()))
                .append(",\"transportProfile\":")
                .append(transportProfile == null ? "null" : json(transportProfile.name()))
                .append(",\"apiFamily\":")
                .append(apiFamily == null ? "null" : json(apiFamily.name()))
                .append(",\"connectorOperation\":").append(json(connectorOperation.name()))
                .append(",\"providerOperation\":").append(json(providerOperation))
                .append(",\"preDispatchGateResult\":")
                .append(json(preDispatchGateResult.name()))
                .append(",\"dispatchAttempted\":").append(dispatchAttempted)
                .append(",\"dispatchCount\":").append(dispatchCount)
                .append(",\"completionClassification\":")
                .append(json(completionClassification.name()))
                .append(",\"durationBucket\":").append(json(durationBucket.name()))
                .append(",\"stableFailureCode\":").append(json(stableFailureCode.name()))
                .append(",\"evidenceHash\":").append(json(evidenceHash))
                .append('}')
                .toString();
        }
    }

    public record EvidenceInput(
        String tenantHash,
        String requestHash,
        String routePlanHash,
        String routeDefinitionHash,
        String credentialReferenceHash,
        String credentialBindingFingerprint,
        String credentialVersionReference,
        String credentialVersionHash,
        String tokenEvidenceHash,
        DingTalkTokenOutcome tokenOutcome,
        TransportProfile transportProfile,
        ProviderApiFamily apiFamily,
        ConnectorOperation connectorOperation,
        String providerOperation,
        GateResult preDispatchGateResult,
        boolean dispatchAttempted,
        int dispatchCount,
        CompletionClassification completionClassification,
        DurationBucket durationBucket,
        StableFailureCode stableFailureCode
    ) {
        public EvidenceInput {
            tenantHash = sha256(tenantHash, "tenantHash");
            requestHash = sha256(requestHash, "requestHash");
            connectorOperation = Objects.requireNonNull(
                connectorOperation,
                "connectorOperation must not be null"
            );
            providerOperation = requiredText(
                providerOperation,
                "providerOperation",
                MAX_IDENTIFIER_LENGTH
            );
            preDispatchGateResult = Objects.requireNonNull(
                preDispatchGateResult,
                "preDispatchGateResult must not be null"
            );
            completionClassification = Objects.requireNonNull(
                completionClassification,
                "completionClassification must not be null"
            );
            durationBucket = Objects.requireNonNull(
                durationBucket,
                "durationBucket must not be null"
            );
            stableFailureCode = Objects.requireNonNull(
                stableFailureCode,
                "stableFailureCode must not be null"
            );
        }
    }

    public record InvocationResult(
        Optional<ReadOnlyProviderResult> providerResult,
        InvocationEvidence evidence,
        boolean readOnly,
        boolean approvalStateMutationAuthorized,
        boolean productionExecutionAuthorized
    ) {
        public InvocationResult {
            providerResult = providerResult == null ? Optional.empty() : providerResult;
            evidence = Objects.requireNonNull(evidence, "evidence must not be null");
            if (!readOnly || approvalStateMutationAuthorized || productionExecutionAuthorized) {
                throw new IllegalArgumentException("P7 result authority flags are immutable");
            }
            if ((evidence.completionClassification() == CompletionClassification.SUCCEEDED)
                != providerResult.isPresent()) {
                throw new IllegalArgumentException("only successful invocation may carry a result");
            }
        }

        public static InvocationResult success(
            ReadOnlyProviderResult result,
            InvocationEvidence evidence
        ) {
            return new InvocationResult(
                Optional.of(Objects.requireNonNull(result, "result must not be null")),
                evidence,
                true,
                false,
                false
            );
        }

        public static InvocationResult failure(InvocationEvidence evidence) {
            return new InvocationResult(Optional.empty(), evidence, true, false, false);
        }
    }

    public static boolean closedMatrixAllows(RoutePlan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        if (!"dingtalk".equals(plan.providerKey())
            || plan.transportProfile() != TransportProfile.DINGTALK_JAVA21_FIXED_HTTPS_V1
            || plan.connectorOperation() != plan.intent().connectorOperation()) {
            return false;
        }
        return switch (plan.intent()) {
            case ORGANIZATION_READ_USER_BY_ID ->
                plan.apiFamily() == ProviderApiFamily.OPEN_API_V1
                    || plan.apiFamily() == ProviderApiFamily.LEGACY_OAPI;
            case IDENTITY_RESOLVE_DINGTALK_USERID ->
                plan.apiFamily() == ProviderApiFamily.LEGACY_OAPI;
        };
    }

    public static String tenantHash(String trustedTenantId) {
        return hash("tenant\n" + identifier(trustedTenantId, "trustedTenantId"));
    }

    private static String computeResultHash(
        String providerSubjectId,
        Map<String, String> attributes
    ) {
        StringBuilder canonical = new StringBuilder(512)
            .append(hash(providerSubjectId));
        attributes.forEach((key, value) -> canonical.append('\n')
            .append(key)
            .append('=')
            .append(hash(value)));
        return hash(canonical.toString());
    }

    private static Map<String, String> boundedAttributes(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > 16) {
            throw new IllegalArgumentException("Provider result attributes exceed 16 entries");
        }
        Map<String, String> sorted = new TreeMap<>();
        source.forEach((key, value) -> {
            String safeKey = requiredText(key, "attribute key", 64);
            if (!SAFE_ATTRIBUTE.matcher(safeKey).matches()) {
                throw new IllegalArgumentException("Provider result attribute key is not allowlisted");
            }
            sorted.put(safeKey, requiredText(value, "attribute value", 512));
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static String identifier(String value, String name) {
        String normalized = requiredText(value, name, MAX_IDENTIFIER_LENGTH);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (normalized.contains("*") || lower.contains("://")
            || lower.equals("all") || lower.equals("any") || lower.equals("default")) {
            throw new IllegalArgumentException(name + " is not an exact identifier");
        }
        return normalized;
    }

    private static String optionalIdentifier(String value, String name) {
        return value == null ? null : identifier(value, name);
    }

    private static String stableCode(String value, String name) {
        String normalized = requiredText(value, name, 128).toUpperCase(Locale.ROOT);
        if (!SAFE_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " is not a stable code");
        }
        return normalized;
    }

    private static String requiredText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or exceeds " + maximumLength);
        }
        return normalized;
    }

    private static String canonical(String... values) {
        StringBuilder canonical = new StringBuilder(values.length * 64);
        for (String value : values) {
            String safe = Objects.requireNonNull(value, "canonical value must not be null");
            byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
            canonical.append(bytes.length).append(':').append(safe).append('|');
        }
        return canonical.toString();
    }

    private static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    private static String nullable(String value) {
        return value == null ? "<null>" : value;
    }

    private static String sha256(String value, String name) {
        String normalized = requiredText(value, name, 64).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return normalized;
    }

    private static String optionalSha256(String value, String name) {
        return value == null ? null : sha256(value, name);
    }

    private static String jsonNullable(String value) {
        return value == null ? "null" : json(value);
    }

    private static String json(String value) {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }
}

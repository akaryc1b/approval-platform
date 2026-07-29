package io.github.akaryc1b.approval.connector.operations;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenOutcome;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DurationBucket;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationEvidence;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .StableFailureCode;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .TransportProfile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Closed secret-free contracts for process-local Connector operations diagnostics. */
public final class ConnectorOperationsDiagnosticsContracts {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private ConnectorOperationsDiagnosticsContracts() {
    }

    public enum Capability {
        ORGANIZATION,
        AUTHENTICATION
    }

    public enum RouteState {
        VALID,
        MISSING,
        DISABLED,
        STALE,
        REJECTED,
        NOT_EVALUATED
    }

    public enum CredentialState {
        VALID,
        REJECTED,
        NOT_EVALUATED
    }

    public enum TokenCacheState {
        HIT,
        ACQUIRED,
        REFRESHED,
        SINGLE_FLIGHT,
        FAILED,
        NOT_EVALUATED
    }

    public enum InvocationOutcome {
        SUCCEEDED,
        REJECTED_BEFORE_DISPATCH,
        PROVIDER_REJECTED,
        UNKNOWN_AFTER_DISPATCH
    }

    public enum Sort {
        CREATED_AT_DESC
    }

    public record DiagnosticEntry(
        String provider,
        Capability capability,
        ConnectorOperation connectorOperation,
        ProviderApiFamily apiFamily,
        TransportProfile transportProfile,
        RouteState routeState,
        CredentialState credentialState,
        String credentialVersionReference,
        String credentialVersionHash,
        TokenCacheState tokenCacheState,
        DingTalkTokenOutcome tokenOutcome,
        InvocationOutcome invocationOutcome,
        boolean dispatchAttempted,
        int dispatchCount,
        StableFailureCode stableFailureCode,
        DurationBucket durationBucket,
        Instant createdAt,
        Instant evaluatedAt,
        String evidenceHash
    ) {
        public DiagnosticEntry {
            provider = requireText(provider, "provider", 32);
            capability = Objects.requireNonNull(capability, "capability must not be null");
            connectorOperation = Objects.requireNonNull(
                connectorOperation,
                "connectorOperation must not be null"
            );
            routeState = Objects.requireNonNull(routeState, "routeState must not be null");
            credentialState = Objects.requireNonNull(
                credentialState,
                "credentialState must not be null"
            );
            credentialVersionReference = optionalText(
                credentialVersionReference,
                "credentialVersionReference",
                256
            );
            credentialVersionHash = optionalSha256(
                credentialVersionHash,
                "credentialVersionHash"
            );
            tokenCacheState = Objects.requireNonNull(
                tokenCacheState,
                "tokenCacheState must not be null"
            );
            invocationOutcome = Objects.requireNonNull(
                invocationOutcome,
                "invocationOutcome must not be null"
            );
            if (dispatchCount < 0 || dispatchCount > 1
                || dispatchAttempted != (dispatchCount == 1)) {
                throw new IllegalArgumentException("dispatch count must be exactly zero or one");
            }
            stableFailureCode = Objects.requireNonNull(
                stableFailureCode,
                "stableFailureCode must not be null"
            );
            durationBucket = Objects.requireNonNull(
                durationBucket,
                "durationBucket must not be null"
            );
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
            evidenceHash = sha256(evidenceHash, "evidenceHash");
        }

        public static DiagnosticEntry from(
            InvocationEvidence evidence,
            Instant evaluatedAt
        ) {
            Objects.requireNonNull(evidence, "evidence must not be null");
            Instant instant = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
            return new DiagnosticEntry(
                "dingtalk",
                mapCapability(evidence.connectorOperation()),
                evidence.connectorOperation(),
                evidence.apiFamily(),
                evidence.transportProfile(),
                mapRouteState(evidence.stableFailureCode()),
                mapCredentialState(evidence.stableFailureCode()),
                evidence.credentialVersionReference(),
                evidence.credentialVersionHash(),
                mapTokenCacheState(evidence),
                evidence.tokenOutcome(),
                InvocationOutcome.valueOf(evidence.completionClassification().name()),
                evidence.dispatchAttempted(),
                evidence.dispatchCount(),
                evidence.stableFailureCode(),
                evidence.durationBucket(),
                instant,
                instant,
                evidence.evidenceHash()
            );
        }

        public String canonicalJson() {
            return new StringBuilder(1_536)
                .append('{')
                .append("\"provider\":").append(json(provider))
                .append(",\"capability\":").append(json(capability.name()))
                .append(",\"connectorOperation\":")
                .append(json(connectorOperation.name()))
                .append(",\"apiFamily\":")
                .append(apiFamily == null ? "null" : json(apiFamily.name()))
                .append(",\"transportProfile\":")
                .append(transportProfile == null ? "null" : json(transportProfile.name()))
                .append(",\"routeState\":").append(json(routeState.name()))
                .append(",\"credentialState\":").append(json(credentialState.name()))
                .append(",\"credentialVersionReference\":")
                .append(jsonNullable(credentialVersionReference))
                .append(",\"credentialVersionHash\":")
                .append(jsonNullable(credentialVersionHash))
                .append(",\"tokenCacheState\":").append(json(tokenCacheState.name()))
                .append(",\"tokenOutcome\":")
                .append(tokenOutcome == null ? "null" : json(tokenOutcome.name()))
                .append(",\"invocationOutcome\":").append(json(invocationOutcome.name()))
                .append(",\"dispatchAttempted\":").append(dispatchAttempted)
                .append(",\"dispatchCount\":").append(dispatchCount)
                .append(",\"stableFailureCode\":").append(json(stableFailureCode.name()))
                .append(",\"durationBucket\":").append(json(durationBucket.name()))
                .append(",\"createdAt\":").append(json(createdAt.toString()))
                .append(",\"evaluatedAt\":").append(json(evaluatedAt.toString()))
                .append(",\"evidenceHash\":").append(json(evidenceHash))
                .append('}')
                .toString();
        }
    }

    public record DiagnosticsCriteria(
        int pageSize,
        String pageToken,
        String provider,
        Capability capability,
        ConnectorOperation connectorOperation,
        ProviderApiFamily apiFamily,
        TransportProfile transportProfile,
        InvocationOutcome invocationOutcome,
        Boolean dispatchAttempted,
        StableFailureCode stableFailureCode,
        Sort sort
    ) {
        public DiagnosticsCriteria {
            if (pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("pageSize must be between 1 and 100");
            }
            pageToken = optionalText(pageToken, "pageToken", 1_024);
            provider = optionalText(provider, "provider", 32);
            if (provider != null && !"dingtalk".equals(provider)) {
                throw new IllegalArgumentException("provider is outside the closed set");
            }
            sort = Objects.requireNonNull(sort, "sort must not be null");
        }

        public String filterHash() {
            return hash(canonical(
                provider,
                capability,
                connectorOperation,
                apiFamily,
                transportProfile,
                invocationOutcome,
                dispatchAttempted,
                stableFailureCode,
                sort
            ));
        }
    }

    public record PageCursor(
        String tenantHash,
        String filterHash,
        long highWatermark,
        long beforeSequence
    ) {
        public PageCursor {
            tenantHash = sha256(tenantHash, "tenantHash");
            filterHash = sha256(filterHash, "filterHash");
            if (highWatermark < 0 || beforeSequence < 0 || beforeSequence > highWatermark) {
                throw new IllegalArgumentException("page cursor range is invalid");
            }
        }
    }

    public record QueryWindow(
        List<DiagnosticEntry> items,
        long highWatermark,
        long nextBeforeSequence,
        boolean moreAvailable
    ) {
        public QueryWindow {
            items = items == null ? List.of() : List.copyOf(items);
            if (highWatermark < 0 || nextBeforeSequence < 0
                || nextBeforeSequence > highWatermark) {
                throw new IllegalArgumentException("query window range is invalid");
            }
            if (items.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("query window contains null entries");
            }
        }
    }

    public record DiagnosticsPage(
        List<DiagnosticEntry> items,
        String nextPageToken,
        int pageSize,
        Instant evaluatedAt,
        boolean processLocal,
        boolean persistent,
        boolean auditSystem,
        boolean recoveryMechanism,
        boolean productionExecutionAuthorized,
        boolean approvalStateMutationAuthorized
    ) {
        public DiagnosticsPage {
            items = items == null ? List.of() : List.copyOf(items);
            nextPageToken = optionalText(nextPageToken, "nextPageToken", 1_024);
            if (pageSize < 1 || pageSize > 100 || items.size() > pageSize) {
                throw new IllegalArgumentException("diagnostics page size is invalid");
            }
            evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
            if (!processLocal || persistent || auditSystem || recoveryMechanism
                || productionExecutionAuthorized || approvalStateMutationAuthorized) {
                throw new IllegalArgumentException("diagnostics authority flags are immutable");
            }
        }

        public String canonicalJson() {
            StringBuilder json = new StringBuilder(2_048).append('{').append("\"items\":[");
            for (int index = 0; index < items.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append(items.get(index).canonicalJson());
            }
            return json.append(']')
                .append(",\"nextPageToken\":").append(jsonNullable(nextPageToken))
                .append(",\"pageSize\":").append(pageSize)
                .append(",\"evaluatedAt\":").append(json(evaluatedAt.toString()))
                .append(",\"processLocal\":true")
                .append(",\"persistent\":false")
                .append(",\"auditSystem\":false")
                .append(",\"recoveryMechanism\":false")
                .append(",\"productionExecutionAuthorized\":false")
                .append(",\"approvalStateMutationAuthorized\":false")
                .append('}')
                .toString();
        }
    }

    public record DiagnosticsSummary(
        long total,
        Map<InvocationOutcome, Long> outcomes,
        Map<StableFailureCode, Long> failures,
        Instant evaluatedAt,
        boolean processLocal,
        boolean persistent,
        boolean auditSystem,
        boolean productionExecutionAuthorized
    ) {
        public DiagnosticsSummary {
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            outcomes = immutableCounts(outcomes);
            failures = immutableCounts(failures);
            evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
            if (!processLocal || persistent || auditSystem || productionExecutionAuthorized) {
                throw new IllegalArgumentException("summary authority flags are immutable");
            }
        }
    }

    private static Capability mapCapability(ConnectorOperation operation) {
        return switch (operation) {
            case ORGANIZATION_READ -> Capability.ORGANIZATION;
            case IDENTITY_RESOLVE -> Capability.AUTHENTICATION;
            default -> throw new IllegalArgumentException("operation is outside P8 diagnostics");
        };
    }

    private static RouteState mapRouteState(StableFailureCode failure) {
        return switch (failure) {
            case ROUTE_MISSING -> RouteState.MISSING;
            case ROUTE_DISABLED -> RouteState.DISABLED;
            case ROUTE_STALE, POST_TOKEN_ROUTE_DRIFT, TOKEN_ROUTE_DRIFT -> RouteState.STALE;
            case ROUTE_REJECTED, UNSUPPORTED_OPERATION -> RouteState.REJECTED;
            case INVALID_REQUEST, COORDINATOR_CLOSED -> RouteState.NOT_EVALUATED;
            default -> RouteState.VALID;
        };
    }

    private static CredentialState mapCredentialState(StableFailureCode failure) {
        return switch (failure) {
            case CREDENTIAL_REVALIDATION_FAILED, TOKEN_REQUEST_INVALID,
                TOKEN_ACQUISITION_FAILED, TOKEN_POLICY_DRIFT -> CredentialState.REJECTED;
            case INVALID_REQUEST, COORDINATOR_CLOSED, ROUTE_MISSING,
                ROUTE_DISABLED, ROUTE_STALE, ROUTE_REJECTED,
                KILL_SWITCH_BLOCKED, KILL_SWITCH_REVISION_DRIFT,
                KILL_SWITCH_UNAVAILABLE -> CredentialState.NOT_EVALUATED;
            default -> CredentialState.VALID;
        };
    }

    private static TokenCacheState mapTokenCacheState(InvocationEvidence evidence) {
        if (evidence.tokenOutcome() == null) {
            return evidence.stableFailureCode() == StableFailureCode.TOKEN_ACQUISITION_FAILED
                ? TokenCacheState.FAILED
                : TokenCacheState.NOT_EVALUATED;
        }
        return switch (evidence.tokenOutcome()) {
            case CACHE_HIT -> TokenCacheState.HIT;
            case ACQUIRED -> TokenCacheState.ACQUIRED;
            case REFRESHED -> TokenCacheState.REFRESHED;
            case SINGLE_FLIGHT_JOIN -> TokenCacheState.SINGLE_FLIGHT;
        };
    }

    private static <K> Map<K, Long> immutableCounts(Map<K, Long> source) {
        Map<K, Long> sorted = new TreeMap<>((left, right) -> left.toString().compareTo(right.toString()));
        if (source != null) {
            source.forEach((key, value) -> {
                Objects.requireNonNull(key, "count key must not be null");
                if (value == null || value < 0) {
                    throw new IllegalArgumentException("count value must not be negative");
                }
                sorted.put(key, value);
            });
        }
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sorted));
    }

    private static String canonical(Object... values) {
        StringBuilder canonical = new StringBuilder(values.length * 48);
        for (Object value : values) {
            String text = value == null ? "<null>" : value.toString();
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            canonical.append(bytes.length).append(':').append(text).append('|');
        }
        return canonical.toString();
    }

    private static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    private static String sha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase(java.util.Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return normalized;
    }

    private static String optionalSha256(String value, String name) {
        return value == null ? null : sha256(value, name);
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or exceeds " + maximum);
        }
        return normalized;
    }

    private static String optionalText(String value, String name, int maximum) {
        return value == null || value.isBlank() ? null : requireText(value, name, maximum);
    }

    private static String jsonNullable(String value) {
        return value == null ? "null" : json(value);
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}

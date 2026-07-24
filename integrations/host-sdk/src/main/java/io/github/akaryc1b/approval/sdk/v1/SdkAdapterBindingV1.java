package io.github.akaryc1b.approval.sdk.v1;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure logical-endpoint, server-authentication, credential-lease and adapter lifecycle binding. */
public final class SdkAdapterBindingV1 {
    public static final String BINDING_VERSION = "1";

    private SdkAdapterBindingV1() {
    }

    public enum AdapterKind {
        GENERIC_REST,
        RUOYI5,
        RUOYI6
    }

    public enum CredentialKind {
        NONE,
        MTLS_REFERENCE,
        OAUTH_CLIENT_REFERENCE,
        SIGNED_REQUEST_REFERENCE
    }

    public enum CredentialReleaseReason {
        COMPLETED,
        BINDING_FAILED,
        ADAPTER_FAILED
    }

    public enum LifecycleState {
        CREATED,
        OPEN,
        CLOSED
    }

    public enum BindingStatus {
        EXECUTED,
        BINDING_FAILED
    }

    public record CredentialReference(
        String providerId,
        String credentialId,
        CredentialKind kind
    ) {
        public CredentialReference {
            providerId = required(providerId, "credentialReference.providerId");
            credentialId = required(credentialId, "credentialReference.credentialId");
            kind = Objects.requireNonNull(kind, "credentialReference.kind");
        }
    }

    /** Logical server-owned descriptor; no address or trusted identity evidence is accepted. */
    public record EndpointDescriptor(
        String bindingVersion,
        String endpointId,
        AdapterKind adapterKind,
        String audience,
        List<String> supportedOperations,
        CredentialReference credentialReference
    ) {
        public EndpointDescriptor {
            requireBindingVersion(bindingVersion);
            endpointId = required(endpointId, "endpoint.endpointId");
            adapterKind = Objects.requireNonNull(adapterKind, "endpoint.adapterKind");
            audience = required(audience, "endpoint.audience");
            supportedOperations = uniqueStrings(supportedOperations, "endpoint.supportedOperations");
            credentialReference = Objects.requireNonNull(
                credentialReference,
                "endpoint.credentialReference"
            );
        }
    }

    public record AuthenticationContextRequest(
        String endpointId,
        String operation,
        String requestId,
        String traceId
    ) {
        public AuthenticationContextRequest {
            endpointId = required(endpointId, "authenticationContextRequest.endpointId");
            operation = required(operation, "authenticationContextRequest.operation");
            requestId = required(requestId, "authenticationContextRequest.requestId");
            traceId = required(traceId, "authenticationContextRequest.traceId");
        }
    }

    public record AuthenticationContextFields(
        String contextId,
        String tenantId,
        String operatorId,
        String permissionSnapshotHash,
        String auditReference,
        long authenticatedAtEpochSeconds,
        long expiresAtEpochSeconds
    ) {
        public AuthenticationContextFields {
            contextId = required(contextId, "authenticationContext.contextId");
            tenantId = required(tenantId, "authenticationContext.tenantId");
            operatorId = required(operatorId, "authenticationContext.operatorId");
            permissionSnapshotHash = required(
                permissionSnapshotHash,
                "authenticationContext.permissionSnapshotHash"
            );
            auditReference = required(auditReference, "authenticationContext.auditReference");
            if (expiresAtEpochSeconds <= authenticatedAtEpochSeconds) {
                throw new IllegalArgumentException(
                    "Authentication context expiry must follow authentication time"
                );
            }
        }
    }

    /** Nominal server-issued context. Its constructor is inaccessible to SDK callers. */
    public static final class AuthenticationContext {
        private final AuthenticationContextFields fields;

        private AuthenticationContext(AuthenticationContextFields fields) {
            this.fields = Objects.requireNonNull(fields, "fields");
        }

        public String contextId() {
            return fields.contextId();
        }

        public String tenantId() {
            return fields.tenantId();
        }

        public String operatorId() {
            return fields.operatorId();
        }

        public String permissionSnapshotHash() {
            return fields.permissionSnapshotHash();
        }

        public String auditReference() {
            return fields.auditReference();
        }

        public long authenticatedAtEpochSeconds() {
            return fields.authenticatedAtEpochSeconds();
        }

        public long expiresAtEpochSeconds() {
            return fields.expiresAtEpochSeconds();
        }
    }

    public abstract static class AuthenticationContextResolver {
        public abstract AuthenticationContext resolve(AuthenticationContextRequest request);

        protected final AuthenticationContext issue(AuthenticationContextFields fields) {
            return new AuthenticationContext(Objects.requireNonNull(fields, "fields"));
        }
    }

    /** Deterministic server-side resolver for conformance tests only. */
    public static final class StaticAuthenticationContextResolver extends AuthenticationContextResolver {
        private final AuthenticationContextFields fields;
        private final List<AuthenticationContextRequest> invocations = new ArrayList<>();

        public StaticAuthenticationContextResolver(AuthenticationContextFields fields) {
            this.fields = Objects.requireNonNull(fields, "fields");
        }

        @Override
        public synchronized AuthenticationContext resolve(AuthenticationContextRequest request) {
            invocations.add(Objects.requireNonNull(request, "request"));
            return issue(fields);
        }

        public synchronized List<AuthenticationContextRequest> invocations() {
            return List.copyOf(invocations);
        }
    }

    public record CredentialLeaseFields(
        String leaseId,
        String providerId,
        String credentialId,
        CredentialKind kind,
        String endpointId,
        String authenticationContextId,
        String operation,
        String bindingId,
        long issuedAtEpochSeconds,
        long expiresAtEpochSeconds
    ) {
        public CredentialLeaseFields {
            leaseId = required(leaseId, "credentialLease.leaseId");
            providerId = required(providerId, "credentialLease.providerId");
            credentialId = required(credentialId, "credentialLease.credentialId");
            kind = Objects.requireNonNull(kind, "credentialLease.kind");
            endpointId = required(endpointId, "credentialLease.endpointId");
            authenticationContextId = required(
                authenticationContextId,
                "credentialLease.authenticationContextId"
            );
            operation = required(operation, "credentialLease.operation");
            bindingId = required(bindingId, "credentialLease.bindingId");
            if (expiresAtEpochSeconds <= issuedAtEpochSeconds) {
                throw new IllegalArgumentException("Credential lease expiry must follow issue time");
            }
        }
    }

    /** Nominal provider-issued lease; it contains references and binding evidence, never a secret. */
    public static final class CredentialLease {
        private final CredentialLeaseFields fields;

        private CredentialLease(CredentialLeaseFields fields) {
            this.fields = Objects.requireNonNull(fields, "fields");
        }

        public String leaseId() {
            return fields.leaseId();
        }

        public String providerId() {
            return fields.providerId();
        }

        public String credentialId() {
            return fields.credentialId();
        }

        public CredentialKind kind() {
            return fields.kind();
        }

        public String endpointId() {
            return fields.endpointId();
        }

        public String authenticationContextId() {
            return fields.authenticationContextId();
        }

        public String operation() {
            return fields.operation();
        }

        public String bindingId() {
            return fields.bindingId();
        }

        public long issuedAtEpochSeconds() {
            return fields.issuedAtEpochSeconds();
        }

        public long expiresAtEpochSeconds() {
            return fields.expiresAtEpochSeconds();
        }
    }

    public record CredentialLeaseRequest(
        EndpointDescriptor endpoint,
        AuthenticationContext authenticationContext,
        String operation,
        long nowEpochSeconds
    ) {
        public CredentialLeaseRequest {
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            authenticationContext = Objects.requireNonNull(authenticationContext, "authenticationContext");
            operation = required(operation, "credentialLeaseRequest.operation");
        }
    }

    public abstract static class CredentialLeaseProvider {
        public abstract CredentialLease acquire(CredentialLeaseRequest request);

        public abstract void release(CredentialLease lease, CredentialReleaseReason reason);

        protected final CredentialLease issue(CredentialLeaseFields fields) {
            return new CredentialLease(Objects.requireNonNull(fields, "fields"));
        }
    }

    public record CredentialRelease(String leaseId, CredentialReleaseReason reason) {
        public CredentialRelease {
            leaseId = required(leaseId, "credentialRelease.leaseId");
            reason = Objects.requireNonNull(reason, "credentialRelease.reason");
        }
    }

    /** Deterministic provider for conformance tests only. */
    public static final class StaticCredentialLeaseProvider extends CredentialLeaseProvider {
        private final CredentialLeaseFields fields;
        private final List<CredentialLeaseRequest> acquisitions = new ArrayList<>();
        private final List<CredentialRelease> releases = new ArrayList<>();

        public StaticCredentialLeaseProvider(CredentialLeaseFields fields) {
            this.fields = Objects.requireNonNull(fields, "fields");
        }

        @Override
        public synchronized CredentialLease acquire(CredentialLeaseRequest request) {
            CredentialLeaseRequest validated = Objects.requireNonNull(request, "request");
            acquisitions.add(validated);
            CredentialReference reference = validated.endpoint().credentialReference();
            if (!reference.providerId().equals(fields.providerId())
                || !reference.credentialId().equals(fields.credentialId())
                || reference.kind() != fields.kind()
                || !validated.endpoint().endpointId().equals(fields.endpointId())
                || !validated.authenticationContext().contextId().equals(fields.authenticationContextId())
                || !validated.operation().equals(fields.operation())) {
                throw new IllegalArgumentException(
                    "Credential lease fixture does not match the server binding request"
                );
            }
            return issue(fields);
        }

        @Override
        public synchronized void release(CredentialLease lease, CredentialReleaseReason reason) {
            releases.add(new CredentialRelease(
                Objects.requireNonNull(lease, "lease").leaseId(),
                Objects.requireNonNull(reason, "reason")
            ));
        }

        public synchronized List<CredentialLeaseRequest> acquisitions() {
            return List.copyOf(acquisitions);
        }

        public synchronized List<CredentialRelease> releases() {
            return List.copyOf(releases);
        }
    }

    public record AdapterOpenContext(
        EndpointDescriptor endpoint,
        AuthenticationContext authenticationContext,
        CredentialLease credentialLease,
        long nowEpochSeconds
    ) {
        public AdapterOpenContext {
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            authenticationContext = Objects.requireNonNull(authenticationContext, "authenticationContext");
            credentialLease = Objects.requireNonNull(credentialLease, "credentialLease");
        }
    }

    public record SecurityBoundAttempt(
        SdkTransportPolicyV1.TransportAttempt transportAttempt,
        EndpointDescriptor endpoint,
        AuthenticationContext authenticationContext,
        CredentialLease credentialLease
    ) {
        public SecurityBoundAttempt {
            transportAttempt = Objects.requireNonNull(transportAttempt, "transportAttempt");
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            authenticationContext = Objects.requireNonNull(authenticationContext, "authenticationContext");
            credentialLease = Objects.requireNonNull(credentialLease, "credentialLease");
        }
    }

    public interface SecurityBoundAdapter {
        LifecycleState lifecycleState();

        List<LifecycleState> lifecycleEvents();

        void open(AdapterOpenContext context);

        SdkTransportPolicyV1.AdapterExchange exchange(SecurityBoundAttempt attempt);

        void close(AdapterOpenContext context);
    }

    /** Deterministic lifecycle-aware adapter; it never resolves an address or performs I/O. */
    public static final class ScriptedSecurityBoundAdapter implements SecurityBoundAdapter {
        private final ArrayDeque<SdkTransportPolicyV1.AdapterExchange> script;
        private final List<LifecycleState> events = new ArrayList<>(List.of(LifecycleState.CREATED));
        private final List<SecurityBoundAttempt> invocations = new ArrayList<>();
        private LifecycleState state = LifecycleState.CREATED;
        private AdapterOpenContext openContext;

        public ScriptedSecurityBoundAdapter(List<SdkTransportPolicyV1.AdapterExchange> script) {
            Objects.requireNonNull(script, "script");
            if (script.isEmpty()) {
                throw new IllegalArgumentException("script must contain at least one exchange");
            }
            this.script = new ArrayDeque<>(script);
        }

        @Override
        public synchronized LifecycleState lifecycleState() {
            return state;
        }

        @Override
        public synchronized List<LifecycleState> lifecycleEvents() {
            return List.copyOf(events);
        }

        @Override
        public synchronized void open(AdapterOpenContext context) {
            if (state != LifecycleState.CREATED) {
                throw new IllegalStateException("Adapter can only open from created state");
            }
            openContext = Objects.requireNonNull(context, "context");
            state = LifecycleState.OPEN;
            events.add(LifecycleState.OPEN);
        }

        @Override
        public synchronized SdkTransportPolicyV1.AdapterExchange exchange(SecurityBoundAttempt attempt) {
            if (state != LifecycleState.OPEN || openContext == null) {
                throw new IllegalStateException("Adapter exchange requires open state");
            }
            SecurityBoundAttempt validated = Objects.requireNonNull(attempt, "attempt");
            assertSameBinding(openContext, validated);
            invocations.add(validated);
            SdkTransportPolicyV1.AdapterExchange exchange = script.pollFirst();
            if (exchange == null) {
                return new SdkTransportPolicyV1.AdapterExchange(
                    new SdkTransportPolicyV1.AdapterResponse(
                        0,
                        null,
                        "adapter_script_exhausted",
                        "Adapter script has no response for this attempt",
                        null
                    ),
                    0
                );
            }
            return exchange;
        }

        @Override
        public synchronized void close(AdapterOpenContext context) {
            if (state != LifecycleState.OPEN || openContext == null) {
                throw new IllegalStateException("Adapter can only close from open state");
            }
            assertSameOpenContext(openContext, Objects.requireNonNull(context, "context"));
            state = LifecycleState.CLOSED;
            events.add(LifecycleState.CLOSED);
        }

        public synchronized List<SecurityBoundAttempt> invocations() {
            return List.copyOf(invocations);
        }
    }

    public record BindingExecutionResult(
        BindingStatus status,
        String endpointId,
        String authenticationContextId,
        String credentialLeaseId,
        SdkTransportPolicyV1.ExecutionResult transport,
        ApprovalSdk.Error error,
        List<LifecycleState> lifecycle,
        boolean credentialReleased
    ) {
        public BindingExecutionResult {
            status = Objects.requireNonNull(status, "status");
            endpointId = required(endpointId, "endpointId");
            lifecycle = List.copyOf(lifecycle);
            if (status == BindingStatus.EXECUTED && error != null) {
                throw new IllegalArgumentException("Executed binding cannot contain an error");
            }
            if (status == BindingStatus.BINDING_FAILED && error == null) {
                throw new IllegalArgumentException("Failed binding requires a structured error");
            }
        }
    }

    public static final class UnsupportedAdapterBindingVersionException extends IllegalArgumentException {
        private final String bindingVersion;

        public UnsupportedAdapterBindingVersionException(String bindingVersion) {
            super("Unsupported adapter binding version: " + bindingVersion);
            this.bindingVersion = bindingVersion;
        }

        public String bindingVersion() {
            return bindingVersion;
        }
    }

    public static String credentialLeaseBindingId(
        String endpointId,
        String authenticationContextId,
        String credentialId,
        String operation
    ) {
        return required(endpointId, "endpointId") + "\n"
            + required(authenticationContextId, "authenticationContextId") + "\n"
            + required(credentialId, "credentialId") + "\n"
            + required(operation, "operation");
    }

    public static BindingExecutionResult execute(
        ApprovalSdk.Request request,
        SdkTransportPolicyV1.OperationPolicy policy,
        EndpointDescriptor endpoint,
        AuthenticationContextResolver authenticationContextResolver,
        CredentialLeaseProvider credentialLeaseProvider,
        SecurityBoundAdapter adapter,
        long nowEpochSeconds
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(authenticationContextResolver, "authenticationContextResolver");
        Objects.requireNonNull(credentialLeaseProvider, "credentialLeaseProvider");
        Objects.requireNonNull(adapter, "adapter");
        String requestId = request.correlation().requestId();

        if (!policy.operation().equals(request.operation())
            || !endpoint.supportedOperations().contains(request.operation())) {
            return failure(
                endpoint.endpointId(),
                null,
                null,
                null,
                requestId,
                adapter.lifecycleEvents(),
                false,
                "adapter_operation_not_supported",
                "The logical endpoint does not support the requested operation",
                ApprovalSdk.ErrorCategory.PERMANENT
            );
        }

        AuthenticationContext authenticationContext;
        try {
            authenticationContext = Objects.requireNonNull(
                authenticationContextResolver.resolve(new AuthenticationContextRequest(
                    endpoint.endpointId(),
                    request.operation(),
                    request.correlation().requestId(),
                    request.correlation().traceId()
                )),
                "authenticationContext"
            );
        } catch (RuntimeException exception) {
            return failure(
                endpoint.endpointId(),
                null,
                null,
                null,
                requestId,
                adapter.lifecycleEvents(),
                false,
                "authentication_context_resolution_failed",
                "Server authentication context resolution failed",
                ApprovalSdk.ErrorCategory.UNAUTHORIZED
            );
        }
        if (!active(
            authenticationContext.authenticatedAtEpochSeconds(),
            authenticationContext.expiresAtEpochSeconds(),
            nowEpochSeconds
        )) {
            return failure(
                endpoint.endpointId(),
                authenticationContext.contextId(),
                null,
                null,
                requestId,
                adapter.lifecycleEvents(),
                false,
                "authentication_context_expired",
                "Server authentication context is outside its active interval",
                ApprovalSdk.ErrorCategory.EXPIRED
            );
        }

        CredentialLease credentialLease;
        try {
            credentialLease = Objects.requireNonNull(
                credentialLeaseProvider.acquire(new CredentialLeaseRequest(
                    endpoint,
                    authenticationContext,
                    request.operation(),
                    nowEpochSeconds
                )),
                "credentialLease"
            );
        } catch (RuntimeException exception) {
            return failure(
                endpoint.endpointId(),
                authenticationContext.contextId(),
                null,
                null,
                requestId,
                adapter.lifecycleEvents(),
                false,
                "credential_lease_acquisition_failed",
                "Server credential lease acquisition failed",
                ApprovalSdk.ErrorCategory.UNAUTHORIZED
            );
        }

        String expectedBindingId = credentialLeaseBindingId(
            endpoint.endpointId(),
            authenticationContext.contextId(),
            endpoint.credentialReference().credentialId(),
            request.operation()
        );
        if (!credentialLease.endpointId().equals(endpoint.endpointId())
            || !credentialLease.authenticationContextId().equals(authenticationContext.contextId())
            || !credentialLease.operation().equals(request.operation())
            || !credentialLease.providerId().equals(endpoint.credentialReference().providerId())
            || !credentialLease.credentialId().equals(endpoint.credentialReference().credentialId())
            || credentialLease.kind() != endpoint.credentialReference().kind()
            || !credentialLease.bindingId().equals(expectedBindingId)) {
            return releaseAndFail(
                endpoint,
                authenticationContext,
                credentialLease,
                credentialLeaseProvider,
                adapter,
                requestId,
                "credential_binding_mismatch",
                "Credential lease is not bound to the logical endpoint, context and operation",
                ApprovalSdk.ErrorCategory.PERMANENT,
                CredentialReleaseReason.BINDING_FAILED
            );
        }
        if (!active(
            credentialLease.issuedAtEpochSeconds(),
            credentialLease.expiresAtEpochSeconds(),
            nowEpochSeconds
        )) {
            return releaseAndFail(
                endpoint,
                authenticationContext,
                credentialLease,
                credentialLeaseProvider,
                adapter,
                requestId,
                "credential_lease_expired",
                "Credential lease is outside its active interval",
                ApprovalSdk.ErrorCategory.EXPIRED,
                CredentialReleaseReason.BINDING_FAILED
            );
        }

        AdapterOpenContext openContext = new AdapterOpenContext(
            endpoint,
            authenticationContext,
            credentialLease,
            nowEpochSeconds
        );
        try {
            adapter.open(openContext);
        } catch (RuntimeException exception) {
            return releaseAndFail(
                endpoint,
                authenticationContext,
                credentialLease,
                credentialLeaseProvider,
                adapter,
                requestId,
                "adapter_open_failed",
                "Security-bound adapter failed to open",
                ApprovalSdk.ErrorCategory.PERMANENT,
                CredentialReleaseReason.ADAPTER_FAILED
            );
        }

        SdkTransportPolicyV1.ExecutionResult transport = null;
        boolean lifecycleFailure = false;
        try {
            transport = SdkTransportPolicyV1.execute(request, policy, attempt -> {
                long currentEpochSeconds = nowEpochSeconds + (attempt.elapsedMillis() / 1000L);
                if (currentEpochSeconds >= authenticationContext.expiresAtEpochSeconds()
                    || currentEpochSeconds >= credentialLease.expiresAtEpochSeconds()) {
                    return new SdkTransportPolicyV1.AdapterExchange(
                        new SdkTransportPolicyV1.AdapterResponse(
                            401,
                            null,
                            "security_binding_expired",
                            "Authentication context or credential lease expired before the attempt",
                            null
                        ),
                        0
                    );
                }
                return adapter.exchange(new SecurityBoundAttempt(
                    attempt,
                    endpoint,
                    authenticationContext,
                    credentialLease
                ));
            });
        } catch (RuntimeException exception) {
            lifecycleFailure = true;
        }
        try {
            adapter.close(openContext);
        } catch (RuntimeException exception) {
            lifecycleFailure = true;
        }

        boolean released = false;
        try {
            credentialLeaseProvider.release(
                credentialLease,
                lifecycleFailure
                    ? CredentialReleaseReason.ADAPTER_FAILED
                    : CredentialReleaseReason.COMPLETED
            );
            released = true;
        } catch (RuntimeException exception) {
            lifecycleFailure = true;
        }

        if (lifecycleFailure || transport == null) {
            return failure(
                endpoint.endpointId(),
                authenticationContext.contextId(),
                credentialLease.leaseId(),
                transport,
                requestId,
                adapter.lifecycleEvents(),
                released,
                "adapter_lifecycle_failed",
                "Security-bound adapter lifecycle did not complete cleanly",
                ApprovalSdk.ErrorCategory.PERMANENT
            );
        }
        return new BindingExecutionResult(
            BindingStatus.EXECUTED,
            endpoint.endpointId(),
            authenticationContext.contextId(),
            credentialLease.leaseId(),
            transport,
            null,
            adapter.lifecycleEvents(),
            released
        );
    }

    private static BindingExecutionResult releaseAndFail(
        EndpointDescriptor endpoint,
        AuthenticationContext authenticationContext,
        CredentialLease credentialLease,
        CredentialLeaseProvider provider,
        SecurityBoundAdapter adapter,
        String requestId,
        String code,
        String message,
        ApprovalSdk.ErrorCategory category,
        CredentialReleaseReason reason
    ) {
        boolean released = false;
        try {
            provider.release(credentialLease, reason);
            released = true;
        } catch (RuntimeException exception) {
            // The result exposes only that release did not complete, never provider internals.
        }
        return failure(
            endpoint.endpointId(),
            authenticationContext.contextId(),
            credentialLease.leaseId(),
            null,
            requestId,
            adapter.lifecycleEvents(),
            released,
            code,
            message,
            category
        );
    }

    private static BindingExecutionResult failure(
        String endpointId,
        String authenticationContextId,
        String credentialLeaseId,
        SdkTransportPolicyV1.ExecutionResult transport,
        String requestId,
        List<LifecycleState> lifecycle,
        boolean credentialReleased,
        String code,
        String message,
        ApprovalSdk.ErrorCategory category
    ) {
        return new BindingExecutionResult(
            BindingStatus.BINDING_FAILED,
            endpointId,
            authenticationContextId,
            credentialLeaseId,
            transport,
            new ApprovalSdk.Error(code, message, category, requestId),
            lifecycle,
            credentialReleased
        );
    }

    private static void assertSameBinding(
        AdapterOpenContext context,
        SecurityBoundAttempt attempt
    ) {
        if (!context.endpoint().endpointId().equals(attempt.endpoint().endpointId())
            || !context.authenticationContext().contextId().equals(
                attempt.authenticationContext().contextId()
            )
            || !context.credentialLease().leaseId().equals(attempt.credentialLease().leaseId())) {
            throw new IllegalArgumentException("Attempt does not match the open security binding");
        }
    }

    private static void assertSameOpenContext(
        AdapterOpenContext left,
        AdapterOpenContext right
    ) {
        if (!left.endpoint().endpointId().equals(right.endpoint().endpointId())
            || !left.authenticationContext().contextId().equals(
                right.authenticationContext().contextId()
            )
            || !left.credentialLease().leaseId().equals(right.credentialLease().leaseId())) {
            throw new IllegalArgumentException("Close context does not match the open security binding");
        }
    }

    private static boolean active(long issuedAt, long expiresAt, long now) {
        return now >= issuedAt && now < expiresAt;
    }

    private static void requireBindingVersion(String value) {
        if (!BINDING_VERSION.equals(value)) {
            throw new UnsupportedAdapterBindingVersionException(value);
        }
    }

    private static List<String> uniqueStrings(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        Set<String> seen = new HashSet<>();
        List<String> output = new ArrayList<>();
        for (String value : values) {
            String required = required(value, field);
            if (!seen.add(required)) {
                throw new IllegalArgumentException(field + " contains duplicate value: " + required);
            }
            output.add(required);
        }
        return List.copyOf(output);
    }

    private static String required(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value;
    }
}

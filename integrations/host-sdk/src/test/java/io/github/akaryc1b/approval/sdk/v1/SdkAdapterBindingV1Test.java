package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkAdapterBindingV1Test {
    @Test
    void crossLanguageFixtureExecutesDeterministically() throws IOException {
        Map<String, Object> fixture = AdapterBindingFixtureSupport.fixture();
        Components components = components(fixture, null, null);

        SdkAdapterBindingV1.BindingExecutionResult result = SdkAdapterBindingV1.execute(
            request(fixture),
            policy(fixture),
            endpoint(fixture),
            components.resolver(),
            components.provider(),
            components.adapter(),
            AdapterBindingFixtureSupport.number(fixture, "nowEpochSeconds")
        );
        Map<String, Object> expected = AdapterBindingFixtureSupport.object(fixture, "expectations");

        assertEquals(SdkAdapterBindingV1.BindingStatus.EXECUTED, result.status());
        assertEquals(SdkTransportPolicyV1.ExecutionStatus.SUCCEEDED, result.transport().status());
        assertEquals(AdapterBindingFixtureSupport.number(expected, "totalElapsedMillis"), result.transport().totalElapsedMillis());
        assertEquals(List.of(
            SdkAdapterBindingV1.LifecycleState.CREATED,
            SdkAdapterBindingV1.LifecycleState.OPEN,
            SdkAdapterBindingV1.LifecycleState.CLOSED
        ), result.lifecycle());
        assertTrue(result.credentialReleased());
        assertEquals(1, components.resolver().invocations().size());
        assertEquals(1, components.provider().acquisitions().size());
        assertEquals(1, components.provider().releases().size());
        assertEquals(2, components.adapter().invocations().size());
        ApprovalSdk.Request original = request(fixture);
        Components identityComponents = components(fixture, null, null);
        SdkAdapterBindingV1.execute(
            original,
            policy(fixture),
            endpoint(fixture),
            identityComponents.resolver(),
            identityComponents.provider(),
            identityComponents.adapter(),
            AdapterBindingFixtureSupport.number(fixture, "nowEpochSeconds")
        );
        for (SdkAdapterBindingV1.SecurityBoundAttempt attempt : identityComponents.adapter().invocations()) {
            assertSame(original, attempt.transportAttempt().request());
        }
    }

    @Test
    void bindingIdentifierContainsReferencesOnly() throws IOException {
        Map<String, Object> fixture = AdapterBindingFixtureSupport.fixture();
        Map<String, Object> endpoint = AdapterBindingFixtureSupport.object(fixture, "endpoint");
        Map<String, Object> context = AdapterBindingFixtureSupport.object(fixture, "authenticationContext");
        Map<String, Object> lease = AdapterBindingFixtureSupport.object(fixture, "credentialLease");
        Map<String, Object> reference = AdapterBindingFixtureSupport.object(endpoint, "credentialReference");

        assertEquals(lease.get("bindingId"), SdkAdapterBindingV1.credentialLeaseBindingId(
            (String) endpoint.get("endpointId"),
            (String) context.get("contextId"),
            (String) reference.get("credentialId"),
            (String) lease.get("operation")
        ));
        assertEquals(List.of("credentialId", "kind", "providerId"), reference.keySet().stream().sorted().toList());
    }

    @Test
    void unknownVersionAndDuplicateOperationsFailClosed() throws IOException {
        SdkAdapterBindingV1.EndpointDescriptor valid = endpoint(AdapterBindingFixtureSupport.fixture());
        assertThrows(SdkAdapterBindingV1.UnsupportedAdapterBindingVersionException.class, () ->
            new SdkAdapterBindingV1.EndpointDescriptor(
                "2",
                valid.endpointId(),
                valid.adapterKind(),
                valid.audience(),
                valid.supportedOperations(),
                valid.credentialReference()
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new SdkAdapterBindingV1.EndpointDescriptor(
                "1",
                valid.endpointId(),
                valid.adapterKind(),
                valid.audience(),
                List.of("approval.task.read", "approval.task.read"),
                valid.credentialReference()
            )
        );
    }

    @Test
    void unsupportedOperationStopsBeforeServerBinding() throws IOException {
        Map<String, Object> fixture = AdapterBindingFixtureSupport.fixture();
        Components components = components(fixture, null, null);
        ApprovalSdk.Request original = request(fixture);
        ApprovalSdk.Request unsupported = new ApprovalSdk.Request(
            "approval.task.write",
            original.payload(),
            original.correlation(),
            original.idempotencyKey()
        );
        SdkTransportPolicyV1.OperationPolicy originalPolicy = policy(fixture);
        SdkTransportPolicyV1.OperationPolicy unsupportedPolicy = new SdkTransportPolicyV1.OperationPolicy(
            originalPolicy.policyVersion(),
            unsupported.operation(),
            originalPolicy.retryMode(),
            originalPolicy.budget(),
            originalPolicy.retryableStatusCodes()
        );

        SdkAdapterBindingV1.BindingExecutionResult result = SdkAdapterBindingV1.execute(
            unsupported,
            unsupportedPolicy,
            endpoint(fixture),
            components.resolver(),
            components.provider(),
            components.adapter(),
            AdapterBindingFixtureSupport.number(fixture, "nowEpochSeconds")
        );
        assertEquals("adapter_operation_not_supported", result.error().code());
        assertTrue(components.resolver().invocations().isEmpty());
        assertTrue(components.provider().acquisitions().isEmpty());
        assertEquals(List.of(SdkAdapterBindingV1.LifecycleState.CREATED), components.adapter().lifecycleEvents());
    }

    @Test
    void expiredContextAndLeaseFailClosed() throws IOException {
        Map<String, Object> fixture = AdapterBindingFixtureSupport.fixture();
        long now = AdapterBindingFixtureSupport.number(fixture, "nowEpochSeconds");
        SdkAdapterBindingV1.AuthenticationContextFields context = authenticationContext(fixture);
        SdkAdapterBindingV1.AuthenticationContextFields expiredContext = new SdkAdapterBindingV1.AuthenticationContextFields(
            context.contextId(),
            context.tenantId(),
            context.operatorId(),
            context.permissionSnapshotHash(),
            context.auditReference(),
            context.authenticatedAtEpochSeconds(),
            now
        );
        Components contextComponents = components(fixture, expiredContext, null);
        SdkAdapterBindingV1.BindingExecutionResult contextResult = execute(fixture, contextComponents);
        assertEquals("authentication_context_expired", contextResult.error().code());
        assertTrue(contextComponents.provider().acquisitions().isEmpty());

        SdkAdapterBindingV1.CredentialLeaseFields lease = credentialLease(fixture);
        SdkAdapterBindingV1.CredentialLeaseFields expiredLease = new SdkAdapterBindingV1.CredentialLeaseFields(
            lease.leaseId(),
            lease.providerId(),
            lease.credentialId(),
            lease.kind(),
            lease.endpointId(),
            lease.authenticationContextId(),
            lease.operation(),
            lease.bindingId(),
            now - 10,
            now
        );
        Components leaseComponents = components(fixture, null, expiredLease);
        SdkAdapterBindingV1.BindingExecutionResult leaseResult = execute(fixture, leaseComponents);
        assertEquals("credential_lease_expired", leaseResult.error().code());
        assertTrue(leaseResult.credentialReleased());
        assertEquals(SdkAdapterBindingV1.CredentialReleaseReason.BINDING_FAILED, leaseComponents.provider().releases().get(0).reason());
    }

    @Test
    void credentialBindingMismatchIsReleased() throws IOException {
        Map<String, Object> fixture = AdapterBindingFixtureSupport.fixture();
        SdkAdapterBindingV1.CredentialLeaseFields lease = credentialLease(fixture);
        SdkAdapterBindingV1.CredentialLeaseFields mismatch = new SdkAdapterBindingV1.CredentialLeaseFields(
            lease.leaseId(),
            lease.providerId(),
            lease.credentialId(),
            lease.kind(),
            lease.endpointId(),
            lease.authenticationContextId(),
            lease.operation(),
            "wrong",
            lease.issuedAtEpochSeconds(),
            lease.expiresAtEpochSeconds()
        );
        Components components = components(fixture, null, mismatch);
        SdkAdapterBindingV1.BindingExecutionResult result = execute(fixture, components);
        assertEquals("credential_binding_mismatch", result.error().code());
        assertTrue(result.credentialReleased());
        assertEquals(List.of(SdkAdapterBindingV1.LifecycleState.CREATED), components.adapter().lifecycleEvents());
    }

    @Test
    void trustedContextAndLeaseCannotBeConstructedOrSubmittedByClient() {
        assertTrue(Arrays.stream(SdkAdapterBindingV1.AuthenticationContext.class.getDeclaredConstructors())
            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(Arrays.stream(SdkAdapterBindingV1.CredentialLease.class.getDeclaredConstructors())
            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        List<String> requestComponents = Arrays.stream(ApprovalSdk.Request.class.getRecordComponents())
            .map(component -> component.getName())
            .toList();
        assertEquals(List.of("operation", "payload", "correlation", "idempotencyKey"), requestComponents);
        assertFalse(requestComponents.contains("tenantId"));
        assertFalse(requestComponents.contains("operatorId"));
        assertFalse(requestComponents.contains("credentialLease"));
    }

    private static SdkAdapterBindingV1.BindingExecutionResult execute(
        Map<String, Object> fixture,
        Components components
    ) {
        return SdkAdapterBindingV1.execute(
            request(fixture),
            policy(fixture),
            endpoint(fixture),
            components.resolver(),
            components.provider(),
            components.adapter(),
            AdapterBindingFixtureSupport.number(fixture, "nowEpochSeconds")
        );
    }

    private static Components components(
        Map<String, Object> fixture,
        SdkAdapterBindingV1.AuthenticationContextFields contextOverride,
        SdkAdapterBindingV1.CredentialLeaseFields leaseOverride
    ) {
        return new Components(
            new SdkAdapterBindingV1.StaticAuthenticationContextResolver(
                contextOverride == null ? authenticationContext(fixture) : contextOverride
            ),
            new SdkAdapterBindingV1.StaticCredentialLeaseProvider(
                leaseOverride == null ? credentialLease(fixture) : leaseOverride
            ),
            new SdkAdapterBindingV1.ScriptedSecurityBoundAdapter(script(fixture))
        );
    }

    private static SdkAdapterBindingV1.EndpointDescriptor endpoint(Map<String, Object> fixture) {
        Map<String, Object> endpoint = AdapterBindingFixtureSupport.object(fixture, "endpoint");
        Map<String, Object> reference = AdapterBindingFixtureSupport.object(endpoint, "credentialReference");
        return new SdkAdapterBindingV1.EndpointDescriptor(
            (String) endpoint.get("bindingVersion"),
            (String) endpoint.get("endpointId"),
            SdkAdapterBindingV1.AdapterKind.valueOf(((String) endpoint.get("adapterKind")).toUpperCase()),
            (String) endpoint.get("audience"),
            AdapterBindingFixtureSupport.strings(endpoint, "supportedOperations"),
            new SdkAdapterBindingV1.CredentialReference(
                (String) reference.get("providerId"),
                (String) reference.get("credentialId"),
                SdkAdapterBindingV1.CredentialKind.valueOf(((String) reference.get("kind")).toUpperCase())
            )
        );
    }

    private static SdkAdapterBindingV1.AuthenticationContextFields authenticationContext(
        Map<String, Object> fixture
    ) {
        Map<String, Object> context = AdapterBindingFixtureSupport.object(fixture, "authenticationContext");
        return new SdkAdapterBindingV1.AuthenticationContextFields(
            (String) context.get("contextId"),
            (String) context.get("tenantId"),
            (String) context.get("operatorId"),
            (String) context.get("permissionSnapshotHash"),
            (String) context.get("auditReference"),
            AdapterBindingFixtureSupport.number(context, "authenticatedAtEpochSeconds"),
            AdapterBindingFixtureSupport.number(context, "expiresAtEpochSeconds")
        );
    }

    private static SdkAdapterBindingV1.CredentialLeaseFields credentialLease(
        Map<String, Object> fixture
    ) {
        Map<String, Object> lease = AdapterBindingFixtureSupport.object(fixture, "credentialLease");
        return new SdkAdapterBindingV1.CredentialLeaseFields(
            (String) lease.get("leaseId"),
            (String) lease.get("providerId"),
            (String) lease.get("credentialId"),
            SdkAdapterBindingV1.CredentialKind.valueOf(((String) lease.get("kind")).toUpperCase()),
            (String) lease.get("endpointId"),
            (String) lease.get("authenticationContextId"),
            (String) lease.get("operation"),
            (String) lease.get("bindingId"),
            AdapterBindingFixtureSupport.number(lease, "issuedAtEpochSeconds"),
            AdapterBindingFixtureSupport.number(lease, "expiresAtEpochSeconds")
        );
    }

    private static ApprovalSdk.Request request(Map<String, Object> fixture) {
        Map<String, Object> request = AdapterBindingFixtureSupport.object(fixture, "request");
        Map<String, Object> correlation = AdapterBindingFixtureSupport.object(request, "correlation");
        return new ApprovalSdk.Request(
            (String) request.get("operation"),
            request.get("payload"),
            new ApprovalSdk.Correlation(
                (String) correlation.get("requestId"),
                (String) correlation.get("traceId")
            ),
            (String) request.get("idempotencyKey")
        );
    }

    private static SdkTransportPolicyV1.OperationPolicy policy(Map<String, Object> fixture) {
        Map<String, Object> policy = AdapterBindingFixtureSupport.object(fixture, "policy");
        Map<String, Object> budget = AdapterBindingFixtureSupport.object(policy, "budget");
        @SuppressWarnings("unchecked")
        List<Number> statuses = (List<Number>) policy.get("retryableStatusCodes");
        return new SdkTransportPolicyV1.OperationPolicy(
            (String) policy.get("policyVersion"),
            (String) policy.get("operation"),
            SdkTransportPolicyV1.RetryMode.valueOf(((String) policy.get("retryMode")).toUpperCase()),
            new SdkTransportPolicyV1.RequestBudget(
                (int) AdapterBindingFixtureSupport.number(budget, "maxAttempts"),
                AdapterBindingFixtureSupport.number(budget, "totalBudgetMillis"),
                AdapterBindingFixtureSupport.number(budget, "attemptTimeoutMillis"),
                AdapterBindingFixtureSupport.number(budget, "baseBackoffMillis"),
                AdapterBindingFixtureSupport.number(budget, "maxBackoffMillis")
            ),
            statuses.stream().map(Number::intValue).toList()
        );
    }

    private static List<SdkTransportPolicyV1.AdapterExchange> script(Map<String, Object> fixture) {
        return AdapterBindingFixtureSupport.objects(fixture, "script").stream().map(entry -> {
            Map<String, Object> response = AdapterBindingFixtureSupport.object(entry, "response");
            Object retryAfter = response.get("retryAfterMillis");
            return new SdkTransportPolicyV1.AdapterExchange(
                new SdkTransportPolicyV1.AdapterResponse(
                    ((Number) response.get("statusCode")).intValue(),
                    response.get("payload"),
                    (String) response.get("errorCode"),
                    (String) response.get("errorMessage"),
                    retryAfter == null ? null : ((Number) retryAfter).longValue()
                ),
                AdapterBindingFixtureSupport.number(entry, "durationMillis")
            );
        }).toList();
    }

    private record Components(
        SdkAdapterBindingV1.StaticAuthenticationContextResolver resolver,
        SdkAdapterBindingV1.StaticCredentialLeaseProvider provider,
        SdkAdapterBindingV1.ScriptedSecurityBoundAdapter adapter
    ) {
    }
}

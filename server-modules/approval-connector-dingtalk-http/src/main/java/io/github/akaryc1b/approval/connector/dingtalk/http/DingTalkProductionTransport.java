package io.github.akaryc1b.approval.connector.dingtalk.http;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import io.github.akaryc1b.approval.connector.credential.CapturedCredentialBindingPlan;
import io.github.akaryc1b.approval.connector.credential.CredentialResolutionRequest;
import io.github.akaryc1b.approval.connector.credential.CredentialResolutionStatus;
import io.github.akaryc1b.approval.connector.credential.DingTalkCredentialProfile;
import io.github.akaryc1b.approval.connector.credential.ResolvedScopedCredential;
import io.github.akaryc1b.approval.connector.credential.ServerOwnedCredentialResolver;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransport;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportRequest;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportResponse;

import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default-disabled production DingTalk HTTP transport using one exact P2 credential binding plan.
 */
public final class DingTalkProductionTransport implements DingTalkTransport {

    private final ServerOwnedCredentialResolver credentialResolver;
    private final DingTalkCredentialPlanSource planSource;
    private final DingTalkEndpointPolicy endpointPolicy;
    private final DingTalkHttpSender sender;
    private final Clock clock;

    public DingTalkProductionTransport(
        ServerOwnedCredentialResolver credentialResolver,
        DingTalkCredentialPlanSource planSource,
        Clock clock
    ) {
        this(
            credentialResolver,
            planSource,
            DingTalkEndpointPolicy.systemDefault(),
            JdkDingTalkHttpSender.create(clock),
            clock
        );
    }

    DingTalkProductionTransport(
        ServerOwnedCredentialResolver credentialResolver,
        DingTalkCredentialPlanSource planSource,
        DingTalkEndpointPolicy endpointPolicy,
        DingTalkHttpSender sender,
        Clock clock
    ) {
        this.credentialResolver = Objects.requireNonNull(
            credentialResolver,
            "credentialResolver must not be null"
        );
        this.planSource = Objects.requireNonNull(planSource, "planSource must not be null");
        this.endpointPolicy = Objects.requireNonNull(
            endpointPolicy,
            "endpointPolicy must not be null"
        );
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public DingTalkTransportResponse exchange(DingTalkTransportRequest request) {
        throw new UnsupportedOperationException(
            "production DingTalk transport requires trusted context and operation"
        );
    }

    @Override
    public TransportMode mode() {
        return TransportMode.PRODUCTION;
    }

    @Override
    public DingTalkTransportResponse exchange(
        TrustedConnectorExecutionContext context,
        ConnectorOperation operation,
        DingTalkTransportRequest request
    ) {
        requireDingTalkContext(context);
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(request, "request must not be null");
        DingTalkCredentialProfile.requiredMaterialType(operation);

        DingTalkEndpointPolicy.PreparedEndpoint endpoint;
        try {
            endpoint = endpointPolicy.prepare(operation, request);
        } catch (UnknownHostException exception) {
            return DingTalkTransportResponse.unknown(clock.instant());
        }

        CapturedCredentialBindingPlan plan = plan(context, operation);
        CredentialResolutionRequest resolutionRequest = new CredentialResolutionRequest(
            context,
            operation,
            plan.credentialType(),
            plan.keyId(),
            plan.versionId()
        );
        AtomicReference<DingTalkTransportResponse> response = new AtomicReference<>();
        credentialResolver.useCredential(resolutionRequest, credential -> {
            validateResolvedCredential(plan, credential);
            credential.useSecretBytes(accessToken -> response.set(send(
                endpoint,
                accessToken,
                request
            )));
        });
        DingTalkTransportResponse result = response.get();
        return result == null ? DingTalkTransportResponse.unknown(clock.instant()) : result;
    }

    private DingTalkTransportResponse send(
        DingTalkEndpointPolicy.PreparedEndpoint endpoint,
        byte[] accessToken,
        DingTalkTransportRequest request
    ) {
        try {
            DingTalkTransportResponse response = endpointPolicy.sendWithCredential(
                endpoint,
                accessToken,
                request,
                sender
            );
            return response == null
                ? DingTalkTransportResponse.unknown(clock.instant())
                : response;
        } catch (DingTalkTransportPolicyException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return DingTalkTransportResponse.unknown(clock.instant());
        }
    }

    private CapturedCredentialBindingPlan plan(
        TrustedConnectorExecutionContext context,
        ConnectorOperation operation
    ) {
        CapturedCredentialBindingPlan plan;
        try {
            plan = Objects.requireNonNull(
                planSource.planFor(context, operation),
                "credential plan source returned null"
            );
        } catch (DingTalkTransportPolicyException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DingTalkTransportPolicyException(
                "DingTalk credential binding plan is unavailable",
                exception
            );
        }
        if (!DingTalkCredentialProfile.PROVIDER_KEY.equals(plan.providerKey())
            || plan.operation() != operation
            || plan.credentialType() != DingTalkCredentialProfile.requiredMaterialType(operation)
            || !referenceHash(context).equals(plan.credentialReferenceHash())) {
            throw new DingTalkTransportPolicyException(
                "DingTalk credential binding plan does not match the trusted invocation"
            );
        }
        return plan;
    }

    private static void validateResolvedCredential(
        CapturedCredentialBindingPlan plan,
        ResolvedScopedCredential credential
    ) {
        if (credential.evidence().status() != CredentialResolutionStatus.RESOLVED
            || !plan.credentialReferenceHash().equals(
                credential.evidence().credentialReferenceHash()
            )
            || !plan.descriptorFingerprint().equals(
                credential.evidence().descriptorFingerprint()
            )
            || !plan.providerKey().equals(credential.evidence().providerKey())
            || !plan.keyId().equals(credential.keyId())
            || !plan.versionId().equals(credential.versionId())
            || plan.credentialType() != credential.credentialType()
            || plan.operation() != credential.evidence().operation()) {
            throw new DingTalkTransportPolicyException(
                "resolved credential evidence does not match the DingTalk binding plan"
            );
        }
    }

    private static void requireDingTalkContext(TrustedConnectorExecutionContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (!DingTalkCredentialProfile.PROVIDER_KEY.equals(context.providerKey())
            || !DingTalkCredentialProfile.PROVIDER_KEY.equals(
                context.credentialReference().providerKey()
            )) {
            throw new DingTalkTransportPolicyException(
                "trusted connector context is not owned by DingTalk"
            );
        }
    }

    private static String referenceHash(TrustedConnectorExecutionContext context) {
        return CanonicalPayloadHash.sha256Utf8(
            context.credentialReference().providerKey()
                + "\n"
                + context.credentialReference().referenceId()
        );
    }

    @Override
    public String toString() {
        return "DingTalkProductionTransport[hosts=official-only, credential=<redacted>]";
    }
}

package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.ClientRequestIdSource;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.Deadline;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.ExchangeResult;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.Resolution;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.SecureChannel;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.SecureNetwork;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialEnvironment;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialLease;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialRequest;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSource;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSourceException;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * P6-D exact OpenAI Responses sender with bound DNS/TLS evidence and one HTTP attempt.
 *
 * <p>This type is not a Spring component and is not wired into the server in P6-D. Tests inject a
 * deterministic secure-network boundary and never contact an external Provider.</p>
 */
public final class OpenAiResponsesSecureHttpSender
    implements OpenAiResponsesTransportPort {

    private static final Pattern CLIENT_REQUEST_ID = Pattern.compile(
        "[A-Za-z0-9._:-]{1,128}"
    );
    private static final Duration MAXIMUM_RESOLUTION_AGE = Duration.ofSeconds(30);

    private final OpenAiResponsesEndpointPolicy endpoint;
    private final CredentialMaterialSource credentialSource;
    private final CredentialMaterialRequest credentialRequest;
    private final OpenAiResponsesTransportAdmission admission;
    private final SecureNetwork secureNetwork;
    private final ClientRequestIdSource clientRequestIdSource;
    private final Clock clock;

    public static OpenAiResponsesSecureHttpSender production(
        CredentialMaterialSource credentialSource,
        CredentialMaterialRequest credentialRequest,
        OpenAiResponsesTransportAdmission admission,
        Clock clock
    ) {
        Clock exactClock = Objects.requireNonNull(clock, "clock must not be null");
        return new OpenAiResponsesSecureHttpSender(
            OpenAiResponsesEndpointPolicy.exact(),
            credentialSource,
            credentialRequest,
            admission,
            new OpenAiResponsesJdkSecureNetwork(exactClock),
            () -> UUID.randomUUID().toString(),
            exactClock
        );
    }

    OpenAiResponsesSecureHttpSender(
        OpenAiResponsesEndpointPolicy endpoint,
        CredentialMaterialSource credentialSource,
        CredentialMaterialRequest credentialRequest,
        OpenAiResponsesTransportAdmission admission,
        SecureNetwork secureNetwork,
        ClientRequestIdSource clientRequestIdSource,
        Clock clock
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (endpoint != OpenAiResponsesEndpointPolicy.exact()) {
            throw failure(OpenAiResponsesTransportException.Failure.ENDPOINT_REJECTED);
        }
        this.credentialSource = Objects.requireNonNull(
            credentialSource,
            "credentialSource must not be null"
        );
        this.credentialRequest = requireCredentialRequest(credentialRequest);
        this.admission = Objects.requireNonNull(admission, "admission must not be null");
        if (!this.credentialRequest.tenantHash().equals(admission.tenantHash())) {
            throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
        }
        this.secureNetwork = Objects.requireNonNull(
            secureNetwork,
            "secureNetwork must not be null"
        );
        this.clientRequestIdSource = Objects.requireNonNull(
            clientRequestIdSource,
            "clientRequestIdSource must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Response exchange(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        Deadline totalDeadline = Deadline.start(request.totalTimeout());
        int maximumOutputTokens = OpenAiResponsesRequestProfileValidator.requireExact(request);
        totalDeadline.requireRemaining();
        try (OpenAiResponsesTransportAdmission.Permit permit =
                 admission.admit(request, maximumOutputTokens)) {
            Deadline connectDeadline = Deadline.start(request.connectTimeout());
            Resolution resolution = secureNetwork.resolve(
                endpoint,
                request,
                connectDeadline
            );
            requireResolution(resolution, connectDeadline);
            try (SecureChannel channel = secureNetwork.connect(
                endpoint,
                resolution,
                request,
                connectDeadline
            )) {
                requireChannel(channel, resolution);
                totalDeadline.requireRemaining();
                permit.revalidateBeforeSecret(request);
                ExchangeResult result = exchangeWithSecret(
                    channel,
                    request,
                    permit,
                    totalDeadline
                );
                if (result.statusCode() >= 300 && result.statusCode() <= 399) {
                    throw failure(
                        OpenAiResponsesTransportException.Failure.REDIRECT_REJECTED
                    );
                }
                TransportEvidence evidence = TransportEvidence.verified(
                    endpoint.endpointHash(),
                    permit.admissionEvidenceHash(),
                    resolution.evidenceHash(),
                    channel.connectedAddressHash(),
                    channel.tlsPeerHash(),
                    result.clientRequestIdHash(),
                    OpenAiResponsesProtocol.sha256(result.bodyCopy())
                );
                return new Response(
                    result.statusCode(),
                    result.requestId(),
                    result.bodyCopy(),
                    evidence
                );
            }
        }
    }

    @Override
    public String toString() {
        return "OpenAiResponsesSecureHttpSender[endpointHash=" + endpoint.endpointHash()
            + ", admissionCostPolicyHash=" + admission.costPolicyHash() + "]";
    }

    private ExchangeResult exchangeWithSecret(
        SecureChannel channel,
        Request request,
        OpenAiResponsesTransportAdmission.Permit permit,
        Deadline deadline
    ) {
        AtomicReference<ExchangeResult> result = new AtomicReference<>();
        try (CredentialMaterialLease lease = credentialSource.openLease(credentialRequest)) {
            lease.useMaterial(secret -> {
                permit.revalidateBeforeDispatch(request);
                OpenAiResponsesNetworkSupport.requireNotCancelled(request);
                String clientRequestId = requireClientRequestId(
                    clientRequestIdSource.next()
                );
                OpenAiResponsesHttpCodec.requireApiKey(secret);
                permit.markDispatched(request);
                result.set(channel.exchange(
                    request,
                    secret,
                    clientRequestId,
                    deadline
                ));
            });
        } catch (CredentialMaterialSourceException exception) {
            throw failure(OpenAiResponsesTransportException.Failure.SECRET_UNAVAILABLE);
        } catch (OpenAiResponsesTransportException exception) {
            if (permit.dispatched()) {
                throw failure(OpenAiResponsesTransportException.Failure.UNKNOWN);
            }
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(OpenAiResponsesTransportException.Failure.UNKNOWN);
        }
        ExchangeResult completed = result.get();
        if (completed == null) {
            throw failure(OpenAiResponsesTransportException.Failure.UNKNOWN);
        }
        OpenAiResponsesTransportControls.Outcome outcome = completed.statusCode() == 200
            ? OpenAiResponsesTransportControls.Outcome.SUCCESS
            : OpenAiResponsesTransportControls.Outcome.HTTP_REJECTED;
        permit.record(outcome);
        return completed;
    }

    private void requireResolution(Resolution resolution, Deadline deadline) {
        Objects.requireNonNull(resolution, "resolution must not be null");
        deadline.requireRemaining();
        Instant now = clock.instant();
        if (!endpoint.endpointHash().equals(resolution.endpointHash())
            || resolution.addresses().isEmpty()
            || resolution.addresses().size() > 32
            || now.isBefore(resolution.resolvedAt())
            || Duration.between(resolution.resolvedAt(), now)
                .compareTo(MAXIMUM_RESOLUTION_AGE) > 0) {
            throw failure(OpenAiResponsesTransportException.Failure.DNS_DRIFT);
        }
        for (java.net.InetAddress address : resolution.addresses()) {
            if (!OpenAiResponsesNetworkSupport.isPublicAddress(address)) {
                throw failure(OpenAiResponsesTransportException.Failure.DNS_UNSAFE);
            }
        }
    }

    private static void requireChannel(SecureChannel channel, Resolution resolution) {
        Objects.requireNonNull(channel, "channel must not be null");
        if (!channel.tlsVerified()
            || !resolution.addressHashes().contains(channel.connectedAddressHash())) {
            throw failure(OpenAiResponsesTransportException.Failure.CONNECTION_DRIFT);
        }
    }

    private static CredentialMaterialRequest requireCredentialRequest(
        CredentialMaterialRequest request
    ) {
        Objects.requireNonNull(request, "credentialRequest must not be null");
        boolean invalid = !OpenAiEnvironmentCredentialMaterialSource.PROVIDER_KEY.equals(
            request.providerKey()
        ) || !OpenAiEnvironmentCredentialMaterialSource.CREDENTIAL_REFERENCE_ID.equals(
            request.credentialReference().referenceId()
        ) || !OpenAiEnvironmentCredentialMaterialSource.PROTOCOL_PROFILE.equals(
            request.protocolProfile()
        ) || !OpenAiEnvironmentCredentialMaterialSource.CAPABILITY.equals(
            request.capability()
        ) || request.materialType() != CredentialMaterialType.API_KEY
            || request.operation() != ConnectorOperation.AI_ADVISORY_GENERATE
            || request.environment() != CredentialMaterialEnvironment.PRODUCTION;
        if (invalid) {
            throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
        }
        return request;
    }

    private static String requireClientRequestId(String value) {
        if (value == null || !CLIENT_REQUEST_ID.matcher(value).matches()) {
            throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
        }
        return value;
    }

    private static OpenAiResponsesTransportException failure(
        OpenAiResponsesTransportException.Failure failure
    ) {
        return new OpenAiResponsesTransportException(failure);
    }
}

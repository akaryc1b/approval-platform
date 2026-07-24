package io.github.akaryc1b.approval.connector.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.connector.contract.ConnectorExecutionPort;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryQuery;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryReadCommand;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryReadResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorOutcome;
import io.github.akaryc1b.approval.connector.contract.ConnectorRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorResult;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import io.github.akaryc1b.approval.connector.contract.ProviderFailureClass;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;

import java.util.Map;
import java.util.Objects;

/**
 * Captured DingTalk directory adapter. Only USER_BY_ID is executable in M6-A-P1.
 */
public final class DingTalkDirectoryExecutionPort
    implements ConnectorExecutionPort<DirectoryReadCommand, DirectoryReadResult> {

    private final DingTalkTransport transport;
    private final DingTalkRequestEncoder encoder;
    private final DingTalkResponseDecoder decoder;

    public DingTalkDirectoryExecutionPort(DingTalkTransport transport) {
        this(transport, new DingTalkRequestEncoder(), new DingTalkResponseDecoder(new ObjectMapper()));
    }

    DingTalkDirectoryExecutionPort(
        DingTalkTransport transport,
        DingTalkRequestEncoder encoder,
        DingTalkResponseDecoder decoder
    ) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
        this.decoder = Objects.requireNonNull(decoder, "decoder must not be null");
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DingTalkProviderContract.descriptor();
    }

    @Override
    public ConnectorResult<DirectoryReadResult> execute(
        TrustedConnectorExecutionContext context,
        ConnectorRequest<DirectoryReadCommand> request
    ) {
        DingTalkProviderContract.requireContext(context);
        Objects.requireNonNull(request, "request must not be null");
        if (request.operation() != ConnectorOperation.ORGANIZATION_READ) {
            throw new IllegalArgumentException("request is not an organization read");
        }
        descriptor().requireEnabledCapability(request.operation().requiredCapability());
        DirectoryReadCommand command = request.payload();
        if (command.query() != DirectoryQuery.USER_BY_ID) {
            throw new IllegalArgumentException(
                "DingTalk execution supports only USER_BY_ID in this slice"
            );
        }

        DingTalkTransportRequest transportRequest = encoder.encodeDirectory(command);
        DingTalkTransportResponse response = Objects.requireNonNull(
            transport.exchange(transportRequest),
            "transport must not return null"
        );
        DingTalkResultSupport.TransportFailure transportFailure =
            DingTalkResultSupport.classify(response);
        if (transportFailure != null) {
            return DingTalkResultSupport.failure(
                request,
                transportRequest,
                response,
                transportFailure
            );
        }

        try {
            DingTalkUserDetail detail = decoder.decodeUserDetail(response.body());
            if (!command.subjectId().value().equals(detail.userId())) {
                return DingTalkResultSupport.failure(
                    request,
                    transportRequest,
                    response,
                    ConnectorOutcome.UNKNOWN,
                    "DINGTALK_RESPONSE_ID_MISMATCH",
                    ProviderFailureClass.UNKNOWN,
                    "DingTalk user-detail response ID does not match request",
                    Map.of()
                );
            }
            return DingTalkResultSupport.success(
                DingTalkUserMappings.directoryResult(detail),
                request,
                transportRequest,
                response
            );
        } catch (DingTalkProviderException exception) {
            return DingTalkResultSupport.failure(
                request,
                transportRequest,
                response,
                ConnectorOutcome.REJECTED,
                "DINGTALK_PROVIDER_REJECTED",
                ProviderFailureClass.PERMANENT,
                exception.getMessage(),
                Map.of("providerCode", Long.toString(exception.providerCode()))
            );
        } catch (RuntimeException exception) {
            return DingTalkResultSupport.failure(
                request,
                transportRequest,
                response,
                ConnectorOutcome.UNKNOWN,
                "DINGTALK_RESPONSE_INVALID",
                ProviderFailureClass.UNKNOWN,
                "DingTalk response could not be parsed",
                Map.of()
            );
        }
    }
}

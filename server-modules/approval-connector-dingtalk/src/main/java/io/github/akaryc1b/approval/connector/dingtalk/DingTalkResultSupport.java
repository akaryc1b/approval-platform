package io.github.akaryc1b.approval.connector.dingtalk;

import io.github.akaryc1b.approval.connector.contract.ConnectorError;
import io.github.akaryc1b.approval.connector.contract.ConnectorOutcome;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorResult;
import io.github.akaryc1b.approval.connector.contract.IdempotencyEvidence;
import io.github.akaryc1b.approval.connector.contract.IdempotencyEvidence.IdempotencyResult;
import io.github.akaryc1b.approval.connector.contract.ProviderFailureClass;

import java.util.LinkedHashMap;
import java.util.Map;

final class DingTalkResultSupport {

    private DingTalkResultSupport() {
    }

    static TransportFailure classify(DingTalkTransportResponse response) {
        if (response.state() == DingTalkTransportResponse.State.TIMEOUT) {
            return new TransportFailure(
                ConnectorOutcome.TIMEOUT,
                ProviderFailureClass.TIMEOUT,
                "DINGTALK_TIMEOUT",
                "DingTalk transport timed out"
            );
        }
        if (response.state() == DingTalkTransportResponse.State.UNKNOWN) {
            return new TransportFailure(
                ConnectorOutcome.UNKNOWN,
                ProviderFailureClass.UNKNOWN,
                "DINGTALK_UNKNOWN",
                "DingTalk transport outcome is unknown"
            );
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return null;
        }
        if (response.statusCode() == 408) {
            return new TransportFailure(
                ConnectorOutcome.TIMEOUT,
                ProviderFailureClass.TIMEOUT,
                "DINGTALK_HTTP_TIMEOUT",
                "DingTalk returned HTTP timeout"
            );
        }
        if (response.statusCode() == 429) {
            return new TransportFailure(
                ConnectorOutcome.RATE_LIMITED,
                ProviderFailureClass.RATE_LIMIT,
                "DINGTALK_RATE_LIMITED",
                "DingTalk rate limited the request"
            );
        }
        if (response.statusCode() >= 500) {
            return new TransportFailure(
                ConnectorOutcome.RETRYABLE_PROVIDER_FAILURE,
                ProviderFailureClass.TRANSIENT,
                "DINGTALK_HTTP_RETRYABLE",
                "DingTalk returned a retryable HTTP failure"
            );
        }
        if (response.statusCode() >= 400) {
            return new TransportFailure(
                ConnectorOutcome.PERMANENT_PROVIDER_FAILURE,
                ProviderFailureClass.PERMANENT,
                "DINGTALK_HTTP_PERMANENT",
                "DingTalk returned a permanent HTTP failure"
            );
        }
        return new TransportFailure(
            ConnectorOutcome.UNKNOWN,
            ProviderFailureClass.UNKNOWN,
            "DINGTALK_HTTP_UNKNOWN",
            "DingTalk returned an unclassified HTTP status"
        );
    }

    static <R> ConnectorResult<R> success(
        R value,
        ConnectorRequest<?> request,
        DingTalkTransportRequest transportRequest,
        DingTalkTransportResponse response
    ) {
        return ConnectorResult.success(
            value,
            providerResult(transportRequest, response),
            idempotency(request),
            request.securityEvidence()
        );
    }

    static <R> ConnectorResult<R> failure(
        ConnectorRequest<?> request,
        DingTalkTransportRequest transportRequest,
        DingTalkTransportResponse response,
        TransportFailure failure
    ) {
        return failure(
            request,
            transportRequest,
            response,
            failure.outcome(),
            failure.code(),
            failure.failureClass(),
            failure.message(),
            Map.of("statusCode", Integer.toString(response.statusCode()))
        );
    }

    static <R> ConnectorResult<R> failure(
        ConnectorRequest<?> request,
        DingTalkTransportRequest transportRequest,
        DingTalkTransportResponse response,
        ConnectorOutcome outcome,
        String code,
        ProviderFailureClass failureClass,
        String message,
        Map<String, String> details
    ) {
        return ConnectorResult.failure(
            outcome,
            providerResult(transportRequest, response),
            idempotency(request),
            request.securityEvidence(),
            new ConnectorError(code, failureClass, message, details)
        );
    }

    private static ConnectorProviderResult providerResult(
        DingTalkTransportRequest request,
        DingTalkTransportResponse response
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("adapter", "dingtalk-captured");
        metadata.put("apiFamily", request.apiFamily().name());
        metadata.put("path", request.path());
        metadata.put("transportState", response.state().name());
        return new ConnectorProviderResult(
            response.providerRequestId(),
            response.statusCode(),
            response.completedAt(),
            metadata
        );
    }

    private static IdempotencyEvidence idempotency(ConnectorRequest<?> request) {
        return new IdempotencyEvidence(
            request.idempotencyKey(),
            request.canonicalPayloadHash(),
            IdempotencyResult.FIRST_SEEN
        );
    }

    record TransportFailure(
        ConnectorOutcome outcome,
        ProviderFailureClass failureClass,
        String code,
        String message
    ) {
    }
}

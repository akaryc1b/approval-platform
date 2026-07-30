package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderTransportFixtureObservation;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingResult;

import java.util.Objects;

/** Evaluates precomputed transport fixtures without dispatching a request. */
public final class AiProviderTransportLifecycleEvaluator {

    public AiProviderTransportLifecycleReport evaluate(
        AiProviderTransportMappingRequest request,
        AiProviderTransportMappingResult mapping,
        AiProviderTransportFixtureObservation observation
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(mapping, "mapping must not be null");
        Objects.requireNonNull(observation, "observation must not be null");
        requireIdentityMatch(request, mapping, observation);

        AiProviderTransportLifecycleReport.Status status;
        AiProviderTransportLifecycleReport.FailureClass failure;

        if (request.cancellationRequested() || observation.cancellationObserved()) {
            status = AiProviderTransportLifecycleReport.Status.CANCELLED_BEFORE_DISPATCH;
            failure = AiProviderTransportLifecycleReport.FailureClass.CANCELLATION_REQUESTED;
        } else if (observation.timeoutObserved()) {
            status = AiProviderTransportLifecycleReport.Status.TIMED_OUT_BEFORE_DISPATCH;
            failure = AiProviderTransportLifecycleReport.FailureClass.TIMEOUT_OBSERVED;
        } else if (mapping.status()
            != AiProviderTransportMappingResult.Status.MAPPED_FOR_OFFLINE_VALIDATION) {
            status = AiProviderTransportLifecycleReport.Status.BLOCKED;
            failure = mappingFailure(mapping.status());
        } else {
            failure = responseFailure(
                observation.shape(),
                mapping.responseSchemaHash(),
                observation.observedResponseSchemaHash()
            );
            status = failure == AiProviderTransportLifecycleReport.FailureClass.NONE
                ? AiProviderTransportLifecycleReport.Status.READY_FOR_OFFLINE_ASSERTION
                : AiProviderTransportLifecycleReport.Status.RESPONSE_REJECTED;
        }

        return AiProviderTransportLifecycleReport.create(
            request.providerVersion(),
            mapping.status(),
            observation.shape(),
            status,
            failure,
            mapping.requestEvidenceHash(),
            mapping.transportEnvelopeHash(),
            observation.responseEvidenceHash(),
            mapping.responseSchemaHash(),
            observation.observedResponseSchemaHash()
        );
    }

    private static void requireIdentityMatch(
        AiProviderTransportMappingRequest request,
        AiProviderTransportMappingResult mapping,
        AiProviderTransportFixtureObservation observation
    ) {
        if (!request.providerVersion().equals(mapping.providerVersion())
            || !request.providerVersion().equals(observation.providerVersion())) {
            throw new IllegalArgumentException("Provider version evidence must match exactly");
        }
        if (!request.mapperAuthorizationKey().equals(mapping.mapperAuthorizationKey())
            || !request.mapperAuthorizationKey().equals(
                observation.mapperAuthorizationKey()
            )) {
            throw new IllegalArgumentException("transport mapper identity must match exactly");
        }
        if (!request.requestEvidenceHash().equals(mapping.requestEvidenceHash())) {
            throw new IllegalArgumentException("request evidence hash must match exactly");
        }
    }

    private static AiProviderTransportLifecycleReport.FailureClass mappingFailure(
        AiProviderTransportMappingResult.Status status
    ) {
        return switch (status) {
            case REJECTED -> AiProviderTransportLifecycleReport.FailureClass.MAPPING_REJECTED;
            case UNSUPPORTED ->
                AiProviderTransportLifecycleReport.FailureClass.MAPPING_UNSUPPORTED;
            case UNKNOWN -> AiProviderTransportLifecycleReport.FailureClass.MAPPING_UNKNOWN;
            case MAPPED_FOR_OFFLINE_VALIDATION ->
                AiProviderTransportLifecycleReport.FailureClass.NONE;
        };
    }

    private static AiProviderTransportLifecycleReport.FailureClass responseFailure(
        AiProviderTransportFixtureObservation.Shape shape,
        String expectedSchemaHash,
        String observedSchemaHash
    ) {
        return switch (shape) {
            case STRUCTURED_VALID -> expectedSchemaHash.equals(observedSchemaHash)
                ? AiProviderTransportLifecycleReport.FailureClass.NONE
                : AiProviderTransportLifecycleReport.FailureClass.RESPONSE_SCHEMA_MISMATCH;
            case MALFORMED_JSON ->
                AiProviderTransportLifecycleReport.FailureClass.MALFORMED_JSON;
            case SCHEMA_DRIFT -> AiProviderTransportLifecycleReport.FailureClass.SCHEMA_DRIFT;
            case UNKNOWN_FIELDS ->
                AiProviderTransportLifecycleReport.FailureClass.UNKNOWN_FIELDS;
            case BODY_TOO_LARGE ->
                AiProviderTransportLifecycleReport.FailureClass.BODY_TOO_LARGE;
            case TIMEOUT -> AiProviderTransportLifecycleReport.FailureClass.TIMEOUT_OBSERVED;
            case CANCELLED ->
                AiProviderTransportLifecycleReport.FailureClass.CANCELLATION_REQUESTED;
            case CONNECTION_ERROR ->
                AiProviderTransportLifecycleReport.FailureClass.CONNECTION_ERROR;
            case EMPTY_BODY -> AiProviderTransportLifecycleReport.FailureClass.EMPTY_BODY;
            case UNKNOWN -> AiProviderTransportLifecycleReport.FailureClass.UNKNOWN_RESPONSE;
        };
    }
}

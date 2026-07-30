package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMapper;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingResult;

import java.util.List;

/** Deterministic offline transport mapper for tests only. */
final class DeterministicProviderTransportMapper implements AiProviderTransportMapper {

    private final Mode mode;
    private final String envelopeHash;

    DeterministicProviderTransportMapper(Mode mode, String envelopeHash) {
        this.mode = mode;
        this.envelopeHash = envelopeHash;
    }

    @Override
    public AiProviderTransportMappingResult map(AiProviderTransportMappingRequest request) {
        if (request.cancellationRequested()) {
            return result(
                request,
                AiProviderTransportMappingResult.Status.REJECTED,
                null,
                List.of("AI_TRANSPORT_CANCELLED_BEFORE_MAPPING")
            );
        }
        return switch (mode) {
            case MAPPED -> result(
                request,
                AiProviderTransportMappingResult.Status.MAPPED_FOR_OFFLINE_VALIDATION,
                envelopeHash,
                List.of()
            );
            case REJECTED -> result(
                request,
                AiProviderTransportMappingResult.Status.REJECTED,
                null,
                List.of("AI_TRANSPORT_MAPPING_REJECTED")
            );
            case UNSUPPORTED -> result(
                request,
                AiProviderTransportMappingResult.Status.UNSUPPORTED,
                null,
                List.of("AI_TRANSPORT_MAPPING_UNSUPPORTED")
            );
            case UNKNOWN -> result(
                request,
                AiProviderTransportMappingResult.Status.UNKNOWN,
                null,
                List.of("AI_TRANSPORT_MAPPING_UNKNOWN")
            );
        };
    }

    enum Mode {
        MAPPED,
        REJECTED,
        UNSUPPORTED,
        UNKNOWN
    }

    private static AiProviderTransportMappingResult result(
        AiProviderTransportMappingRequest request,
        AiProviderTransportMappingResult.Status status,
        String envelopeHash,
        List<String> codes
    ) {
        return new AiProviderTransportMappingResult(
            request.mapperAuthorizationKey(),
            request.providerVersion(),
            status,
            request.requestEvidenceHash(),
            envelopeHash,
            request.responseSchemaHash(),
            codes,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false
        );
    }
}

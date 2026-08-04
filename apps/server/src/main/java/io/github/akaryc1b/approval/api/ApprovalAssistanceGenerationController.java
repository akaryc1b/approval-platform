package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.akaryc1b.approval.api.ApprovalAssistanceGenerationContracts.GenerationRequest;
import io.github.akaryc1b.approval.api.ApprovalAssistanceGenerationContracts.GenerationView;
import io.github.akaryc1b.approval.api.ApprovalAssistanceGenerationContracts.InvalidGenerationRequestException;
import io.github.akaryc1b.approval.api.ApprovalAssistanceGenerationContracts.ResultStatus;
import io.github.akaryc1b.approval.api.ApprovalAssistanceGenerationService.GenerationOutcome;
import io.github.akaryc1b.approval.api.ApprovalAssistanceGenerationService.GenerationStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

import static io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.OPERATOR_ID_HEADER;
import static io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.REQUEST_ID_HEADER;
import static io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.TENANT_ID_HEADER;
import static io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter.TRACE_ID_HEADER;

/** Explicit, synchronous P6-E advisory generation API. */
@RestController
@RequestMapping("/api/approval/tasks")
public final class ApprovalAssistanceGenerationController {

    private final ApprovalAssistanceGenerationService service;

    public ApprovalAssistanceGenerationController(
        ApprovalAssistanceGenerationService service
    ) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @PostMapping("/{taskId}/assistance/generations")
    public ResponseEntity<GenerationView> generate(
        @RequestHeader(TENANT_ID_HEADER) String trustedTenantId,
        @RequestHeader(OPERATOR_ID_HEADER) String trustedOperatorId,
        @RequestHeader(REQUEST_ID_HEADER) String trustedRequestId,
        @RequestHeader(TRACE_ID_HEADER) String trustedTraceId,
        @PathVariable UUID taskId,
        @RequestBody JsonNode body
    ) {
        GenerationRequest request;
        try {
            request = ApprovalAssistanceGenerationContracts.parseRequest(body);
        } catch (InvalidGenerationRequestException invalid) {
            return response(
                HttpStatus.BAD_REQUEST,
                GenerationView.failure(
                    ResultStatus.INVALID_REQUEST,
                    "AI_ASSISTANCE_REQUEST_INVALID"
                )
            );
        }

        GenerationOutcome outcome = service.generate(
            trustedTenantId,
            trustedOperatorId,
            trustedRequestId,
            trustedTraceId,
            taskId,
            request.useCase()
        );
        return switch (outcome.status()) {
            case SUCCESS -> response(
                HttpStatus.OK,
                GenerationView.success(false, outcome.evidenceId(), outcome.advisory())
            );
            case LOW_CONFIDENCE -> response(
                HttpStatus.OK,
                GenerationView.success(true, outcome.evidenceId(), outcome.advisory())
            );
            case DISABLED -> failure(
                HttpStatus.SERVICE_UNAVAILABLE,
                ResultStatus.DISABLED,
                "AI_ASSISTANCE_DISABLED"
            );
            case NOT_FOUND -> failure(
                HttpStatus.NOT_FOUND,
                ResultStatus.NOT_FOUND,
                "AI_ASSISTANCE_NOT_FOUND"
            );
            case STALE_TASK -> failure(
                HttpStatus.CONFLICT,
                ResultStatus.STALE_TASK,
                "AI_ASSISTANCE_STALE_TASK"
            );
            case POLICY_BLOCKED -> failure(
                HttpStatus.TOO_MANY_REQUESTS,
                ResultStatus.POLICY_BLOCKED,
                "AI_ASSISTANCE_POLICY_BLOCKED"
            );
            case PROVIDER_UNAVAILABLE -> failure(
                HttpStatus.SERVICE_UNAVAILABLE,
                ResultStatus.PROVIDER_UNAVAILABLE,
                "AI_ASSISTANCE_PROVIDER_UNAVAILABLE"
            );
            case TIMEOUT -> failure(
                HttpStatus.GATEWAY_TIMEOUT,
                ResultStatus.TIMEOUT,
                "AI_ASSISTANCE_TIMEOUT"
            );
            case INVALID_OUTPUT -> failure(
                HttpStatus.BAD_GATEWAY,
                ResultStatus.INVALID_OUTPUT,
                "AI_ASSISTANCE_INVALID_OUTPUT"
            );
            case UNKNOWN -> failure(
                HttpStatus.BAD_GATEWAY,
                ResultStatus.UNKNOWN,
                "AI_ASSISTANCE_UNKNOWN"
            );
            case EVIDENCE_CONFLICT -> failure(
                HttpStatus.CONFLICT,
                ResultStatus.EVIDENCE_CONFLICT,
                "AI_ASSISTANCE_EVIDENCE_CONFLICT"
            );
            case EVIDENCE_UNAVAILABLE -> failure(
                HttpStatus.SERVICE_UNAVAILABLE,
                ResultStatus.EVIDENCE_UNAVAILABLE,
                "AI_ASSISTANCE_EVIDENCE_UNAVAILABLE"
            );
        };
    }

    private static ResponseEntity<GenerationView> failure(
        HttpStatus status,
        ResultStatus resultStatus,
        String code
    ) {
        return response(status, GenerationView.failure(resultStatus, code));
    }

    private static ResponseEntity<GenerationView> response(
        HttpStatus status,
        GenerationView body
    ) {
        return ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore())
            .body(body);
    }
}

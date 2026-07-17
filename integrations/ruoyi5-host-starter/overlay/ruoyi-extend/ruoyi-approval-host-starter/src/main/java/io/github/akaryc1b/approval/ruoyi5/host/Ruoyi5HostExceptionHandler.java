package io.github.akaryc1b.approval.ruoyi5.host;

import io.github.akaryc1b.approval.host.model.ConnectorError;
import io.github.akaryc1b.approval.host.security.HostVerificationException;
import io.github.akaryc1b.approval.host.web.HostErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = Ruoyi5ApprovalHostController.class)
public final class Ruoyi5HostExceptionHandler {

    @ExceptionHandler(HostVerificationException.class)
    public ResponseEntity<HostErrorResponse> verification(
        HostVerificationException exception,
        HttpServletRequest request
    ) {
        ConnectorError error = exception.error();
        int status = switch (error.code()) {
            case "REPLAYED_REQUEST" -> 409;
            case "INVALID_REQUEST" -> 400;
            default -> 401;
        };
        return response(status, error, requestId(request));
    }

    @ExceptionHandler(Ruoyi5HostException.class)
    public ResponseEntity<HostErrorResponse> adapter(
        Ruoyi5HostException exception,
        HttpServletRequest request
    ) {
        return response(
            exception.status(),
            new ConnectorError(exception.code(), exception.getMessage(), exception.retryable()),
            requestId(request)
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<HostErrorResponse> missingHeader(
        MissingRequestHeaderException exception,
        HttpServletRequest request
    ) {
        return response(
            400,
            ConnectorError.invalidRequest("required connector header is missing"),
            requestId(request)
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<HostErrorResponse> unexpected(
        Exception exception,
        HttpServletRequest request
    ) {
        return response(
            500,
            ConnectorError.temporaryFailure("host connector request failed"),
            requestId(request)
        );
    }

    private static ResponseEntity<HostErrorResponse> response(
        int status,
        ConnectorError error,
        String requestId
    ) {
        return ResponseEntity.status(status).body(
            HostErrorResponse.from(error, requestId, Instant.now())
        );
    }

    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}

package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferExceptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProcessTemplateManagementApiExceptionHandlerTest {

    private final ProcessTemplateManagementApiExceptionHandler handler =
        new ProcessTemplateManagementApiExceptionHandler();

    @Test
    void artifactTransferFailuresRetainEstablishedClientMappings() {
        MockHttpServletRequest request = request();

        assertMapping(handler.transferInvalidFormat(
            new ApprovalArtifactTransferExceptions.InvalidFormat("invalid"), request),
            400, "APPROVAL_TRANSFER_INVALID_FORMAT");
        assertMapping(handler.transferUnsupportedVersion(
            new ApprovalArtifactTransferExceptions.UnsupportedVersion("unsupported"), request),
            400, "APPROVAL_TRANSFER_UNSUPPORTED_VERSION");
        assertMapping(handler.transferTooLarge(
            new ApprovalArtifactTransferExceptions.TooLarge("too large"), request),
            400, "APPROVAL_TRANSFER_TOO_LARGE");
        assertMapping(handler.transferHashMismatch(
            new ApprovalArtifactTransferExceptions.HashMismatch("hash"), request),
            400, "APPROVAL_TRANSFER_HASH_MISMATCH");
        assertMapping(handler.transferIntegrityFailed(
            new ApprovalArtifactTransferExceptions.ArtifactIntegrityFailed("integrity"), request),
            400, "APPROVAL_TRANSFER_ARTIFACT_INTEGRITY_FAILED");
        assertMapping(handler.transferSourceNotFound(
            new ApprovalArtifactTransferExceptions.SourceNotFound("missing"), request),
            404, "APPROVAL_TRANSFER_SOURCE_NOT_FOUND");
        assertMapping(handler.transferFormPackageIncompatible(
            new ApprovalArtifactTransferExceptions.FormPackageIncompatible("incompatible"),
            request), 409, "APPROVAL_TRANSFER_FORM_PACKAGE_INCOMPATIBLE");
        assertMapping(handler.transferImportConflict(
            new ApprovalArtifactTransferExceptions.ImportConflict("conflict"), request),
            409, "APPROVAL_TRANSFER_IMPORT_CONFLICT");
        assertMapping(handler.transferValidationFailed(
            new ApprovalArtifactTransferExceptions.ValidationFailed("validation"), request),
            422, "APPROVAL_TRANSFER_VALIDATION_FAILED");
    }

    private static void assertMapping(
        ResponseEntity<ProcessTemplateManagementApiExceptionHandler.ApiError> response,
        int status,
        String errorCode
    ) {
        assertEquals(status, response.getStatusCode().value());
        assertEquals(errorCode, response.getBody().errorCode());
        assertEquals("request-a", response.getHeaders().getFirst("X-Request-Id"));
        assertEquals("trace-a", response.getHeaders().getFirst("X-Trace-Id"));
        assertFalse(response.getBody().retryable());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-a");
        request.addHeader("X-Trace-Id", "trace-a");
        return request;
    }
}

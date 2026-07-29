package io.github.akaryc1b.approval.api;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectorOperationsDiagnosticsApiExceptionHandlerTest {

    @Test
    void stableRedactedStatusesCoverTheClosedFailureSet() {
        ConnectorOperationsDiagnosticsApiExceptionHandler handler =
            new ConnectorOperationsDiagnosticsApiExceptionHandler();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Request-Id")).thenReturn("request-1");
        assertEquals(400, handler.invalid(request).getStatusCode().value());
        assertEquals(404, handler.notFound(request).getStatusCode().value());
        assertEquals(409, handler.conflict(request).getStatusCode().value());
        assertEquals(422, handler.tooLarge(request).getStatusCode().value());
        assertEquals(503, handler.unavailable(request).getStatusCode().value());
        assertEquals(500, handler.internal(request).getStatusCode().value());
    }
}

package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalAuditService;
import io.github.akaryc1b.approval.application.ApprovalCommentService;
import io.github.akaryc1b.approval.application.ApprovalNotificationService;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditPage;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationHistoryPage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual controller binding with mocked services, not authorization or database acceptance. */
class ApprovalAuditMessageTypedInputTest {
    private static final String AUDIT = "/api/approval/management/audit";
    private static final String RANGE = "?occurredFrom=2026-10-01T00:00:00Z&occurredTo=2026-10-02T00:00:00Z";
    private static final UUID INSTANCE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String COMMENTS = "/api/approval/instances/" + INSTANCE + "/comments";
    private final ApprovalAuditService audits = mock(ApprovalAuditService.class);
    private final ApprovalCommentService comments = mock(ApprovalCommentService.class);
    private final ApprovalNotificationService notifications = mock(ApprovalNotificationService.class);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
            new ApprovalAuditController(audits, new ObjectMapper().findAndRegisterModules()),
            new ApprovalCommentController(comments), new ApprovalNotificationController(notifications)
        ).setControllerAdvice(
            new ApprovalAuditApiExceptionHandler(), new ApprovalCommentApiExceptionHandler(),
            new ApprovalNotificationApiExceptionHandler()
        ).build();
    }

    @TestFactory
    Stream<DynamicTest> malformedRequestsNeverReachServices() {
        return Stream.of(
            new Probe("audit typed limit", get(AUDIT + RANGE + "&limit=bad"), "INVALID_REQUEST"),
            new Probe("audit missing range", get(AUDIT), "INVALID_REQUEST"),
            new Probe("audit malformed JSON", post(AUDIT + "/integrity/verify")
                .contentType("application/json").content("{"), "INVALID_REQUEST"),
            new Probe("comment typed limit", get(COMMENTS + "?limit=bad"), "INVALID_REQUEST"),
            new Probe("comment malformed JSON", post(COMMENTS)
                .contentType("application/json").content("{"), "INVALID_REQUEST"),
            new Probe("notification typed limit", get("/api/approval/notifications?limit=bad"),
                "APPROVAL_NOTIFICATION_INVALID_REQUEST"),
            new Probe("notification invalid limit", get("/api/approval/notifications?limit=0"),
                "APPROVAL_NOTIFICATION_INVALID_REQUEST")
        ).map(probe -> DynamicTest.dynamicTest(probe.name(), () -> {
            mvc().perform(headers(probe.request()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(probe.code()))
                .andExpect(jsonPath("$.retryable").value(false));
            verifyNoInteractions(audits, comments, notifications);
        }));
    }

    @Test
    void validListsAndStorageFailuresKeepTheirExistingSemantics() throws Exception {
        when(audits.find(any())).thenReturn(new AuditPage(List.of(), 0, 50, 0));
        when(comments.findComments("tenant-a", "actor", INSTANCE, 50, 0))
            .thenReturn(new ApprovalCommentService.CommentPage(List.of(), 0, 50, 0, false, false));
        when(notifications.findHistory("tenant-a", "actor", false, 20, 0))
            .thenReturn(new NotificationHistoryPage(List.of(), 0, 20, 0));
        MockMvc mvc = mvc();
        for (String path : List.of(AUDIT + RANGE, COMMENTS, "/api/approval/notifications")) {
            mvc.perform(headers(get(path))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        }
        when(audits.find(any())).thenThrow(new RuntimeException("private storage failure"));
        when(comments.findComments("tenant-a", "actor", INSTANCE, 50, 0))
            .thenThrow(new RuntimeException("private storage failure"));
        when(notifications.findHistory("tenant-a", "actor", false, 20, 0))
            .thenThrow(new RuntimeException("private storage failure"));
        for (String path : List.of(AUDIT + RANGE, COMMENTS, "/api/approval/notifications")) {
            mvc.perform(headers(get(path))).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.retryable").value(true));
        }
    }

    @Test
    void returnValueValidationRemainsServerError() {
        var exception = mock(HandlerMethodValidationException.class);
        when(exception.isForReturnValue()).thenReturn(true);
        var response = new ApprovalNotificationApiExceptionHandler().methodValidation(
            exception, new MockHttpServletRequest()
        );
        assertEquals(500, response.getStatusCode().value());
        assertEquals("APPROVAL_NOTIFICATION_COMMAND_FAILED", response.getBody().code());
        assertEquals("Approval notification command failed", response.getBody().message());
        assertEquals(true, response.getBody().retryable());
    }

    private static MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request) {
        return request.header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "actor")
            .header("X-Request-Id", "request-test").header("Idempotency-Key", "key-test");
    }

    private record Probe(String name, MockHttpServletRequestBuilder request, String code) { }
}

package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalTaskOutcomeContext;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCompletionGuard.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalTaskOutcomeInterceptorTest {

    @Test
    void mapsEverySupportedTaskActionToTheExactDomainOutcome() throws Exception {
        ApprovalTaskOutcomeContext context = new ApprovalTaskOutcomeContext();
        ApprovalTaskOutcomeInterceptor interceptor = new ApprovalTaskOutcomeInterceptor(context);
        Map<String, TaskOutcome> outcomes = Map.of(
            "approve", TaskOutcome.APPROVED,
            "reject", TaskOutcome.REJECTED,
            "resubmit", TaskOutcome.RESUBMITTED
        );

        for (Map.Entry<String, TaskOutcome> entry : outcomes.entrySet()) {
            MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/approval/tasks/" + UUID.randomUUID() + "/" + entry.getKey()
            );
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertTrue(interceptor.preHandle(request, response, new Object()));
            assertEquals(entry.getValue(), context.current().orElseThrow());

            interceptor.afterCompletion(request, response, new Object(), null);
            assertTrue(context.current().isEmpty());
        }
    }

    @Test
    void ignoresNonTaskActionsAndClearsStaleOutcome() throws Exception {
        ApprovalTaskOutcomeContext context = new ApprovalTaskOutcomeContext();
        context.set(TaskOutcome.APPROVED);
        ApprovalTaskOutcomeInterceptor interceptor = new ApprovalTaskOutcomeInterceptor(context);
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET",
            "/api/approval/tasks/pending"
        );

        assertTrue(interceptor.preHandle(
            request,
            new MockHttpServletResponse(),
            new Object()
        ));
        assertTrue(context.current().isEmpty());
    }
}

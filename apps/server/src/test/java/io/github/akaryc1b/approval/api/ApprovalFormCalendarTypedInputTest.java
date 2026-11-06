package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService;
import io.github.akaryc1b.approval.application.ApprovalSlaService;
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore.DraftPage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarPage;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MVC binding regression only; production authorization remains separately enforced. */
class ApprovalFormCalendarTypedInputTest {

    @Test
    void designRejectsMalformedParametersAndJsonBeforeService() throws Exception {
        var service = mock(ApprovalFormDesignService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApprovalFormDesignController(service))
            .setControllerAdvice(new ApprovalFormDesignApiExceptionHandler()).build();
        for (String path : List.of(
            "/api/approval/form-design-drafts?limit=bad",
            "/api/approval/form-design-drafts?status=bad",
            "/api/approval/form-design-drafts/not-a-uuid"
        )) {
            mvc.perform(get(path).header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_DESIGN_INVALID_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false));
        }
        mvc.perform(post("/api/approval/form-design-drafts")
                .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "designer")
                .header("X-Request-Id", "invalid-json").header("Idempotency-Key", "invalid-json")
                .contentType("application/json").content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FORM_DESIGN_INVALID_REQUEST"));
        verifyNoInteractions(service);
        when(service.findDrafts("tenant-a", null, null, 20, 0))
            .thenReturn(new DraftPage(List.of(), 0, 20, 0));
        mvc.perform(get("/api/approval/form-design-drafts").header("X-Tenant-Id", "tenant-a"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void runtimeRejectsTypedPathsAndPreservesUnexpectedFailure() throws Exception {
        var service = mock(ApprovalFormRuntimeService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApprovalFormRuntimeController(service))
            .setControllerAdvice(new ApprovalFormRuntimeApiExceptionHandler()).build();
        for (String path : List.of(
            "/api/approval/forms/purchase-payment/versions/bad/runtime",
            "/api/approval/tasks/not-a-uuid/form-runtime"
        )) {
            mvc.perform(get(path).header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "actor"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(service);
        when(service.startRuntime("tenant-a", "actor", "purchase-payment", 1))
            .thenThrow(new RuntimeException("private storage failure"));
        mvc.perform(get("/api/approval/forms/purchase-payment/versions/1/runtime")
                .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "actor"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void calendarRejectsTypedParametersAndPreservesValidList() throws Exception {
        var service = mock(ApprovalSlaService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApprovalCalendarManagementController(service))
            .setControllerAdvice(new ApprovalSlaApiExceptionHandler(Clock.systemUTC())).build();
        for (String path : List.of(
            "/api/approval/management/sla/calendars?limit=bad",
            "/api/approval/management/sla/calendars/not-a-uuid"
        )) {
            mvc.perform(get(path).header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(service);
        when(service.findCalendars("tenant-a", 50, 0)).thenReturn(new CalendarPage(List.of(), 0, 50, 0));
        mvc.perform(get("/api/approval/management/sla/calendars").header("X-Tenant-Id", "tenant-a"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }
}

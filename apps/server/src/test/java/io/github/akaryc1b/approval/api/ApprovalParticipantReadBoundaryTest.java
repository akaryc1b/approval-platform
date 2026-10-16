package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalTaskQueryService;
import io.github.akaryc1b.approval.application.ApprovalTimelineQueryService;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.PurchasePaymentTaskActionService;
import io.github.akaryc1b.approval.application.port.ApprovalTimelineQuery;
import io.github.akaryc1b.approval.application.port.ApprovalTimelineQuery.ApprovalTimeline;
import io.github.akaryc1b.approval.application.port.ApprovalTimelineQuery.TimelineIdentity;
import io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter;
import io.github.akaryc1b.approval.security.ApprovalPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalParticipantReadBoundaryTest {

    private static final UUID INSTANCE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private final PurchasePaymentApplicationService service = mock(PurchasePaymentApplicationService.class);
    private final ApprovalTimelineQuery timeline = mock(ApprovalTimelineQuery.class);

    @Test
    void outsiderAdminCrossTenantAndMissingInstanceCannotReadRawProjections() throws Exception {
        MockMvc mvc = mvc();
        for (ApprovalPrincipal principal : List.of(
            ApprovalPrincipal.active("tenant-a", "outsider", Set.of(), null),
            ApprovalPrincipal.active("tenant-a", "admin", Set.of("approval.management.admin"), null),
            ApprovalPrincipal.active("tenant-b", "initiator", Set.of(), null)
        )) {
            assertHidden(mvc, principal, INSTANCE_ID);
        }
        assertHidden(mvc, ApprovalPrincipal.active("tenant-a", "initiator", Set.of(), null),
            UUID.fromString("20000000-0000-0000-0000-000000000099"));
        verifyNoInteractions(service);
    }

    @Test
    void timelineParticipantsKeepAccessAndTrustedOperatorOverridesNominatedHeader() throws Exception {
        MockMvc mvc = mvc();
        for (String operator : List.of("initiator", "assignee", "delegate", "handover", "copy", "former")) {
            when(timeline.findTimeline(new TimelineIdentity("tenant-a", operator, INSTANCE_ID)))
                .thenReturn(Optional.of(new ApprovalTimeline(INSTANCE_ID, List.of())));
            when(service.findInstance("tenant-a", INSTANCE_ID)).thenReturn(Optional.of(
                new PurchasePaymentApplicationService.InstanceDetails(null, List.of())
            ));
            when(service.findTasks("tenant-a", INSTANCE_ID)).thenReturn(List.of());
            ApprovalPrincipal principal = ApprovalPrincipal.active("tenant-a", operator, Set.of(), null);
            mvc.perform(get("/api/approval/instances/{id}", INSTANCE_ID)
                    .principal(principal).header("X-Operator-Id", "outsider"))
                .andExpect(status().isOk());
            mvc.perform(get("/api/approval/tasks").param("instanceId", INSTANCE_ID.toString())
                    .principal(principal).header("X-Operator-Id", "outsider"))
                .andExpect(status().isOk());
        }
        verify(timeline, org.mockito.Mockito.times(2)).findTimeline(
            new TimelineIdentity("tenant-a", "delegate", INSTANCE_ID)
        );
    }

    private void assertHidden(MockMvc mvc, ApprovalPrincipal principal, UUID instanceId) throws Exception {
        for (var request : List.of(
            get("/api/approval/instances/{id}", instanceId),
            get("/api/approval/tasks").param("instanceId", instanceId.toString())
        )) {
            mvc.perform(request.principal(principal))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPROVAL_RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("approval resource was not found"))
                .andExpect(jsonPath("$.retryable").value(false));
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new PurchasePaymentController(
                service,
                mock(PurchasePaymentTaskActionService.class),
                mock(ApprovalTaskQueryService.class),
                new ApprovalTimelineQueryService(timeline)
            ))
            .setControllerAdvice(new ApprovalApiExceptionHandler(), new ApprovalParticipantReadApiExceptionHandler())
            .addFilters(new ApprovalIdentityContextFilter(
                ApprovalIdentityContextFilter.AuthenticationMode.PRINCIPAL,
                "X-Approval-Trusted-Permissions", Clock.systemUTC(), new ObjectMapper(), () -> "read-test"
            ))
            .build();
    }
}

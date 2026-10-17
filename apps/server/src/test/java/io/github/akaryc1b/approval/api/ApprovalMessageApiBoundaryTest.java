package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalMessageService;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.security.ApprovalIdentityContextFilter;
import io.github.akaryc1b.approval.security.ApprovalPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalMessageApiBoundaryTest {

    private static final UUID INSTANCE = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID MISSING = UUID.fromString("40000000-0000-0000-0000-000000000099");
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private final ApprovalProjectionStore projections = mock(ApprovalProjectionStore.class);
    private final ApprovalMessageStore messages = mock(ApprovalMessageStore.class);
    private final AuditEventSink audit = mock(AuditEventSink.class);
    private final InstanceProjection instance = mock(InstanceProjection.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(projections.findInstance("tenant-a", INSTANCE)).thenReturn(Optional.of(instance));
        when(instance.instanceId()).thenReturn(INSTANCE);
        when(instance.initiatorId()).thenReturn("initiator");
        when(instance.status()).thenReturn(InstanceStatus.RUNNING);
        when(instance.businessKey()).thenReturn("business");
        when(instance.supplier()).thenReturn("supplier");
        when(instance.assigneeSnapshot()).thenReturn(new AssigneeSnapshot("current", "snapshot-only", List.of(), Map.of()));
        when(projections.findTasks("tenant-a", INSTANCE)).thenReturn(List.of(
            task("current", TaskStatus.PENDING), task("historical", TaskStatus.COMPLETED)
        ));
        when(messages.append(anyList())).thenReturn(1);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var service = new ApprovalMessageService(new ActorBoundReplayGuard(), projections, messages, audit, clock, UUID::randomUUID);
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalMessageController(service))
            .setControllerAdvice(
                new ApprovalParticipantReadApiExceptionHandler(),
                new ApprovalMessageApiExceptionHandler(),
                new ApprovalRuntimeIdempotencyApiExceptionHandler()
            )
            .addFilters(new ApprovalIdentityContextFilter(
                ApprovalIdentityContextFilter.AuthenticationMode.PRINCIPAL,
                "X-Approval-Trusted-Permissions", clock, new ObjectMapper(), () -> "message-boundary"
            )).build();
    }

    @Test
    void freshMissingCrossTenantAndUnrelatedRequestsReturnSame404() throws Exception {
        for (ApprovalPrincipal principal : List.of(
            principal("tenant-a", "outsider"), principal("tenant-a", "pure-collaborator"),
            principal("tenant-a", "copy-recipient"), principal("tenant-a", "snapshot-only"),
            principal("tenant-b", "initiator"),
            ApprovalPrincipal.active("tenant-a", "admin", Set.of("approval.management.admin"), null)
        )) {
            assertHiddenRequests(principal, INSTANCE);
        }
        assertHiddenRequests(principal("tenant-a", "initiator"), MISSING);
        verifyNoInteractions(messages, audit);
    }

    @Test
    void optionsKeepOnlyInitiatorCurrentAndHistoricalTaskAssignees() throws Exception {
        for (String actor : List.of("initiator", "current", "historical")) {
            mvc.perform(get("/api/approval/instances/{id}/collaboration-options", INSTANCE)
                    .principal(principal("tenant-a", actor)).header("X-Operator-Id", "outsider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value(INSTANCE.toString()))
                .andExpect(jsonPath("$.activeAssignees[0].userId").value("current"));
        }
    }

    @Test
    void receiptsKeepInitiatorOnlyWithNonDisclosingDenials() throws Exception {
        for (String actor : List.of("current", "historical", "copy-recipient", "pure-collaborator", "outsider")) {
            assertHidden(get("/api/approval/instances/{id}/receipts", INSTANCE)
                .principal(principal("tenant-a", actor)));
        }
        assertHidden(get("/api/approval/instances/{id}/receipts", INSTANCE).principal(principal("tenant-b", "initiator")));
        assertHidden(get("/api/approval/instances/{id}/receipts", MISSING).principal(principal("tenant-a", "initiator")));
        verifyNoInteractions(messages);
        mvc.perform(get("/api/approval/instances/{id}/receipts", INSTANCE).principal(principal("tenant-a", "initiator")))
            .andExpect(status().isOk());
        verify(messages).findReceipts("tenant-a", INSTANCE);
    }

    @Test
    void preciseMessageStateConflictsAre409AndNotRetryable() throws Exception {
        when(instance.status()).thenReturn(InstanceStatus.COMPLETED);
        assertStateConflict(command("urge", "initiator", "closed", INSTANCE));
        when(instance.status()).thenReturn(InstanceStatus.RUNNING);
        assertStateConflict(command("urge", "current", "self-only", INSTANCE));
        assertStateConflict(command("copy", "initiator", "invalid-copy", INSTANCE)
            .content("{\"recipientIds\":[\"outsider\"]}"));
        when(messages.append(anyList())).thenReturn(0);
        assertStateConflict(command("urge", "initiator", "dedup-urge", INSTANCE));
        assertStateConflict(command("copy", "initiator", "dedup-copy", INSTANCE));
        verifyNoInteractions(audit);
    }

    @Test
    void completedSameActorReplaySucceedsWhileCrossActorCachedCommandsRemain409() throws Exception {
        for (String action : List.of("urge", "copy")) {
            when(instance.status()).thenReturn(InstanceStatus.RUNNING);
            String key = action + "-replay";
            String original = mvc.perform(command(action, "initiator", key, INSTANCE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            when(instance.status()).thenReturn(InstanceStatus.COMPLETED);
            clearInvocations(projections);
            String replayed = mvc.perform(command(action, "initiator", key, INSTANCE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(original, replayed);
            for (String actor : List.of("current", "outsider")) {
                mvc.perform(command(action, actor, key, INSTANCE))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                    .andExpect(jsonPath("$.message").value("idempotency key conflicts with the current command"))
                    .andExpect(jsonPath("$.retryable").value(false))
                    .andExpect(jsonPath("$.createdMessages").doesNotExist())
                    .andExpect(jsonPath("$.recipients").doesNotExist());
            }
            verifyNoInteractions(projections);
        }
        verify(messages, times(2)).append(anyList());
        verify(audit, times(2)).append(org.mockito.ArgumentMatchers.any());
    }

    private void assertHiddenRequests(ApprovalPrincipal principal, UUID id) throws Exception {
        assertHidden(get("/api/approval/instances/{id}/collaboration-options", id).principal(principal));
        for (String action : List.of("urge", "copy")) {
            assertHidden(command(action, "unused", action + "-fresh", id).principal(principal));
        }
    }

    private void assertHidden(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.header("X-Operator-Id", "initiator").with(servletRequest -> {
                servletRequest.removeHeader("X-Request-Id");
                servletRequest.addHeader("X-Request-Id", "hidden-request");
                return servletRequest;
            }))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Request-Id", "hidden-request"))
            .andExpect(jsonPath("$.code").value("APPROVAL_RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("approval resource was not found"))
            .andExpect(jsonPath("$.retryable").value(false));
    }

    private void assertStateConflict(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("APPROVAL_MESSAGE_STATE_CONFLICT"))
            .andExpect(jsonPath("$.retryable").value(false));
    }

    private static MockHttpServletRequestBuilder command(String action, String actor, String key, UUID id) {
        return post("/api/approval/instances/{id}/" + action, id)
            .principal(principal("tenant-a", actor))
            .header("X-Request-Id", "request-" + key).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(action.equals("copy") ? "{\"recipientIds\":[\"snapshot-only\"]}" : "{}");
    }

    private static ApprovalPrincipal principal(String tenant, String actor) {
        return ApprovalPrincipal.active(tenant, actor, Set.of(), null);
    }

    private static TaskProjection task(String actor, TaskStatus status) {
        return new TaskProjection(UUID.randomUUID(), INSTANCE, "tenant-a", "engine-" + actor,
            "approval", "Approval", actor, status, 1, NOW, NOW, status == TaskStatus.COMPLETED ? NOW : null);
    }

    /** Contract stub, leaving production JDBC binding and persistence tests unchanged. */
    private static final class ActorBoundReplayGuard implements IdempotencyGuard {
        private final Map<String, CachedResult> results = new HashMap<>();

        @Override
        public <T> T execute(RequestContext context, String operation, String hash, Class<T> type, Supplier<T> action) {
            String key = context.tenantId() + ':' + operation + ':' + context.idempotencyKey();
            CachedResult cached = results.get(key);
            if (cached != null) {
                if (!cached.actor().equals(context.operatorId()) || !cached.hash().equals(hash)) {
                    throw new IdempotencyConflictException("idempotency key is bound to another actor or request");
                }
                return type.cast(cached.value());
            }
            T result = action.get();
            results.put(key, new CachedResult(context.operatorId(), hash, result));
            return result;
        }
    }

    private record CachedResult(String actor, String hash, Object value) {
    }
}

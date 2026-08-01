package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult.AssertionStatus;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult.Authority;
import io.github.akaryc1b.approval.api.ApprovalAssistanceReadContracts.Availability;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAssistanceReadControllerTest {

    private static final UUID TASK_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID INSTANCE_ID = UUID.fromString(
        "20000000-0000-0000-0000-000000000001"
    );
    private static final Instant NOW = Instant.parse("2026-08-01T16:00:00Z");

    @Test
    void authorizedPendingTaskReturnsNoStoreProviderRequiredView() {
        ApprovalAssistanceReadController controller = controller();

        var response = controller.findAssistance(
            "tenant-a",
            "operator-a",
            TASK_ID,
            UseCase.SUMMARY
        );
        var body = response.getBody();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(TASK_ID, body.taskId());
        assertEquals(INSTANCE_ID, body.instanceId());
        assertEquals(UseCase.SUMMARY, body.requestedUseCase());
        assertEquals(List.of(UseCase.values()), body.availableUseCases());
        assertEquals(Availability.PROVIDER_NOT_CONFIGURED, body.availability());
        assertEquals(
            ApprovalAssistanceReadContracts.PROVIDER_REQUIRED_CODE,
            body.code()
        );
        assertEquals(Authority.ADVISORY, body.authority());
        assertEquals(AssertionStatus.UNVERIFIED_ADVISORY, body.assertionStatus());
        assertTrue(body.needsHumanReview());
        assertFalse(body.providerInvocationStarted());
        assertFalse(body.providerSelectable());
        assertFalse(body.commandAvailable());
        assertFalse(body.resultAvailable());
        assertNull(body.advisoryResult());
        assertEquals(3, body.limitations().size());
        assertEquals("purchase-payment", body.taskSnapshot().definitionKey());
        assertEquals(3, body.taskSnapshot().definitionVersion());
        assertEquals("purchase-payment-form", body.taskSnapshot().formKey());
        assertEquals(2, body.taskSnapshot().formVersion());
        assertEquals(NOW, body.taskSnapshot().taskUpdatedAt());
    }

    @Test
    void everyClosedP2UseCaseCanBeRequestedWithoutProviderInvocation() {
        ApprovalAssistanceReadController controller = controller();

        for (UseCase useCase : UseCase.values()) {
            var response = controller.findAssistance(
                "tenant-a",
                "operator-a",
                TASK_ID,
                useCase
            );
            var body = response.getBody();
            assertEquals(useCase, body.requestedUseCase());
            assertEquals(Availability.PROVIDER_NOT_CONFIGURED, body.availability());
            assertFalse(body.providerInvocationStarted());
            assertNull(body.advisoryResult());
        }
    }

    @Test
    void tenantOperatorOrTaskMismatchReturnsNoStoreNotFound() {
        ApprovalAssistanceReadController controller = controller();

        var wrongTenant = controller.findAssistance(
            "tenant-b",
            "operator-a",
            TASK_ID,
            UseCase.SUMMARY
        );
        var wrongOperator = controller.findAssistance(
            "tenant-a",
            "operator-b",
            TASK_ID,
            UseCase.SUMMARY
        );
        var wrongTask = controller.findAssistance(
            "tenant-a",
            "operator-a",
            UUID.fromString("10000000-0000-0000-0000-000000000099"),
            UseCase.SUMMARY
        );

        for (var response : List.of(wrongTenant, wrongOperator, wrongTask)) {
            assertEquals(404, response.getStatusCode().value());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertNull(response.getBody());
        }
    }

    private static ApprovalAssistanceReadController controller() {
        return new ApprovalAssistanceReadController(new FakeTaskQuery());
    }

    private static PendingTaskDetails task() {
        return new PendingTaskDetails(
            TASK_ID,
            INSTANCE_ID,
            "purchase-payment",
            3,
            "purchase-payment-form",
            2,
            "compiler-v1",
            "content-hash-v3",
            "managerApproval",
            "部门负责人审批",
            "PAYMENT-2026-0001",
            "initiator-a",
            new BigDecimal("1250.00"),
            "Supplier A",
            "PO-2026-0001",
            List.of("attachment-1"),
            List.of(),
            NOW.minusSeconds(120),
            NOW.minusSeconds(30),
            NOW.minusSeconds(90),
            NOW
        );
    }

    private static final class FakeTaskQuery implements ApprovalTaskQuery {

        @Override
        public PendingTaskPage findPendingTasks(PendingTaskCriteria criteria) {
            return new PendingTaskPage(List.of(), 0, criteria.limit(), criteria.offset());
        }

        @Override
        public Optional<PendingTaskDetails> findPendingTask(PendingTaskIdentity identity) {
            if (identity.tenantId().equals("tenant-a")
                && identity.assigneeId().equals("operator-a")
                && identity.taskId().equals(TASK_ID)) {
                return Optional.of(task());
            }
            return Optional.empty();
        }
    }
}

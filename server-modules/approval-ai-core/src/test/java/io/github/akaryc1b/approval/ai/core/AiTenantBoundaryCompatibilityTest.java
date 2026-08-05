package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceStateSnapshot;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiTenantBoundaryCompatibilityTest {

    private static final String TENANT_128 = "t".repeat(128);
    private static final String TENANT_129 = "t".repeat(129);

    @Test
    void everyTrustedTenantCarrierAcceptsThePlatformMaximum() {
        assertEquals(
            TENANT_128,
            new AiServerRequestContext(
                TENANT_128,
                "operator-a",
                "request-a",
                "trace-a"
            ).tenantId()
        );
        assertEquals(
            TENANT_128,
            new AiAuthorizedResource(
                TENANT_128,
                AiAuthorizedResource.ResourceType.APPROVAL_TASK,
                "task-a",
                "authorization-a",
                Set.of("amount")
            ).tenantId()
        );
        assertEquals(
            TENANT_128,
            new ResourceStateSnapshot(
                TENANT_128,
                "instance-a",
                "task-a",
                "managerApproval",
                ResourceState.TASK_PENDING,
                1,
                Instant.parse("2026-08-04T00:00:00Z")
            ).tenantId()
        );
        assertEquals(
            TENANT_128,
            new AiProviderRequest.AuthorizedContext(
                TENANT_128,
                "operator-a",
                "request-a",
                "trace-a"
            ).tenantId()
        );
        assertEquals(
            TENANT_128,
            new AiProviderRequest.AuthorizedResource(
                TENANT_128,
                "APPROVAL_TASK",
                "task-a",
                "authorization-a"
            ).tenantId()
        );
    }

    @Test
    void everyTrustedTenantCarrierRejectsAboveThePlatformMaximum() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiServerRequestContext(
                TENANT_129,
                "operator-a",
                "request-a",
                "trace-a"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiAuthorizedResource(
                TENANT_129,
                AiAuthorizedResource.ResourceType.APPROVAL_TASK,
                "task-a",
                "authorization-a",
                Set.of("amount")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ResourceStateSnapshot(
                TENANT_129,
                "instance-a",
                "task-a",
                "managerApproval",
                ResourceState.TASK_PENDING,
                1,
                Instant.parse("2026-08-04T00:00:00Z")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderRequest.AuthorizedContext(
                TENANT_129,
                "operator-a",
                "request-a",
                "trace-a"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderRequest.AuthorizedResource(
                TENANT_129,
                "APPROVAL_TASK",
                "task-a",
                "authorization-a"
            )
        );
    }
}

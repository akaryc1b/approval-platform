package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskDetails;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApprovalAssistanceGenerationProjectionFieldCountTest {

    private static final UUID TASK_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000004"
    );
    private static final UUID INSTANCE_ID = UUID.fromString(
        "20000000-0000-0000-0000-000000000004"
    );
    private static final Instant NOW = Instant.parse("2026-08-05T01:00:00Z");

    @Test
    void processMetadataDoesNotConsumeTrustedFormSchemaFieldCount() throws Exception {
        ApprovalAssistanceContextProjection projection = projection(task());
        Set<String> fieldKeys = projection.providerFields().stream()
            .map(AiProviderRequest.InputField::key)
            .collect(Collectors.toUnmodifiableSet());

        assertNotNull(projection);
        assertEquals(6, projection.providerFields().size());
        assertEquals(
            Set.of(
                "definitionKey",
                "taskName",
                "businessKey",
                "amount",
                "supplier",
                "purchaseOrderReference"
            ),
            fieldKeys
        );
        assertEquals(4, projection.form().schemaFieldCount());
        assertEquals(3, projection.evidence().authorizedVisibleFieldCount());
        assertEquals(6, projection.evidence().providerFieldCount());
        assertEquals(3, projection.evidence().formProviderFieldCount());
        assertEquals(1, projection.evidence().omittedFieldCount());
        assertEquals(0, projection.evidence().maskedFieldCount());
        assertEquals(0, projection.evidence().attachmentMetadataCount());
    }

    private static ApprovalAssistanceContextProjection projection(
        PendingTaskDetails task
    ) throws Exception {
        Method method = ApprovalAssistanceGenerationService.class.getDeclaredMethod(
            "projection",
            String.class,
            String.class,
            String.class,
            String.class,
            PendingTaskDetails.class,
            UseCase.class
        );
        method.setAccessible(true);
        return (ApprovalAssistanceContextProjection) method.invoke(
            null,
            "tenant-a",
            "operator-a",
            "request-a",
            "trace-a",
            task,
            UseCase.SUMMARY
        );
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
            hash("definition-content-3"),
            "managerApproval",
            "部门负责人审批",
            "PAYMENT-2026-0004",
            "initiator-a",
            new BigDecimal("1250.00"),
            "Supplier A",
            "PO-2026-0004",
            List.of("attachment-1"),
            List.of(),
            NOW.minusSeconds(120),
            NOW.minusSeconds(30),
            NOW.minusSeconds(90),
            NOW,
            11,
            hash("release-package-11"),
            7,
            hash("form-package-7"),
            hash("form-content-2"),
            5,
            hash("ui-schema-5"),
            "schema-2026-08",
            4
        );
    }

    private static String hash(String value) {
        return io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash
            .sha256Utf8(value);
    }
}

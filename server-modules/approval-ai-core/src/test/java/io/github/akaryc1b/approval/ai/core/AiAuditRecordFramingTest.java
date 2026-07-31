package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAuditRecord;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AiAuditRecordFramingTest {

    @Test
    void delimiterPlacementCannotCollideAcrossAuditIdentityFields() {
        AiAuditRecord first = AiAuditRecord.create(
            request("a|b", "c"),
            AiOutcomeClassification.SUCCESS
        );
        AiAuditRecord second = AiAuditRecord.create(
            request("a", "b|c"),
            AiOutcomeClassification.SUCCESS
        );

        assertNotEquals(first.requestEvidenceHash(), second.requestEvidenceHash());
        assertNotEquals(first.auditEvidenceHash(), second.auditEvidenceHash());
    }

    private static AiProviderRequest request(String tenantId, String requestId) {
        return new AiProviderRequest(
            new AiProviderRequest.AuthorizedContext(
                tenantId,
                "operator-a",
                requestId,
                "trace-a"
            ),
            new AiProviderRequest.AuthorizedResource(
                tenantId,
                "APPROVAL_TASK",
                "task-a",
                "authorization-a"
            ),
            AiCapability.APPROVAL_SUMMARY,
            Set.of("amount"),
            List.of(new AiProviderRequest.InputField(
                "amount",
                "MONEY",
                "100.00",
                AiProviderRequest.MaskingDisposition.INCLUDED
            )),
            AiTestFixtures.versions(),
            Duration.ofMillis(200)
        );
    }
}

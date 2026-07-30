package io.github.akaryc1b.approval.domain.migration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationPlanConsumptionTest {

    @Test
    void normalizesBoundedServerOwnedAdmissionEvidence() {
        ApprovalMigrationPlanConsumption value = consumption(" admission-key ", hash('a'));

        assertEquals("admission-key", value.idempotencyKey());
        assertEquals("migration-executor", value.consumedBy());
        assertEquals(hash('a'), value.requestHash());
    }

    @Test
    void rejectsMissingAuthorizationInvalidHashAndBlankOperator() {
        ApprovalMigrationPlanConsumption base = consumption("admission-key", hash('a'));

        assertThrows(NullPointerException.class, () -> new ApprovalMigrationPlanConsumption(
            base.consumptionId(), base.tenantId(), base.planId(), base.planHash(), null,
            base.authorizationEvidenceHash(), base.intentId(), base.intentEvidenceHash(),
            base.idempotencyKey(), base.requestHash(), base.consumedBy(), base.reason(),
            base.consumedAt(), base.requestId(), base.traceId(), base.auditChainReference()
        ));
        assertThrows(IllegalArgumentException.class, () -> new ApprovalMigrationPlanConsumption(
            base.consumptionId(), base.tenantId(), base.planId(), base.planHash(),
            base.authorizationId(), base.authorizationEvidenceHash(), base.intentId(),
            base.intentEvidenceHash(), base.idempotencyKey(), "invalid", base.consumedBy(),
            base.reason(), base.consumedAt(), base.requestId(), base.traceId(),
            base.auditChainReference()
        ));
        assertThrows(IllegalArgumentException.class, () -> new ApprovalMigrationPlanConsumption(
            base.consumptionId(), base.tenantId(), base.planId(), base.planHash(),
            base.authorizationId(), base.authorizationEvidenceHash(), base.intentId(),
            base.intentEvidenceHash(), base.idempotencyKey(), base.requestHash(), " ",
            base.reason(), base.consumedAt(), base.requestId(), base.traceId(),
            base.auditChainReference()
        ));
    }

    private static ApprovalMigrationPlanConsumption consumption(String key, String requestHash) {
        return new ApprovalMigrationPlanConsumption(
            UUID.fromString("79000000-0000-0000-0000-000000000001"),
            "tenant-migration-plan",
            UUID.fromString("79000000-0000-0000-0000-000000000002"),
            hash('1'),
            UUID.fromString("79000000-0000-0000-0000-000000000003"),
            hash('2'),
            UUID.fromString("79000000-0000-0000-0000-000000000004"),
            hash('3'),
            key,
            requestHash,
            "migration-executor",
            "Admit exact authorized migration plan",
            Instant.parse("2026-07-25T16:00:00Z"),
            "request-admission",
            "trace-admission",
            "audit-admission"
        );
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}

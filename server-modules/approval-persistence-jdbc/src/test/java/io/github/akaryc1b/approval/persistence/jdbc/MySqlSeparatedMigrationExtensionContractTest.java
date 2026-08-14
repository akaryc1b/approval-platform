package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MySqlSeparatedMigrationExtensionContractTest {

    @Test
    void targetsOnlyTheIndependentRunBMigrationFailureSet() throws Exception {
        Set<String> expected = Set.of(
            "io.github.akaryc1b.approval.persistence.jdbc."
                + "JdbcApprovalFormDesignDraftStoreMySqlIntegrationTest",
            "io.github.akaryc1b.approval.persistence.jdbc."
                + "JdbcApprovalFormPackageStoreMySqlIntegrationTest",
            "io.github.akaryc1b.approval.persistence.jdbc."
                + "JdbcApprovalFormStoreMySqlIntegrationTest",
            "io.github.akaryc1b.approval.persistence.jdbc."
                + "JdbcApprovalUiSchemaStoreMySqlIntegrationTest",
            "io.github.akaryc1b.approval.persistence.jdbc."
                + "JdbcAuditEventSinkMySqlIntegrationTest",
            "io.github.akaryc1b.approval.persistence.jdbc."
                + "JdbcIdempotencyGuardMySqlContractIntegrationTest",
            "io.github.akaryc1b.approval.persistence.jdbc."
                + "JdbcIdempotencyGuardMySqlIntegrationTest",
            "io.github.akaryc1b.approval.persistence.jdbc."
                + "JdbcMySqlApprovalTaskCasStoreIntegrationTest"
        );

        assertEquals(expected, MySqlSeparatedMigrationExtension.targetClasses());
        for (String className : expected) {
            Class<?> type = Class.forName(className);
            assertFalse(java.lang.reflect.Modifier.isAbstract(type.getModifiers()));
        }
    }
}

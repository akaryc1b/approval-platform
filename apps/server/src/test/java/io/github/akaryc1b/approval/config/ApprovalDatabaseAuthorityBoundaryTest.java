package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDatabaseVendor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalDatabaseAuthorityBoundaryTest {

    @Test
    void rejectsPrivilegedMySqlRuntimeSystemAccounts() {
        for (String account : new String[]{
            "root",
            "ROOT",
            "mysql.sys",
            "mysql.session",
            "mysql.infoschema"
        }) {
            var failure = assertThrows(
                ApprovalDatabaseAuthorityBoundary
                    .InvalidDatabaseAuthorityBoundaryException.class,
                () -> new ApprovalDatabaseAuthorityBoundary(
                    ApprovalDatabaseVendor.MYSQL,
                    account,
                    "approval_migrator"
                )
            );
            assertTrue(failure.getMessage().contains(
                "must not be a privileged system account"
            ));
        }
    }

    @Test
    void leavesPostgreSqlIdentityConfigurationOptional() {
        var boundary = new ApprovalDatabaseAuthorityBoundary(
            ApprovalDatabaseVendor.POSTGRESQL,
            null,
            null
        );

        assertTrue(boundary.runtimeIdentity() == null);
        assertTrue(boundary.migrationIdentity() == null);
    }
}

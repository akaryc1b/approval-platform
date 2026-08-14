package io.github.akaryc1b.approval.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMySqlMigrationAuthorityProfileContractTest {

    @Test
    void mysqlProfileUsesSeparateRuntimeAndFlywayIdentities() throws IOException {
        String profile = resource("application-mysql.yml");

        assertTrue(profile.contains("username: ${APPROVAL_DB_USERNAME}"));
        assertTrue(profile.contains("password: ${APPROVAL_DB_PASSWORD}"));
        assertTrue(profile.contains("user: ${APPROVAL_DB_MIGRATION_USERNAME}"));
        assertTrue(profile.contains("password: ${APPROVAL_DB_MIGRATION_PASSWORD}"));
        assertTrue(profile.contains(
            "runtime-identity: ${APPROVAL_DB_USERNAME}"
        ));
        assertTrue(profile.contains(
            "migration-identity: ${APPROVAL_DB_MIGRATION_USERNAME}"
        ));
        assertFalse(profile.toLowerCase().contains(
            "log_bin_trust_function_creators"
        ));
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = ApprovalMySqlMigrationAuthorityProfileContractTest.class
            .getClassLoader()
            .getResourceAsStream(name)) {
            if (input == null) {
                throw new AssertionError("missing classpath resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

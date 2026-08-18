package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalAssistanceGovernanceHistoryQueryFactoryTest {

    private static final String PACKAGE =
        "io.github.akaryc1b.approval.persistence.jdbc.";

    @Test
    void exposesTrustedVendorFactoryAndDedicatedMySqlQuery() throws Exception {
        Class<?> port = Class.forName(
            "io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery"
        );
        Class<?> factory = Class.forName(
            PACKAGE + "JdbcApprovalAssistanceGovernanceHistoryQueryFactory"
        );
        Class<?> postgresql = Class.forName(
            PACKAGE + "JdbcApprovalAssistanceGovernanceHistoryQuery"
        );
        Class<?> mysql = Class.forName(
            PACKAGE + "JdbcMySqlApprovalAssistanceGovernanceHistoryQuery"
        );

        assertTrue(port.isAssignableFrom(postgresql));
        assertTrue(port.isAssignableFrom(mysql));

        Method[] createMethods = Arrays.stream(factory.getDeclaredMethods())
            .filter(method -> method.getName().equals("create"))
            .filter(method -> Modifier.isStatic(method.getModifiers()))
            .filter(method -> port.isAssignableFrom(method.getReturnType()))
            .toArray(Method[]::new);
        assertTrue(
            createMethods.length > 0,
            "factory must expose a static create method returning the application port"
        );

        String factorySource = source(
            "server-modules/approval-persistence-jdbc/src/main/java/"
                + PACKAGE.replace('.', '/')
                + "JdbcApprovalAssistanceGovernanceHistoryQueryFactory.java"
        );
        assertTrue(factorySource.contains("ApprovalDatabaseVendorResolver"));
        assertTrue(factorySource.contains("case POSTGRESQL"));
        assertTrue(factorySource.contains("case MYSQL"));
        assertTrue(factorySource.contains("JdbcApprovalAssistanceGovernanceHistoryQuery"));
        assertTrue(factorySource.contains("JdbcMySqlApprovalAssistanceGovernanceHistoryQuery"));
    }

    @Test
    void executableCompositionUsesFactoryInsteadOfDirectPostgresqlConstruction() throws Exception {
        String configuration = source(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalAssistanceProductionConfiguration.java"
        );
        assertTrue(
            configuration.contains(
                "JdbcApprovalAssistanceGovernanceHistoryQueryFactory.create("
            )
        );
        assertFalse(
            configuration.contains(
                "new JdbcApprovalAssistanceGovernanceHistoryQuery("
            )
        );
    }

    private static String source(String relative) throws Exception {
        Path root = repositoryRoot();
        Path file = root.resolve(relative);
        assertTrue(Files.isRegularFile(file), "missing source file: " + relative);
        return Files.readString(file);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                && Files.isDirectory(current.resolve("server-modules"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}

package io.github.akaryc1b.approval.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6FControlledAutomationLineageArchitectureTest {

    @Test
    void coreLineagePortCannotDependOnCommandsPersistenceNetworkConnectorOrFlowable() {
        ArchRule rule = noClasses()
            .that().haveSimpleNameStartingWith("ControlledAutomationLineage")
            .should().dependOnClassesThat().resideInAnyPackage(
                "io.github.akaryc1b.approval.application..",
                "io.github.akaryc1b.approval.persistence..",
                "io.github.akaryc1b.approval.connector..",
                "io.github.akaryc1b.approval.engine..",
                "java.net..",
                "java.sql..",
                "javax.sql..",
                "org.springframework..",
                "org.flowable.."
            );

        rule.check(new ClassFileImporter().importPackages(
            ControlledAutomationLineageStore.class.getPackageName()
        ));
    }

    @Test
    void p4PersistenceContainsOnlyHashLineageAndNoExecutionAuthority() throws IOException {
        Path root = repositoryRoot();
        String core = Files.readString(root.resolve(
            "server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/"
                + "ControlledAutomationLineageStore.java"
        ));
        String jdbc = Files.readString(root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/java/"
                + "io/github/akaryc1b/approval/persistence/jdbc/"
                + "JdbcControlledAutomationLineageStore.java"
        ));
        String migration = Files.readString(root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources/db/migration/m6f/"
                + "V50__create_ai_controlled_automation_lineage.sql"
        ));
        String production = core + jdbc + migration;

        for (String forbidden : List.of(
            "ApprovalMessageService",
            "ApprovalTaskCollaborationService",
            "PurchasePaymentTaskActionService",
            "ProcessMigrationService",
            "ConnectorInvocation",
            "AiAdvisoryProvider",
            ".advise(",
            "HttpClient",
            "WebClient",
            "RestClient",
            "RuntimeService",
            "TaskService",
            "@Scheduled",
            "TaskScheduler",
            "ProcessBuilder",
            "Runtime.getRuntime().exec",
            "ACT_",
            "passwordValue",
            "bearerToken",
            "sessionCredential",
            "permissionToken",
            "secretValue",
            "rawParameterValue"
        )) {
            assertFalse(production.contains(forbidden), "forbidden P4 authority: " + forbidden);
        }
        assertTrue(production.contains("automaticRetryAllowed"));
        assertTrue(production.contains("commandAttempts"));
        assertTrue(production.contains("UNKNOWN"));
        assertTrue(production.contains("PARTIAL"));
        assertTrue(production.contains("on conflict do nothing"));
        assertTrue(production.contains("for update"));
        assertTrue(production.contains("append-only"));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path pom = current.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    String content = Files.readString(pom).toLowerCase(Locale.ROOT);
                    if (content.contains("<artifactid>approval-platform</artifactid>")
                        && content.contains("<module>server-modules</module>")) {
                        return current;
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("cannot read repository pom", exception);
                }
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}

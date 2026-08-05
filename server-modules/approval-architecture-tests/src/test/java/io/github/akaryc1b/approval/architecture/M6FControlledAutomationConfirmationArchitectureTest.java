package io.github.akaryc1b.approval.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationConfirmationService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6FControlledAutomationConfirmationArchitectureTest {

    @Test
    void confirmationBoundaryCannotDependOnCommandsPersistenceNetworkConnectorOrFlowable() {
        ArchRule rule = noClasses()
            .that().haveSimpleNameStartingWith("ControlledAutomationConfirmation")
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
            ControlledAutomationConfirmationService.class.getPackageName()
        ));
    }

    @Test
    void p3SourceRequiresExplicitClickAndContainsNoExecutionOrCredentialPath()
        throws IOException {
        Path core = repositoryRoot().resolve(
            "server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core"
        );
        String p3 = Files.readString(core.resolve(
            "ControlledAutomationReauthenticationVerifier.java"
        )) + Files.readString(core.resolve(
            "ControlledAutomationConfirmationService.java"
        ));

        for (String forbidden : List.of(
            "ApprovalMessageService",
            "PurchasePaymentTaskActionService",
            "ProcessMigrationService",
            "ConnectorInvocation",
            "AiAdvisoryProvider",
            ".advise(",
            "HttpClient",
            "JdbcTemplate",
            "DataSource",
            "@Transactional",
            "@Scheduled",
            "TaskScheduler",
            "RuntimeService",
            "TaskService",
            "passwordValue",
            "bearerToken",
            "sessionCredential",
            "permissionToken",
            "confirmationToken",
            "ProcessBuilder",
            "Runtime.getRuntime().exec",
            "ACT_"
        )) {
            assertFalse(p3.contains(forbidden), "forbidden P3 boundary: " + forbidden);
        }
        assertTrue(p3.contains("EXPLICIT_CLICK"));
        assertTrue(p3.contains("REAUTHENTICATION_UNAVAILABLE"));
        assertTrue(p3.contains("NON_EXECUTABLE_CONFIRMATION"));
        assertTrue(p3.contains("commandAdmitted"));
        assertTrue(p3.contains("singleUseRequired"));
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

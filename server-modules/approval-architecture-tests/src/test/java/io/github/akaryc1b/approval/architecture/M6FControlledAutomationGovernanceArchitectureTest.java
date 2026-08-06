package io.github.akaryc1b.approval.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationGovernanceEvaluator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6FControlledAutomationGovernanceArchitectureTest {

    @Test
    void governancePreviewCannotDependOnApplicationPersistenceNetworkConnectorOrFlowable() {
        ArchRule rule = noClasses()
            .that().haveSimpleNameStartingWith("ControlledAutomationGovernance")
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
            ControlledAutomationGovernanceEvaluator.class.getPackageName()
        ));
    }

    @Test
    void p2SourceIsFreshReadOnlyAndContainsNoExecutionOrMutationPath() throws IOException {
        Path core = repositoryRoot().resolve(
            "server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core"
        );
        String p2 = Files.readString(core.resolve(
            "ControlledAutomationGovernanceSnapshotSource.java"
        )) + Files.readString(core.resolve(
            "ControlledAutomationGovernanceEvaluator.java"
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
            "save(",
            "insert(",
            "update(",
            "delete(",
            "ProcessBuilder",
            "Runtime.getRuntime().exec",
            "ACT_"
        )) {
            assertFalse(p2.contains(forbidden), "forbidden P2 boundary: " + forbidden);
        }
        assertTrue(p2.contains("snapshotSource.load"));
        assertTrue(p2.contains("whitelistSupplier.get"));
        assertTrue(p2.contains("READ_ONLY_NON_EXECUTING_PREVIEW"));
        assertTrue(p2.contains("businessSideEffectProduced"));
        assertTrue(p2.contains("providerInvoked"));
        assertTrue(p2.contains("connectorInvoked"));
        assertTrue(p2.contains("commandAttempted"));
        assertTrue(p2.contains("ACTION_NOT_WHITELISTED"));
        assertTrue(p2.contains("REAUTHENTICATION_REQUIRED"));
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

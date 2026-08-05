package io.github.akaryc1b.approval.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6FControlledAutomationProposalArchitectureTest {

    @Test
    void controlledAutomationFoundationCannotDependOnApplicationPersistenceNetworkOrFlowable() {
        ArchRule rule = noClasses()
            .that().haveSimpleNameStartingWith("ControlledAutomation")
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
            ControlledAutomationProposal.class.getPackageName()
        ));
    }

    @Test
    void p1SourceContainsNoCommandProviderPersistenceOrAutomaticExecutionPath()
        throws IOException {
        Path root = repositoryRoot();
        String source = readTree(root.resolve(
            "server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core"
        ));
        String p1 = source.lines()
            .filter(line -> line.contains("ControlledAutomation")
                || line.contains("controlled-automation"))
            .reduce("", (left, right) -> left + '\n' + right);

        for (String forbidden : List.of(
            "ApprovalMessageService",
            "PurchasePaymentTaskActionService",
            "ProcessMigrationService",
            "ConnectorInvocation",
            "AiAdvisoryProvider",
            "HttpClient",
            "JdbcTemplate",
            "DataSource",
            "@Transactional",
            "@Scheduled",
            "TaskScheduler",
            "RuntimeService",
            "TaskService",
            "ProcessBuilder",
            "Runtime.getRuntime().exec",
            "ACT_"
        )) {
            assertFalse(p1.contains(forbidden), "forbidden P1 boundary: " + forbidden);
        }
        assertTrue(p1.contains("NON_EXECUTABLE_PROPOSAL"));
        assertTrue(p1.contains("EXPLICIT_USER_ACTION"));
        assertTrue(p1.contains("ACTION_NOT_WHITELISTED"));
        assertTrue(p1.contains("requiresHumanConfirmation"));
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

    private static String readTree(Path root) throws IOException {
        StringBuilder combined = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                combined.append(Files.readString(path)).append('\n');
            }
        }
        return combined.toString();
    }
}

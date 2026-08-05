package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6FControlledAutomationGovernanceOperationsArchitectureTest {

    @Test
    void p6aSurfaceIsGetOnlyHashOnlyAndCannotInvokeOrMutate() throws IOException {
        Path root = repositoryRoot();
        String contracts = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceReadContracts.java"
        ));
        String source = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceSnapshotSource.java"
        ));
        String controller = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceReadController.java"
        ));
        String configuration = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ControlledAutomationGovernanceConfiguration.java"
        ));
        String production = contracts + source + controller + configuration;

        assertTrue(controller.contains("@GetMapping(\"/snapshot\")"));
        assertTrue(controller.contains("CacheControl.noStore()"));
        assertTrue(controller.contains("ApprovalManagementPermission.Requirement.READ"));
        assertTrue(controller.contains("ApprovalManagementPermission.ResourceScope.TENANT"));
        assertTrue(contracts.contains("EMPTY_PENDING_EXISTING_COMMAND_AUDIT"));
        assertTrue(contracts.contains("P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND"));
        assertTrue(contracts.contains("LIVE_STATE_NOT_EXPOSED"));
        assertTrue(contracts.contains("rawSecretExposed"));
        assertTrue(contracts.contains("automaticRetryAuthorized"));
        assertTrue(configuration.contains("ApprovalAssistanceProductionConfiguration.runtime"));
        assertTrue(configuration.contains("OpenAiResponsesAdvisoryProvider.promptVersion"));

        for (String forbidden : List.of(
            "@PostMapping",
            "@PutMapping",
            "@PatchMapping",
            "@DeleteMapping",
            "ApprovalTaskCommandService",
            "ApprovalTaskCollaborationService",
            "ApprovalMessageService",
            "PurchasePaymentTaskActionService",
            "ConnectorExecutionPort",
            "AiAdvisoryService",
            ".advise(",
            ".generate(",
            ".execute(",
            "HttpClient",
            "WebClient",
            "RestClient",
            "System.getenv",
            "OPENAI_API_KEY\"",
            "@Scheduled",
            "TaskScheduler",
            "ExecutorService",
            "ProcessBuilder",
            "Runtime.getRuntime().exec",
            "ACT_RU_",
            "ACT_HI_",
            "ACT_RE_",
            "ACT_GE_",
            "ACT_ID_"
        )) {
            assertFalse(production.contains(forbidden), "forbidden P6-A authority: " + forbidden);
        }
    }

    @Test
    void p6aAddsNoMigrationAndRetainsSingleAutomaticWorkflow() throws IOException {
        Path root = repositoryRoot();
        Path migrationRoot = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources"
        );
        boolean p6aMigration = Files.walk(migrationRoot)
            .filter(Files::isRegularFile)
            .map(path -> path.getFileName().toString())
            .anyMatch(name -> name.matches("V5[1-9]__.*\\.sql"));
        assertFalse(p6aMigration);

        Path workflowRoot = root.resolve(".github/workflows");
        List<Path> automatic = Files.list(workflowRoot)
            .filter(path -> path.getFileName().toString().matches(".*\\.ya?ml"))
            .filter(path -> {
                try {
                    String content = Files.readString(path);
                    return content.matches("(?s).*\\n\\s{0,4}(pull_request|push):\\s*\\n.*");
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            })
            .toList();
        assertTrue(automatic.size() == 1);
        assertTrue(
            automatic.getFirst().getFileName().toString()
                .equals("approval-platform-validation.yml")
        );
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

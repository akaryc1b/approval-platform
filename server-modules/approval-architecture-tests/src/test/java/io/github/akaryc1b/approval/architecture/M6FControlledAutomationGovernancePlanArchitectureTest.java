package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6FControlledAutomationGovernancePlanArchitectureTest {

    @Test
    void p6bPlansRemainGetOnlyReviewProjectionsWithoutRuntimeAuthority() throws IOException {
        Path root = repositoryRoot();
        String contracts = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernancePlanContracts.java"
        ));
        String controller = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernancePlanController.java"
        ));
        String production = contracts + controller;

        assertTrue(controller.contains("@GetMapping(\"/change-plan\")"));
        assertTrue(controller.contains("CacheControl.noStore()"));
        assertTrue(controller.contains("ApprovalManagementPermission.Requirement.READ"));
        assertTrue(contracts.contains("NON_EXECUTABLE_REVIEW_ONLY"));
        assertTrue(contracts.contains("AI_PROVIDER_CANARY_RUNTIME_NOT_IMPLEMENTED"));
        assertTrue(contracts.contains("AI_PROVIDER_ROLLOUT_MUTATION_NOT_AVAILABLE"));
        assertTrue(contracts.contains("DISABLE_RUNTIME_FLAG_AND_REDEPLOY"));
        assertTrue(contracts.contains("plannedTrafficPercent != 0"));
        assertTrue(contracts.contains("applyAuthorized"));
        assertTrue(contracts.contains("automaticRetryAuthorized"));

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
            "OpenAiResponsesProductionRuntimeFactory",
            ".advise(",
            ".generate(",
            "HttpClient",
            "WebClient",
            "RestClient",
            "System.getenv",
            "System.getProperty",
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
            assertFalse(production.contains(forbidden), "forbidden P6-B authority: " + forbidden);
        }
    }

    @Test
    void p6bAddsNoMigrationAndRetainsSingleAutomaticWorkflow() throws IOException {
        Path root = repositoryRoot();
        Path migrationRoot = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources"
        );
        boolean p6bMigration = Files.walk(migrationRoot)
            .filter(Files::isRegularFile)
            .map(path -> path.getFileName().toString())
            .anyMatch(name -> name.matches("V5[1-9]__.*\\.sql"));
        assertFalse(p6bMigration);

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

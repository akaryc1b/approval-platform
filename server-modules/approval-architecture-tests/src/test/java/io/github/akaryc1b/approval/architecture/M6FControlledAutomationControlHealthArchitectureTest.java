package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6FControlledAutomationControlHealthArchitectureTest {

    @Test
    void p6cUsesOneSharedRuntimeAndExposesOnlyGetHealth() throws IOException {
        Path root = repositoryRoot();
        String holder = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalAssistanceProductionRuntime.java");
        String productionConfiguration = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalAssistanceProductionConfiguration.java");
        String governanceConfiguration = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ControlledAutomationGovernanceConfiguration.java");
        String controller = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceControlHealthController.java");
        String contracts = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceControlHealthContracts.java");
        String source = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceControlHealthSource.java");
        String apiProduction = controller + contracts + source + governanceConfiguration;

        assertTrue(holder.contains("Optional<OpenAiResponsesProductionRuntimeFactory>"));
        assertTrue(productionConfiguration.contains(
            "ApprovalAssistanceProductionRuntime approvalAssistanceProductionRuntime"
        ));
        assertTrue(productionConfiguration.contains("productionRuntime.factory()"));
        assertTrue(governanceConfiguration.contains(
            "ApprovalAssistanceProductionRuntime productionRuntime"
        ));
        assertTrue(governanceConfiguration.contains("factory.controlSnapshot()"));
        assertFalse(governanceConfiguration.contains(
            "ApprovalAssistanceProductionConfiguration.runtime("
        ));
        assertTrue(controller.contains("@GetMapping(\"/control-health\")"));
        assertTrue(controller.contains("CacheControl.noStore()"));
        assertTrue(controller.contains("ApprovalManagementPermission.Requirement.READ"));
        assertTrue(contracts.contains("CONFIGURED_USAGE_NOT_EXPOSED"));
        assertTrue(contracts.contains("REQUEST_CAP_ONLY_CONSUMPTION_NOT_EXPOSED"));

        for (String forbidden : List.of(
            "@PostMapping",
            "@PutMapping",
            "@PatchMapping",
            "@DeleteMapping",
            ".bind(",
            ".advise(",
            ".execute(",
            ".reserve(",
            ".tryAcquire(",
            "System.getenv",
            "OPENAI_API_KEY\"",
            "ApprovalTaskCommandService",
            "ApprovalTaskCollaborationService",
            "ApprovalMessageService",
            "ConnectorExecutionPort",
            "HttpClient",
            "WebClient",
            "RestClient",
            "@Scheduled",
            "TaskScheduler",
            "ExecutorService",
            "ProcessBuilder",
            "Runtime.getRuntime().exec"
        )) {
            assertFalse(apiProduction.contains(forbidden),
                "forbidden P6-C authority: " + forbidden);
        }
    }

    @Test
    void runtimeSnapshotIsReadOnlyAndAddsNoMigrationOrWorkflow() throws IOException {
        Path root = repositoryRoot();
        String factory = read(root,
            "server-modules/approval-ai-openai/src/main/java/"
                + "io/github/akaryc1b/approval/ai/openai/"
                + "OpenAiResponsesProductionRuntimeFactory.java");

        assertTrue(factory.contains("public RuntimeControlSnapshot controlSnapshot()"));
        assertTrue(factory.contains("circuitBreaker.state()"));
        assertTrue(factory.contains("circuitBreaker.generation()"));
        assertTrue(factory.contains("false,\n            false"));
        assertFalse(factory.contains("public void applyControl"));
        assertFalse(factory.contains("public void mutateControl"));

        Path migrationRoot = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources"
        );
        boolean p6cMigration = Files.walk(migrationRoot)
            .filter(Files::isRegularFile)
            .map(path -> path.getFileName().toString())
            .anyMatch(name -> name.matches("V5[1-9]__.*\\.sql"));
        assertFalse(p6cMigration);

        Path workflowRoot = root.resolve(".github/workflows");
        List<Path> automatic = Files.list(workflowRoot)
            .filter(path -> path.getFileName().toString().matches(".*\\.ya?ml"))
            .filter(path -> {
                try {
                    String content = Files.readString(path);
                    return content.matches(
                        "(?s).*\\n\\s{0,4}(pull_request|push):\\s*\\n.*"
                    );
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

    private static String read(Path root, String relativePath) throws IOException {
        return Files.readString(root.resolve(relativePath));
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

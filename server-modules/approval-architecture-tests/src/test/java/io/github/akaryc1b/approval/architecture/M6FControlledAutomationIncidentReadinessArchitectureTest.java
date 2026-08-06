package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6FControlledAutomationIncidentReadinessArchitectureTest {

    @Test
    void p6fReadinessIsCompositeGetOnlyManualAndNonExecuting() throws IOException {
        Path root = repositoryRoot();
        String contracts = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceIncidentReadinessContracts.java");
        String source = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceIncidentReadinessSource.java");
        String controller = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceIncidentReadinessController.java");
        String configuration = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ControlledAutomationGovernanceConfiguration.java");
        String surface = contracts + source + controller + configuration;

        assertTrue(controller.contains("@GetMapping(\"/incident-readiness\")"));
        assertTrue(controller.contains("CacheControl.noStore()"));
        assertTrue(controller.contains("ApprovalManagementPermission.Requirement.READ"));
        assertTrue(controller.contains("ApprovalManagementPermission.ResourceScope.TENANT"));
        assertTrue(source.contains("IncidentReadinessView readiness("));
        assertTrue(configuration.contains("OperationsView snapshot = snapshotSource.current()"));
        assertTrue(configuration.contains("factory.controlSnapshot()"));
        assertTrue(configuration.contains("factory.usageSnapshot(trustedTenantId)"));
        assertTrue(configuration.contains("historyQuery.summarize(new HistoryWindow("));
        assertTrue(configuration.contains("ReviewPlan.preview(Operation.ROLLBACK, snapshot)"));
        assertTrue(contracts.contains("all P6-F components must bind to the exact same P6-A"));
        assertTrue(contracts.contains("AI_INCIDENT_STEP_DO_NOT_AUTOMATICALLY_RETRY"));
        assertTrue(contracts.contains("AI_INCIDENT_STEP_REVIEW_NON_EXECUTABLE_ROLLBACK_PLAN"));
        assertTrue(contracts.contains("notificationAutomationAvailable"));
        assertTrue(contracts.contains("rollbackExecutionAvailable"));
        assertTrue(contracts.contains("AI_INCIDENT_RESPONSE_MANUAL_RELEASE_ONLY"));

        for (String forbidden : List.of(
            "@PostMapping",
            "@PutMapping",
            "@PatchMapping",
            "@DeleteMapping",
            ".bind(",
            ".advise(",
            ".generate(",
            ".execute(",
            "evidenceStore.store",
            "ApprovalTaskCommandService",
            "ApprovalTaskCollaborationService",
            "ConnectorExecutionPort",
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
            assertFalse(surface.contains(forbidden), "forbidden P6-F authority: " + forbidden);
        }
    }

    @Test
    void p6fAddsNoMigrationAndRetainsSingleAutomaticWorkflow() throws IOException {
        Path root = repositoryRoot();
        Path migrationRoot = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources"
        );
        boolean p6fMigration = Files.walk(migrationRoot)
            .filter(Files::isRegularFile)
            .map(path -> path.getFileName().toString())
            .anyMatch(name -> name.matches("V5[1-9]__.*\\.sql"));
        assertFalse(p6fMigration);

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

    private static String read(Path root, String relative) throws IOException {
        return Files.readString(root.resolve(relative));
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

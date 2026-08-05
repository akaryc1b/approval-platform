package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6FControlledAutomationUsageArchitectureTest {

    @Test
    void p6dUsageIsTenantScopedGetOnlyAndCannotInvokeOrMutate() throws IOException {
        Path root = repositoryRoot();
        String ledger = read(root,
            "server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/"
                + "ai/openai/OpenAiResponsesRuntimeUsageLedger.java");
        String admission = read(root,
            "server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/"
                + "ai/openai/OpenAiResponsesTransportAdmission.java");
        String factory = read(root,
            "server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/"
                + "ai/openai/OpenAiResponsesProductionRuntimeFactory.java");
        String contracts = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceUsageContracts.java");
        String source = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceUsageSource.java");
        String controller = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceUsageController.java");
        String configuration = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ControlledAutomationGovernanceConfiguration.java");
        String surface = contracts + source + controller + configuration;

        assertTrue(controller.contains("@GetMapping(\"/usage\")"));
        assertTrue(controller.contains("CacheControl.noStore()"));
        assertTrue(controller.contains("ApprovalManagementPermission.Requirement.READ"));
        assertTrue(controller.contains("ApprovalManagementPermission.ResourceScope.TENANT"));
        assertTrue(source.contains("current(String trustedTenantId)"));
        assertTrue(configuration.contains("factory.usageSnapshot(trustedTenantId)"));
        assertTrue(factory.contains("new OpenAiResponsesRuntimeUsageLedger("));
        assertTrue(factory.contains("usageLedger,"));
        assertTrue(admission.contains("usageRecorder.record("));
        assertTrue(admission.contains("costEstimate.estimatedMicros()"));
        assertTrue(ledger.contains("processLocal"));
        assertTrue(ledger.contains("actualProviderCost"));
        assertTrue(contracts.contains("globalExactUsageExposed"));
        assertTrue(contracts.contains("otherTenantUsageExposed"));
        assertTrue(contracts.contains("AI_USAGE_HISTORY_NOT_DURABLE"));
        assertTrue(contracts.contains("AI_USAGE_ACTUAL_PROVIDER_COST_NOT_AVAILABLE"));
        assertFalse(contracts.contains("globalCommittedRequests"));
        assertFalse(contracts.contains("otherTenantCommittedRequests"));

        for (String forbidden : List.of(
            "@PostMapping",
            "@PutMapping",
            "@PatchMapping",
            "@DeleteMapping",
            "ApprovalTaskCommandService",
            "ApprovalTaskCollaborationService",
            "ConnectorExecutionPort",
            "AiAdvisoryService",
            ".advise(",
            ".generate(",
            ".execute(",
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
            assertFalse(surface.contains(forbidden), "forbidden P6-D authority: " + forbidden);
        }
    }

    @Test
    void p6dAddsNoMigrationAndRetainsSingleAutomaticWorkflow() throws IOException {
        Path root = repositoryRoot();
        Path migrationRoot = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources"
        );
        boolean p6dMigration = Files.walk(migrationRoot)
            .filter(Files::isRegularFile)
            .map(path -> path.getFileName().toString())
            .anyMatch(name -> name.matches("V5[1-9]__.*\\.sql"));
        assertFalse(p6dMigration);

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

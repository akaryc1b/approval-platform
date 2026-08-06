package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6FControlledAutomationDurableHistoryArchitectureTest {

    @Test
    void p6eHistoryUsesV49ReadOnlyTenantAggregatesWithoutAuthority() throws IOException {
        Path root = repositoryRoot();
        String queryContract = read(root,
            "server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/"
                + "ai/core/ApprovalAssistanceGovernanceHistoryQuery.java");
        String jdbc = read(root,
            "server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/"
                + "approval/persistence/jdbc/JdbcApprovalAssistanceGovernanceHistoryQuery.java");
        String contracts = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceHistoryContracts.java");
        String source = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceHistorySource.java");
        String controller = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ControlledAutomationGovernanceHistoryController.java");
        String configuration = read(root,
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ControlledAutomationGovernanceConfiguration.java");
        String surface = queryContract + contracts + source + controller + configuration;
        String normalizedJdbc = jdbc.toLowerCase(Locale.ROOT);

        assertTrue(controller.contains("@GetMapping(\"/history\")"));
        assertTrue(controller.contains("CacheControl.noStore()"));
        assertTrue(controller.contains("ApprovalManagementPermission.Requirement.READ"));
        assertTrue(controller.contains("ApprovalManagementPermission.ResourceScope.TENANT"));
        assertTrue(source.contains("HistoryView history("));
        assertTrue(queryContract.contains("Duration.ofDays(31)"));
        assertTrue(queryContract.contains("Duration.ofDays(3_650)"));
        assertTrue(queryContract.contains("unsafeRetryCount != 0"));
        assertTrue(jdbc.contains("setReadOnly(true)"));
        assertTrue(jdbc.contains("ISOLATION_REPEATABLE_READ"));
        assertTrue(jdbc.contains("ap_ai_approval_assistance_evidence"));
        assertTrue(jdbc.contains("ap_ai_approval_assistance_evidence_state"));
        assertTrue(jdbc.contains("count(distinct e.version_evidence_hash)"));
        assertTrue(jdbc.contains("retention_due_count"));
        assertTrue(configuration.contains("snapshot.observedAt()"));
        assertTrue(contracts.contains("durableHistory"));
        assertTrue(contracts.contains("crossProcessHistory"));
        assertTrue(contracts.contains("actualProviderCostAvailable"));
        assertTrue(contracts.contains("costUpperBoundHistoryAvailable"));
        assertTrue(contracts.contains("AI_RETENTION_TOMBSTONE_DUE"));
        assertTrue(contracts.contains("AI_DURABLE_HISTORY_VERSION_DRIFT_DETECTED"));

        assertFalse(normalizedJdbc.contains("insert into"));
        assertFalse(normalizedJdbc.contains("update ap_ai_"));
        assertFalse(normalizedJdbc.contains("delete from"));
        assertFalse(jdbc.contains("JdbcTemplate.update"));

        for (String forbidden : List.of(
            "@PostMapping",
            "@PutMapping",
            "@PatchMapping",
            "@DeleteMapping",
            "ApprovalTaskCommandService",
            "ApprovalTaskCollaborationService",
            "ConnectorExecutionPort",
            ".advise(",
            ".generate(",
            ".bind(",
            "System.getenv",
            "OPENAI_API_KEY\"",
            "HttpClient",
            "WebClient",
            "RestClient",
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
            assertFalse(surface.contains(forbidden), "forbidden P6-E authority: " + forbidden);
        }
    }

    @Test
    void p6eAddsNoMigrationAndRetainsSingleAutomaticWorkflow() throws IOException {
        Path root = repositoryRoot();
        Path migrationRoot = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources"
        );
        boolean p6eMigration = Files.walk(migrationRoot)
            .filter(Files::isRegularFile)
            .map(path -> path.getFileName().toString())
            .anyMatch(name -> name.matches("V5[1-9]__.*\\.sql"));
        assertFalse(p6eMigration);

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

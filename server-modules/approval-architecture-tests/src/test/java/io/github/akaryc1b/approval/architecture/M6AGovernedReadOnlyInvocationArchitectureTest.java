package io.github.akaryc1b.approval.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.github.akaryc1b.approval.connector.invocation.GovernedReadOnlyConnectorInvocationCoordinator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6AGovernedReadOnlyInvocationArchitectureTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V([0-9]+)__.*\\.sql$");
    private static final String GOVERNED_M6_E_V49 =
        "V49__create_ai_approval_assistance_durable_evidence.sql";

    @Test
    void invocationCoreDoesNotDependOnPersistenceFlowableWebOrScheduling() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..connector.invocation..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "java.sql..",
                "javax.sql..",
                "org.springframework.transaction..",
                "org.springframework.jdbc..",
                "org.springframework.web..",
                "org.flowable..",
                "io.github.akaryc1b.approval.persistence..",
                "io.github.akaryc1b.approval.engine.."
            );

        rule.check(new ClassFileImporter().importPackages(
            GovernedReadOnlyConnectorInvocationCoordinator.class.getPackageName()
        ));
    }

    @Test
    void invocationProductionSourceHasNoMutationPersistenceWorkerSchedulerOrRetry() throws IOException {
        Path root = repositoryRoot();
        String source = readTree(root.resolve(
            "server-modules/approval-connector-invocation-core/src/main/java"
        ));
        String configuration = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalConnectorInvocationConfiguration.java"
        )) + Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ApprovalConnectorInvocationProperties.java"
        ));
        String combined = source + '\n' + configuration;

        for (String forbidden : List.of(
            "@Transactional",
            "JdbcTemplate",
            "DataSource",
            "@Scheduled",
            "SchedulingConfigurer",
            "TaskScheduler",
            "RuntimeService",
            "TaskService",
            "ProcessMigrationService",
            "@RestController",
            "@PostMapping",
            "@PutMapping",
            "@PatchMapping",
            "@DeleteMapping",
            "automaticRetry",
            "retryInvocation",
            "replayInvocation",
            "RecoveryWorker",
            "ReconciliationWorker",
            "ACT_"
        )) {
            assertFalse(combined.contains(forbidden), "forbidden boundary: " + forbidden);
        }
        assertFalse(source.contains("http://"));
        assertFalse(source.contains("https://"));
        assertFalse(source.contains("endpointUrl"));
        assertTrue(source.contains("UNKNOWN_AFTER_DISPATCH"));
        assertTrue(source.contains("dispatchCount"));
        assertTrue(source.contains("productionExecutionAuthorized"));
        assertTrue(source.contains("approvalStateMutationAuthorized"));
    }

    @Test
    void flywayRecognizesOnlyGovernedM6EV49AndKeepsOneAutomaticWorkflow()
        throws IOException {
        Path root = repositoryRoot();
        Path migrationRoot = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources/db/migration"
        );
        int highest;
        List<String> v49;
        try (Stream<Path> paths = Files.list(migrationRoot)) {
            List<String> names = paths.map(path -> path.getFileName().toString()).toList();
            highest = names.stream()
                .map(MIGRATION_VERSION::matcher)
                .filter(Matcher::matches)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max()
                .orElseThrow();
            v49 = names.stream().filter(name -> name.startsWith("V49__")).toList();
        }
        assertEquals(49, highest);
        assertEquals(List.of(GOVERNED_M6_E_V49), v49);

        Path workflowRoot = root.resolve(".github/workflows");
        List<Path> automatic;
        try (Stream<Path> paths = Files.list(workflowRoot)) {
            automatic = paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    try {
                        String content = Files.readString(path);
                        return content.contains("  pull_request:")
                            || content.contains("  push:");
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();
        }
        assertEquals(1, automatic.size());
        assertEquals("approval-platform-validation.yml", automatic.getFirst()
            .getFileName().toString());
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

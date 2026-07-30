package io.github.akaryc1b.approval.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.github.akaryc1b.approval.connector.operations.BoundedConnectorOperationsDiagnosticsStore;
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

class M6AConnectorOperationsDiagnosticsArchitectureTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V([0-9]+)__.*\\.sql$");

    @Test
    void operationsCoreHasNoPersistenceWebFlowableOrSchedulingDependency() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..connector.operations..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "java.sql..",
                "javax.sql..",
                "org.springframework..",
                "org.flowable..",
                "io.github.akaryc1b.approval.persistence..",
                "io.github.akaryc1b.approval.engine.."
            );
        rule.check(new ClassFileImporter().importPackages(
            BoundedConnectorOperationsDiagnosticsStore.class.getPackageName()
        ));
    }

    @Test
    void productionSourceHasNoDurableMutationRetryWorkerOrSecretSurface() throws IOException {
        Path root = repositoryRoot();
        String core = readTree(root.resolve(
            "server-modules/approval-connector-operations-core/src/main/java"
        ));
        Path server = root.resolve("apps/server/src/main/java/io/github/akaryc1b/approval");
        String api = Files.readString(server.resolve("api/ConnectorOperationsDiagnosticsController.java"))
            + Files.readString(server.resolve("api/ConnectorOperationsDiagnosticsParameters.java"))
            + Files.readString(server.resolve("api/ConnectorOperationsDiagnosticsApiExceptionHandler.java"))
            + Files.readString(server.resolve("config/ApprovalConnectorOperationsDiagnosticsConfiguration.java"))
            + Files.readString(server.resolve("config/ApprovalConnectorOperationsDiagnosticsProperties.java"))
            + Files.readString(server.resolve("config/MicrometerConnectorOperationsObservationSink.java"));
        String combined = core + '\n' + api;
        for (String forbidden : List.of(
            "@Transactional",
            "JdbcTemplate",
            "DataSource",
            "@Scheduled",
            "TaskScheduler",
            "RuntimeService",
            "TaskService",
            "ProcessMigrationService",
            "@PostMapping",
            "@PutMapping",
            "@PatchMapping",
            "@DeleteMapping",
            "retryInvocation",
            "replayInvocation",
            "recoverInvocation",
            "refreshToken",
            "invalidateToken",
            "rotateCredential",
            "clearCache",
            "ACT_"
        )) {
            assertFalse(combined.contains(forbidden), "forbidden P8 boundary: " + forbidden);
        }
        assertFalse(core.contains("rawTenant"));
        assertFalse(core.contains("endpointUrl"));
        assertFalse(core.contains("Authorization"));
        assertFalse(core.contains("Bearer "));
        assertTrue(core.contains("processLocal"));
        assertTrue(core.contains("persistent"));
        assertTrue(core.contains("productionExecutionAuthorized"));
    }

    @Test
    void controllerIsGetOnlyNoStoreAndTenantScoped() throws IOException {
        Path root = repositoryRoot();
        String controller = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ConnectorOperationsDiagnosticsController.java"
        ));
        assertEquals(2, occurrences(controller, "@GetMapping"));
        assertTrue(controller.contains("CacheControl.noStore()"));
        assertTrue(controller.contains("@RequestHeader(TENANT_ID)"));
        assertTrue(controller.contains("OPERATIONAL_FAILURE_READ"));
        assertFalse(controller.contains("@RequestBody"));
    }

    @Test
    void metricsHaveOnlyClosedLowCardinalityTags() throws IOException {
        Path root = repositoryRoot();
        String metrics = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "MicrometerConnectorOperationsObservationSink.java"
        ));
        for (String tag : List.of("provider", "operation", "outcome", "failure", "duration")) {
            assertTrue(metrics.contains(".tag(\"" + tag + "\""));
        }
        for (String forbidden : List.of(
            ".tag(\"tenant",
            ".tag(\"credential",
            ".tag(\"token",
            ".tag(\"request",
            ".tag(\"trace",
            ".tag(\"user",
            ".tag(\"endpoint"
        )) {
            assertFalse(metrics.contains(forbidden));
        }
    }

    @Test
    void invocationObserverIsBestEffortAndDoesNotModifyP7Coordinator() throws IOException {
        Path root = repositoryRoot();
        String facade = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ObservedReadOnlyConnectorInvocationService.java"
        ));
        String coordinator = Files.readString(root.resolve(
            "server-modules/approval-connector-invocation-core/src/main/java/"
                + "io/github/akaryc1b/approval/connector/invocation/"
                + "GovernedReadOnlyConnectorInvocationCoordinator.java"
        ));
        assertTrue(facade.contains("coordinator.invoke(trustedTenantId, request)"));
        assertTrue(facade.contains("observationSink.record(result.evidence(), clock.instant())"));
        assertTrue(facade.contains("catch (RuntimeException problem)"));
        assertFalse(coordinator.contains("ConnectorInvocationObservationSink"));
    }

    @Test
    void flywayAndAutomaticWorkflowBoundariesRemainFrozen() throws IOException {
        Path root = repositoryRoot();
        Path migrationRoot = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources/db/migration"
        );
        int highest;
        try (Stream<Path> paths = Files.list(migrationRoot)) {
            highest = paths
                .map(path -> path.getFileName().toString())
                .map(MIGRATION_VERSION::matcher)
                .filter(Matcher::matches)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max()
                .orElseThrow();
        }
        assertEquals(48, highest);
        Path workflowRoot = root.resolve(".github/workflows");
        List<Path> automatic;
        try (Stream<Path> paths = Files.list(workflowRoot)) {
            automatic = paths.filter(Files::isRegularFile).filter(path -> {
                try {
                    String content = Files.readString(path);
                    return content.contains("  pull_request:") || content.contains("  push:");
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
        }
        assertEquals(1, automatic.size());
        assertEquals("approval-platform-validation.yml", automatic.getFirst()
            .getFileName().toString());
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
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

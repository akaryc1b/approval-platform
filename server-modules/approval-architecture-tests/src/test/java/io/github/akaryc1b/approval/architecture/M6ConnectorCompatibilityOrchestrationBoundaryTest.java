package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6ConnectorCompatibilityOrchestrationBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path CONTRACT_ROOT = ROOT.resolve(
        "server-modules/approval-connector-spi/src/main/java/"
            + "io/github/akaryc1b/approval/connector/contract"
    );

    @Test
    void compatibilityMatrixUsesImmutableRegistryEvidenceOnly() throws IOException {
        for (String fileName : List.of(
            "ConnectorProviderCompatibilityEntry.java",
            "ConnectorProviderCompatibilityReport.java",
            "DeterministicConnectorProviderCompatibilityMatrix.java"
        )) {
            String content = Files.readString(CONTRACT_ROOT.resolve(fileName));
            for (String forbidden : List.of(
                "java.net.",
                "HttpClient",
                "WebClient",
                "RestClient",
                "ServiceLoader",
                "Class.forName",
                "System.currentTimeMillis",
                "Instant.now(",
                "healthCheck",
                "loadBalancer",
                "metrics"
            )) {
                assertFalse(content.contains(forbidden), fileName + " contains " + forbidden);
            }
        }
    }

    @Test
    void orchestrationPlanningCannotExecuteProviderOrAuthorizeRetry() throws IOException {
        String planner = Files.readString(
            CONTRACT_ROOT.resolve("DeterministicConnectorOrchestrationPlanner.java")
        );
        String plan = Files.readString(CONTRACT_ROOT.resolve("ConnectorOrchestrationPlan.java"));

        for (String forbidden : List.of(
            ".execute(",
            "ConnectorResult",
            "Thread.sleep",
            "ExecutorService",
            "ScheduledExecutor",
            "RetryTemplate",
            "java.net.",
            "HttpClient",
            "WebClient",
            "RestClient"
        )) {
            assertFalse(planner.contains(forbidden), "planner contains " + forbidden);
        }
        assertTrue(plan.contains("boolean automaticExecutionAllowed()"));
        assertTrue(plan.contains("boolean automaticRetryAllowed()"));
        assertTrue(
            plan.lines().filter(line -> line.trim().equals("return false;")).count() >= 2,
            "execution and retry must remain explicitly disabled"
        );
        assertFalse(plan.contains("ConnectorProviderBinding"));
        assertFalse(plan.contains("ConnectorExecutionPort"));
    }

    @Test
    void clientsCannotManufactureAuthorizationOrOrchestrationEvidence() throws IOException {
        for (Path clientRoot : List.of(
            ROOT.resolve("apps/web/overlay"),
            ROOT.resolve("apps/mobile/overlay")
        )) {
            if (!Files.isDirectory(clientRoot)) {
                continue;
            }
            for (Path source : filesUnder(clientRoot)) {
                String fileName = source.getFileName().toString();
                if (!fileName.endsWith(".ts") && !fileName.endsWith(".vue")) {
                    continue;
                }
                String content = Files.readString(source);
                for (String forbidden : List.of(
                    "ConnectorInvocationAuthorizationEvidence",
                    "ConnectorOrchestrationPlanningRequest",
                    "ConnectorOrchestrationPlan",
                    "ConnectorProviderCompatibilityReport",
                    "TrustedConnectorExecutionContext",
                    "CredentialReference"
                )) {
                    assertFalse(
                        content.contains(forbidden),
                        relative(source) + " exposes server-owned connector evidence " + forbidden
                    );
                }
            }
        }
    }

    private static List<Path> filesUnder(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).toList();
        }
    }

    private static String relative(Path path) {
        return ROOT.relativize(path).toString().replace('\\', '/');
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                && Files.isDirectory(current.resolve("server-modules"))
                && Files.isDirectory(current.resolve(".github"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("approval-platform repository root was not found");
    }
}

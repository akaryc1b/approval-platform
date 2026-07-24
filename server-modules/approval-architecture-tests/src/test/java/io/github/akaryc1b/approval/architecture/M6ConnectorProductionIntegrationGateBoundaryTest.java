package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6ConnectorProductionIntegrationGateBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path CONTRACT_ROOT = ROOT.resolve(
        "server-modules/approval-connector-spi/src/main/java/"
            + "io/github/akaryc1b/approval/connector/contract"
    );
    private static final Path GATE_DOCUMENT = ROOT.resolve(
        "docs/m6/M6_A_PRODUCTION_INTEGRATION_GATE.md"
    );

    @Test
    void productionOwnershipGateCannotExecuteOrResolveInfrastructure()
        throws IOException {
        String evaluator = Files.readString(
            CONTRACT_ROOT.resolve(
                "DeterministicConnectorProductionIntegrationGateEvaluator.java"
            )
        );
        String evidence = Files.readString(
            CONTRACT_ROOT.resolve("ConnectorProductionIntegrationEvidence.java")
        );
        String anchor = Files.readString(
            CONTRACT_ROOT.resolve("ConnectorFoundationAcceptanceAnchor.java")
        );
        String combined = evaluator + evidence + anchor;

        for (String forbidden : List.of(
            ".execute(",
            "ConnectorExecutionPort",
            "ConnectorProviderBinding",
            "ConnectorCredentialResolver",
            "withSecretBytes",
            "java.net.",
            "HttpClient",
            "WebClient",
            "RestClient",
            "DataSource",
            "JdbcTemplate",
            "Flyway",
            "Thread.sleep",
            "ExecutorService",
            "ScheduledExecutor",
            "ServiceLoader",
            "Class.forName",
            "org.flowable"
        )) {
            assertFalse(
                combined.contains(forbidden),
                "production ownership gate contains " + forbidden
            );
        }
    }

    @Test
    void ownershipEvidenceCannotEnableProductionCapabilities()
        throws IOException {
        String evidence = Files.readString(
            CONTRACT_ROOT.resolve("ConnectorProductionIntegrationEvidence.java")
        );
        String evaluator = Files.readString(
            CONTRACT_ROOT.resolve(
                "DeterministicConnectorProductionIntegrationGateEvaluator.java"
            )
        );

        for (String method : List.of(
            "boolean productionEnabled()",
            "boolean providerTransportEnabled()",
            "boolean credentialResolutionEnabled()",
            "boolean tenantRoutingEnabled()",
            "boolean persistenceEnabled()",
            "boolean providerExecutionEnabled()",
            "boolean automaticRetryEnabled()",
            "boolean recoveryWorkerEnabled()",
            "boolean schemaChangeAllowed()",
            "boolean approvalStateMutationAllowed()"
        )) {
            assertTrue(evidence.contains(method), "missing safety method " + method);
        }
        assertTrue(
            evidence.lines().filter(line -> line.trim().equals("return false;")).count() >= 10,
            "all production capability methods must remain false"
        );
        assertTrue(evidence.contains("boolean requiresExplicitCapabilityGate()"));
        assertTrue(evaluator.contains("PLATFORM_INTEGRATION_CORE"));
        assertTrue(evaluator.contains("SHARED_COORDINATION_REQUIRED"));
        assertTrue(evaluator.contains("APPROVAL_STATE_ACTIONS"));
        assertTrue(evaluator.contains("ConnectorProductionDecision.BLOCKED"));
    }

    @Test
    void documentationAndClientsPreserveTheProductionGate()
        throws IOException {
        String document = Files.readString(GATE_DOCUMENT);

        for (String required : List.of(
            "Status: `OWNERSHIP_GATE_IMPLEMENTED_NO_PRODUCTION_ENABLEMENT`",
            "approval-integration-core",
            "approval-integration-jdbc",
            "approval-connector-generic",
            "READY_FOR_SCOPED_IMPLEMENTATION_REVIEW",
            "PR #67 remains Open + Draft",
            "no real Provider network call",
            "no production credentials",
            "no connector-owned persistence",
            "no `V33`",
            "no connector worker",
            "no automatic retry",
            "no approval process-state mutation"
        )) {
            assertTrue(document.contains(required), "missing gate text " + required);
        }

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
                    "ConnectorFoundationAcceptanceAnchor",
                    "ConnectorProductionIntegrationGateRequest",
                    "ConnectorProductionIntegrationEvidence",
                    "ConnectorProductionOwnershipEntry"
                )) {
                    assertFalse(
                        content.contains(forbidden),
                        relative(source) + " exposes server-owned gate type " + forbidden
                    );
                }
            }
        }
    }

    private static List<Path> filesUnder(Path root) throws IOException {
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

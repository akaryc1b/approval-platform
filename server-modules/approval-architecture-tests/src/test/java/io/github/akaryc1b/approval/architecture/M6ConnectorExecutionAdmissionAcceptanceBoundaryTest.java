package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6ConnectorExecutionAdmissionAcceptanceBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path CONTRACT_ROOT = ROOT.resolve(
        "server-modules/approval-connector-spi/src/main/java/"
            + "io/github/akaryc1b/approval/connector/contract"
    );

    @Test
    void executionAdmissionRevalidatesEvidenceButCannotExecuteProvider() throws IOException {
        String policy = Files.readString(
            CONTRACT_ROOT.resolve("DeterministicConnectorExecutionAdmissionPolicy.java")
        );
        String admission = Files.readString(
            CONTRACT_ROOT.resolve("ConnectorExecutionAdmission.java")
        );
        String evidence = Files.readString(
            CONTRACT_ROOT.resolve("ConnectorExecutionAdmissionEvidence.java")
        );

        for (String forbidden : List.of(
            ".execute(",
            "ConnectorResult",
            "ConnectorCredentialResolver",
            "withSecretBytes",
            "java.net.",
            "HttpClient",
            "WebClient",
            "RestClient",
            "ServiceLoader",
            "Class.forName",
            "Thread.sleep",
            "ExecutorService",
            "ScheduledExecutor"
        )) {
            assertFalse(policy.contains(forbidden), "admission policy contains " + forbidden);
        }
        assertFalse(admission.contains("ConnectorProviderBinding"));
        assertFalse(admission.contains("ConnectorExecutionPort"));
        assertFalse(evidence.contains("referenceId"));
        assertTrue(admission.contains("boolean automaticExecutionAllowed()"));
        assertTrue(admission.contains("boolean automaticRetryAllowed()"));
        assertTrue(
            admission.lines().filter(line -> line.trim().equals("return false;")).count() >= 2,
            "admission must explicitly disable automatic execution and retry"
        );
    }

    @Test
    void foundationEvidenceCannotGrantAcceptanceOrProductionEnablement() throws IOException {
        String evaluator = Files.readString(
            CONTRACT_ROOT.resolve("DeterministicConnectorFoundationAcceptanceEvaluator.java")
        );
        String evidence = Files.readString(
            CONTRACT_ROOT.resolve("ConnectorFoundationAcceptanceEvidence.java")
        );
        String blocked = Files.readString(
            CONTRACT_ROOT.resolve("ConnectorFoundationBlockedCapability.java")
        );

        for (String forbidden : List.of(
            ".execute(",
            "ConnectorExecutionPort",
            "ConnectorCredentialResolver",
            "java.net.",
            "HttpClient",
            "WebClient",
            "RestClient",
            "ServiceLoader",
            "Class.forName"
        )) {
            assertFalse(evaluator.contains(forbidden), "acceptance evaluator contains " + forbidden);
        }
        assertTrue(evidence.contains("boolean formalAcceptanceGranted()"));
        assertTrue(evidence.contains("boolean productionEnabled()"));
        assertTrue(evidence.contains("boolean automaticExecutionEnabled()"));
        assertTrue(evidence.contains("boolean automaticRetryEnabled()"));
        assertTrue(
            evidence.lines().filter(line -> line.trim().equals("return false;")).count() >= 4,
            "formal acceptance and production enablement must remain false"
        );
        for (String required : List.of(
            "REAL_PROVIDER_TRANSPORT",
            "PRODUCTION_CREDENTIALS",
            "PERSISTENCE",
            "CONNECTOR_WORKER",
            "AUTOMATIC_EXECUTION",
            "AUTOMATIC_RETRY",
            "HEALTH_BASED_ROUTING",
            "APPROVAL_STATE_MUTATION"
        )) {
            assertTrue(blocked.contains(required), "missing production safety block " + required);
        }
    }

    @Test
    void clientsCannotManufactureAdmissionOrFoundationAcceptanceEvidence() throws IOException {
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
                    "ConnectorExecutionAdmissionRequest",
                    "ConnectorExecutionAdmissionEvidence",
                    "ConnectorExecutionAdmission",
                    "ConnectorFoundationAcceptanceRequest",
                    "ConnectorFoundationAcceptanceEvidence",
                    "TrustedConnectorExecutionContext",
                    "ConnectorInvocationAuthorizationEvidence",
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

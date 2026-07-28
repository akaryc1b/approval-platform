package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6ConnectorResolutionBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path CONTRACT_ROOT = ROOT.resolve(
        "server-modules/approval-connector-spi/src/main/java/"
            + "io/github/akaryc1b/approval/connector/contract"
    );

    @Test
    void credentialResolutionUsesCallbackScopeWithoutRawSecretGetter() throws IOException {
        String contract = Files.readString(CONTRACT_ROOT.resolve(
            "ConnectorCredentialResolver.java"
        ));
        String deterministic = Files.readString(ROOT.resolve(
            "server-modules/approval-connector-spi/src/main/java/"
                + "io/github/akaryc1b/approval/connector/testing/"
                + "DeterministicCredentialResolver.java"
        ));

        assertTrue(contract.contains("withCredential("));
        assertTrue(contract.contains("withSecretBytes("));
        assertTrue(deterministic.contains("Arrays.fill("));
        assertTrue(deterministic.contains("material=<redacted>"));

        Pattern rawGetter = Pattern.compile(
            "(?i)\\b(?:String|byte\\[\\])\\s+"
                + "(?:getSecret|getToken|getPassword|credentialValue|secret|token|password)"
                + "\\s*\\(\\s*\\)"
        );
        assertFalse(rawGetter.matcher(contract).find());
        assertFalse(rawGetter.matcher(deterministic).find());
        assertFalse(contract.contains("record ResolvedCredential"));
    }

    @Test
    void reconciliationNeverAuthorizesBlindAutomaticRetry() throws IOException {
        String request = Files.readString(CONTRACT_ROOT.resolve(
            "ConnectorReconciliationRequest.java"
        ));
        String result = Files.readString(CONTRACT_ROOT.resolve(
            "ConnectorReconciliationResult.java"
        ));
        String decision = Files.readString(CONTRACT_ROOT.resolve(
            "ReconciliationDecision.java"
        ));

        assertTrue(request.contains("ConnectorOutcome.TIMEOUT"));
        assertTrue(request.contains("ConnectorOutcome.UNKNOWN"));
        assertTrue(request.contains("only TIMEOUT or UNKNOWN results may enter reconciliation"));
        assertTrue(result.contains("automaticRetryAllowed()"));
        assertTrue(result.contains("return false;"));
        assertFalse(result.contains("RetryDisposition.RETRY_WITH_BACKOFF"));
        assertFalse(decision.contains("RETRY_ORIGINAL_OPERATION"));
    }

    @Test
    void providerRegistryIsImmutableAndRejectsDuplicateOperations() throws IOException {
        String registry = Files.readString(CONTRACT_ROOT.resolve(
            "ConnectorProviderRegistry.java"
        ));
        String binding = Files.readString(CONTRACT_ROOT.resolve(
            "ConnectorProviderBinding.java"
        ));

        assertTrue(registry.contains("Collections.unmodifiableMap"));
        assertTrue(registry.contains("duplicate provider operation"));
        assertTrue(registry.contains("provider binding type mismatch"));
        assertTrue(binding.contains("request operation does not match provider binding"));
        assertTrue(binding.contains("reconciliation operation does not match provider binding"));
        assertFalse(registry.contains("Class.forName("));
        assertFalse(registry.contains("ServiceLoader.load("));
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

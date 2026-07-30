package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6ConnectorTypedPayloadBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path CONTRACT_ROOT = ROOT.resolve(
        "server-modules/approval-connector-spi/src/main/java/"
            + "io/github/akaryc1b/approval/connector/contract"
    );
    private static final Path PAYLOADS = CONTRACT_ROOT.resolve(
        "ConnectorOperationPayloads.java"
    );
    private static final Path SELECTOR = CONTRACT_ROOT.resolve(
        "DeterministicConnectorProviderSelector.java"
    );
    private static final Path SELECTION_REQUEST = CONTRACT_ROOT.resolve(
        "ConnectorProviderSelectionRequest.java"
    );

    @Test
    void typedBusinessPayloadsCannotCarryTrustedOrCredentialIdentity() throws IOException {
        String content = Files.readString(PAYLOADS);

        for (String forbidden : List.of(
            "String tenantId",
            "String operatorId",
            "String authority",
            "String auditIdentity",
            "CredentialReference",
            "TrustedConnectorExecutionContext",
            "String token",
            "String secret",
            "String password"
        )) {
            assertFalse(
                content.contains(forbidden),
                "typed provider payload exposes trusted or credential field: " + forbidden
            );
        }
        assertTrue(content.contains("establishesTrustedPlatformIdentity()"));
        assertTrue(content.contains("return false;"));
    }

    @Test
    void providerSelectionIsExplicitDeterministicAndFailClosed() throws IOException {
        String request = Files.readString(SELECTION_REQUEST);
        String selector = Files.readString(SELECTOR);

        assertTrue(request.contains("allowedProviderKeys"));
        assertTrue(request.contains("preferredProviderKey"));
        assertTrue(request.contains("policyVersion"));
        assertTrue(request.contains("must contain between 1 and 32 entries"));

        for (String forbidden : List.of(
            "Random",
            "ThreadLocalRandom",
            "ServiceLoader",
            "Class.forName",
            "getDeclared",
            "java.net",
            "HttpClient",
            "WebClient",
            "RestClient",
            ".execute("
        )) {
            assertFalse(
                selector.contains(forbidden),
                "selector contains dynamic, network, reflection or execution marker: " + forbidden
            );
        }
        assertTrue(selector.contains("AMBIGUOUS"));
        assertTrue(selector.contains("PREFERRED_PROVIDER_INELIGIBLE"));
        assertTrue(selector.contains("Missing or differently typed operation bindings"));
    }

    @Test
    void browserAndMobileCannotSelectOrInvokeTrustedProviders() throws IOException {
        for (Path clientRoot : List.of(
            ROOT.resolve("apps/web/overlay"),
            ROOT.resolve("apps/mobile/overlay")
        )) {
            if (!Files.isDirectory(clientRoot)) {
                continue;
            }
            try (var stream = Files.walk(clientRoot)) {
                for (Path source : stream.filter(Files::isRegularFile).toList()) {
                    String name = source.getFileName().toString();
                    if (!name.endsWith(".ts") && !name.endsWith(".vue")) {
                        continue;
                    }
                    String content = Files.readString(source);
                    for (String forbidden : List.of(
                        "ConnectorProviderSelectionRequest",
                        "DeterministicConnectorProviderSelector",
                        "ConnectorOperationPayloads",
                        "allowedProviderKeys",
                        "preferredProviderKey"
                    )) {
                        assertFalse(
                            content.contains(forbidden),
                            relative(source)
                                + " exposes server-owned provider selection through "
                                + forbidden
                        );
                    }
                }
            }
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

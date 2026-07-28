package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6DingTalkTransportConformanceBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path MODULE = ROOT.resolve(
        "server-modules/approval-connector-dingtalk"
    );
    private static final Path DOCUMENT = ROOT.resolve(
        "docs/m6/M6_A_DINGTALK_TRANSPORT_CONFORMANCE.md"
    );

    @Test
    void dingtalkAdapterHasNoRealNetworkCredentialOrPersistenceImplementation()
        throws IOException {
        String source = mainSource();

        for (String forbidden : List.of(
            "java.net.",
            "HttpClient",
            "WebClient",
            "RestClient",
            "DataSource",
            "JdbcTemplate",
            "ConnectorCredentialResolver",
            "withSecretBytes",
            "Thread.sleep",
            "ExecutorService",
            "ScheduledExecutor",
            "ServiceLoader",
            "Class.forName",
            "org.flowable",
            "api.dingtalk.com",
            "oapi.dingtalk.com",
            "x-acs-dingtalk-access-token",
            "Authorization"
        )) {
            assertFalse(source.contains(forbidden), "DingTalk adapter contains " + forbidden);
        }
        assertTrue(source.contains("interface DingTalkTransport"));
        assertTrue(source.contains("credentialMaterialPresent()"));
        assertTrue(source.contains("return false;"));
    }

    @Test
    void dingtalkModuleDependsOnlyOnSpiJsonAndTests() throws IOException {
        String pom = Files.readString(MODULE.resolve("pom.xml"));

        assertTrue(pom.contains("approval-connector-spi"));
        assertTrue(pom.contains("jackson-databind"));
        assertTrue(pom.contains("junit-jupiter"));
        for (String forbidden : List.of(
            "approval-integration-core",
            "approval-integration-jdbc",
            "approval-persistence-jdbc",
            "spring-boot-starter",
            "flowable"
        )) {
            assertFalse(pom.contains(forbidden), "DingTalk module depends on " + forbidden);
        }
    }

    @Test
    void documentationAndClientsPreserveSingleCapabilityScope() throws IOException {
        String document = Files.readString(DOCUMENT);

        for (String required : List.of(
            "Status: `DINGTALK_CAPTURED_TRANSPORT_CONFORMANCE_IMPLEMENTED`",
            "DIRECTORY_READ",
            "IDENTITY_RESOLUTION",
            "/v1.0/contact/users/search",
            "/topapi/v2/user/get",
            "PR #67 remains Open + Draft",
            "no real DingTalk network call",
            "no production credentials",
            "no tenant routing",
            "no connector-owned persistence",
            "no `V33`",
            "no automatic retry",
            "no approval process-state mutation"
        )) {
            assertTrue(document.contains(required), "missing DingTalk gate text " + required);
        }

        for (Path clientRoot : List.of(
            ROOT.resolve("apps/web/overlay"),
            ROOT.resolve("apps/mobile/overlay")
        )) {
            if (!Files.isDirectory(clientRoot)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(clientRoot)) {
                for (Path source : stream.filter(Files::isRegularFile).toList()) {
                    String name = source.getFileName().toString();
                    if (!name.endsWith(".ts") && !name.endsWith(".vue")) {
                        continue;
                    }
                    String content = Files.readString(source);
                    for (String forbidden : List.of(
                        "DingTalkTransportRequest",
                        "DingTalkTransportResponse",
                        "DingTalkDirectoryExecutionPort",
                        "DingTalkIdentityExecutionPort"
                    )) {
                        assertFalse(
                            content.contains(forbidden),
                            relative(source) + " exposes server-owned DingTalk type " + forbidden
                        );
                    }
                }
            }
        }
    }

    private static String mainSource() throws IOException {
        Path root = MODULE.resolve("src/main/java");
        StringBuilder content = new StringBuilder();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path source : stream.filter(Files::isRegularFile).toList()) {
                content.append(Files.readString(source)).append('\n');
            }
        }
        return content.toString();
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

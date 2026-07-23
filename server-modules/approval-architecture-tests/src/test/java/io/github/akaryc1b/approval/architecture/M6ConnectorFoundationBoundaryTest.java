package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6ConnectorFoundationBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Pattern FLYWAY_VERSION = Pattern.compile("V(\\d+)__.*\\.sql");

    @Test
    void connectorSliceAddsNoV33OrOtherFlywayMigration() throws IOException {
        List<Path> migrations = filesUnder(ROOT).stream()
            .filter(path -> path.toString().replace('\\', '/').contains(
                "/src/main/resources/db/migration/"
            ))
            .filter(path -> path.getFileName().toString().endsWith(".sql"))
            .toList();

        assertFalse(migrations.isEmpty(), "expected the existing Flyway baseline");
        for (Path migration : migrations) {
            var matcher = FLYWAY_VERSION.matcher(migration.getFileName().toString());
            if (matcher.matches()) {
                int version = Integer.parseInt(matcher.group(1));
                assertTrue(version <= 32, "unexpected Flyway migration: " + relative(migration));
            }
        }
        assertFalse(
            migrations.stream().anyMatch(path -> path.getFileName().toString().startsWith("V33__")),
            "M6-A must not create V33"
        );
    }

    @Test
    void connectorSliceDoesNotCopyOrModifyM5MigrationSources() {
        for (String forbidden : List.of(
            "docs/M5_PROCESS_INSTANCE_MIGRATION_FEASIBILITY.md",
            "scripts/tests/m5-migration-boundary.test.mjs",
            "server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationCapabilityTest.java",
            "server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationEvidenceCapabilityTest.java"
        )) {
            assertFalse(Files.exists(ROOT.resolve(forbidden)), "M5 source crossed into M6-A: " + forbidden);
        }
    }

    @Test
    void connectorFoundationHasNoRealProviderNetworkAdapter() throws IOException {
        for (Path source : connectorFoundationProductionSources()) {
            String content = Files.readString(source);
            for (String forbidden : List.of(
                "java.net.",
                "java.net.http",
                "HttpClient",
                "WebClient",
                "RestClient",
                "open.feishu.cn",
                "oapi.dingtalk.com",
                "api.dingtalk.com"
            )) {
                assertFalse(
                    content.contains(forbidden),
                    relative(source) + " contains real provider/network marker " + forbidden
                );
            }
        }
        List<String> providerPaths = filesUnder(ROOT.resolve("server-modules")).stream()
            .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
            .map(path -> path.toString().toLowerCase())
            .filter(path -> path.contains("dingtalk") || path.contains("feishu"))
            .toList();
        assertTrue(providerPaths.isEmpty(), "real provider adapter exists: " + providerPaths);
    }

    @Test
    void connectorFoundationCannotCallFlowableOrMutateApprovalState() throws IOException {
        for (Path source : connectorFoundationProductionSources()) {
            String content = Files.readString(source);
            for (String forbidden : List.of(
                "org.flowable",
                "RuntimeService",
                "TaskService",
                "io.github.akaryc1b.approval.application",
                "io.github.akaryc1b.approval.engine",
                "completeTask(",
                "approve(",
                "reject(",
                "withdraw(",
                "terminate(",
                "migrate("
            )) {
                assertFalse(
                    content.contains(forbidden),
                    relative(source) + " crosses approval/Flowable boundary through " + forbidden
                );
            }
        }
    }

    @Test
    void browserContractsCannotManufactureTrustedConnectorContext() throws IOException {
        List<Path> clientRoots = List.of(
            ROOT.resolve("apps/web/overlay"),
            ROOT.resolve("apps/mobile/overlay")
        );
        for (Path clientRoot : clientRoots) {
            if (!Files.isDirectory(clientRoot)) {
                continue;
            }
            for (Path source : filesUnder(clientRoot)) {
                String name = source.getFileName().toString();
                if (!name.endsWith(".ts") && !name.endsWith(".vue")) {
                    continue;
                }
                String content = Files.readString(source);
                assertFalse(
                    content.contains("TrustedConnectorExecutionContext"),
                    relative(source) + " exposes trusted connector context to a client"
                );
                assertFalse(
                    content.contains("CredentialReference"),
                    relative(source) + " exposes provider credential identity to a client"
                );
            }
        }
    }

    @Test
    void approvalValidationRemainsTheOnlyAutomaticWorkflow() throws IOException {
        List<String> automatic = new ArrayList<>();
        for (Path workflow : filesUnder(ROOT.resolve(".github/workflows"))) {
            String name = workflow.getFileName().toString();
            if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
                continue;
            }
            String content = Files.readString(workflow);
            String uncommented = content.lines()
                .map(line -> line.replaceFirst("\\s+#.*$", ""))
                .reduce("", (left, right) -> left + "\n" + right);
            if (Pattern.compile("(?m)^\\s*(pull_request|push):\\s*$")
                .matcher(uncommented).find()) {
                automatic.add(name);
            }
        }
        assertEquals(
            List.of("approval-platform-validation.yml"),
            automatic.stream().sorted().toList()
        );
    }

    @Test
    void acceptedGovernanceDocumentsRemainByteForByteFrozen() throws IOException {
        Map<String, String> frozen = new LinkedHashMap<>();
        frozen.put("docs/M3_FINAL_ACCEPTANCE.md", "459c684027e4a08f08655bff3e31721912dc35bc");
        frozen.put(
            "docs/M4_IDENTITY_AND_TENANT_GOVERNANCE.md",
            "716ecf6503aeaea7a6dbfa5980964a5c4b983619"
        );
        frozen.put(
            "docs/M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md",
            "888f07df905726cfb3507d2ae495db3247d6c4fe"
        );
        frozen.put(
            "docs/M4_SLA_AND_CALENDAR_GOVERNANCE.md",
            "beb098bc6b4ee68c6ca11da0678a76780b72a049"
        );
        frozen.put(
            "docs/M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md",
            "dc687d073e0352e0b88d96bd8df0f4ee36775b6e"
        );
        frozen.put(
            "docs/M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md",
            "3c78cee75ed1ec3536fc8e26d440592e2038c6f2"
        );

        for (var entry : frozen.entrySet()) {
            assertEquals(
                entry.getValue(),
                gitBlobSha(ROOT.resolve(entry.getKey())),
                entry.getKey() + " is frozen"
            );
        }
    }

    private static List<Path> connectorFoundationProductionSources() throws IOException {
        Path connectorRoot = ROOT.resolve(
            "server-modules/approval-connector-spi/src/main/java/io/github/akaryc1b/approval/connector"
        );
        return filesUnder(connectorRoot).stream()
            .filter(path -> path.getFileName().toString().endsWith(".java"))
            .filter(path -> {
                String normalized = path.toString().replace('\\', '/');
                return normalized.contains("/contract/") || normalized.contains("/testing/");
            })
            .toList();
    }

    private static List<Path> filesUnder(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).toList();
        }
    }

    private static String gitBlobSha(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.UTF_8));
            digest.update(content);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
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

package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6ProductionSecretMaterialSourceBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path CORE = ROOT.resolve(
        "server-modules/approval-connector-credential-core"
    );
    private static final Path CONFIG = ROOT.resolve(
        "apps/server/src/main/java/io/github/akaryc1b/approval/config"
    );
    private static final Path DOCUMENT = ROOT.resolve(
        "docs/m6/M6_A_PRODUCTION_SECRET_MATERIAL_SOURCE.md"
    );
    private static final String GOVERNED_M6_E_V49 =
        "V49__create_ai_approval_assistance_durable_evidence.sql";

    @Test
    void backendNeutralCoreHasNoConcreteSecretBackendOrInfrastructureDependency()
        throws IOException {
        String pom = Files.readString(CORE.resolve("pom.xml")).toLowerCase();
        String source = mainSource(CORE);

        for (String forbidden : List.of(
            "spring-boot", "flowable", "approval-persistence-jdbc",
            "approval-integration-jdbc", "httpclient", "vault",
            "secretsmanager", "kubernetes"
        )) {
            assertFalse(pom.contains(forbidden), "credential core depends on " + forbidden);
        }
        for (String forbidden : List.of(
            "System.getenv", "System.getProperty", "Files.read", "Path.of",
            "java.net.", "HttpClient", "WebClient", "RestClient", "JdbcTemplate",
            "DataSource", "VaultTemplate", "SecretsManagerClient", "KmsClient",
            "KubernetesClient", "@Scheduled"
        )) {
            assertFalse(source.contains(forbidden), "credential core contains " + forbidden);
        }
    }

    @Test
    void productionSourceRetainsNoRawSecretArrayFieldOrPublicSecretRendering()
        throws IOException {
        Pattern secretArrayField = Pattern.compile(
            "(?m)^\\s*(private|protected|public)\\s+(static\\s+)?(final\\s+)?"
                + "(byte|char)\\[\\]\\s+\\w+"
        );
        for (Path source : javaFiles(CORE.resolve("src/main/java"))) {
            String content = Files.readString(source);
            assertFalse(
                secretArrayField.matcher(content).find(),
                relative(source) + " retains a secret-capable array field"
            );
            assertFalse(content.contains("String secretValue"));
            assertFalse(content.contains("String accessToken"));
        }
    }

    @Test
    void serverGateIsLiteralDefaultDisabledAndConstructsNoMaterialSource()
        throws IOException {
        String configuration = Files.readString(
            CONFIG.resolve("ApprovalConnectorSecretMaterialConfiguration.java")
        );
        String application = Files.readString(
            ROOT.resolve("apps/server/src/main/resources/application.yml")
        );

        assertFalse(configuration.contains("CredentialMaterialSource"));
        assertFalse(configuration.contains("@RestController"));
        assertFalse(configuration.contains("@Scheduled"));
        assertTrue(application.contains("secret-material:"));
        assertTrue(application.contains("enabled: false"));
        assertTrue(application.contains(
            "backend-selection: BLOCKED_PENDING_BACKEND_SELECTION"
        ));
        assertFalse(application.contains("APPROVAL_CONNECTOR_SECRET"));
    }

    @Test
    void p5OwnsNoMigrationAndRecognizesOnlyGovernedM6EV49() throws IOException {
        Pattern flywayVersion = Pattern.compile("V(\\d+)__.*\\.sql");
        List<String> v49 = new ArrayList<>();
        for (Path migration : filesUnder(ROOT)) {
            String normalized = relative(migration);
            if (!normalized.contains("/src/main/resources/db/migration/")
                || !migration.getFileName().toString().endsWith(".sql")) {
                continue;
            }
            var matcher = flywayVersion.matcher(migration.getFileName().toString());
            if (!matcher.matches()) {
                continue;
            }
            int version = Integer.parseInt(matcher.group(1));
            if (version == 49) {
                v49.add(migration.getFileName().toString());
                assertEquals(GOVERNED_M6_E_V49, migration.getFileName().toString());
            } else {
                assertTrue(version <= 48, "unexpected P5 migration " + normalized);
            }
        }
        assertEquals(List.of(GOVERNED_M6_E_V49), v49);

        List<String> automatic = new ArrayList<>();
        for (Path workflow : filesUnder(ROOT.resolve(".github/workflows"))) {
            String name = workflow.getFileName().toString();
            if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
                continue;
            }
            String uncommented = Files.readString(workflow).lines()
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
    void governanceDocumentKeepsBackendAndExecutionBlocked() throws IOException {
        String document = Files.readString(DOCUMENT);
        for (String required : List.of(
            "Status: `BACKEND_NEUTRAL_MATERIAL_SOURCE_DEFAULT_DISABLED`",
            "`BLOCKED_PENDING_BACKEND_SELECTION`",
            "no concrete production Secret Backend",
            "no Token Acquisition",
            "no Token Refresh",
            "no worker",
            "no automatic retry",
            "no Approval-State Mutation",
            "PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED",
            "PR #67 remains Open + Draft"
        )) {
            assertTrue(document.contains(required), "missing governance text " + required);
        }
    }

    private static String mainSource(Path module) throws IOException {
        StringBuilder content = new StringBuilder();
        for (Path source : javaFiles(module.resolve("src/main/java"))) {
            content.append(Files.readString(source)).append('\n');
        }
        return content.toString();
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        return filesUnder(root).stream()
            .filter(path -> path.getFileName().toString().endsWith(".java"))
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

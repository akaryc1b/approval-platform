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

class M6CredentialBindingFoundationBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path CORE = ROOT.resolve(
        "server-modules/approval-connector-credential-core"
    );
    private static final Path DINGTALK = ROOT.resolve(
        "server-modules/approval-connector-dingtalk"
    );
    private static final Path DOCUMENT = ROOT.resolve(
        "docs/m6/M6_A_SERVER_OWNED_CREDENTIAL_BINDING.md"
    );
    private static final Pattern SECRET_FIELD = Pattern.compile(
        "(?m)^\\s*(private|protected|public)\\s+(static\\s+)?(final\\s+)?byte\\[\\]\\s+\\w+"
    );

    @Test
    void credentialCoreHasOnlySpiAndTestDependencies() throws IOException {
        String pom = Files.readString(CORE.resolve("pom.xml"));

        assertTrue(pom.contains("approval-connector-spi"));
        assertTrue(pom.contains("approval-connector-dingtalk"));
        assertTrue(pom.contains("<scope>test</scope>"));
        assertTrue(pom.contains("junit-jupiter"));
        for (String forbidden : List.of(
            "spring-boot", "flowable", "approval-integration-jdbc",
            "approval-persistence-jdbc", "approval-application", "httpclient",
            "vault", "secretsmanager", "aliyun", "kubernetes"
        )) {
            assertFalse(
                pom.toLowerCase().contains(forbidden),
                "credential core depends on " + forbidden
            );
        }
    }

    @Test
    void credentialCoreHasNoNetworkPersistenceWorkerOrSecretLoader() throws IOException {
        String source = mainSource(CORE);

        for (String forbidden : List.of(
            "java.net.", "HttpClient", "WebClient", "RestClient", "DataSource",
            "JdbcTemplate", "org.springframework", "org.flowable", "System.getenv",
            "System.getProperty", "Files.read", "Path.of", "ThreadLocal",
            "ExecutorService", "ScheduledExecutor", "@Scheduled", "HashiCorp",
            "VaultTemplate", "SecretsManagerClient", "KmsClient", "Aliyun",
            "KubernetesClient"
        )) {
            assertFalse(source.contains(forbidden), "credential core contains " + forbidden);
        }
    }

    @Test
    void productionSourceDoesNotRetainRawSecretFieldsOrUseGenericEscapeCalls()
        throws IOException {
        for (Path source : javaFiles(CORE.resolve("src/main/java"))) {
            String content = Files.readString(source);
            assertFalse(
                SECRET_FIELD.matcher(content).find(),
                relative(source) + " retains a byte-array field"
            );
            assertFalse(
                content.contains(".withCredential("),
                relative(source) + " calls the generic credential escape API"
            );
            assertFalse(
                content.contains(".withSecretBytes("),
                relative(source) + " calls the generic secret escape API"
            );
        }
    }

    @Test
    void evidenceAndPlansCannotContainRawSecretTypes() throws IOException {
        for (String file : List.of(
            "CredentialResolutionEvidence.java",
            "CredentialRotationEvidence.java",
            "CapturedCredentialBindingPlan.java"
        )) {
            String content = Files.readString(findMainSource(file));
            for (String forbidden : List.of(
                "byte[]", "SecretUse", "SecretBytesUse", "AuthorizationHeader",
                "PrivateKey", "AppSecret"
            )) {
                assertFalse(content.contains(forbidden), file + " contains " + forbidden);
            }
            assertTrue(content.contains("productionExecutionAuthorized()")
                || content.contains("productionTransportEnabled()"));
            assertTrue(content.contains("return false;"));
        }
    }

    @Test
    void dingTalkAdapterDoesNotOwnCredentialResolutionOrMaterial() throws IOException {
        String source = mainSource(DINGTALK);

        for (String forbidden : List.of(
            "implements ConnectorCredentialResolver", "ServerOwnedCredentialResolver",
            "CredentialMaterialSource", "ResolvedScopedCredential", "byte[] secret",
            "accessToken =", "appSecret =", "credentialCache"
        )) {
            assertFalse(source.contains(forbidden), "DingTalk adapter contains " + forbidden);
        }
        assertTrue(source.contains("credentialMaterialPresent()"));
        assertTrue(source.contains("absoluteEndpointPresent()"));
    }

    @Test
    void browserAndMobileCannotAccessTrustedCredentialTypes() throws IOException {
        for (Path root : List.of(
            ROOT.resolve("apps/web/overlay"),
            ROOT.resolve("apps/mobile/overlay")
        )) {
            for (Path source : javaScriptAndVueFiles(root)) {
                String content = Files.readString(source);
                for (String forbidden : List.of(
                    "TrustedConnectorExecutionContext", "CredentialResolutionRequest",
                    "CredentialResolutionEvidence", "ResolvedScopedCredential",
                    "CredentialMaterialSource"
                )) {
                    assertFalse(
                        content.contains(forbidden),
                        relative(source) + " exposes " + forbidden
                    );
                }
            }
        }
    }

    @Test
    void foundationAddsNoWorkflowMigrationM5OrApprovalMutation() throws IOException {
        List<String> automaticWorkflows = new ArrayList<>();
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
                automaticWorkflows.add(name);
            }
        }
        assertEquals(
            List.of("approval-platform-validation.yml"),
            automaticWorkflows.stream().sorted().toList()
        );

        Pattern flywayVersion = Pattern.compile("V(\\d+)__.*\\.sql");
        for (Path migration : filesUnder(ROOT)) {
            String normalized = relative(migration);
            if (!normalized.contains("/src/main/resources/db/migration/")
                || !migration.getFileName().toString().endsWith(".sql")) {
                continue;
            }
            var matcher = flywayVersion.matcher(migration.getFileName().toString());
            if (matcher.matches()) {
                assertTrue(
                    Integer.parseInt(matcher.group(1)) <= 32,
                    "unexpected M6 migration " + normalized
                );
            }
        }

        String source = mainSource(CORE);
        for (String forbidden : List.of(
            "migration_intent", "migration_attempt", "reconciliation", "runtime_binding",
            "completeTask(", "approve(", "reject(", "transfer(", "withdraw(",
            "terminate(", "migrate("
        )) {
            assertFalse(source.contains(forbidden), "credential core crosses boundary " + forbidden);
        }
    }

    @Test
    void governanceDocumentFreezesOnlyCredentialResolutionFoundation() throws IOException {
        String document = Files.readString(DOCUMENT);

        for (String required : List.of(
            "Status: `CREDENTIAL_BINDING_FOUNDATION_IMPLEMENTED_NO_PRODUCTION_SECRET_BACKEND`",
            "selected capability: `CREDENTIAL_RESOLUTION`",
            "owner: `PLATFORM_SECURITY`",
            "decision: `SHARED_COORDINATION_REQUIRED`",
            "DingTalk production adapter source remains unchanged",
            "no production secret backend",
            "no real provider transport",
            "no tenant routing",
            "no persistence",
            "no automatic retry",
            "no worker",
            "no approval process-state mutation",
            "PR #67 remains Open + Draft"
        )) {
            assertTrue(document.contains(required), "missing governance text " + required);
        }
    }

    private static Path findMainSource(String fileName) throws IOException {
        return javaFiles(CORE.resolve("src/main/java")).stream()
            .filter(path -> path.getFileName().toString().equals(fileName))
            .findFirst()
            .orElseThrow();
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

    private static List<Path> javaScriptAndVueFiles(Path root) throws IOException {
        return filesUnder(root).stream()
            .filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".ts") || name.endsWith(".vue");
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

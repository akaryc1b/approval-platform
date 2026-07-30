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

class M6DingTalkProductionTransportBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path HTTP = ROOT.resolve(
        "server-modules/approval-connector-dingtalk-http"
    );
    private static final Path DINGTALK = ROOT.resolve(
        "server-modules/approval-connector-dingtalk"
    );
    private static final Path DOCUMENT = ROOT.resolve(
        "docs/m6/M6_A_DINGTALK_PRODUCTION_TRANSPORT.md"
    );
    private static final Pattern SECRET_ARRAY_FIELD = Pattern.compile(
        "(?m)^\\s*(private|protected|public)\\s+(static\\s+)?(final\\s+)?"
            + "(byte|char)\\[\\]\\s+\\w+"
    );

    @Test
    void productionTransportDependsOnlyOnDingTalkCredentialCoreAndTests() throws IOException {
        String pom = Files.readString(HTTP.resolve("pom.xml"));

        assertTrue(pom.contains("approval-connector-dingtalk"));
        assertTrue(pom.contains("approval-connector-credential-core"));
        assertTrue(pom.contains("junit-jupiter"));
        assertTrue(pom.contains("<scope>test</scope>"));
        for (String forbidden : List.of(
            "spring-boot", "flowable", "approval-integration-core",
            "approval-integration-jdbc", "approval-persistence-jdbc",
            "approval-application", "jackson-databind", "httpclient5"
        )) {
            assertFalse(pom.toLowerCase().contains(forbidden), "HTTP module depends on " + forbidden);
        }
    }

    @Test
    void productionEndpointsAreFixedOfficialHttpsBindings() throws IOException {
        String source = mainSource(HTTP);

        assertTrue(source.contains("api.dingtalk.com"));
        assertTrue(source.contains("oapi.dingtalk.com"));
        assertTrue(source.contains("x-acs-dingtalk-access-token"));
        assertTrue(source.contains("access_token"));
        assertTrue(source.contains("new URI(\"https\""));
        for (String forbidden : List.of(
            "System.getenv", "System.getProperty", "Files.read", "Path.of",
            "customerEndpoint", "tenantEndpoint", "baseUrl", "endpointOverride"
        )) {
            assertFalse(source.contains(forbidden), "transport contains endpoint override " + forbidden);
        }
    }

    @Test
    void jdkClientDisablesRedirectProxyAmbientAuthAndUnboundedBodies() throws IOException {
        String source = Files.readString(findMainSource("JdkDingTalkHttpSender.java"));

        for (String required : List.of(
            "HttpClient.Redirect.NEVER", "NoProxySelector.INSTANCE",
            "setEndpointIdentificationAlgorithm(\"HTTPS\")",
            "\"TLSv1.3\"", "\"TLSv1.2\"",
            "readNBytes(MAX_RESPONSE_BODY_BYTES + 1)",
            "HttpTimeoutException", "Thread.currentThread().interrupt()"
        )) {
            assertTrue(source.contains(required), "missing JDK transport boundary " + required);
        }
        assertTrue(source.contains("authenticator().isPresent()"));
        assertTrue(source.contains("cookieHandler().isPresent()"));
    }

    @Test
    void transportContainsNoTokenLifecyclePersistenceWorkerRetryOrApprovalAction()
        throws IOException {
        String source = mainSource(HTTP).toLowerCase();

        for (String forbidden : List.of(
            "clientsecret", "appsecret", "refresh_token", "refreshtoken",
            "granttype", "/oauth2/", "gettoken", "token cache", "tokencache",
            "jdbctemplate", "datasource", "entitymanager", "@scheduled",
            "scheduledexecutor", "retrytemplate", "thread.sleep", "flowable",
            "completetask(", "approve(", "reject(", "transfer(", "withdraw(",
            "terminate(", "migrate("
        )) {
            assertFalse(source.contains(forbidden), "transport crosses boundary " + forbidden);
        }
    }

    @Test
    void productionSourceRetainsNoRawSecretArrayFieldAndRendersRedacted() throws IOException {
        for (Path source : javaFiles(HTTP.resolve("src/main/java"))) {
            String content = Files.readString(source);
            assertFalse(
                SECRET_ARRAY_FIELD.matcher(content).find(),
                relative(source) + " retains a secret-capable array field"
            );
        }
        String all = mainSource(HTTP);
        assertTrue(all.contains("credential=<redacted>"));
        assertFalse(all.contains("String accessToken;"));
        assertFalse(all.contains("byte[] accessToken;"));
    }

    @Test
    void adaptersUseTrustedContextBoundTransportAndRecordProductionMode() throws IOException {
        String transport = Files.readString(
            DINGTALK.resolve(
                "src/main/java/io/github/akaryc1b/approval/connector/dingtalk/DingTalkTransport.java"
            )
        );
        String directory = Files.readString(findDingTalkSource("DingTalkDirectoryExecutionPort.java"));
        String identity = Files.readString(findDingTalkSource("DingTalkIdentityExecutionPort.java"));
        String result = Files.readString(findDingTalkSource("DingTalkResultSupport.java"));
        String production = Files.readString(findMainSource("DingTalkProductionTransport.java"));

        assertTrue(transport.contains("TrustedConnectorExecutionContext context"));
        assertTrue(transport.contains("enum TransportMode"));
        assertTrue(production.contains("return TransportMode.PRODUCTION"));
        assertTrue(production.contains("production DingTalk transport requires trusted context"));
        String invocation = "transport.exchange(context, request.operation(), transportRequest)";
        assertTrue(directory.contains(invocation));
        assertTrue(identity.contains(invocation));
        assertTrue(result.contains("dingtalk-production"));
        assertTrue(result.contains("transportMode"));
    }

    @Test
    void productionTransportRemainsDefaultDisabledAndTestsUseInjectedFakes() throws IOException {
        String server = mainSource(ROOT.resolve("apps/server"));
        String tests = testSource(HTTP);

        assertFalse(server.contains("DingTalkProductionTransport"));
        assertFalse(server.contains("approval-connector-dingtalk-http"));
        assertTrue(tests.contains("RecordingSender"));
        assertTrue(tests.contains("FixtureHttpClient"));
        assertTrue(tests.contains("InetAddress.getByAddress"));
        assertFalse(tests.contains("HttpServer"));
        assertFalse(tests.contains("ServerSocket"));
    }

    @Test
    void p3AddsNoWorkflowPostM5MigrationOrExecutionCoordinator() throws IOException {
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
                    Integer.parseInt(matcher.group(1)) <= 48,
                    "unexpected M6 migration " + normalized
                );
            }
        }

        String source = mainSource(HTTP);
        for (String forbidden : List.of(
            "migration_intent", "migration_attempt", "runtime_binding",
            "execution coordinator", "recovery worker", "automatic retry"
        )) {
            assertFalse(source.toLowerCase().contains(forbidden), "P3 contains " + forbidden);
        }
    }

    @Test
    void governanceDocumentFreezesOnlyProviderTransport() throws IOException {
        String document = Files.readString(DOCUMENT);

        for (String required : List.of(
            "Status: `DINGTALK_PRODUCTION_TRANSPORT_IMPLEMENTED_DEFAULT_DISABLED`",
            "selected capability: `PROVIDER_TRANSPORT`",
            "owner: `CONNECTOR_ADAPTER`",
            "no Token Acquisition",
            "no Token Refresh",
            "no Tenant Routing",
            "no Persistence",
            "no Worker",
            "no Automatic Retry",
            "no Recovery",
            "no Approval-State Mutation",
            "PR #67 remains Open + Draft"
        )) {
            assertTrue(document.contains(required), "missing governance text " + required);
        }
    }

    private static Path findMainSource(String fileName) throws IOException {
        return javaFiles(HTTP.resolve("src/main/java")).stream()
            .filter(path -> path.getFileName().toString().equals(fileName))
            .findFirst()
            .orElseThrow();
    }

    private static Path findDingTalkSource(String fileName) throws IOException {
        return javaFiles(DINGTALK.resolve("src/main/java")).stream()
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

    private static String testSource(Path module) throws IOException {
        StringBuilder content = new StringBuilder();
        for (Path source : javaFiles(module.resolve("src/test/java"))) {
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

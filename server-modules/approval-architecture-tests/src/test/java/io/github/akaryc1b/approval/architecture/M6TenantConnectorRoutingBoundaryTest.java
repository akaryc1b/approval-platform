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

class M6TenantConnectorRoutingBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path ROUTING = ROOT.resolve(
        "server-modules/approval-connector-routing-core"
    );
    private static final Path APPLICATION_SERVICE = ROOT.resolve(
        "server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/"
            + "application/TenantConnectorRouteResolutionService.java"
    );
    private static final Path SERVER_CONFIGURATION = ROOT.resolve(
        "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
            + "ApprovalTenantConnectorRoutingConfiguration.java"
    );
    private static final Path DOCUMENT = ROOT.resolve(
        "docs/m6/M6_A_TENANT_ROUTING.md"
    );

    @Test
    void routeCoreHasNoFlowableJdbcSpringOrTransportDependency() throws IOException {
        String pom = Files.readString(ROUTING.resolve("pom.xml"));
        assertTrue(pom.contains("approval-connector-spi"));
        assertTrue(pom.contains("approval-connector-credential-core"));
        for (String forbidden : List.of(
            "flowable",
            "approval-persistence-jdbc",
            "approval-integration-jdbc",
            "approval-integration-core",
            "spring-boot",
            "approval-connector-dingtalk-http",
            "approval-application"
        )) {
            assertFalse(pom.contains(forbidden), "routing core depends on " + forbidden);
        }
    }

    @Test
    void routingSourceCannotOpenSecretsAcquireTokensOrDispatchNetwork() throws IOException {
        String source = mainSource(ROUTING);
        for (String forbidden : List.of(
            "CredentialMaterialSource",
            "ServerOwnedCredentialResolver",
            "openMaterial(",
            "useSecretBytes(",
            "DingTalkProductionTransport",
            "DingTalkHttpSender",
            "HttpClient",
            "getToken",
            "refreshToken",
            "refresh_token",
            "clientSecret",
            "appSecret",
            "authorizationHeader",
            "access_token=",
            "Thread.sleep",
            "@Scheduled",
            "@EventListener"
        )) {
            assertFalse(source.contains(forbidden), "routing source contains " + forbidden);
        }
    }

    @Test
    void routingContainsNoPersistenceRetryFallbackWorkerOrApprovalMutation() throws IOException {
        String source = mainSource(ROUTING) + Files.readString(APPLICATION_SERVICE);
        String normalized = source.toLowerCase();
        for (String forbidden : List.of(
            "jdbctemplate",
            "datasource",
            "entitymanager",
            "flyway",
            "retrytemplate",
            "scheduledexecutor",
            "weighted routing",
            "health routing",
            "load balancing",
            "fallback provider",
            "recovery worker",
            "connector worker",
            "completetask(",
            "approve(",
            "reject(",
            "transfer(",
            "withdraw(",
            "terminate(",
            "migrate("
        )) {
            assertFalse(normalized.contains(forbidden), "routing crosses boundary " + forbidden);
        }
        assertFalse(Files.exists(ROUTING.resolve("src/main/resources/db/migration")));
    }

    @Test
    void serverWiringIsDefaultDisabledAndHasNoPublicOrBackgroundSurface() throws IOException {
        String configuration = Files.readString(SERVER_CONFIGURATION);
        String properties = Files.readString(SERVER_CONFIGURATION.resolveSibling(
            "ApprovalTenantConnectorRoutingProperties.java"
        ));
        String application = Files.readString(ROOT.resolve(
            "apps/server/src/main/resources/application.yml"
        ));

        assertTrue(configuration.contains("@ConditionalOnProperty"));
        assertTrue(configuration.contains("havingValue = \"true\""));
        assertTrue(properties.contains("ignoreUnknownFields = false"));
        assertTrue(application.contains("tenant-routing:"));
        assertTrue(application.contains("enabled: false"));
        for (String forbidden : List.of(
            "@RestController",
            "@Controller",
            "@RequestMapping",
            "@PostMapping",
            "@GetMapping",
            "@Scheduled",
            "@EventListener",
            "DingTalkProductionTransport",
            "DingTalkHttpSender"
        )) {
            assertFalse(configuration.contains(forbidden));
            assertFalse(properties.contains(forbidden));
        }
    }

    @Test
    void routeRequestAndPlanRemainClosedAndSecretFree() throws IOException {
        String contracts = Files.readString(findMainSource("TenantConnectorRouteContracts.java"));
        String requestBlock = slice(
            contracts,
            "public record RouteRequest(",
            "public record RouteDefinition("
        );
        String planBlock = slice(
            contracts,
            "public record RoutePlan(",
            "public record RouteEvidence("
        );

        for (String forbidden : List.of(
            "tenantId",
            "providerKey",
            "host",
            "endpoint",
            "path",
            "apiFamily",
            "credentialReference",
            "secret",
            "token",
            "appKey",
            "appSecret",
            "operator",
            "permission"
        )) {
            assertFalse(requestBlock.contains(forbidden), "request exposes " + forbidden);
        }
        for (String forbidden : List.of(
            "CredentialReference credentialReference",
            "tenantId",
            "host",
            "endpoint",
            "path",
            "authorizationHeader",
            "requestBody",
            "responseBody",
            "appSecret"
        )) {
            assertFalse(planBlock.contains(forbidden), "plan exposes " + forbidden);
        }
    }

    @Test
    void m5AndClientSurfacesContainNoTenantRoutingImplementation() throws IOException {
        for (Path root : List.of(
            ROOT.resolve("server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/migration"),
            ROOT.resolve("server-modules/approval-engine-flowable/src/main/java"),
            ROOT.resolve("apps/web"),
            ROOT.resolve("apps/mobile"),
            ROOT.resolve("apps/uniapp")
        )) {
            if (!Files.exists(root)) {
                continue;
            }
            String source = textFiles(root);
            assertFalse(source.contains("TenantConnectorRoute"));
            assertFalse(source.contains("tenant-routing"));
        }
    }

    @Test
    void onlyOneAutomaticPullRequestOrPushWorkflowExists() throws IOException {
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
    void governanceDocumentFreezesOnlyTenantRouting() throws IOException {
        String document = Files.readString(DOCUMENT);
        for (String required : List.of(
            "Status: `TENANT_ROUTING_IMPLEMENTED_DEFAULT_DISABLED`",
            "selected capability: `TENANT_ROUTING`",
            "owner: `PLATFORM_APPLICATION`",
            "M6-A-P3: `ACCEPTED / PERMANENTLY_VALIDATED`",
            "no Token Acquisition",
            "no Token Refresh",
            "no production Secret Backend",
            "no Connector Worker",
            "no Scheduler",
            "no Automatic Retry",
            "no Approval-State Mutation",
            "PR #67 remains Open + Draft"
        )) {
            assertTrue(document.contains(required), "missing governance text " + required);
        }
    }

    private static String slice(String source, String start, String end) {
        int first = source.indexOf(start);
        int last = source.indexOf(end, first + start.length());
        if (first < 0 || last < 0) {
            throw new IllegalStateException("expected contract block was not found");
        }
        return source.substring(first, last);
    }

    private static Path findMainSource(String fileName) throws IOException {
        return javaFiles(ROUTING.resolve("src/main/java")).stream()
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

    private static String textFiles(Path root) throws IOException {
        StringBuilder content = new StringBuilder();
        for (Path path : filesUnder(root)) {
            String name = path.getFileName().toString();
            if (name.endsWith(".java") || name.endsWith(".ts")
                || name.endsWith(".vue") || name.endsWith(".mjs")) {
                content.append(Files.readString(path)).append('\n');
            }
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

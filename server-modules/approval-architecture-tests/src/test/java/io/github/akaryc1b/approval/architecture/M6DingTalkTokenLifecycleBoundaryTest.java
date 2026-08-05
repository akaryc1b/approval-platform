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

class M6DingTalkTokenLifecycleBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path TOKEN = ROOT.resolve(
        "server-modules/approval-connector-dingtalk-token"
    );
    private static final Path CONFIG = ROOT.resolve(
        "apps/server/src/main/java/io/github/akaryc1b/approval/config"
    );
    private static final Path DOCUMENT = ROOT.resolve(
        "docs/m6/M6_A_DINGTALK_TOKEN_LIFECYCLE.md"
    );
    private static final String GOVERNED_M6_E_V49 =
        "V49__create_ai_approval_assistance_durable_evidence.sql";

    @Test
    void tokenModuleHasNoProductionTransportPersistenceOrFrameworkDependency()
        throws IOException {
        String pom = Files.readString(TOKEN.resolve("pom.xml")).toLowerCase();
        String source = mainSource(TOKEN);

        assertTrue(pom.contains("approval-connector-credential-core"));
        assertTrue(pom.contains("approval-connector-routing-core"));
        assertTrue(pom.contains("junit-jupiter"));
        for (String forbidden : List.of(
            "approval-connector-dingtalk-http", "spring-boot", "flowable",
            "approval-persistence-jdbc", "approval-integration-jdbc",
            "jackson-databind", "httpclient"
        )) {
            assertFalse(pom.contains(forbidden), "Token module depends on " + forbidden);
        }
        for (String forbidden : List.of(
            "java.net.", "java.net.http", "HttpClient", "WebClient", "RestClient",
            "JdbcTemplate", "DataSource", "EntityManager", "@Scheduled",
            "ScheduledExecutor", "Thread.sleep", "System.getenv", "System.getProperty",
            "Files.read", "Path.of", "org.flowable"
        )) {
            assertFalse(source.contains(forbidden), "Token module contains " + forbidden);
        }
    }

    @Test
    void lifecycleIsOnDemandSingleFlightAndRevalidatesEverySecurityBoundary()
        throws IOException {
        String lifecycleSource = mainSource(TOKEN);

        for (String required : List.of(
            "flights.putIfAbsent", "routeGate.revalidate", "killSwitch.evaluate",
            "CredentialMaterialAdmission.requireAdmitted", "materialSource.openLease",
            "CredentialMaterialEnvironment.PRODUCTION", "invalidateFamily",
            "rotateFamily", "ByteBuffer.allocateDirect", "Arrays.fill"
        )) {
            assertTrue(lifecycleSource.contains(required), "missing P6 lifecycle boundary " + required);
        }
        for (String forbidden : List.of(
            "@Scheduled", "scheduleAtFixedRate", "scheduleWithFixedDelay",
            "RetryTemplate", "automaticRetry", "fallbackToken", "previousToken"
        )) {
            assertFalse(lifecycleSource.contains(forbidden), "P6 contains " + forbidden);
        }
    }

    @Test
    void productionSourceRetainsNoRawTokenOrApplicationSecretArrayField()
        throws IOException {
        Pattern rawArrayField = Pattern.compile(
            "(?m)^\\s*(private|protected|public)\\s+(static\\s+)?(final\\s+)?"
                + "(byte|char)\\[\\]\\s+\\w+"
        );
        for (Path source : javaFiles(TOKEN.resolve("src/main/java"))) {
            String content = Files.readString(source);
            assertFalse(
                rawArrayField.matcher(content).find(),
                relative(source) + " retains a raw material array field"
            );
            assertFalse(content.contains("String accessToken"));
            assertFalse(content.contains("String appSecret"));
            assertFalse(content.contains("String clientSecret"));
        }
        String all = mainSource(TOKEN);
        assertTrue(all.contains("material=<redacted>"));
    }

    @Test
    void serverWiringIsLiteralDefaultDisabledAndProvidesNoEndpointImplementation()
        throws IOException {
        String configuration = Files.readString(
            CONFIG.resolve("ApprovalDingTalkTokenConfiguration.java")
        );
        String application = Files.readString(
            ROOT.resolve("apps/server/src/main/resources/application.yml")
        );

        assertTrue(configuration.contains("ConditionalOnProperty"));
        assertTrue(configuration.contains("DingTalkTokenEndpointPort endpointPort"));
        assertFalse(configuration.contains("new DingTalkTokenEndpoint"));
        assertFalse(configuration.contains("@RestController"));
        assertFalse(configuration.contains("@Scheduled"));
        assertTrue(application.contains("dingtalk-token:"));
        assertTrue(application.contains("enabled: false"));
        assertFalse(application.contains("APPROVAL_DINGTALK_TOKEN"));
    }

    @Test
    void p6OwnsNoMigrationAndRecognizesOnlyGovernedM6EV49() throws IOException {
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
                assertTrue(version <= 48, "unexpected P6 migration " + normalized);
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

        String source = mainSource(TOKEN).toLowerCase();
        for (String forbidden : List.of(
            "completetask(", "approve(", "reject(", "transfer(", "withdraw(",
            "terminate(", "migrate(", "runtime_binding", "migration_intent"
        )) {
            assertFalse(source.contains(forbidden), "P6 crosses boundary " + forbidden);
        }
    }

    @Test
    void governanceDocumentKeepsProductionExecutionAndLaterSlicesBlocked()
        throws IOException {
        String document = Files.readString(DOCUMENT);
        for (String required : List.of(
            "Status: `DINGTALK_TOKEN_LIFECYCLE_IMPLEMENTED_DEFAULT_DISABLED`",
            "selected capability: `TOKEN_LIFECYCLE`",
            "owner: `PLATFORM_SECURITY`",
            "no real DingTalk Token endpoint",
            "no concrete production Secret Backend",
            "no Persistence",
            "no Worker",
            "no Scheduler",
            "no Automatic Retry",
            "no Approval-State Mutation",
            "P7 remains blocked",
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

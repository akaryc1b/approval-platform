package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6AConnectorSecurityAcceptanceTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V([0-9]+)__.*\\.sql$");
    private static final Pattern HARD_CODED_SECRET = Pattern.compile(
        "(?i)\\b(?:appSecret|accessToken|apiKey|clientSecret|privateKey)\\b"
            + "\\s*=\\s*\"([^\"\\r\\n]{12,})\""
    );
    private static final Pattern BEARER_LITERAL = Pattern.compile(
        "Bearer\\s+[A-Za-z0-9._~+/=-]{16,}"
    );
    private static final Set<String> FORBIDDEN_SECRET_EXTENSIONS = Set.of(
        ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".env"
    );

    @Test
    void productionConnectorSourcesContainNoUsableSecretLiteral() throws IOException {
        Path root = repositoryRoot();
        List<Path> roots = List.of(
            root.resolve("server-modules/approval-connector-credential-core/src/main/java"),
            root.resolve("server-modules/approval-connector-routing-core/src/main/java"),
            root.resolve("server-modules/approval-connector-dingtalk/src/main/java"),
            root.resolve("server-modules/approval-connector-dingtalk-http/src/main/java"),
            root.resolve("server-modules/approval-connector-dingtalk-token/src/main/java"),
            root.resolve("server-modules/approval-connector-invocation-core/src/main/java"),
            root.resolve("server-modules/approval-connector-operations-core/src/main/java")
        );
        List<String> findings = new ArrayList<>();
        for (Path sourceRoot : roots) {
            scanJavaSourcesForSecrets(sourceRoot, findings);
        }
        Path server = root.resolve("apps/server/src/main/java/io/github/akaryc1b/approval");
        try (Stream<Path> paths = Files.walk(server)) {
            for (Path path : paths.filter(Files::isRegularFile)
                .filter(item -> item.getFileName().toString().endsWith(".java"))
                .filter(M6AConnectorSecurityAcceptanceTest::isConnectorServerSource)
                .sorted()
                .toList()) {
                inspectSecretLiterals(path, findings);
            }
        }
        assertTrue(findings.isEmpty(), "usable Secret literal findings: " + findings);
    }

    @Test
    void connectorScopeContainsNoPrivateKeyOrEnvironmentSecretArtifact() throws IOException {
        Path root = repositoryRoot();
        List<Path> forbidden = new ArrayList<>();
        for (Path scanRoot : List.of(
            root.resolve("server-modules"),
            root.resolve("apps/server"),
            root.resolve("docs/m6")
        )) {
            try (Stream<Path> paths = Files.walk(scanRoot)) {
                paths.filter(Files::isRegularFile)
                    .filter(M6AConnectorSecurityAcceptanceTest::hasForbiddenSecretExtension)
                    .forEach(forbidden::add);
            }
        }
        assertTrue(forbidden.isEmpty(), "forbidden Secret artifacts: " + forbidden);
    }

    @Test
    void diagnosticsRemainDefaultDisabledGetOnlyAndNoStore() throws IOException {
        Path root = repositoryRoot();
        String application = Files.readString(root.resolve("apps/server/src/main/resources/application.yml"));
        int section = application.indexOf("operations-diagnostics:");
        assertTrue(section >= 0);
        String boundedSection = application.substring(section, Math.min(application.length(), section + 256));
        assertTrue(boundedSection.contains("enabled: false"));

        String controller = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ConnectorOperationsDiagnosticsController.java"
        ));
        assertEquals(2, occurrences(controller, "@GetMapping"));
        assertTrue(controller.contains("CacheControl.noStore()"));
        for (String mutation : List.of("@PostMapping", "@PutMapping", "@PatchMapping", "@DeleteMapping")) {
            assertFalse(controller.contains(mutation));
        }
    }

    @Test
    void p8AndP9IntroduceNoDurableBackgroundRetryOrMutationAuthority() throws IOException {
        Path root = repositoryRoot();
        String operations = readTree(root.resolve(
            "server-modules/approval-connector-operations-core/src/main/java"
        ));
        String p9Documents = Files.readString(root.resolve(
            "docs/m6/M6_A_FAULT_SECURITY_ACCEPTANCE.md"
        )) + Files.readString(root.resolve(
            "docs/m6/M6_A_NON_PRODUCTION_RELEASE_REHEARSAL.md"
        )) + Files.readString(root.resolve(
            "docs/m6/M6_A_CONNECTOR_OPERATIONS_RUNBOOK.md"
        )) + Files.readString(root.resolve(
            "docs/m6/M6_A_PRODUCTION_BLOCKER_CATALOG.md"
        ));
        for (String forbidden : List.of(
            "@Transactional", "JdbcTemplate", "DataSource", "@Scheduled", "TaskScheduler",
            "@PostMapping", "@PutMapping", "@PatchMapping", "@DeleteMapping",
            "retryInvocation", "replayInvocation", "recoverInvocation", "ACT_"
        )) {
            assertFalse(operations.contains(forbidden), "forbidden P8/P9 boundary: " + forbidden);
        }
        assertTrue(p9Documents.contains("NON_PRODUCTION"));
        assertTrue(p9Documents.contains("BLOCKED"));
        assertFalse(p9Documents.contains("PRODUCTION_EXECUTION_AUTHORIZED=true"));
    }

    @Test
    void flywayAndAutomaticWorkflowRemainFrozen() throws IOException {
        Path root = repositoryRoot();
        Path migrationRoot = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources/db/migration"
        );
        int highest;
        try (Stream<Path> paths = Files.list(migrationRoot)) {
            highest = paths.map(path -> path.getFileName().toString())
                .map(MIGRATION_VERSION::matcher)
                .filter(Matcher::matches)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max()
                .orElseThrow();
        }
        assertEquals(48, highest);
        assertFalse(Files.exists(migrationRoot.resolve("V49__m6_a_connector_acceptance.sql")));

        Path workflowRoot = root.resolve(".github/workflows");
        List<Path> automatic;
        try (Stream<Path> paths = Files.list(workflowRoot)) {
            automatic = paths.filter(Files::isRegularFile).filter(path -> {
                try {
                    String content = Files.readString(path);
                    return content.contains("  pull_request:") || content.contains("  push:");
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
        }
        assertEquals(1, automatic.size());
        assertEquals("approval-platform-validation.yml", automatic.getFirst().getFileName().toString());
    }

    @Test
    void p7ObserverBoundaryAndP9RunbookRemainNonProduction() throws IOException {
        Path root = repositoryRoot();
        String coordinator = Files.readString(root.resolve(
            "server-modules/approval-connector-invocation-core/src/main/java/"
                + "io/github/akaryc1b/approval/connector/invocation/"
                + "GovernedReadOnlyConnectorInvocationCoordinator.java"
        ));
        String wrapper = Files.readString(root.resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ObservedReadOnlyConnectorInvocationService.java"
        ));
        String runbook = Files.readString(root.resolve(
            "docs/m6/M6_A_CONNECTOR_OPERATIONS_RUNBOOK.md"
        ));
        assertFalse(coordinator.contains("ConnectorInvocationObservationSink"));
        assertTrue(wrapper.contains("observationSink.record(result.evidence(), clock.instant())"));
        assertTrue(wrapper.contains("catch (RuntimeException problem)"));
        assertTrue(runbook.contains("NO_REAL_PROVIDER"));
        assertTrue(runbook.contains("NO_REAL_SECRET_BACKEND"));
        assertTrue(runbook.contains("NO_APPROVAL_STATE_MUTATION"));
        assertTrue(runbook.contains("This runbook grants no production authority"));
    }

    private static boolean isConnectorServerSource(Path path) {
        String name = path.getFileName().toString();
        return name.contains("Connector") || name.contains("DingTalk");
    }

    private static void scanJavaSourcesForSecrets(Path root, List<String> findings) throws IOException {
        assertTrue(Files.isDirectory(root), "missing source root: " + root);
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                .filter(source -> source.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList()) {
                inspectSecretLiterals(path, findings);
            }
        }
    }

    private static void inspectSecretLiterals(Path path, List<String> findings) throws IOException {
        String source = Files.readString(path);
        if (source.contains("-----BEGIN " + "PRIVATE KEY-----")
            || source.contains("-----BEGIN RSA " + "PRIVATE KEY-----")) {
            findings.add(path + ":private-key");
        }
        if (BEARER_LITERAL.matcher(source).find()) {
            findings.add(path + ":bearer-literal");
        }
        Matcher matcher = HARD_CODED_SECRET.matcher(source);
        while (matcher.find()) {
            if (!isSafeNonSecretLiteral(matcher.group(1))) {
                findings.add(path + ":hard-coded-secret-name");
            }
        }
    }

    private static boolean isSafeNonSecretLiteral(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("redacted")
            || normalized.contains("not configured")
            || normalized.matches("[a-z0-9_.-]*\\$\\{[^}]+}.*")
            || value.matches("[A-Z0-9_.-]+");
    }

    private static boolean hasForbiddenSecretExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return FORBIDDEN_SECRET_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path pom = current.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    String content = Files.readString(pom).toLowerCase(Locale.ROOT);
                    if (content.contains("<artifactid>approval-platform</artifactid>")
                        && content.contains("<module>server-modules</module>")) {
                        return current;
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("cannot read repository pom", exception);
                }
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String readTree(Path root) throws IOException {
        StringBuilder combined = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                combined.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return combined.toString();
    }
}

package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCompatibilityR0BoundaryTest {

    private static final Path ROOT = Path.of(
        System.getProperty("maven.multiModuleProjectDirectory")
    );
    private static final Path COMPATIBILITY_RECORD = ROOT.resolve(
        "docs/database/MYSQL_8_4_PRODUCTION_COMPATIBILITY.md"
    );

    @Test
    void restoredCommitmentRemainsExplicitAndFailClosed() throws IOException {
        String record = Files.readString(COMPATIBILITY_RECORD);
        String readme = Files.readString(ROOT.resolve("README.md"));
        String matrix = Files.readString(ROOT.resolve("docs/COMPATIBILITY.md"));

        assertTrue(record.contains("DUAL_DATABASE_COMMITMENT_RESTORED"));
        assertTrue(record.contains("MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED"));
        assertTrue(record.contains("Issue: `#91`"));
        assertTrue(readme.contains("MySQL 8.4 生产兼容恢复中、尚未支持"));
        assertTrue(matrix.contains("active blocking workstream, not yet supported"));
    }

    @Test
    void bothVendorDependenciesAndFailClosedProfilesArePresent() throws IOException {
        String persistencePom = Files.readString(
            ROOT.resolve("server-modules/approval-persistence-jdbc/pom.xml")
        );
        String serverPom = Files.readString(ROOT.resolve("apps/server/pom.xml"));
        String baseConfiguration = Files.readString(
            ROOT.resolve("apps/server/src/main/resources/application.yml")
        );
        String postgreSqlProfile = Files.readString(
            ROOT.resolve("apps/server/src/main/resources/application-postgresql.yml")
        );
        String mySqlProfile = Files.readString(
            ROOT.resolve("apps/server/src/main/resources/application-mysql.yml")
        );

        for (String dependency : List.of(
            "flyway-database-postgresql",
            "flyway-mysql",
            "postgresql",
            "mysql-connector-j",
            "testcontainers-postgresql",
            "testcontainers-mysql"
        )) {
            assertTrue(
                persistencePom.contains(dependency) || serverPom.contains(dependency),
                () -> "missing database compatibility dependency: " + dependency
            );
        }
        assertTrue(baseConfiguration.contains("APPROVAL_DATABASE_VENDOR:POSTGRESQL"));
        assertTrue(postgreSqlProfile.contains("expected-vendor: POSTGRESQL"));
        assertTrue(mySqlProfile.contains("expected-vendor: MYSQL"));
        assertTrue(mySqlProfile.contains("fail-on-missing-locations: true"));
        assertTrue(mySqlProfile.contains("classpath:db/migration/mysql"));
        assertTrue(
            Files.notExists(ROOT.resolve(
                "server-modules/approval-persistence-jdbc/src/main/resources/"
                    + "db/migration/mysql"
            )),
            "MySQL migration location must remain absent until P2 supplies a reviewed lineage"
        );
    }

    @Test
    void currentPostgreSqlCouplingIsCoveredByTheR0Inventory() throws IOException {
        String record = Files.readString(COMPATIBILITY_RECORD).toLowerCase(Locale.ROOT);
        Map<String, String> categories = new LinkedHashMap<>();
        categories.put("jsonb", "jsonb");
        categories.put("timestamptz", "timestamptz");
        categories.put("bytea", "bytea");
        categories.put("on conflict", "on conflict");
        categories.put("pg_advisory", "advisory locks");
        categories.put("skip locked", "skip locked");
        categories.put("returning", "returning");
        categories.put("postgresqlcontainer", "postgresql-specific test fixtures");
        categories.put("jdbc:postgresql", "postgresql jdbc url");
        categories.put("explain (format json)", "json-formatted `explain`");

        List<Path> roots = List.of(
            ROOT.resolve("server-modules/approval-persistence-jdbc"),
            ROOT.resolve("server-modules/approval-integration-jdbc"),
            ROOT.resolve("apps/server")
        );
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String token : categories.keySet()) {
            counts.put(token, 0L);
        }

        for (Path sourceRoot : roots) {
            try (var paths = Files.walk(sourceRoot)) {
                for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(DatabaseCompatibilityR0BoundaryTest::isTextSource)
                    .toList()) {
                    String content = Files.readString(path).toLowerCase(Locale.ROOT);
                    for (String token : categories.keySet()) {
                        long count = counts.get(token) + occurrences(content, token);
                        counts.put(token, count);
                    }
                }
            }
        }

        counts.forEach((token, count) -> {
            System.out.printf("db-compat-r0 %s=%d%n", token, count);
            if (count > 0) {
                assertTrue(
                    record.contains(categories.get(token)),
                    () -> "R0 record does not classify detected token: " + token
                );
            }
        });
    }

    private static boolean isTextSource(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".java")
            || fileName.endsWith(".sql")
            || fileName.endsWith(".xml")
            || fileName.endsWith(".yml")
            || fileName.endsWith(".yaml")
            || fileName.endsWith(".properties")
            || fileName.endsWith(".md")
            || fileName.endsWith(".mjs")
            || fileName.equals("pom.xml");
    }

    private static long occurrences(String content, String token) {
        long count = 0;
        int offset = 0;
        while ((offset = content.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}

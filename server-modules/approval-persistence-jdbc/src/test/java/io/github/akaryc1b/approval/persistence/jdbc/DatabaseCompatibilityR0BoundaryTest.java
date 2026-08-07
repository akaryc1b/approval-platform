package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCompatibilityR0BoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path COMPATIBILITY_RECORD = ROOT.resolve(
        "docs/database/MYSQL_8_4_PRODUCTION_COMPATIBILITY.md"
    );
    private static final Path INVENTORY_RECORD = ROOT.resolve(
        "docs/database/MYSQL_8_4_R0_INVENTORY.md"
    );
    private static final String THIS_TEST = "DatabaseCompatibilityR0BoundaryTest.java";

    @Test
    void restoredCommitmentRemainsExplicitAndFailClosed() throws IOException {
        String record = Files.readString(COMPATIBILITY_RECORD);
        String inventory = Files.readString(INVENTORY_RECORD);
        String readme = Files.readString(ROOT.resolve("README.md"));
        String matrix = Files.readString(ROOT.resolve("docs/COMPATIBILITY.md"));

        assertTrue(record.contains("DUAL_DATABASE_COMMITMENT_RESTORED"));
        assertTrue(record.contains("MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED"));
        assertTrue(record.contains("Issue: `#91`"));
        assertTrue(inventory.contains("R0_COUNTS_ARE_NOT_MYSQL_SUPPORT"));
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
        assertTrue(!baseConfiguration.contains("validation-enabled"));
        assertTrue(postgreSqlProfile.contains("expected-vendor: POSTGRESQL"));
        assertTrue(!postgreSqlProfile.contains("validation-enabled"));
        assertTrue(mySqlProfile.contains("expected-vendor: MYSQL"));
        assertTrue(!mySqlProfile.contains("validation-enabled"));
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
        String records = (
            Files.readString(COMPATIBILITY_RECORD)
                + System.lineSeparator()
                + Files.readString(INVENTORY_RECORD)
        ).toLowerCase(Locale.ROOT);
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
        Map<String, Map<Path, Long>> pathCounts = new LinkedHashMap<>();
        for (String token : categories.keySet()) {
            pathCounts.put(token, new LinkedHashMap<>());
        }

        for (Path sourceRoot : roots) {
            try (var paths = Files.walk(sourceRoot)) {
                for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(DatabaseCompatibilityR0BoundaryTest::isTextSource)
                    .filter(path -> !normalized(path).contains("/target/"))
                    .filter(path -> !path.getFileName().toString().equals(THIS_TEST))
                    .toList()) {
                    String content = Files.readString(path).toLowerCase(Locale.ROOT);
                    for (String token : categories.keySet()) {
                        long count = occurrences(content, token);
                        if (count > 0) {
                            pathCounts.get(token).put(ROOT.relativize(path), count);
                        }
                    }
                }
            }
        }

        pathCounts.forEach((token, matches) -> {
            long count = matches.values().stream().mapToLong(Long::longValue).sum();
            System.out.printf(
                "db-compat-r0 token=%s occurrences=%d files=%d%n",
                token,
                count,
                matches.size()
            );
            new ArrayList<>(matches.entrySet()).stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.printf(
                    "db-compat-r0-path token=%s count=%d path=%s%n",
                    token,
                    entry.getValue(),
                    normalized(entry.getKey())
                ));
            if (count > 0) {
                assertTrue(
                    records.contains(categories.get(token)),
                    () -> "R0 records do not classify detected token: " + token
                );
            }
        });
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("maven.multiModuleProjectDirectory");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                && Files.isDirectory(current.resolve("apps/server"))
                && Files.isDirectory(current.resolve("server-modules"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root could not be resolved");
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

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
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

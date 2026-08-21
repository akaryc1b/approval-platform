package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCompatibilityR0ManifestTest {

    private static final Path ROOT = repositoryRoot();
    private static final Pattern MIGRATION_NAME = Pattern.compile(
        "^V(\\d+)__.+\\.(sql|java)$"
    );
    private static final int CONTENT_CHUNK_SIZE = 4096;

    @Test
    void emitsExactPostgreSqlMigrationSourceAndTestManifest() throws IOException {
        List<Path> migrations = postgreSqlMigrationFiles();
        Map<Integer, List<Path>> byVersion = new TreeMap<>();
        for (Path path : migrations) {
            Matcher matcher = MIGRATION_NAME.matcher(path.getFileName().toString());
            assertTrue(matcher.matches(), () -> "invalid migration name: " + path);
            int version = Integer.parseInt(matcher.group(1));
            byVersion.computeIfAbsent(version, ignored -> new ArrayList<>()).add(path);
        }

        assertEquals(
            50,
            byVersion.size(),
            "PostgreSQL history must cover V1 through V50"
        );
        for (int version = 1; version <= 50; version++) {
            List<Path> versionFiles = byVersion.get(version);
            assertFalse(
                versionFiles == null || versionFiles.isEmpty(),
                "missing V" + version
            );
            assertEquals(
                1,
                versionFiles.size(),
                "duplicate PostgreSQL V" + version + ": " + versionFiles
            );
        }

        for (Map.Entry<Integer, List<Path>> entry : byVersion.entrySet()) {
            Path path = entry.getValue().getFirst();
            emitFile("postgresql-migration", path, "version=" + entry.getKey());
            emitMigrationContent(path);
        }

        List<Path> productionSources = javaFiles(List.of(
            ROOT.resolve("server-modules/approval-integration-jdbc/src/main/java"),
            ROOT.resolve("server-modules/approval-persistence-jdbc/src/main/java"),
            ROOT.resolve("apps/server/src/main/java")
        ));
        List<Path> testSources = javaFiles(List.of(
            ROOT.resolve("server-modules/approval-integration-jdbc/src/test/java"),
            ROOT.resolve("server-modules/approval-persistence-jdbc/src/test/java"),
            ROOT.resolve("apps/server/src/test/java")
        ));
        productionSources.forEach(path -> emitUnchecked("production-java", path));
        testSources.forEach(path -> emitUnchecked("test-java", path));

        Set<String> uniquePaths = new java.util.HashSet<>();
        for (Path path : concat(migrations, productionSources, testSources)) {
            assertTrue(
                uniquePaths.add(normalized(ROOT.relativize(path))),
                () -> "duplicate manifest path: " + path
            );
        }
        System.out.printf(
            "db-compat-manifest-summary postgresqlMigrations=%d productionJava=%d testJava=%d%n",
            migrations.size(),
            productionSources.size(),
            testSources.size()
        );
    }

    private static List<Path> postgreSqlMigrationFiles() throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path root : List.of(
            ROOT.resolve(
                "server-modules/approval-integration-jdbc/src/main/resources/db/migration"
            ),
            ROOT.resolve(
                "server-modules/approval-persistence-jdbc/src/main/resources/db/migration"
            ),
            ROOT.resolve(
                "server-modules/approval-persistence-jdbc/src/main/resources/m6f/db/migration"
            )
        )) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                result.addAll(paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList());
            }
        }
        for (Path root : List.of(
            ROOT.resolve(
                "server-modules/approval-integration-jdbc/src/main/java/db/migration"
            ),
            ROOT.resolve(
                "server-modules/approval-persistence-jdbc/src/main/java/db/migration"
            )
        )) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                result.addAll(paths
                    .filter(Files::isRegularFile)
                    .filter(path -> MIGRATION_NAME.matcher(
                        path.getFileName().toString()
                    ).matches())
                    .toList());
            }
        }
        return sortedDistinct(result);
    }

    private static List<Path> javaFiles(List<Path> roots) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                result.addAll(paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !MIGRATION_NAME.matcher(
                        path.getFileName().toString()
                    ).matches())
                    .toList());
            }
        }
        return sortedDistinct(result);
    }

    private static List<Path> sortedDistinct(List<Path> paths) {
        return paths.stream()
            .map(path -> path.toAbsolutePath().normalize())
            .distinct()
            .sorted(Comparator.comparing(
                DatabaseCompatibilityR0ManifestTest::normalized
            ))
            .toList();
    }

    private static void emitUnchecked(String kind, Path path) {
        try {
            emitFile(kind, path, "");
        } catch (IOException exception) {
            throw new IllegalStateException(
                "manifest file read failed: " + path,
                exception
            );
        }
    }

    private static void emitFile(String kind, Path path, String extra) throws IOException {
        byte[] content = Files.readAllBytes(path);
        String suffix = extra.isBlank() ? "" : " " + extra;
        System.out.printf(
            "db-compat-manifest kind=%s%s bytes=%d sha256=%s path=%s%n",
            kind,
            suffix,
            content.length,
            sha256(content),
            normalized(ROOT.relativize(path))
        );
    }

    private static void emitMigrationContent(Path path) throws IOException {
        String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
        int chunks = Math.max(
            1,
            (encoded.length() + CONTENT_CHUNK_SIZE - 1) / CONTENT_CHUNK_SIZE
        );
        for (int index = 0; index < chunks; index++) {
            int from = index * CONTENT_CHUNK_SIZE;
            int to = Math.min(encoded.length(), from + CONTENT_CHUNK_SIZE);
            System.out.printf(
                "db-compat-migration-content chunk=%d/%d path=%s data=%s%n",
                index + 1,
                chunks,
                normalized(ROOT.relativize(path)),
                encoded.substring(from, to)
            );
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        List<T> result = new ArrayList<>();
        for (List<T> list : lists) {
            result.addAll(Objects.requireNonNull(list, "list must not be null"));
        }
        return result;
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

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }
}

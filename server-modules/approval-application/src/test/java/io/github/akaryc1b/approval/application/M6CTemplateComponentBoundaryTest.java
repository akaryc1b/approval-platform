package io.github.akaryc1b.approval.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class M6CTemplateComponentBoundaryTest {

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile(
        "^V([1-9][0-9]*)__.+\\.sql$"
    );
    private static final String GOVERNED_M6_E_V49 =
        "V49__create_ai_approval_assistance_durable_evidence.sql";

    private static final String GOVERNED_V51_PATH =
        "server-modules/approval-persistence-jdbc/src/main/resources/db/migration/"
            + "V51__preserve_migration_epoch_microsecond_precision.sql";
    private static final String GOVERNED_V51_SHA256 =
        "d56d21e4a1eacf8701adfd8e689b60e3b25916cbff736a7e9052f53c1825d917";

    @Test
    void preservesTemplateScopeAcrossTheExactGovernedM6EV49() throws IOException {
        Path root = repositoryRoot();
        assertExactGovernedV51(root);
        Path migrations = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources/db/migration");
        List<MigrationEntry> actual;
        try (var files = Files.list(migrations)) {
            actual = files.filter(Files::isRegularFile)
                .filter(path -> !path.equals(root.resolve(GOVERNED_V51_PATH)))
                .map(path -> migrationEntry(path.getFileName().toString()))
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(MigrationEntry::version))
                .toList();
        }
        List<Integer> expectedVersions = Stream.concat(
            IntStream.rangeClosed(2, 37).boxed(),
            IntStream.rangeClosed(39, 49).boxed()
        ).toList();
        assertEquals(expectedVersions, actual.stream().map(MigrationEntry::version).toList());
        assertEquals(
            List.of(GOVERNED_M6_E_V49),
            actual.stream().filter(entry -> entry.version() == 49)
                .map(MigrationEntry::fileName).toList()
        );
        assertTrue(actual.stream().noneMatch(entry -> entry.version() >= 50));

        try (var files = Files.list(root.resolve(".github/workflows"))) {
            List<String> automatic = files.filter(Files::isRegularFile)
                .filter(M6CTemplateComponentBoundaryTest::runsAutomatically)
                .map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(List.of("approval-platform-validation.yml"), automatic);
        }
    }

    @Test
    void templateSlicesHaveNoMarketplaceDownloadLoaderOrReleaseMutationDependency()
        throws IOException {
        Path sourceRoot = repositoryRoot().resolve(
            "server-modules/approval-application/src/main/java/"
                + "io/github/akaryc1b/approval/application");
        StringBuilder content = new StringBuilder();
        try (var files = Files.list(sourceRoot)) {
            for (Path file : files.filter(path -> path.getFileName().toString()
                .startsWith("ProcessTemplate")).toList()) {
                content.append(Files.readString(file));
            }
        }
        String source = content.toString();
        assertFalse(source.contains("import java.net."));
        assertFalse(source.contains("import java.lang.reflect."));
        assertFalse(source.contains("URLClassLoader"));
        assertFalse(source.contains("Class.forName"));
        assertFalse(source.contains("ApprovalReleasePublisher"));
        assertFalse(source.contains("ApprovalReleaseDeploymentService"));
        assertFalse(source.contains("ApprovalProcessReleaseActivationService"));
        assertFalse(source.contains("approval.persistence"));
        assertTrue(source.contains("ProcessTemplateImportPreviewService"));
        assertTrue(source.contains("ProcessTemplateDraftCreationService"));
        assertTrue(source.contains("ProcessTemplateImportCoordinator"));
        assertTrue(source.contains("ProcessTemplateTenantRegistryResolver"));
        assertTrue(source.contains("ApprovalArtifactTransferService"));
        assertTrue(source.contains("READONLY_FALLBACK"));
        assertTrue(source.contains("RegistryEvidence"));
        assertTrue(source.contains("process-template-tenant-registry-v1"));
        assertTrue(source.contains("process-template-import-plan-v2"));
    }

    @Test
    void governedV51RequiresExactOwnerAndBytesAndRejectsUnknownLaterVersions(@TempDir Path root) throws IOException {
        Path governed = root.resolve(GOVERNED_V51_PATH);
        Files.createDirectories(governed.getParent());
        byte[] exact = Files.readAllBytes(repositoryRoot().resolve(GOVERNED_V51_PATH));
        Files.write(governed, exact);
        assertExactGovernedV51(root);
        Path other = root.resolve("server-modules/other/src/main/resources/db/migration");
        Files.createDirectories(other);
        for (String name : List.of(governed.getFileName().toString(), "V51__unknown.sql", "V52__unknown.sql")) {
            Path duplicate = other.resolve(name);
            Files.write(duplicate, exact);
            assertThrows(AssertionError.class, () -> assertExactGovernedV51(root));
            Files.delete(duplicate);
        }
        Files.writeString(governed, "-- tampered");
        assertThrows(AssertionError.class, () -> assertExactGovernedV51(root));
        Files.delete(governed);
        assertThrows(AssertionError.class, () -> assertExactGovernedV51(root));
        Path wrongOwner = other.resolve(governed.getFileName());
        Files.write(wrongOwner, exact);
        assertThrows(AssertionError.class, () -> assertExactGovernedV51(root));
        Files.delete(wrongOwner);
        Files.write(governed, exact);
        Path generated = root.resolve("server-modules/other/target/classes/V52__generated.sql");
        Files.createDirectories(generated.getParent()); Files.write(generated, exact);
        assertExactGovernedV51(root);
    }

    private static void assertExactGovernedV51(Path root) throws IOException {
        java.util.ArrayList<Path> later = new java.util.ArrayList<>();
        for (String source : List.of("server-modules", "apps/server/src")) {
            Path directory = root.resolve(source);
            if (!Files.exists(directory)) continue;
            try (var paths = Files.walk(directory)) {
                paths.filter(Files::isRegularFile).filter(path -> {
                    for (Path part : root.relativize(path)) {
                        if (List.of("target", "node_modules", "dist", ".git").contains(part.toString())) return false;
                    }
                    MigrationEntry entry = migrationEntry(path.getFileName().toString());
                    return entry != null && entry.version() >= 51;
                }).forEach(later::add);
            }
        }
        assertEquals(List.of(root.resolve(GOVERNED_V51_PATH)), later.stream().sorted().toList());
        try {
            String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(root.resolve(GOVERNED_V51_PATH))));
            assertEquals(GOVERNED_V51_SHA256, actual);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static MigrationEntry migrationEntry(String fileName) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        return new MigrationEntry(Integer.parseInt(matcher.group(1)), fileName);
    }

    private static boolean runsAutomatically(Path workflow) {
        try {
            String content = Files.readString(workflow);
            return content.contains("\n  pull_request:") || content.contains("\n  push:");
        } catch (IOException exception) {
            throw new IllegalStateException("workflow could not be read", exception);
        }
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("maven.multiModuleProjectDirectory");
        Path current = configured == null || configured.isBlank()
            ? Path.of("").toAbsolutePath().normalize()
            : Path.of(configured).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".github/workflows"))
                && Files.isDirectory(current.resolve("server-modules"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root could not be resolved");
    }

    private record MigrationEntry(int version, String fileName) {
    }
}

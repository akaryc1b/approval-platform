package io.github.akaryc1b.approval.application;

import org.junit.jupiter.api.Test;

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

class M6CTemplateComponentBoundaryTest {

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile(
        "^V([1-9][0-9]*)__.+\\.sql$"
    );
    private static final String GOVERNED_M6_E_V49 =
        "V49__create_ai_approval_assistance_durable_evidence.sql";

    @Test
    void preservesTemplateScopeAcrossTheExactGovernedM6EV49() throws IOException {
        Path root = repositoryRoot();
        Path migrations = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources/db/migration");
        List<MigrationEntry> actual;
        try (var files = Files.list(migrations)) {
            actual = files.filter(Files::isRegularFile)
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

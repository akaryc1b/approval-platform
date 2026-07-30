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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6CTemplateComponentBoundaryTest {

    private static final int CURRENT_MAIN_FLYWAY_MAX = 48;
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile(
        "^V([1-9][0-9]*)__.+\\.sql$"
    );

    @Test
    void preservesMigrationContinuityAndPermanentWorkflowBoundaries() throws IOException {
        Path root = repositoryRoot();
        Path migrations = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources/db/migration");
        List<Integer> actualVersions;
        try (var files = Files.list(migrations)) {
            actualVersions = files.filter(Files::isRegularFile)
                .map(path -> migrationVersion(path.getFileName().toString()))
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        }
        List<Integer> expectedVersions = IntStream.rangeClosed(1, CURRENT_MAIN_FLYWAY_MAX)
            .boxed()
            .toList();
        assertEquals(expectedVersions, actualVersions);

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

    private static Integer migrationVersion(String fileName) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        return Integer.valueOf(matcher.group(1));
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
}

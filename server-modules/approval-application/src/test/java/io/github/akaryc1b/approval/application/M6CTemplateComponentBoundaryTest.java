package io.github.akaryc1b.approval.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6CTemplateComponentBoundaryTest {

    @Test
    void preservesMigrationAndPermanentWorkflowBoundaries() throws IOException {
        Path root = repositoryRoot();
        Path migrations = root.resolve(
            "server-modules/approval-persistence-jdbc/src/main/resources/db/migration");
        try (var files = Files.list(migrations)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("V33__")));
        }
        try (var files = Files.list(root.resolve(".github/workflows"))) {
            List<String> automatic = files.filter(Files::isRegularFile)
                .filter(M6CTemplateComponentBoundaryTest::runsAutomatically)
                .map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(List.of("approval-platform-validation.yml"), automatic);
        }
    }

    @Test
    void previewSliceHasNoMarketplaceDownloadLoaderOrReleaseMutationDependency() throws IOException {
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
        assertTrue(source.contains("READONLY_FALLBACK"));
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

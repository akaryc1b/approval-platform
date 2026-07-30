package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver.FormPackageEvidence;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6CTemplateFormPackageEvidenceBoundaryTest {

    @Test
    void governedCommandsCannotCarryTrustedFormPackageEvidenceOrReleaseMutation() {
        assertFalse(hasEvidenceComponent(
            ProcessTemplateGovernedImportCoordinator.PreviewCommand.class));
        assertFalse(hasEvidenceComponent(
            ProcessTemplateGovernedImportCoordinator.CreateDraftCommand.class));
        List<String> methods = Arrays.stream(
            ProcessTemplateGovernedImportCoordinator.class.getDeclaredMethods())
            .map(java.lang.reflect.Method::getName)
            .toList();
        assertTrue(methods.contains("preview"));
        assertTrue(methods.contains("createDraft"));
        assertFalse(methods.contains("publish"));
        assertFalse(methods.contains("deploy"));
        assertFalse(methods.contains("activate"));
    }

    @Test
    void formPackageEvidenceSliceHasNoNetworkPersistenceOrDynamicLoading() throws IOException {
        Path sourceRoot = repositoryRoot().resolve(
            "server-modules/approval-application/src/main/java/"
                + "io/github/akaryc1b/approval/application"
        );
        String source = Files.readString(sourceRoot.resolve(
            "ProcessTemplateGovernedImportCoordinator.java"
        )) + Files.readString(sourceRoot.resolve(
            "ProcessTemplateLocalFormPackageEvidenceResolver.java"
        )) + Files.readString(sourceRoot.resolve(
            "ProcessTemplateFormPackageEvidenceHasher.java"
        ));
        assertFalse(source.contains("import java.net."));
        assertFalse(source.contains("import java.lang.reflect."));
        assertFalse(source.contains("URLClassLoader"));
        assertFalse(source.contains("Class.forName"));
        assertFalse(source.contains("approval.persistence"));
        assertFalse(source.contains(".save("));
        assertFalse(source.contains(".lockVersion("));
        assertFalse(source.contains("ApprovalReleasePublisher"));
        assertFalse(source.contains("ApprovalReleaseDeploymentService"));
        assertFalse(source.contains("ApprovalProcessReleaseActivationService"));
        assertTrue(source.contains("ApprovalFormPackageResolver"));
        assertTrue(source.contains("process-template-form-package-evidence-v1"));
        assertTrue(source.contains("process-template-governed-preview-v1"));
    }

    private static boolean hasEvidenceComponent(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
            .anyMatch(component -> component.getType() == FormPackageEvidence.class);
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

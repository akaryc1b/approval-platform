package io.github.akaryc1b.approval.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6CTemplateFoundationAcceptanceBoundaryTest {

    @Test
    void acceptanceEvaluatorHasNoRuntimeMutationOrExternalIntegrationDependency()
        throws IOException {
        String source = Files.readString(sourceFile());

        assertFalse(source.contains("private final ProcessTemplateFormPackageEvidenceResolver"));
        assertFalse(source.contains("ApprovalFormPackageStore"));
        assertFalse(source.contains("ApprovalDesignDraftStore"));
        assertFalse(source.contains("ProcessTemplateTenantRegistryResolver"));
        assertFalse(source.contains("java.net."));
        assertFalse(source.contains("java.lang.reflect."));
        assertFalse(source.contains("URLClassLoader"));
        assertFalse(source.contains("Class.forName"));
        assertFalse(source.contains("ApprovalReleasePublisher"));
        assertFalse(source.contains("ApprovalReleaseDeploymentService"));
        assertFalse(source.contains("ApprovalProcessReleaseActivationService"));
        assertFalse(source.contains("createDraft("));
        assertFalse(source.contains("publish("));
        assertFalse(source.contains("deploy("));
        assertFalse(source.contains("activate("));
    }

    @Test
    void acceptanceEvaluatorExposesReviewOnlyOperation() {
        List<String> publicMethods = java.util.Arrays.stream(
                ProcessTemplateFoundationAcceptanceEvaluator.class.getDeclaredMethods()
            )
            .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
            .map(java.lang.reflect.Method::getName)
            .sorted()
            .toList();

        assertTrue(publicMethods.contains("evaluate"));
        assertFalse(publicMethods.contains("accept"));
        assertFalse(publicMethods.contains("enableProduction"));
        assertFalse(publicMethods.contains("publish"));
        assertFalse(publicMethods.contains("deploy"));
        assertFalse(publicMethods.contains("activate"));
        assertFalse(publicMethods.contains("createDraft"));
    }

    private static Path sourceFile() {
        return repositoryRoot().resolve(
            "server-modules/approval-application/src/main/java/"
                + "io/github/akaryc1b/approval/application/"
                + "ProcessTemplateFoundationAcceptanceEvaluator.java"
        );
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

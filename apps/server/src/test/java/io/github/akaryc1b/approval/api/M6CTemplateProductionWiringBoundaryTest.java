package io.github.akaryc1b.approval.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6CTemplateProductionWiringBoundaryTest {

    @Test
    void acceptedGateIsRecordedWithoutPrOrProductionReleaseAuthorization() throws IOException {
        String acceptance = Files.readString(repositoryRoot().resolve(
            "docs/m6/M6_C_TEMPLATE_FOUNDATION_ACCEPTANCE.md"
        ));

        assertTrue(acceptance.contains("FORMALLY_ACCEPTED_TEMPLATE_FOUNDATION"));
        assertTrue(acceptance.contains("Production Wiring and Management Import API gate"));
        assertTrue(acceptance.contains("PR #69 remains Open + Draft"));
        assertTrue(acceptance.contains("may create only an editable tenant-local DRAFT"));
        assertTrue(acceptance.contains("direct publish, deploy or activate during import"));
    }

    @Test
    void wiringAndApiHaveNoRemoteMarketplaceOrReleaseMutationDependency() throws IOException {
        String configuration = Files.readString(repositoryRoot().resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/config/"
                + "ProcessTemplateImportConfiguration.java"
        ));
        String controller = Files.readString(repositoryRoot().resolve(
            "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
                + "ProcessTemplateManagementController.java"
        ));

        assertFalse(configuration.contains("java.net."));
        assertFalse(configuration.contains("WebClient"));
        assertFalse(configuration.contains("RestClient"));
        assertFalse(configuration.contains("Marketplace"));
        assertFalse(configuration.contains("ApprovalReleasePublisher"));
        assertFalse(configuration.contains("ApprovalReleaseDeploymentService"));
        assertFalse(configuration.contains("ApprovalProcessReleaseActivationService"));
        assertFalse(controller.contains("publish("));
        assertFalse(controller.contains("deploy("));
        assertFalse(controller.contains("activate("));
        assertFalse(controller.contains("TenantRegistrySnapshot"));
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

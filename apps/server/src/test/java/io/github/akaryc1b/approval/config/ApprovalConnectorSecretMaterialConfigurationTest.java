package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.credential.CredentialMaterialSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalConnectorSecretMaterialConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ApprovalConnectorSecretMaterialConfiguration.class);

    @Test
    void defaultStartupRecordsBlockedSelectionAndConstructsNoSource() {
        runner.run(context -> {
            assertTrue(context.getStartupFailure() == null);
            ApprovalConnectorSecretMaterialStatus status = context.getBean(
                ApprovalConnectorSecretMaterialStatus.class
            );
            assertFalse(status.enabled());
            assertEquals(
                ApprovalConnectorSecretMaterialProperties.BLOCKED_BACKEND,
                status.backendSelection()
            );
            assertEquals("backend_not_selected", status.statusCode());
            assertEquals(0, context.getBeansOfType(CredentialMaterialSource.class).size());
        });
    }

    @Test
    void enablingWithoutASelectedBackendFailsClosed() {
        runner.withPropertyValues(
            "approval.connector.secret-material.enabled=true"
        ).run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void namingAnUnauthorizedConcreteBackendFailsClosed() {
        runner.withPropertyValues(
            "approval.connector.secret-material.backend-selection=HASHICORP_VAULT"
        ).run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void unknownConfigurationFailsClosed() {
        runner.withPropertyValues(
            "approval.connector.secret-material.secret-value=not-allowed"
        ).run(context -> assertNotNull(context.getStartupFailure()));
    }
}

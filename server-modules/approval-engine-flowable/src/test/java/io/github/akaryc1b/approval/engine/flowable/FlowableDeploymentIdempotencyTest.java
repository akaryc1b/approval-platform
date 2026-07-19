package io.github.akaryc1b.approval.engine.flowable;

import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowableDeploymentIdempotencyTest {

    private ProcessEngine processEngine;
    private ApprovalEngine engine;

    @BeforeEach
    void setUp() {
        processEngine = ProcessEngineConfiguration
            .createStandaloneInMemProcessEngineConfiguration()
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setJdbcUrl("jdbc:h2:mem:deployment-" + UUID.randomUUID())
            .buildProcessEngine();
        engine = new FlowableApprovalEngine(
            processEngine.getRepositoryService(),
            processEngine.getRuntimeService(),
            processEngine.getTaskService()
        );
    }

    @AfterEach
    void tearDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void reusesTheSameTenantDeploymentForEquivalentImmutableContent() {
        var compiled = new ApprovalDslCompiler().compile(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition()
        );
        ApprovalEngine.DeployCommand command = new ApprovalEngine.DeployCommand(
            "tenant-a",
            compiled.definitionKey(),
            compiled.definitionVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash()
        );

        ApprovalEngine.DeploymentResult first = engine.deploy(command);
        ApprovalEngine.DeploymentResult replay = engine.deploy(command);

        assertEquals(first, replay);
        assertEquals(
            1,
            processEngine.getRepositoryService()
                .createDeploymentQuery()
                .list()
                .size()
        );
    }

    @Test
    void keepsEquivalentContentIsolatedByTenant() {
        var compiled = new ApprovalDslCompiler().compile(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition()
        );

        engine.deploy(command("tenant-a", compiled));
        engine.deploy(command("tenant-b", compiled));

        assertEquals(
            2,
            processEngine.getRepositoryService()
                .createDeploymentQuery()
                .list()
                .size()
        );
    }

    private static ApprovalEngine.DeployCommand command(
        String tenantId,
        ApprovalDslCompiler.CompiledDefinition compiled
    ) {
        return new ApprovalEngine.DeployCommand(
            tenantId,
            compiled.definitionKey(),
            compiled.definitionVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash()
        );
    }
}

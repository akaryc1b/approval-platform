package io.github.akaryc1b.approval.engine.flowable;

import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowableExactDefinitionStartTest {

    private ProcessEngine processEngine;
    private ApprovalEngine engine;

    @BeforeEach
    void setUp() {
        processEngine = ProcessEngineConfiguration
            .createStandaloneInMemProcessEngineConfiguration()
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setJdbcUrl("jdbc:h2:mem:exact-start-" + UUID.randomUUID())
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
    void startsTheExplicitEngineDefinitionInsteadOfTheLatestDefinitionForTheKey() {
        var compiled = new ApprovalDslCompiler().compile(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition()
        );
        ApprovalEngine.DeploymentResult versionOne = engine.deploy(new ApprovalEngine.DeployCommand(
            "tenant-a",
            compiled.definitionKey(),
            1,
            compiled.resourceName(),
            compiled.bpmnXml(),
            "1".repeat(64)
        ));
        ApprovalEngine.DeploymentResult versionTwo = engine.deploy(new ApprovalEngine.DeployCommand(
            "tenant-a",
            compiled.definitionKey(),
            2,
            compiled.resourceName(),
            compiled.bpmnXml(),
            "2".repeat(64)
        ));

        ApprovalEngine.StartResult started = engine.startExact(
            new ApprovalEngine.ExactStartCommand(
                "tenant-a",
                compiled.definitionKey(),
                versionOne.deploymentId(),
                versionOne.engineDefinitionId(),
                "business-1",
                "initiator-a",
                1,
                "3".repeat(64),
                1,
                1,
                compiled.compilerVersion(),
                Map.of(
                    "amount", BigDecimal.ONE,
                    PurchasePaymentTemplate.MANAGER_ASSIGNEE_VARIABLE,
                    "manager-a",
                    PurchasePaymentTemplate.FINANCE_REVIEWER_VARIABLE,
                    "reviewer-a",
                    PurchasePaymentTemplate.FINANCE_APPROVERS_VARIABLE,
                    List.of("finance-a")
                )
            )
        );

        String actualDefinitionId = processEngine.getRuntimeService()
            .createProcessInstanceQuery()
            .processInstanceId(started.engineInstanceId())
            .singleResult()
            .getProcessDefinitionId();
        assertEquals(versionOne.engineDefinitionId(), actualDefinitionId);
        assertEquals(2, versionTwo.engineVersion());
    }
}

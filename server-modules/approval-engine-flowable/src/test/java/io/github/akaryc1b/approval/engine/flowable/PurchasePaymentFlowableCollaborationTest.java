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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchasePaymentFlowableCollaborationTest {

    private static final String TENANT_ID = "tenant-a";

    private ProcessEngine processEngine;
    private ApprovalEngine engine;

    @BeforeEach
    void setUp() {
        processEngine = ProcessEngineConfiguration
            .createStandaloneInMemProcessEngineConfiguration()
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setJdbcUrl("jdbc:h2:mem:collaboration-" + UUID.randomUUID())
            .buildProcessEngine();
        engine = new FlowableApprovalEngine(
            processEngine.getRepositoryService(),
            processEngine.getRuntimeService(),
            processEngine.getTaskService()
        );
        var compiled = new ApprovalDslCompiler().compile(
            PurchasePaymentTemplate.processDefinition(),
            PurchasePaymentTemplate.formDefinition()
        );
        engine.deploy(new ApprovalEngine.DeployCommand(
            TENANT_ID,
            compiled.definitionKey(),
            compiled.definitionVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash()
        ));
    }

    @AfterEach
    void tearDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void transferReassignsOnlyTheCurrentAssigneesTask() {
        String instanceId = start(new BigDecimal("5000.00"), "transfer-po");
        ApprovalEngine.TaskSnapshot managerTask = onlyTask(instanceId);

        ApprovalEngine.TaskSnapshot transferred = engine.transfer(
            new ApprovalEngine.TransferTaskCommand(
                TENANT_ID,
                managerTask.taskId(),
                "manager-1",
                "finance-reviewer"
            )
        );

        assertEquals("finance-reviewer", transferred.assigneeId());
        assertEquals("finance-reviewer", onlyTask(instanceId).assigneeId());
        ApprovalEngine.EngineOperationException exception = assertThrows(
            ApprovalEngine.EngineOperationException.class,
            () -> engine.transfer(new ApprovalEngine.TransferTaskCommand(
                TENANT_ID,
                managerTask.taskId(),
                "manager-1",
                "finance-a"
            ))
        );
        assertEquals("TASK_NOT_ASSIGNED_TO_OPERATOR", exception.code());
    }

    @Test
    void terminateRemovesTheRunningInstanceAndItsTasks() {
        String instanceId = start(new BigDecimal("5000.00"), "withdraw-po");
        assertEquals(1, activeTasks(instanceId).size());

        engine.terminate(new ApprovalEngine.TerminateInstanceCommand(
            TENANT_ID,
            instanceId,
            "withdrawn by initiator"
        ));

        assertTrue(activeTasks(instanceId).isEmpty());
        ApprovalEngine.EngineOperationException exception = assertThrows(
            ApprovalEngine.EngineOperationException.class,
            () -> engine.terminate(new ApprovalEngine.TerminateInstanceCommand(
                TENANT_ID,
                instanceId,
                "second withdrawal"
            ))
        );
        assertEquals("PROCESS_INSTANCE_NOT_FOUND", exception.code());
    }

    @Test
    void retrieveMovesOneUntouchedDownstreamTaskBackToThePreviousActivity() {
        String instanceId = start(new BigDecimal("25000.00"), "retrieve-po");
        ApprovalEngine.TaskSnapshot managerTask = onlyTask(instanceId);
        complete(managerTask, "manager-1");
        ApprovalEngine.TaskSnapshot financeTask = onlyTask(instanceId);
        assertEquals("financeReview", financeTask.taskDefinitionKey());

        engine.retrieve(new ApprovalEngine.RetrieveTaskCommand(
            TENANT_ID,
            instanceId,
            financeTask.taskId(),
            "managerApproval"
        ));

        ApprovalEngine.TaskSnapshot restored = onlyTask(instanceId);
        assertEquals("managerApproval", restored.taskDefinitionKey());
        assertEquals("manager-1", restored.assigneeId());
    }

    private String start(BigDecimal amount, String businessKey) {
        return engine.start(new ApprovalEngine.StartCommand(
            TENANT_ID,
            PurchasePaymentTemplate.DEFINITION_KEY,
            businessKey,
            "initiator-1",
            Map.of(
                "amount", amount,
                PurchasePaymentTemplate.MANAGER_ASSIGNEE_VARIABLE, "manager-1",
                PurchasePaymentTemplate.FINANCE_REVIEWER_VARIABLE, "finance-reviewer",
                PurchasePaymentTemplate.FINANCE_APPROVERS_VARIABLE,
                List.of("finance-a", "finance-b")
            )
        )).engineInstanceId();
    }

    private ApprovalEngine.TaskSnapshot onlyTask(String processInstanceId) {
        List<ApprovalEngine.TaskSnapshot> tasks = activeTasks(processInstanceId);
        assertEquals(1, tasks.size());
        return tasks.getFirst();
    }

    private List<ApprovalEngine.TaskSnapshot> activeTasks(String processInstanceId) {
        return engine.findActiveTasks(new ApprovalEngine.TaskQuery(
            TENANT_ID,
            processInstanceId,
            null
        ));
    }

    private void complete(ApprovalEngine.TaskSnapshot task, String operatorId) {
        engine.complete(new ApprovalEngine.CompleteTaskCommand(
            TENANT_ID,
            task.taskId(),
            operatorId,
            Map.of(
                ApprovalDslCompiler.DECISION_VARIABLE,
                "APPROVED"
            )
        ));
    }
}

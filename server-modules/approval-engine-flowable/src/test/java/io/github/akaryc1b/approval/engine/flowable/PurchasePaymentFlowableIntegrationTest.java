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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchasePaymentFlowableIntegrationTest {

    private static final String TENANT_ID = "tenant-a";

    private ProcessEngine processEngine;
    private ApprovalEngine engine;

    @BeforeEach
    void setUp() {
        String databaseName = "purchase-payment-" + UUID.randomUUID();
        processEngine = ProcessEngineConfiguration
            .createStandaloneInMemProcessEngineConfiguration()
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setJdbcUrl("jdbc:h2:mem:" + databaseName)
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
        var result = engine.deploy(new ApprovalEngine.DeployCommand(
            TENANT_ID,
            compiled.definitionKey(),
            compiled.definitionVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash()
        ));
        assertEquals(PurchasePaymentTemplate.DEFINITION_KEY, result.definitionKey());
        assertEquals(1, result.engineVersion());
    }

    @AfterEach
    void tearDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void lowValueRequestSkipsFinanceReviewAndRequiresEveryCountersigner() {
        String instanceId = start(new BigDecimal("5000.00"), "low-value-po");
        ApprovalEngine.TaskSnapshot managerTask = onlyTask(instanceId);
        assertEquals("managerApproval", managerTask.taskDefinitionKey());
        assertEquals("manager-1", managerTask.assigneeId());

        ApprovalEngine.EngineOperationException wrongOperator = assertThrows(
            ApprovalEngine.EngineOperationException.class,
            () -> complete(managerTask, "intruder", "APPROVED")
        );
        assertEquals("TASK_NOT_ASSIGNED_TO_OPERATOR", wrongOperator.code());

        complete(managerTask, "manager-1", "APPROVED");
        List<ApprovalEngine.TaskSnapshot> countersign = activeTasks(instanceId);
        assertEquals(2, countersign.size());
        assertEquals(
            Set.of("finance-a", "finance-b"),
            countersign.stream().map(ApprovalEngine.TaskSnapshot::assigneeId).collect(Collectors.toSet())
        );
        assertTrue(countersign.stream()
            .allMatch(task -> "financeCountersign".equals(task.taskDefinitionKey())));

        complete(countersign.getFirst(), countersign.getFirst().assigneeId(), "APPROVED");
        assertEquals(1, activeTasks(instanceId).size());
        ApprovalEngine.TaskSnapshot remaining = activeTasks(instanceId).getFirst();
        complete(remaining, remaining.assigneeId(), "APPROVED");
        assertTrue(activeTasks(instanceId).isEmpty());
    }

    @Test
    void highValueRequestAddsFinanceReviewBeforeParallelCountersign() {
        String instanceId = start(new BigDecimal("25000.00"), "high-value-po");
        ApprovalEngine.TaskSnapshot managerTask = onlyTask(instanceId);
        complete(managerTask, "manager-1", "APPROVED");

        ApprovalEngine.TaskSnapshot financeReview = onlyTask(instanceId);
        assertEquals("financeReview", financeReview.taskDefinitionKey());
        assertEquals("finance-reviewer", financeReview.assigneeId());
        complete(financeReview, "finance-reviewer", "APPROVED");

        List<ApprovalEngine.TaskSnapshot> countersign = activeTasks(instanceId);
        assertEquals(2, countersign.size());
        assertTrue(countersign.stream()
            .allMatch(task -> "financeCountersign".equals(task.taskDefinitionKey())));
    }

    @Test
    void rejectedManagerTaskReturnsToInitiatorAndCanBeResubmitted() {
        String instanceId = start(new BigDecimal("5000.00"), "manager-reject-po");
        ApprovalEngine.TaskSnapshot managerTask = onlyTask(instanceId);

        complete(managerTask, "manager-1", "REJECTED");

        ApprovalEngine.TaskSnapshot revision = onlyTask(instanceId);
        assertEquals(PurchasePaymentTemplate.REVISION_TASK_KEY, revision.taskDefinitionKey());
        assertEquals("initiator-1", revision.assigneeId());

        complete(revision, "initiator-1", "RESUBMITTED");

        ApprovalEngine.TaskSnapshot restartedManager = onlyTask(instanceId);
        assertEquals("managerApproval", restartedManager.taskDefinitionKey());
        assertEquals("manager-1", restartedManager.assigneeId());
    }

    @Test
    void oneCountersignerCanRejectAndCancelRemainingCountersignTasks() {
        String instanceId = start(new BigDecimal("5000.00"), "countersign-reject-po");
        complete(onlyTask(instanceId), "manager-1", "APPROVED");
        List<ApprovalEngine.TaskSnapshot> countersign = activeTasks(instanceId);
        assertEquals(2, countersign.size());

        ApprovalEngine.TaskSnapshot rejecting = countersign.getFirst();
        complete(rejecting, rejecting.assigneeId(), "REJECTED");

        List<ApprovalEngine.TaskSnapshot> active = activeTasks(instanceId);
        assertEquals(1, active.size());
        assertEquals(PurchasePaymentTemplate.REVISION_TASK_KEY, active.getFirst().taskDefinitionKey());
        assertEquals("initiator-1", active.getFirst().assigneeId());
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

    private void complete(
        ApprovalEngine.TaskSnapshot task,
        String operatorId,
        String decision
    ) {
        engine.complete(new ApprovalEngine.CompleteTaskCommand(
            TENANT_ID,
            task.taskId(),
            operatorId,
            Map.of(
                ApprovalDslCompiler.DECISION_VARIABLE, decision,
                "decision", decision
            )
        ));
    }
}

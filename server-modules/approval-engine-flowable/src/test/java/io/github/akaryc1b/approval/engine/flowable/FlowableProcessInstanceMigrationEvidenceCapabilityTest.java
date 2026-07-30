package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.migration.ProcessInstanceMigrationBuilder;
import org.flowable.engine.migration.ProcessInstanceMigrationValidationResult;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationEvidenceCapabilityTest {

    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private HistoryService historyService;
    private ProcessMigrationService processMigrationService;

    @BeforeEach
    void setUp() {
        processEngine = ProcessEngineConfiguration
            .createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-evidence-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setJdbcUsername("sa")
            .setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false)
            .buildProcessEngine();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        historyService = processEngine.getHistoryService();
        processMigrationService = processEngine.getProcessMigrationService();
    }

    @AfterEach
    void tearDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void preservesDirectUserTaskLocalIdentityAndHistoryEvidence() {
        ProcessDefinition source = deploy(
            "m5-evidence-source.bpmn20.xml",
            singleUserTaskProcessXml("m5Evidence", "review")
        );
        ProcessInstance instance = runtimeService.startProcessInstanceById(source.getId());
        Task sourceTask = activeTask(instance.getId(), "review");
        assertNotNull(sourceTask);

        runtimeService.setVariableLocal(
            sourceTask.getExecutionId(),
            "executionLocalEvidence",
            "execution-local"
        );
        taskService.setVariableLocal(sourceTask.getId(), "taskLocalEvidence", "task-local");
        taskService.addCandidateUser(sourceTask.getId(), "candidate-user");
        taskService.addCandidateGroup(sourceTask.getId(), "candidate-group");
        Set<String> sourceIdentityLinks = identityLinkEvidence(sourceTask.getId());

        ProcessDefinition target = deploy(
            "m5-evidence-target.bpmn20.xml",
            singleUserTaskProcessXml("m5Evidence", "review")
        );

        ProcessInstanceMigrationBuilder builder = processMigrationService
            .createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId());
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(instance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());

        builder.migrate(instance.getId());

        Task migratedTask = activeTask(instance.getId(), "review");
        assertNotNull(migratedTask);
        assertEquals(sourceTask.getId(), migratedTask.getId());
        assertEquals(sourceTask.getExecutionId(), migratedTask.getExecutionId());
        assertEquals(target.getId(), migratedTask.getProcessDefinitionId());
        assertEquals(
            "execution-local",
            runtimeService.getVariableLocal(migratedTask.getExecutionId(), "executionLocalEvidence")
        );
        assertEquals("task-local", taskService.getVariableLocal(migratedTask.getId(), "taskLocalEvidence"));
        assertEquals(sourceIdentityLinks, identityLinkEvidence(migratedTask.getId()));

        HistoricProcessInstance historicProcess = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(instance.getId())
            .singleResult();
        HistoricTaskInstance historicTask = historyService.createHistoricTaskInstanceQuery()
            .taskId(migratedTask.getId())
            .singleResult();

        assertNotNull(historicProcess);
        assertNotNull(historicTask);
        assertEquals(target.getId(), historicProcess.getProcessDefinitionId());
        assertEquals(target.getId(), historicTask.getProcessDefinitionId());
        assertEquals("review", historicTask.getTaskDefinitionKey());
    }

    @Test
    void migratesASuspendedDirectUserTaskAndPreservesSuspensionState() {
        ProcessDefinition source = deploy(
            "m5-suspended-source.bpmn20.xml",
            singleUserTaskProcessXml("m5Suspended", "review")
        );
        ProcessInstance instance = runtimeService.startProcessInstanceById(source.getId());
        Task sourceTask = activeTask(instance.getId(), "review");
        assertNotNull(sourceTask);

        runtimeService.suspendProcessInstanceById(instance.getId());
        ProcessInstance suspended = processInstance(instance.getId());
        assertNotNull(suspended);
        assertTrue(suspended.isSuspended());
        assertTrue(activeTask(instance.getId(), "review").isSuspended());

        ProcessDefinition target = deploy(
            "m5-suspended-target.bpmn20.xml",
            singleUserTaskProcessXml("m5Suspended", "review")
        );

        ProcessInstanceMigrationBuilder builder = processMigrationService
            .createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId());
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(instance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());

        builder.migrate(instance.getId());

        ProcessInstance migrated = processInstance(instance.getId());
        Task migratedTask = activeTask(instance.getId(), "review");
        assertNotNull(migrated);
        assertNotNull(migratedTask);
        assertEquals(target.getId(), migrated.getProcessDefinitionId());
        assertTrue(migrated.isSuspended());
        assertTrue(migratedTask.isSuspended());
    }

    @Test
    void rejectsAnEndedInstanceThroughPublicMigrationValidation() {
        ProcessDefinition source = deploy(
            "m5-ended-source.bpmn20.xml",
            singleUserTaskProcessXml("m5Ended", "review")
        );
        ProcessInstance instance = runtimeService.startProcessInstanceById(source.getId());
        Task task = activeTask(instance.getId(), "review");
        assertNotNull(task);
        taskService.complete(task.getId());
        assertNull(processInstance(instance.getId()));

        ProcessDefinition target = deploy(
            "m5-ended-target.bpmn20.xml",
            singleUserTaskProcessXml("m5Ended", "review")
        );

        ProcessInstanceMigrationValidationResult validation = processMigrationService
            .createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .validateMigration(instance.getId());

        assertFalse(validation.isMigrationValid());
        assertFalse(validation.getValidationMessages().isEmpty());
    }

    @Test
    void migratesAReceiveTaskWhenHistoricTasksExistButNoActiveTaskExists() {
        ProcessDefinition source = deploy(
            "m5-receive-source.bpmn20.xml",
            userTaskThenReceiveTaskProcessXml("m5Receive")
        );
        ProcessInstance instance = runtimeService.startProcessInstanceById(source.getId());
        Task reviewTask = activeTask(instance.getId(), "review");
        assertNotNull(reviewTask);
        taskService.complete(reviewTask.getId());

        assertNull(activeTask(instance.getId(), "review"));
        assertEquals(Set.of("waitSignal"), new HashSet<>(runtimeService.getActiveActivityIds(instance.getId())));
        assertNotNull(
            historyService.createHistoricTaskInstanceQuery()
                .taskId(reviewTask.getId())
                .singleResult()
        );

        ProcessDefinition target = deploy(
            "m5-receive-target.bpmn20.xml",
            userTaskThenReceiveTaskProcessXml("m5Receive")
        );

        ProcessInstanceMigrationBuilder builder = processMigrationService
            .createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId());
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(instance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());

        builder.migrate(instance.getId());

        ProcessInstance migrated = processInstance(instance.getId());
        assertNotNull(migrated);
        assertEquals(target.getId(), migrated.getProcessDefinitionId());
        assertEquals(Set.of("waitSignal"), new HashSet<>(runtimeService.getActiveActivityIds(instance.getId())));
        assertNull(taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult());

        HistoricTaskInstance historicTask = historyService.createHistoricTaskInstanceQuery()
            .taskId(reviewTask.getId())
            .singleResult();
        assertNotNull(historicTask);
        assertEquals(target.getId(), historicTask.getProcessDefinitionId());
    }

    private ProcessDefinition deploy(String resourceName, String bpmnXml) {
        Deployment deployment = repositoryService.createDeployment()
            .addString(resourceName, bpmnXml)
            .deploy();
        return repositoryService.createProcessDefinitionQuery()
            .deploymentId(deployment.getId())
            .singleResult();
    }

    private ProcessInstance processInstance(String processInstanceId) {
        return runtimeService.createProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .singleResult();
    }

    private Task activeTask(String processInstanceId, String activityId) {
        return taskService.createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskDefinitionKey(activityId)
            .singleResult();
    }

    private Set<String> identityLinkEvidence(String taskId) {
        return taskService.getIdentityLinksForTask(taskId).stream()
            .map(FlowableProcessInstanceMigrationEvidenceCapabilityTest::identityLinkEvidence)
            .collect(Collectors.toSet());
    }

    private static String identityLinkEvidence(IdentityLink identityLink) {
        return String.join(
            "|",
            value(identityLink.getType()),
            value(identityLink.getUserId()),
            value(identityLink.getGroupId())
        );
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String singleUserTaskProcessXml(String processKey, String taskId) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 migration evidence" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-review" sourceRef="start" targetRef="%s"/>
                <userTask id="%s" name="Review"/>
                <sequenceFlow id="to-end" sourceRef="%s" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(processKey, taskId, taskId, taskId);
    }

    private static String userTaskThenReceiveTaskProcessXml(String processKey) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 receive task evidence" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-review" sourceRef="start" targetRef="review"/>
                <userTask id="review" name="Review"/>
                <sequenceFlow id="to-wait" sourceRef="review" targetRef="waitSignal"/>
                <receiveTask id="waitSignal" name="Wait for signal"/>
                <sequenceFlow id="to-end" sourceRef="waitSignal" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(processKey);
    }
}

package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.migration.ProcessInstanceMigrationBuilder;
import org.flowable.engine.migration.ProcessInstanceMigrationValidationResult;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationBoundaryTimerCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private TaskService tasks;
    private ManagementService management;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-boundary-timer-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setJdbcUsername("sa")
            .setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false)
            .buildProcessEngine();
        repository = engine.getRepositoryService();
        runtime = engine.getRuntimeService();
        tasks = engine.getTaskService();
        management = engine.getManagementService();
        migrations = engine.getProcessMigrationService();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
    }

    @Test
    void addsBoundaryTimerToAnExistingUserTaskWaitState() {
        ProcessDefinition source = deploy("boundary-add-source.bpmn20.xml", userTaskXml("m5BoundaryAdd", false));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        assertNotNull(task(instance.getId()));
        assertEquals(0, management.createTimerJobQuery().processInstanceId(instance.getId()).count());

        ProcessDefinition target = deploy("boundary-add-target.bpmn20.xml", userTaskXml("m5BoundaryAdd", true));
        migrate(instance.getId(), target.getId());

        Task migratedTask = task(instance.getId());
        Job boundaryTimer = timerJob(instance.getId());
        assertNotNull(migratedTask);
        assertNotNull(boundaryTimer);
        assertEquals(target.getId(), migratedTask.getProcessDefinitionId());
        assertEquals(target.getId(), boundaryTimer.getProcessDefinitionId());
        assertEquals("reviewBoundary", boundaryTimer.getElementId());
        assertEquals(1, management.createTimerJobQuery().processInstanceId(instance.getId()).count());
    }

    @Test
    void removesBoundaryTimerWhileKeepingTheUserTaskWaitState() {
        ProcessDefinition source = deploy("boundary-remove-source.bpmn20.xml", userTaskXml("m5BoundaryRemove", true));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        assertNotNull(task(instance.getId()));
        assertNotNull(timerJob(instance.getId()));

        ProcessDefinition target = deploy("boundary-remove-target.bpmn20.xml", userTaskXml("m5BoundaryRemove", false));
        migrate(instance.getId(), target.getId());

        Task migratedTask = task(instance.getId());
        assertNotNull(migratedTask);
        assertEquals(target.getId(), migratedTask.getProcessDefinitionId());
        assertNull(timerJob(instance.getId()));
        assertEquals(0, management.createTimerJobQuery().processInstanceId(instance.getId()).count());
    }

    private void migrate(String processInstanceId, String targetDefinitionId) {
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(targetDefinitionId);
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(processInstanceId);
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());
        builder.migrate(processInstanceId);
    }

    private Task task(String processInstanceId) {
        return tasks.createTaskQuery().processInstanceId(processInstanceId)
            .taskDefinitionKey("review").singleResult();
    }

    private Job timerJob(String processInstanceId) {
        return management.createTimerJobQuery().processInstanceId(processInstanceId).singleResult();
    }

    private ProcessDefinition deploy(String resourceName, String xml) {
        Deployment deployment = repository.createDeployment().addString(resourceName, xml).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private static String userTaskXml(String key, boolean withBoundaryTimer) {
        String boundary = withBoundaryTimer ? """
                <boundaryEvent id="reviewBoundary" attachedToRef="review" cancelActivity="false">
                  <timerEventDefinition>
                    <timeDate>2099-01-01T00:00:00Z</timeDate>
                  </timerEventDefinition>
                </boundaryEvent>
                <sequenceFlow id="boundary-to-escalated" sourceRef="reviewBoundary" targetRef="escalated"/>
                <userTask id="escalated" name="Escalated review"/>
                <sequenceFlow id="escalated-to-end" sourceRef="escalated" targetRef="end"/>
            """ : "";
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 boundary timer migration evidence" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-review" sourceRef="start" targetRef="review"/>
                <userTask id="review" name="Review"/>
            %s
                <sequenceFlow id="review-to-end" sourceRef="review" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key, boundary);
    }
}

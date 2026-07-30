package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.migration.ActivityMigrationMapping;
import org.flowable.engine.migration.ProcessInstanceMigrationBuilder;
import org.flowable.engine.migration.ProcessInstanceMigrationValidationResult;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationCallActivityExitCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private TaskService tasks;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-call-exit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setJdbcUsername("sa").setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false).buildProcessEngine();
        repository = engine.getRepositoryService();
        runtime = engine.getRuntimeService();
        tasks = engine.getTaskService();
        migrations = engine.getProcessMigrationService();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void movesCalledChildWaitStateToParentTargetTask() {
        deploy("call-exit-child.bpmn20.xml", child("m5CallExitChild"));
        ProcessDefinition sourceParent = deploy("call-exit-parent-source.bpmn20.xml", parentCall("m5CallExitParent"));
        ProcessInstance parent = runtime.startProcessInstanceById(sourceParent.getId());
        ProcessInstance child = runtime.createProcessInstanceQuery()
            .superProcessInstanceId(parent.getId()).singleResult();
        assertNotNull(child);
        Task childTask = task(child.getId(), "childReview");
        assertNotNull(childTask);

        ProcessDefinition targetParent = deploy("call-exit-parent-target.bpmn20.xml", parentTask("m5CallExitParent"));
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(targetParent.getId())
            .addActivityMigrationMapping(
                ActivityMigrationMapping.createMappingFor("childReview", "parentReview")
                    .inParentProcessOfCallActivityId("callChild")
            );
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(parent.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());
        builder.migrate(parent.getId());

        assertEquals(targetParent.getId(), process(parent.getId()).getProcessDefinitionId());
        assertNotNull(task(parent.getId(), "parentReview"));
        assertNull(runtime.createProcessInstanceQuery().processInstanceId(child.getId()).singleResult());
        assertNull(runtime.createProcessInstanceQuery().superProcessInstanceId(parent.getId()).singleResult());
        assertNull(tasks.createTaskQuery().taskId(childTask.getId()).singleResult());
    }

    private ProcessDefinition deploy(String name, String xml) {
        Deployment deployment = repository.createDeployment().addString(name, xml).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private ProcessInstance process(String id) {
        return runtime.createProcessInstanceQuery().processInstanceId(id).singleResult();
    }

    private Task task(String instanceId, String key) {
        return tasks.createTaskQuery().processInstanceId(instanceId).taskDefinitionKey(key).singleResult();
    }

    private static String child(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="%s" isExecutable="true"><startEvent id="start"/>
                <sequenceFlow id="a" sourceRef="start" targetRef="childReview"/>
                <userTask id="childReview"/><sequenceFlow id="b" sourceRef="childReview" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key);
    }

    private static String parentCall(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="%s" isExecutable="true"><startEvent id="start"/>
                <sequenceFlow id="a" sourceRef="start" targetRef="callChild"/>
                <callActivity id="callChild" calledElement="m5CallExitChild"/>
                <sequenceFlow id="b" sourceRef="callChild" targetRef="end"/><endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key);
    }

    private static String parentTask(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="%s" isExecutable="true"><startEvent id="start"/>
                <sequenceFlow id="a" sourceRef="start" targetRef="parentReview"/>
                <userTask id="parentReview"/><sequenceFlow id="b" sourceRef="parentReview" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key);
    }
}

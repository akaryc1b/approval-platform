package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationCalledChildCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private TaskService tasks;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-called-child-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
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
    void migratesExactCalledChildAndPreservesParentRelation() {
        ProcessDefinition childV1 = deploy("called-child-v1.bpmn20.xml", child("m5CalledChild", "childReview"));
        ProcessDefinition parent = deploy("called-parent.bpmn20.xml", parentCall("m5CalledParent", "m5CalledChild"));
        ProcessInstance parentInstance = runtime.startProcessInstanceById(parent.getId());
        ProcessInstance childInstance = runtime.createProcessInstanceQuery()
            .superProcessInstanceId(parentInstance.getId()).singleResult();
        assertNotNull(childInstance);
        assertEquals(childV1.getId(), childInstance.getProcessDefinitionId());
        Task childTask = task(childInstance.getId(), "childReview");
        assertNotNull(childTask);

        ProcessDefinition childV2 = deploy("called-child-v2.bpmn20.xml", child("m5CalledChild", "childReview"));
        ProcessInstanceMigrationValidationResult validation = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(childV2.getId())
            .validateMigration(childInstance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());
        migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(childV2.getId())
            .migrate(childInstance.getId());

        ProcessInstance migratedChild = runtime.createProcessInstanceQuery()
            .superProcessInstanceId(parentInstance.getId()).singleResult();
        assertNotNull(migratedChild);
        assertEquals(childInstance.getId(), migratedChild.getId());
        assertEquals(childV2.getId(), migratedChild.getProcessDefinitionId());
        assertEquals(parent.getId(), process(parentInstance.getId()).getProcessDefinitionId());
        assertNotNull(task(migratedChild.getId(), "childReview"));
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

    private static String child(String key, String taskId) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="%s" isExecutable="true"><startEvent id="start"/>
                <sequenceFlow id="a" sourceRef="start" targetRef="%s"/><userTask id="%s"/>
                <sequenceFlow id="b" sourceRef="%s" targetRef="end"/><endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key, taskId, taskId, taskId);
    }

    private static String parentCall(String key, String childKey) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="%s" isExecutable="true"><startEvent id="start"/>
                <sequenceFlow id="a" sourceRef="start" targetRef="callChild"/>
                <callActivity id="callChild" calledElement="%s"/>
                <sequenceFlow id="b" sourceRef="callChild" targetRef="end"/><endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key, childKey);
    }
}

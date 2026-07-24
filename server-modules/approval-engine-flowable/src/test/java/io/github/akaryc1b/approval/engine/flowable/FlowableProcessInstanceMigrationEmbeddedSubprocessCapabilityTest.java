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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationEmbeddedSubprocessCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private TaskService tasks;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-embedded-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setJdbcUsername("sa")
            .setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false)
            .buildProcessEngine();
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
    void migratesRootWaitStateIntoEmbeddedSubprocess() {
        ProcessDefinition source = deploy("embedded-enter-source.bpmn20.xml", rootTask("m5EmbeddedEnter", "rootReview"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        Task sourceTask = task(instance.getId(), "rootReview");
        assertNotNull(sourceTask);

        ProcessDefinition target = deploy("embedded-enter-target.bpmn20.xml", embeddedTask("m5EmbeddedEnter", "nestedReview"));
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("rootReview", "nestedReview"));
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(instance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());
        builder.migrate(instance.getId());

        assertEquals(target.getId(), process(instance.getId()).getProcessDefinitionId());
        assertNull(task(instance.getId(), "rootReview"));
        assertNotNull(task(instance.getId(), "nestedReview"));
        assertEquals(1, tasks.createTaskQuery().processInstanceId(instance.getId()).count());
        assertTrue(activeActivities(instance.getId()).contains("nestedReview"));
        assertTrue(!activeActivities(instance.getId()).contains("rootReview"));
    }

    @Test
    void migratesEmbeddedWaitStateBackToRoot() {
        ProcessDefinition source = deploy("embedded-exit-source.bpmn20.xml", embeddedTask("m5EmbeddedExit", "nestedReview"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        assertNotNull(task(instance.getId(), "nestedReview"));

        ProcessDefinition target = deploy("embedded-exit-target.bpmn20.xml", rootTask("m5EmbeddedExit", "rootReview"));
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("nestedReview", "rootReview"));
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(instance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());
        builder.migrate(instance.getId());

        assertEquals(target.getId(), process(instance.getId()).getProcessDefinitionId());
        assertNull(task(instance.getId(), "nestedReview"));
        assertNotNull(task(instance.getId(), "rootReview"));
        assertEquals(1, tasks.createTaskQuery().processInstanceId(instance.getId()).count());
        assertTrue(activeActivities(instance.getId()).contains("rootReview"));
        assertTrue(!activeActivities(instance.getId()).contains("nestedReview"));
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

    private Set<String> activeActivities(String id) {
        return new HashSet<>(runtime.getActiveActivityIds(id));
    }

    private static String rootTask(String key, String taskId) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="%s" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-task" sourceRef="start" targetRef="%s"/>
                <userTask id="%s"/>
                <sequenceFlow id="to-end" sourceRef="%s" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key, taskId, taskId, taskId);
    }

    private static String embeddedTask(String key, String taskId) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="%s" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-scope" sourceRef="start" targetRef="reviewScope"/>
                <subProcess id="reviewScope">
                  <startEvent id="scopeStart"/>
                  <sequenceFlow id="scope-to-task" sourceRef="scopeStart" targetRef="%s"/>
                  <userTask id="%s"/>
                  <sequenceFlow id="scope-to-end" sourceRef="%s" targetRef="scopeEnd"/>
                  <endEvent id="scopeEnd"/>
                </subProcess>
                <sequenceFlow id="scope-to-process-end" sourceRef="reviewScope" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key, taskId, taskId, taskId);
    }
}

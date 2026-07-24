package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.engine.HistoryService;
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
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
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

class FlowableProcessInstanceMigrationCompletedBranchCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private TaskService tasks;
    private HistoryService history;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-completed-branch-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setJdbcUsername("sa").setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false).buildProcessEngine();
        repository = engine.getRepositoryService();
        runtime = engine.getRuntimeService();
        tasks = engine.getTaskService();
        history = engine.getHistoryService();
        migrations = engine.getProcessMigrationService();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
    }

    @Test
    void preservesCompletedAndActiveBranchesInTheSameParallelTopology() {
        ProcessDefinition source = deploy("source.bpmn20.xml");
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        Task left = task(instance.getId(), "leftReview");
        assertNotNull(left);
        assertNotNull(task(instance.getId(), "rightReview"));
        tasks.complete(left.getId());
        assertNull(task(instance.getId(), "leftReview"));
        HistoricTaskInstance completed = history.createHistoricTaskInstanceQuery()
            .taskId(left.getId()).singleResult();
        assertNotNull(completed);
        assertNotNull(completed.getEndTime());

        ProcessDefinition target = deploy("target.bpmn20.xml");
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId());
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(instance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());
        builder.migrate(instance.getId());

        assertEquals(target.getId(), runtime.createProcessInstanceQuery()
            .processInstanceId(instance.getId()).singleResult().getProcessDefinitionId());
        assertNull(task(instance.getId(), "leftReview"));
        assertNotNull(task(instance.getId(), "rightReview"));
        assertEquals(Set.of("rightReview"), new HashSet<>(runtime.getActiveActivityIds(instance.getId())));
        HistoricTaskInstance migrated = history.createHistoricTaskInstanceQuery()
            .taskId(left.getId()).singleResult();
        assertEquals(target.getId(), migrated.getProcessDefinitionId());
        assertNotNull(migrated.getEndTime());
    }

    private ProcessDefinition deploy(String name) {
        Deployment deployment = repository.createDeployment().addString(name, processXml()).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private Task task(String instanceId, String key) {
        return tasks.createTaskQuery().processInstanceId(instanceId).taskDefinitionKey(key).singleResult();
    }

    private static String processXml() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="m5CompletedBranch" isExecutable="true"><startEvent id="start"/>
                <sequenceFlow id="a" sourceRef="start" targetRef="split"/><parallelGateway id="split"/>
                <sequenceFlow id="b" sourceRef="split" targetRef="leftReview"/>
                <sequenceFlow id="c" sourceRef="split" targetRef="rightReview"/>
                <userTask id="leftReview"/><userTask id="rightReview"/>
                <sequenceFlow id="d" sourceRef="leftReview" targetRef="join"/>
                <sequenceFlow id="e" sourceRef="rightReview" targetRef="join"/>
                <parallelGateway id="join"/><sequenceFlow id="f" sourceRef="join" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;
    }
}

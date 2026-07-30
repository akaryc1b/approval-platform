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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationParallelMappingCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private TaskService tasks;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-parallel-map-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
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
        if (engine != null) engine.close();
    }

    @Test
    void mapsOneExecutionToTwoParallelUserTasks() {
        ProcessDefinition source = deploy("one-source.bpmn20.xml", single("m5OneToMany", "singleReview"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        Task sourceTask = singleTask(instance.getId());
        assertNotNull(sourceTask);

        ProcessDefinition target = deploy("one-target.bpmn20.xml", parallel("m5OneToMany"));
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor(
                "singleReview", List.of("leftReview", "rightReview")
            ));
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(instance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());
        builder.migrate(instance.getId());

        assertEquals(target.getId(), process(instance.getId()).getProcessDefinitionId());
        assertEquals(Set.of("leftReview", "rightReview"), activeActivities(instance.getId()));
        assertEquals(Set.of("leftReview", "rightReview"), taskKeys(instance.getId()));
        assertEquals(2, activeTasks(instance.getId()).size());
        assertNull(tasks.createTaskQuery().taskId(sourceTask.getId()).singleResult());
    }

    @Test
    void mapsTwoParallelExecutionsToOneUserTask() {
        ProcessDefinition source = deploy("many-source.bpmn20.xml", parallel("m5ManyToOne"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        Set<String> sourceTaskIds = activeTasks(instance.getId()).stream()
            .map(Task::getId)
            .collect(Collectors.toSet());
        assertEquals(Set.of("leftReview", "rightReview"), taskKeys(instance.getId()));

        ProcessDefinition target = deploy("many-target.bpmn20.xml", single("m5ManyToOne", "mergedReview"));
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor(
                List.of("leftReview", "rightReview"), "mergedReview"
            ));
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(instance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());
        builder.migrate(instance.getId());

        Task merged = singleTask(instance.getId());
        assertNotNull(merged);
        assertEquals(target.getId(), process(instance.getId()).getProcessDefinitionId());
        assertEquals(Set.of("mergedReview"), activeActivities(instance.getId()));
        assertEquals("mergedReview", merged.getTaskDefinitionKey());
        assertFalse(sourceTaskIds.contains(merged.getId()));
        for (String id : sourceTaskIds) assertNull(tasks.createTaskQuery().taskId(id).singleResult());
    }

    private ProcessDefinition deploy(String name, String xml) {
        Deployment deployment = repository.createDeployment().addString(name, xml).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private ProcessInstance process(String id) {
        return runtime.createProcessInstanceQuery().processInstanceId(id).singleResult();
    }

    private Set<String> activeActivities(String id) {
        return new HashSet<>(runtime.getActiveActivityIds(id));
    }

    private List<Task> activeTasks(String id) {
        return tasks.createTaskQuery().processInstanceId(id).list();
    }

    private Set<String> taskKeys(String id) {
        return activeTasks(id).stream().map(Task::getTaskDefinitionKey).collect(Collectors.toSet());
    }

    private Task singleTask(String id) {
        return tasks.createTaskQuery().processInstanceId(id).singleResult();
    }

    private static String single(String key, String task) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="%s" isExecutable="true"><startEvent id="start"/>
                <sequenceFlow id="a" sourceRef="start" targetRef="%s"/><userTask id="%s"/>
                <sequenceFlow id="b" sourceRef="%s" targetRef="end"/><endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key, task, task, task);
    }

    private static String parallel(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="%s" isExecutable="true"><startEvent id="start"/>
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
            """.formatted(key);
    }
}

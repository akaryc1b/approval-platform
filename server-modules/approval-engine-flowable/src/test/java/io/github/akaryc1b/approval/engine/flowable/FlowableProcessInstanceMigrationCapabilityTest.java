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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationCapabilityTest {

    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private ProcessMigrationService processMigrationService;

    @BeforeEach
    void setUp() {
        processEngine = ProcessEngineConfiguration
            .createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-capability-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setJdbcUsername("sa")
            .setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false)
            .buildProcessEngine();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        processMigrationService = processEngine.getProcessMigrationService();
    }

    @AfterEach
    void tearDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void exposesPublicValidationSingleInstanceAndBatchMigrationSurfaceWithoutRollback() throws Exception {
        assertNotNull(processMigrationService.createProcessInstanceMigrationBuilder());

        Set<String> builderMethods = Arrays.stream(ProcessInstanceMigrationBuilder.class.getMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        for (String required : Set.of(
            "validateMigration",
            "validateMigrationOfProcessInstances",
            "migrate",
            "migrateProcessInstances",
            "batchMigrateProcessInstances",
            "migrateToProcessDefinition",
            "addActivityMigrationMapping",
            "getProcessInstanceMigrationDocument"
        )) {
            assertTrue(builderMethods.contains(required), "missing public migration method " + required);
        }
        assertFalse(
            builderMethods.stream().anyMatch(name -> name.toLowerCase().contains("rollback")),
            "Flowable public migration builder must not be represented as providing rollback"
        );

        assertNotNull(ActivityMigrationMapping.createMappingFor("source", "target"));
    }

    @Test
    void validatesAndMigratesOneActiveUserTaskWithTheSameActivityId() {
        ProcessDefinition source = deploy("m5-same-id-source.bpmn20.xml", processXml("m5SameId", "review"));
        ProcessInstance instance = runtimeService.startProcessInstanceById(
            source.getId(),
            Map.of("migrationEvidence", "preserved")
        );
        assertNotNull(activeTask(instance.getId(), "review"));

        ProcessDefinition target = deploy("m5-same-id-target.bpmn20.xml", processXml("m5SameId", "review"));

        ProcessInstanceMigrationValidationResult validation = processMigrationService
            .createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .validateMigration(instance.getId());

        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());

        processMigrationService.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .migrate(instance.getId());

        ProcessInstance migrated = runtimeService.createProcessInstanceQuery()
            .processInstanceId(instance.getId())
            .singleResult();
        assertNotNull(migrated);
        assertEquals(target.getId(), migrated.getProcessDefinitionId());
        assertEquals("preserved", runtimeService.getVariable(instance.getId(), "migrationEvidence"));
        assertNotNull(activeTask(instance.getId(), "review"));
    }

    @Test
    void requiresAndHonorsAnExplicitMappingWhenTheActivityIdChanges() {
        ProcessDefinition source = deploy(
            "m5-explicit-mapping-source.bpmn20.xml",
            processXml("m5ExplicitMapping", "sourceReview")
        );
        ProcessInstance instance = runtimeService.startProcessInstanceById(source.getId());
        assertNotNull(activeTask(instance.getId(), "sourceReview"));

        ProcessDefinition target = deploy(
            "m5-explicit-mapping-target.bpmn20.xml",
            processXml("m5ExplicitMapping", "targetReview")
        );

        ProcessInstanceMigrationValidationResult withoutMapping = processMigrationService
            .createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .validateMigration(instance.getId());
        assertFalse(withoutMapping.isMigrationValid());

        ProcessInstanceMigrationBuilder builder = processMigrationService
            .createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .addActivityMigrationMapping(
                ActivityMigrationMapping.createMappingFor("sourceReview", "targetReview")
            );

        ProcessInstanceMigrationValidationResult withMapping = builder.validateMigration(instance.getId());
        assertTrue(withMapping.isMigrationValid(), withMapping.getValidationMessages().toString());
        builder.migrate(instance.getId());

        assertNotNull(activeTask(instance.getId(), "targetReview"));
        assertEquals(
            target.getId(),
            runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId())
                .singleResult()
                .getProcessDefinitionId()
        );
    }

    private ProcessDefinition deploy(String resourceName, String bpmnXml) {
        Deployment deployment = repositoryService.createDeployment()
            .addString(resourceName, bpmnXml)
            .deploy();
        return repositoryService.createProcessDefinitionQuery()
            .deploymentId(deployment.getId())
            .singleResult();
    }

    private Task activeTask(String processInstanceId, String activityId) {
        return taskService.createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskDefinitionKey(activityId)
            .singleResult();
    }

    private static String processXml(String processKey, String taskId) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 migration capability" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-review" sourceRef="start" targetRef="%s"/>
                <userTask id="%s" name="Review"/>
                <sequenceFlow id="to-end" sourceRef="%s" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(processKey, taskId, taskId, taskId);
    }
}

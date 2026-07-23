package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.migration.ActivityMigrationMapping;
import org.flowable.engine.migration.ProcessInstanceMigrationBuilder;
import org.flowable.engine.migration.ProcessInstanceMigrationDocument;
import org.flowable.engine.migration.ProcessInstanceMigrationValidationResult;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationDuplicateInvocationCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private TaskService tasks;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-duplicate-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
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
    void acceptsAReplayedMigrationDocumentAfterTheInstanceAlreadyReachedTheTarget() {
        ProcessDefinition source = deploy("duplicate-source.bpmn20.xml", processXml("m5Duplicate", "sourceReview"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        assertNotNull(task(instance.getId(), "sourceReview"));

        ProcessDefinition target = deploy("duplicate-target.bpmn20.xml", processXml("m5Duplicate", "targetReview"));
        ProcessInstanceMigrationBuilder firstBuilder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .addActivityMigrationMapping(
                ActivityMigrationMapping.createMappingFor("sourceReview", "targetReview")
            );
        ProcessInstanceMigrationDocument replayedDocument = firstBuilder.getProcessInstanceMigrationDocument();

        ProcessInstanceMigrationValidationResult firstValidation = firstBuilder.validateMigration(instance.getId());
        assertTrue(firstValidation.isMigrationValid(), firstValidation.getValidationMessages().toString());
        firstBuilder.migrate(instance.getId());
        assertEquals(target.getId(), process(instance.getId()).getProcessDefinitionId());
        assertNotNull(task(instance.getId(), "targetReview"));

        ProcessInstanceMigrationBuilder duplicateBuilder = migrations.createProcessInstanceMigrationBuilder()
            .fromProcessInstanceMigrationDocument(replayedDocument);
        ProcessInstanceMigrationValidationResult duplicateValidation = duplicateBuilder.validateMigration(instance.getId());
        assertTrue(duplicateValidation.isMigrationValid(), duplicateValidation.getValidationMessages().toString());
        assertDoesNotThrow(() -> duplicateBuilder.migrate(instance.getId()));

        assertEquals(target.getId(), process(instance.getId()).getProcessDefinitionId());
        assertEquals(1, tasks.createTaskQuery().processInstanceId(instance.getId()).count());
        assertNotNull(task(instance.getId(), "targetReview"));
    }

    private ProcessDefinition deploy(String resourceName, String xml) {
        Deployment deployment = repository.createDeployment().addString(resourceName, xml).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private ProcessInstance process(String processInstanceId) {
        return runtime.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
    }

    private Task task(String processInstanceId, String taskDefinitionKey) {
        return tasks.createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskDefinitionKey(taskDefinitionKey)
            .singleResult();
    }

    private static String processXml(String key, String taskId) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 duplicate migration evidence" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-review" sourceRef="start" targetRef="%s"/>
                <userTask id="%s" name="Review"/>
                <sequenceFlow id="to-end" sourceRef="%s" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key, taskId, taskId, taskId);
    }
}

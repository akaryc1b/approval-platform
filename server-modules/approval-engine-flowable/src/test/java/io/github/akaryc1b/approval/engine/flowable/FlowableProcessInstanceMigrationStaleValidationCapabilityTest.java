package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.common.engine.api.FlowableException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationStaleValidationCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private TaskService tasks;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-stale-validation-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
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
        if (engine != null) engine.close();
    }

    @Test
    void rejectsInvocationWhenTheInstanceEndsAfterValidation() {
        ProcessDefinition source = deploy("source.bpmn20.xml");
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        Task task = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        assertNotNull(task);

        ProcessDefinition target = deploy("target.bpmn20.xml");
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId());
        ProcessInstanceMigrationValidationResult initial = builder.validateMigration(instance.getId());
        assertTrue(initial.isMigrationValid(), initial.getValidationMessages().toString());

        tasks.complete(task.getId());
        assertNull(runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).singleResult());
        ProcessInstanceMigrationValidationResult stale = builder.validateMigration(instance.getId());
        assertFalse(stale.isMigrationValid());
        assertFalse(stale.getValidationMessages().isEmpty());
        assertThrows(FlowableException.class, () -> builder.migrate(instance.getId()));
    }

    private ProcessDefinition deploy(String name) {
        Deployment deployment = repository.createDeployment().addString(name, processXml()).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private static String processXml() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="m5">
              <process id="m5StaleValidation" isExecutable="true"><startEvent id="start"/>
                <sequenceFlow id="a" sourceRef="start" targetRef="review"/><userTask id="review"/>
                <sequenceFlow id="b" sourceRef="review" targetRef="end"/><endEvent id="end"/>
              </process>
            </definitions>
            """;
    }
}

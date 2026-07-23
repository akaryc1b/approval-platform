package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.migration.ProcessInstanceMigrationBuilder;
import org.flowable.engine.migration.ProcessInstanceMigrationValidationResult;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationPendingAsyncJobCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private ManagementService management;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-pending-async-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setJdbcUsername("sa")
            .setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false)
            .buildProcessEngine();
        repository = engine.getRepositoryService();
        runtime = engine.getRuntimeService();
        management = engine.getManagementService();
        migrations = engine.getProcessMigrationService();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
    }

    @Test
    void migratesDirectAsyncServiceTaskWhileItsJobRemainsPending() {
        ProcessDefinition source = deploy("async-source.bpmn20.xml", asyncProcessXml("m5PendingAsync"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        Job sourceJob = asyncJob(instance.getId());
        assertNotNull(sourceJob);
        assertEquals(source.getId(), sourceJob.getProcessDefinitionId());
        assertEquals("asyncWork", sourceJob.getElementId());
        assertEquals(Set.of("asyncWork"), Set.copyOf(runtime.getActiveActivityIds(instance.getId())));

        ProcessDefinition target = deploy("async-target.bpmn20.xml", asyncProcessXml("m5PendingAsync"));
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId());
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(instance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());
        builder.migrate(instance.getId());

        Job migratedJob = asyncJob(instance.getId());
        assertNotNull(migratedJob);
        assertEquals(1, management.createJobQuery().processInstanceId(instance.getId()).count());
        assertEquals(instance.getId(), migratedJob.getProcessInstanceId());
        assertEquals(target.getId(), migratedJob.getProcessDefinitionId());
        assertEquals("asyncWork", migratedJob.getElementId());
        assertEquals(Set.of("asyncWork"), Set.copyOf(runtime.getActiveActivityIds(instance.getId())));
        assertEquals(target.getId(), runtime.createProcessInstanceQuery()
            .processInstanceId(instance.getId()).singleResult().getProcessDefinitionId());
    }

    private Job asyncJob(String processInstanceId) {
        return management.createJobQuery().processInstanceId(processInstanceId).singleResult();
    }

    private ProcessDefinition deploy(String resourceName, String xml) {
        Deployment deployment = repository.createDeployment().addString(resourceName, xml).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private static String asyncProcessXml(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 pending async migration evidence" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-async" sourceRef="start" targetRef="asyncWork"/>
                <serviceTask id="asyncWork" name="Async work"
                             flowable:async="true"
                             flowable:expression="${execution.setVariable('asyncExecuted', true)}"/>
                <sequenceFlow id="to-end" sourceRef="asyncWork" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key);
    }
}

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

import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationTimerCatchCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private ManagementService management;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-timer-catch-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
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
    void migratesIntermediateTimerCatchEventWithTargetJobEvidence() {
        ProcessDefinition source = deploy("timer-catch-source.bpmn20.xml", timerCatchXml("m5TimerCatch"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        Job sourceTimer = timerJob(instance.getId());
        assertNotNull(sourceTimer);
        assertEquals(source.getId(), sourceTimer.getProcessDefinitionId());
        assertEquals("waitTimer", sourceTimer.getElementId());
        Date sourceDueDate = sourceTimer.getDuedate();
        assertNotNull(sourceDueDate);

        ProcessDefinition target = deploy("timer-catch-target.bpmn20.xml", timerCatchXml("m5TimerCatch"));
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId());
        ProcessInstanceMigrationValidationResult validation = builder.validateMigration(instance.getId());
        assertTrue(validation.isMigrationValid(), validation.getValidationMessages().toString());
        builder.migrate(instance.getId());

        Job migratedTimer = timerJob(instance.getId());
        assertNotNull(migratedTimer);
        assertEquals(1, management.createTimerJobQuery().processInstanceId(instance.getId()).count());
        assertEquals(instance.getId(), migratedTimer.getProcessInstanceId());
        assertEquals(target.getId(), migratedTimer.getProcessDefinitionId());
        assertEquals("waitTimer", migratedTimer.getElementId());
        assertEquals(sourceDueDate, migratedTimer.getDuedate());
        assertEquals(target.getId(), runtime.createProcessInstanceQuery()
            .processInstanceId(instance.getId()).singleResult().getProcessDefinitionId());
    }

    private Job timerJob(String processInstanceId) {
        return management.createTimerJobQuery().processInstanceId(processInstanceId).singleResult();
    }

    private ProcessDefinition deploy(String resourceName, String xml) {
        Deployment deployment = repository.createDeployment().addString(resourceName, xml).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private static String timerCatchXml(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 timer catch migration evidence" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-timer" sourceRef="start" targetRef="waitTimer"/>
                <intermediateCatchEvent id="waitTimer" name="Wait until fixed date">
                  <timerEventDefinition>
                    <timeDate>2099-01-01T00:00:00Z</timeDate>
                  </timerEventDefinition>
                </intermediateCatchEvent>
                <sequenceFlow id="to-end" sourceRef="waitTimer" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key);
    }
}

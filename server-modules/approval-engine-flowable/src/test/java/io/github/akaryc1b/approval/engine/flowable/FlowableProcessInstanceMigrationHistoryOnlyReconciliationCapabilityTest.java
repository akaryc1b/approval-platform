package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
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

class FlowableProcessInstanceMigrationHistoryOnlyReconciliationCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private TaskService tasks;
    private HistoryService history;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-history-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setJdbcUsername("sa")
            .setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false)
            .buildProcessEngine();
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
    void classifiesUnknownIdentifierWithoutRuntimeOrHistoryAsMissingNoEvidence() {
        String unknownProcessInstanceId = UUID.randomUUID().toString();

        assertEquals(
            ReconciliationOutcome.MISSING_NO_EVIDENCE,
            classify(unknownProcessInstanceId, "source-definition", "target-definition")
        );
    }

    @Test
    void classifiesDeletedSourceInstanceAsHistoryOnlySourceTerminated() {
        ProcessDefinition source = deploy("history-source-terminated.bpmn20.xml", processXml("m5HistorySourceTerminated"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        assertNotNull(activeTask(instance.getId()));

        runtime.deleteProcessInstance(instance.getId(), "m5-competing-termination");

        assertNull(runtimeInstance(instance.getId()));
        HistoricProcessInstance historic = historicInstance(instance.getId());
        assertNotNull(historic);
        assertNotNull(historic.getEndTime());
        assertEquals("m5-competing-termination", historic.getDeleteReason());
        assertEquals(
            ReconciliationOutcome.HISTORY_ONLY_SOURCE_TERMINATED,
            classify(instance.getId(), source.getId(), "unused-target-definition")
        );
    }

    @Test
    void classifiesNormallyCompletedSourceInstanceAsHistoryOnlySourceCompleted() {
        ProcessDefinition source = deploy("history-source-completed.bpmn20.xml", processXml("m5HistorySourceCompleted"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());

        tasks.complete(activeTask(instance.getId()).getId());

        HistoricProcessInstance historic = historicInstance(instance.getId());
        assertNotNull(historic);
        assertNotNull(historic.getEndTime());
        assertNull(historic.getDeleteReason());
        assertEquals(1, history.createHistoricTaskInstanceQuery()
            .processInstanceId(instance.getId())
            .finished()
            .count());
        assertEquals(
            ReconciliationOutcome.HISTORY_ONLY_SOURCE_COMPLETED,
            classify(instance.getId(), source.getId(), "unused-target-definition")
        );
    }

    @Test
    void classifiesMigratedAndCompletedInstanceAsHistoryOnlyTargetCompleted() {
        ProcessDefinition source = deploy("history-target-completed-source.bpmn20.xml", processXml("m5HistoryTargetCompleted"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        ProcessDefinition target = deploy("history-target-completed-target.bpmn20.xml", processXml("m5HistoryTargetCompleted"));

        migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .migrate(instance.getId());
        tasks.complete(activeTask(instance.getId()).getId());

        HistoricProcessInstance historic = historicInstance(instance.getId());
        assertNotNull(historic);
        assertEquals(target.getId(), historic.getProcessDefinitionId());
        assertNotNull(historic.getEndTime());
        assertNull(historic.getDeleteReason());
        assertEquals(
            ReconciliationOutcome.HISTORY_ONLY_TARGET_COMPLETED,
            classify(instance.getId(), source.getId(), target.getId())
        );
    }

    @Test
    void classifiesMigratedAndDeletedInstanceAsHistoryOnlyTargetTerminated() {
        ProcessDefinition source = deploy("history-target-terminated-source.bpmn20.xml", processXml("m5HistoryTargetTerminated"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        ProcessDefinition target = deploy("history-target-terminated-target.bpmn20.xml", processXml("m5HistoryTargetTerminated"));

        migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .migrate(instance.getId());
        runtime.deleteProcessInstance(instance.getId(), "m5-terminated-after-migration");

        HistoricProcessInstance historic = historicInstance(instance.getId());
        assertNotNull(historic);
        assertEquals(target.getId(), historic.getProcessDefinitionId());
        assertEquals("m5-terminated-after-migration", historic.getDeleteReason());
        assertEquals(
            ReconciliationOutcome.HISTORY_ONLY_TARGET_TERMINATED,
            classify(instance.getId(), source.getId(), target.getId())
        );
    }

    private ReconciliationOutcome classify(
        String processInstanceId,
        String sourceDefinitionId,
        String targetDefinitionId
    ) {
        ProcessInstance runtimeInstance = runtimeInstance(processInstanceId);
        if (runtimeInstance != null) {
            return ReconciliationOutcome.RUNTIME_PRESENT;
        }

        HistoricProcessInstance historic = historicInstance(processInstanceId);
        if (historic == null) {
            return ReconciliationOutcome.MISSING_NO_EVIDENCE;
        }
        if (historic.getEndTime() == null) {
            return ReconciliationOutcome.RECONCILIATION_REQUIRED;
        }

        boolean terminated = historic.getDeleteReason() != null && !historic.getDeleteReason().isBlank();
        if (sourceDefinitionId.equals(historic.getProcessDefinitionId())) {
            return terminated
                ? ReconciliationOutcome.HISTORY_ONLY_SOURCE_TERMINATED
                : ReconciliationOutcome.HISTORY_ONLY_SOURCE_COMPLETED;
        }
        if (targetDefinitionId.equals(historic.getProcessDefinitionId())) {
            return terminated
                ? ReconciliationOutcome.HISTORY_ONLY_TARGET_TERMINATED
                : ReconciliationOutcome.HISTORY_ONLY_TARGET_COMPLETED;
        }
        return ReconciliationOutcome.RECONCILIATION_REQUIRED;
    }

    private ProcessInstance runtimeInstance(String processInstanceId) {
        return runtime.createProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .singleResult();
    }

    private HistoricProcessInstance historicInstance(String processInstanceId) {
        return history.createHistoricProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .singleResult();
    }

    private Task activeTask(String processInstanceId) {
        return tasks.createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskDefinitionKey("review")
            .singleResult();
    }

    private ProcessDefinition deploy(String resourceName, String xml) {
        Deployment deployment = repository.createDeployment().addString(resourceName, xml).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private static String processXml(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 history-only reconciliation evidence" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-review" sourceRef="start" targetRef="review"/>
                <userTask id="review" name="Review"/>
                <sequenceFlow id="to-end" sourceRef="review" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key);
    }

    private enum ReconciliationOutcome {
        RUNTIME_PRESENT,
        MISSING_NO_EVIDENCE,
        HISTORY_ONLY_SOURCE_COMPLETED,
        HISTORY_ONLY_SOURCE_TERMINATED,
        HISTORY_ONLY_TARGET_COMPLETED,
        HISTORY_ONLY_TARGET_TERMINATED,
        RECONCILIATION_REQUIRED
    }
}

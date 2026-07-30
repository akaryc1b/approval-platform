package io.github.akaryc1b.approval.engine.flowable;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort.VerificationCommand;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort.VerificationReadException;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableExactMigrationVerificationAdapterTest {

    private static final String TENANT = "tenant-d4-flowable";

    private ProcessEngine engine;
    private FlowableProcessInstanceVerificationAdapter adapter;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-d4-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setJdbcUsername("sa")
            .setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false)
            .buildProcessEngine();
        adapter = new FlowableProcessInstanceVerificationAdapter(
            engine.getRepositoryService(),
            engine.getRuntimeService(),
            engine.getTaskService(),
            engine.getManagementService(),
            engine.getHistoryService()
        );
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void readsExactSourceThenExactTargetForSimpleActiveUserTask() {
        ProcessDefinition source = deploy("d4-source.bpmn20.xml", userTaskXml("d4Simple", "review"));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceById(source.getId());
        ProcessDefinition target = deploy("d4-target.bpmn20.xml", userTaskXml("d4Simple", "review"));

        ApprovalMigrationEngineSnapshot before = adapter.readOne(command(instance));
        assertEquals(
            ExactClassification.EXACT_SOURCE_RUNTIME,
            ApprovalMigrationExactVerification.classify(before, source.getId(), target.getId())
        );
        assertFalse(before.truncated());
        assertEquals(List.of("review"), before.activeActivityIds());

        var builder = engine.getProcessMigrationService().createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId());
        assertTrue(builder.validateMigration(instance.getId()).isMigrationValid());
        builder.migrate(instance.getId());

        ApprovalMigrationEngineSnapshot after = adapter.readOne(command(instance));
        assertEquals(
            ExactClassification.EXACT_TARGET_RUNTIME,
            ApprovalMigrationExactVerification.classify(after, source.getId(), target.getId())
        );
        assertEquals(target.getId(), after.runtimeEngineDefinitionId());
        assertTrue(after.activeTasks().stream()
            .allMatch(task -> target.getId().equals(task.engineDefinitionId())));
    }

    @Test
    void detectsSourceBoundPendingJobAfterRuntimeMovesToTarget() {
        ProcessDefinition source = deploy("d4-async-source.bpmn20.xml", asyncXml("d4Async"));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceById(source.getId());
        assertEquals(1, engine.getManagementService().createJobQuery()
            .processInstanceId(instance.getId()).count());
        ProcessDefinition target = deploy("d4-async-target.bpmn20.xml", asyncXml("d4Async"));

        var builder = engine.getProcessMigrationService().createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId());
        assertTrue(builder.validateMigration(instance.getId()).isMigrationValid());
        builder.migrate(instance.getId());

        ApprovalMigrationEngineSnapshot snapshot = adapter.readOne(command(instance));
        assertEquals(target.getId(), snapshot.runtimeEngineDefinitionId());
        assertTrue(snapshot.jobs().stream()
            .anyMatch(job -> source.getId().equals(job.engineDefinitionId())));
        assertEquals(
            ExactClassification.MIXED_SOURCE_TARGET_EVIDENCE,
            ApprovalMigrationExactVerification.classify(snapshot, source.getId(), target.getId())
        );
    }

    @Test
    void readsTargetTerminalHistoryAfterMigratedInstanceCompletes() {
        ProcessDefinition source = deploy("d4-terminal-source.bpmn20.xml", userTaskXml("d4Terminal", "review"));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceById(source.getId());
        ProcessDefinition target = deploy("d4-terminal-target.bpmn20.xml", userTaskXml("d4Terminal", "review"));
        engine.getProcessMigrationService().createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .migrate(instance.getId());
        var task = engine.getTaskService().createTaskQuery()
            .processInstanceId(instance.getId())
            .singleResult();
        assertNotNull(task);
        engine.getTaskService().complete(task.getId());

        ApprovalMigrationEngineSnapshot snapshot = adapter.readOne(command(instance));

        assertFalse(snapshot.runtimePresent());
        assertTrue(snapshot.historyPresent());
        assertNotNull(snapshot.historicEndTime());
        assertEquals(
            ExactClassification.TARGET_HISTORY_TERMINAL,
            ApprovalMigrationExactVerification.classify(snapshot, source.getId(), target.getId())
        );
    }

    @Test
    void tenantSpoofingFailsWithoutLeakingOtherTenantEvidence() {
        ProcessDefinition source = deploy("d4-tenant-source.bpmn20.xml", userTaskXml("d4Tenant", "review"));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceById(source.getId());

        VerificationReadException error = assertThrows(
            VerificationReadException.class,
            () -> adapter.readOne(new VerificationCommand(
                "other-tenant",
                instance.getId(),
                List.of()
            ))
        );

        assertEquals("TENANT_MISMATCH", error.stableCode());
    }

    private VerificationCommand command(ProcessInstance instance) {
        return new VerificationCommand(TENANT, instance.getId(), List.of());
    }

    private ProcessDefinition deploy(String name, String xml) {
        Deployment deployment = engine.getRepositoryService().createDeployment()
            .tenantId(TENANT)
            .addString(name, xml)
            .deploy();
        return engine.getRepositoryService().createProcessDefinitionQuery()
            .deploymentId(deployment.getId())
            .singleResult();
    }

    private static String userTaskXml(String key, String taskId) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-task" sourceRef="start" targetRef="%s"/>
                <userTask id="%s" name="Review"/>
                <sequenceFlow id="to-end" sourceRef="%s" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key, taskId, taskId, taskId);
    }

    private static String asyncXml(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-async" sourceRef="start" targetRef="asyncWork"/>
                <serviceTask id="asyncWork" flowable:async="true"
                  flowable:expression="${execution.setVariable('done', true)}"/>
                <sequenceFlow id="to-end" sourceRef="asyncWork" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key);
    }
}

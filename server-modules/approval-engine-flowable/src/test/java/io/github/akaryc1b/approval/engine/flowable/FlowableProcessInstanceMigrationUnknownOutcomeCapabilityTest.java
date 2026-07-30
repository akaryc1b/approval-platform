package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.migration.ProcessInstanceMigrationBuilder;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowableProcessInstanceMigrationUnknownOutcomeCapabilityTest {

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private ManagementService management;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-unknown-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
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
    void classifiesLostResponseAfterCommittedMigrationAsTargetStateConfirmed() {
        ProcessDefinition source = deploy("unknown-target-source.bpmn20.xml", userTaskXml("m5UnknownTarget"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        ProcessDefinition target = deploy("unknown-target-target.bpmn20.xml", userTaskXml("m5UnknownTarget"));
        ProcessInstanceMigrationBuilder builder = migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId());

        assertThrows(SimulatedResponseLoss.class, () -> {
            builder.migrate(instance.getId());
            throw new SimulatedResponseLoss();
        });

        assertEquals(
            ReconciliationOutcome.TARGET_STATE_CONFIRMED,
            classify(
                snapshot(instance.getId()),
                source.getId(),
                Set.of("review"),
                target.getId(),
                Set.of("review")
            )
        );
    }

    @Test
    void classifiesLostResponseBeforeInvocationAsSourceStateConfirmed() {
        ProcessDefinition source = deploy("unknown-source-source.bpmn20.xml", userTaskXml("m5UnknownSource"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        ProcessDefinition target = deploy("unknown-source-target.bpmn20.xml", userTaskXml("m5UnknownSource"));

        assertThrows(SimulatedResponseLoss.class, () -> {
            throw new SimulatedResponseLoss();
        });

        assertEquals(
            ReconciliationOutcome.SOURCE_STATE_CONFIRMED,
            classify(
                snapshot(instance.getId()),
                source.getId(),
                Set.of("review"),
                target.getId(),
                Set.of("review")
            )
        );
    }

    @Test
    void classifiesTargetInstanceWithSourceDefinitionJobAsReconciliationRequired() {
        ProcessDefinition source = deploy("unknown-mismatch-source.bpmn20.xml", asyncProcessXml("m5UnknownMismatch"));
        ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
        Job sourceJob = management.createJobQuery().processInstanceId(instance.getId()).singleResult();
        assertNotNull(sourceJob);
        assertEquals(source.getId(), sourceJob.getProcessDefinitionId());

        ProcessDefinition target = deploy("unknown-mismatch-target.bpmn20.xml", asyncProcessXml("m5UnknownMismatch"));
        migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(target.getId())
            .migrate(instance.getId());

        assertEquals(
            ReconciliationOutcome.RECONCILIATION_REQUIRED,
            classify(
                snapshot(instance.getId()),
                source.getId(),
                Set.of("asyncWork"),
                target.getId(),
                Set.of("asyncWork")
            )
        );
    }

    private Snapshot snapshot(String processInstanceId) {
        ProcessInstance processInstance = runtime.createProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .singleResult();
        Set<String> activeActivities = processInstance == null
            ? Set.of()
            : new HashSet<>(runtime.getActiveActivityIds(processInstanceId));
        Set<String> dependentDefinitionIds = management.createJobQuery()
            .processInstanceId(processInstanceId)
            .list()
            .stream()
            .map(Job::getProcessDefinitionId)
            .collect(Collectors.toSet());
        return new Snapshot(
            processInstance == null ? null : processInstance.getProcessDefinitionId(),
            activeActivities,
            dependentDefinitionIds
        );
    }

    private static ReconciliationOutcome classify(
        Snapshot snapshot,
        String sourceDefinitionId,
        Set<String> sourceActivities,
        String targetDefinitionId,
        Set<String> targetActivities
    ) {
        if (sourceDefinitionId.equals(snapshot.processDefinitionId())
            && sourceActivities.equals(snapshot.activeActivities())
            && allDependenciesMatch(snapshot, sourceDefinitionId)) {
            return ReconciliationOutcome.SOURCE_STATE_CONFIRMED;
        }
        if (targetDefinitionId.equals(snapshot.processDefinitionId())
            && targetActivities.equals(snapshot.activeActivities())
            && allDependenciesMatch(snapshot, targetDefinitionId)) {
            return ReconciliationOutcome.TARGET_STATE_CONFIRMED;
        }
        return ReconciliationOutcome.RECONCILIATION_REQUIRED;
    }

    private static boolean allDependenciesMatch(Snapshot snapshot, String definitionId) {
        return snapshot.dependentDefinitionIds().stream().allMatch(definitionId::equals);
    }

    private ProcessDefinition deploy(String resourceName, String xml) {
        Deployment deployment = repository.createDeployment().addString(resourceName, xml).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private static String userTaskXml(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 unknown outcome evidence" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-review" sourceRef="start" targetRef="review"/>
                <userTask id="review" name="Review"/>
                <sequenceFlow id="to-end" sourceRef="review" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key);
    }

    private static String asyncProcessXml(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 unknown mismatch evidence" isExecutable="true">
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

    private record Snapshot(
        String processDefinitionId,
        Set<String> activeActivities,
        Set<String> dependentDefinitionIds
    ) {
    }

    private enum ReconciliationOutcome {
        SOURCE_STATE_CONFIRMED,
        TARGET_STATE_CONFIRMED,
        RECONCILIATION_REQUIRED
    }

    private static final class SimulatedResponseLoss extends RuntimeException {
    }
}

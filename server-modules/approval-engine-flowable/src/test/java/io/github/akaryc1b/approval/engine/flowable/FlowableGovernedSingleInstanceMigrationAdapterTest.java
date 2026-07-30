package io.github.akaryc1b.approval.engine.flowable;

import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort.DispatchDisposition;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort.MigrationCommand;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort.MigrationDispatchResult;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableGovernedSingleInstanceMigrationAdapterTest {

    private static final String TENANT = "tenant-d3";

    private ProcessEngine engine;
    private FlowableProcessInstanceMigrationAdapter adapter;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-d3-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setJdbcUsername("sa")
            .setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false)
            .buildProcessEngine();
        adapter = new FlowableProcessInstanceMigrationAdapter(
            engine.getRepositoryService(),
            engine.getRuntimeService(),
            engine.getTaskService(),
            engine.getManagementService(),
            engine.getProcessMigrationService()
        );
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void migratesExactlyOneSimpleActiveUserTaskAndStillRequiresVerification() {
        ProcessDefinition source = deploy("d3-source.bpmn20.xml", simpleXml("d3Simple", "review"));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceById(source.getId());
        ProcessDefinition target = deploy("d3-target.bpmn20.xml", simpleXml("d3Simple", "review"));

        MigrationDispatchResult result = adapter.migrateOne(command(instance, source, target));

        assertEquals(DispatchDisposition.CALL_RETURNED_AWAITING_VERIFICATION, result.disposition());
        assertTrue(result.engineCallAttempted());
        assertTrue(result.engineCallReturned());
        assertFalse(result.preDispatchSnapshot().truncated());
        assertEquals(List.of("review"), result.preDispatchSnapshot().activeActivityIds());
        assertEquals(target.getId(), engine.getRuntimeService().createProcessInstanceQuery()
            .processInstanceId(instance.getId()).singleResult().getProcessDefinitionId());
    }

    @Test
    void rejectsParallelShapeBeforeEngineCall() {
        ProcessDefinition source = deploy("d3-parallel-source.bpmn20.xml", parallelXml("d3Parallel"));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceById(source.getId());
        ProcessDefinition target = deploy("d3-parallel-target.bpmn20.xml", parallelXml("d3Parallel"));

        MigrationDispatchResult result = adapter.migrateOne(command(instance, source, target));

        assertEquals(DispatchDisposition.PRE_DISPATCH_REJECTED, result.disposition());
        assertFalse(result.engineCallAttempted());
        assertTrue(result.validationCodes().contains("UNSUPPORTED_SOURCE_MODEL_SHAPE"));
        assertEquals(source.getId(), engine.getRuntimeService().createProcessInstanceQuery()
            .processInstanceId(instance.getId()).singleResult().getProcessDefinitionId());
    }

    @Test
    void rejectsSuspendedRuntimeAndTargetDeploymentDriftBeforeEngineCall() {
        ProcessDefinition source = deploy("d3-suspended-source.bpmn20.xml", simpleXml("d3Suspend", "review"));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceById(source.getId());
        engine.getRuntimeService().suspendProcessInstanceById(instance.getId());
        ProcessDefinition target = deploy("d3-suspended-target.bpmn20.xml", simpleXml("d3Suspend", "review"));
        MigrationCommand drifted = new MigrationCommand(
            TENANT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            instance.getId(),
            source.getId(),
            "stale-deployment",
            target.getId(),
            List.of()
        );

        MigrationDispatchResult result = adapter.migrateOne(drifted);

        assertFalse(result.engineCallAttempted());
        assertTrue(result.validationCodes().contains("SUSPENDED_RUNTIME"));
        assertTrue(result.validationCodes().contains("TARGET_DEPLOYMENT_DRIFT"));
    }

    @Test
    void rejectsPendingAsyncJobBeforeEngineCall() {
        ProcessDefinition source = deploy("d3-async-source.bpmn20.xml", asyncXml("d3Async"));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceById(source.getId());
        ProcessDefinition target = deploy("d3-async-target.bpmn20.xml", asyncXml("d3Async"));

        MigrationDispatchResult result = adapter.migrateOne(command(instance, source, target));

        assertFalse(result.engineCallAttempted());
        assertTrue(result.validationCodes().contains("UNSAFE_JOB_OR_TIMER_STATE"));
        assertEquals(source.getId(), engine.getRuntimeService().createProcessInstanceQuery()
            .processInstanceId(instance.getId()).singleResult().getProcessDefinitionId());
    }

    private MigrationCommand command(
        ProcessInstance instance,
        ProcessDefinition source,
        ProcessDefinition target
    ) {
        return new MigrationCommand(
            TENANT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            instance.getId(),
            source.getId(),
            target.getDeploymentId(),
            target.getId(),
            List.of()
        );
    }

    private ProcessDefinition deploy(String resourceName, String xml) {
        Deployment deployment = engine.getRepositoryService().createDeployment()
            .tenantId(TENANT)
            .addString(resourceName, xml)
            .deploy();
        return engine.getRepositoryService().createProcessDefinitionQuery()
            .deploymentId(deployment.getId())
            .singleResult();
    }

    private static String simpleXml(String key, String taskId) {
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

    private static String parallelXml(String key) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-split" sourceRef="start" targetRef="split"/>
                <parallelGateway id="split"/>
                <sequenceFlow id="to-a" sourceRef="split" targetRef="taskA"/>
                <sequenceFlow id="to-b" sourceRef="split" targetRef="taskB"/>
                <userTask id="taskA"/><userTask id="taskB"/>
                <sequenceFlow id="a-join" sourceRef="taskA" targetRef="join"/>
                <sequenceFlow id="b-join" sourceRef="taskB" targetRef="join"/>
                <parallelGateway id="join"/>
                <sequenceFlow id="to-end" sourceRef="join" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key);
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

package io.github.akaryc1b.approval.engine.flowable;

import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.migration.ActivityMigrationMapping;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowableProcessInstanceMigrationConcurrentCommandCapabilityTest {

    private static final int TRIALS = 20;

    private ProcessEngine engine;
    private RepositoryService repository;
    private RuntimeService runtime;
    private TaskService tasks;
    private HistoryService history;
    private ProcessMigrationService migrations;

    @BeforeEach
    void setUp() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:m5-concurrent-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
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
    void concurrentMigrationAndTaskCompletionProducesOnlyClosedSerializableOutcomes() throws Exception {
        ProcessDefinition source = deploy(
            "concurrent-complete-source.bpmn20.xml",
            processXml("m5ConcurrentCompletion", "review")
        );
        ProcessDefinition target = deploy(
            "concurrent-complete-target.bpmn20.xml",
            processXml("m5ConcurrentCompletion", "review")
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        EnumMap<CompletionRaceOutcome, Integer> observed = new EnumMap<>(CompletionRaceOutcome.class);
        try {
            for (int trial = 0; trial < TRIALS; trial++) {
                ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
                Task task = activeTask(instance.getId(), "review");
                assertNotNull(task);

                RaceResult race = race(
                    executor,
                    () -> migrations.createProcessInstanceMigrationBuilder()
                        .migrateToProcessDefinition(target.getId())
                        .migrate(instance.getId()),
                    () -> tasks.complete(task.getId())
                );

                CompletionSnapshot snapshot = completionSnapshot(instance.getId());
                CompletionRaceOutcome outcome = classifyCompletionRace(
                    source.getId(),
                    target.getId(),
                    race,
                    snapshot
                );
                assertNotEquals(
                    CompletionRaceOutcome.UNEXPECTED,
                    outcome,
                    () -> "unexpected migration/complete result: race=" + race + ", snapshot=" + snapshot
                );
                observed.merge(outcome, 1, Integer::sum);
                deleteIfRunning(instance.getId());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(TRIALS, observed.values().stream().mapToInt(Integer::intValue).sum());
        System.out.println("M5 concurrent migration/complete outcomes=" + observed);
    }

    @Test
    void concurrentDuplicateMigrationsProduceOneTargetTaskWithoutMixedDefinitionEvidence() throws Exception {
        ProcessDefinition source = deploy(
            "concurrent-duplicate-source.bpmn20.xml",
            processXml("m5ConcurrentDuplicate", "sourceReview")
        );
        ProcessDefinition target = deploy(
            "concurrent-duplicate-target.bpmn20.xml",
            processXml("m5ConcurrentDuplicate", "targetReview")
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        EnumMap<DuplicateRaceOutcome, Integer> observed = new EnumMap<>(DuplicateRaceOutcome.class);
        try {
            for (int trial = 0; trial < TRIALS; trial++) {
                ProcessInstance instance = runtime.startProcessInstanceById(source.getId());
                assertNotNull(activeTask(instance.getId(), "sourceReview"));

                RaceResult race = race(
                    executor,
                    () -> migrateRenamedTask(instance.getId(), target.getId()),
                    () -> migrateRenamedTask(instance.getId(), target.getId())
                );

                DuplicateSnapshot snapshot = duplicateSnapshot(instance.getId());
                DuplicateRaceOutcome outcome = classifyDuplicateRace(target.getId(), race, snapshot);
                assertNotEquals(
                    DuplicateRaceOutcome.UNEXPECTED,
                    outcome,
                    () -> "unexpected duplicate migration result: race=" + race + ", snapshot=" + snapshot
                );
                observed.merge(outcome, 1, Integer::sum);
                deleteIfRunning(instance.getId());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(TRIALS, observed.values().stream().mapToInt(Integer::intValue).sum());
        System.out.println("M5 concurrent duplicate migration outcomes=" + observed);
    }

    private void migrateRenamedTask(String processInstanceId, String targetDefinitionId) {
        migrations.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(targetDefinitionId)
            .addActivityMigrationMapping(
                ActivityMigrationMapping.createMappingFor("sourceReview", "targetReview")
            )
            .migrate(processInstanceId);
    }

    private RaceResult race(
        ExecutorService executor,
        ThrowingRunnable firstCommand,
        ThrowingRunnable secondCommand
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<CommandResult> first = executor.submit(() -> invokeAtGate(ready, start, firstCommand));
        Future<CommandResult> second = executor.submit(() -> invokeAtGate(ready, start, secondCommand));

        assertTrue(ready.await(10, TimeUnit.SECONDS), "commands did not reach the concurrent start gate");
        start.countDown();

        return new RaceResult(
            first.get(20, TimeUnit.SECONDS),
            second.get(20, TimeUnit.SECONDS)
        );
    }

    private static CommandResult invokeAtGate(
        CountDownLatch ready,
        CountDownLatch start,
        ThrowingRunnable command
    ) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                return CommandResult.failure(new IllegalStateException("concurrent start gate timed out"));
            }
            command.run();
            return CommandResult.success();
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return CommandResult.failure(error);
        }
    }

    private CompletionRaceOutcome classifyCompletionRace(
        String sourceDefinitionId,
        String targetDefinitionId,
        RaceResult race,
        CompletionSnapshot snapshot
    ) {
        if (race.first().succeeded()
            && race.second().succeeded()
            && snapshot.runtimeDefinitionId() == null
            && targetDefinitionId.equals(snapshot.historicDefinitionId())
            && snapshot.historicEnded()
            && snapshot.deleteReason() == null
            && snapshot.activeTaskCount() == 0
            && snapshot.finishedHistoricTaskCount() == 1) {
            return CompletionRaceOutcome.BOTH_SUCCEEDED_TARGET_COMPLETED;
        }
        if (!race.first().succeeded()
            && race.second().succeeded()
            && snapshot.runtimeDefinitionId() == null
            && sourceDefinitionId.equals(snapshot.historicDefinitionId())
            && snapshot.historicEnded()
            && snapshot.deleteReason() == null
            && snapshot.activeTaskCount() == 0
            && snapshot.finishedHistoricTaskCount() == 1) {
            return CompletionRaceOutcome.COMPLETION_WON_SOURCE_COMPLETED;
        }
        if (race.first().succeeded()
            && !race.second().succeeded()
            && targetDefinitionId.equals(snapshot.runtimeDefinitionId())
            && targetDefinitionId.equals(snapshot.historicDefinitionId())
            && !snapshot.historicEnded()
            && snapshot.activeTaskCount() == 1
            && snapshot.activeTaskDefinitionKeys().equals(Set.of("review"))) {
            return CompletionRaceOutcome.MIGRATION_WON_TARGET_ACTIVE_AFTER_COMPLETE_CONFLICT;
        }
        return CompletionRaceOutcome.UNEXPECTED;
    }

    private DuplicateRaceOutcome classifyDuplicateRace(
        String targetDefinitionId,
        RaceResult race,
        DuplicateSnapshot snapshot
    ) {
        boolean targetStateIsCoherent = targetDefinitionId.equals(snapshot.runtimeDefinitionId())
            && targetDefinitionId.equals(snapshot.historicDefinitionId())
            && !snapshot.historicEnded()
            && snapshot.activeTaskCount() == 1
            && snapshot.activeTaskDefinitionKeys().equals(Set.of("targetReview"));
        if (!targetStateIsCoherent) return DuplicateRaceOutcome.UNEXPECTED;
        if (race.first().succeeded() && race.second().succeeded()) {
            return DuplicateRaceOutcome.BOTH_MIGRATIONS_ACCEPTED;
        }
        if (race.first().succeeded() ^ race.second().succeeded()) {
            return DuplicateRaceOutcome.ONE_MIGRATION_WON;
        }
        return DuplicateRaceOutcome.UNEXPECTED;
    }

    private CompletionSnapshot completionSnapshot(String processInstanceId) {
        ProcessInstance runtimeInstance = runtimeInstance(processInstanceId);
        HistoricProcessInstance historic = historicInstance(processInstanceId);
        List<Task> activeTasks = tasks.createTaskQuery().processInstanceId(processInstanceId).list();
        return new CompletionSnapshot(
            runtimeInstance == null ? null : runtimeInstance.getProcessDefinitionId(),
            historic == null ? null : historic.getProcessDefinitionId(),
            historic != null && historic.getEndTime() != null,
            historic == null ? null : historic.getDeleteReason(),
            activeTasks.size(),
            activeTasks.stream().map(Task::getTaskDefinitionKey).collect(Collectors.toSet()),
            history.createHistoricTaskInstanceQuery().processInstanceId(processInstanceId).finished().count()
        );
    }

    private DuplicateSnapshot duplicateSnapshot(String processInstanceId) {
        ProcessInstance runtimeInstance = runtimeInstance(processInstanceId);
        HistoricProcessInstance historic = historicInstance(processInstanceId);
        List<Task> activeTasks = tasks.createTaskQuery().processInstanceId(processInstanceId).list();
        return new DuplicateSnapshot(
            runtimeInstance == null ? null : runtimeInstance.getProcessDefinitionId(),
            historic == null ? null : historic.getProcessDefinitionId(),
            historic != null && historic.getEndTime() != null,
            activeTasks.size(),
            activeTasks.stream().map(Task::getTaskDefinitionKey).collect(Collectors.toSet())
        );
    }

    private ProcessInstance runtimeInstance(String processInstanceId) {
        return runtime.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
    }

    private HistoricProcessInstance historicInstance(String processInstanceId) {
        return history.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
    }

    private Task activeTask(String processInstanceId, String taskDefinitionKey) {
        return tasks.createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskDefinitionKey(taskDefinitionKey)
            .singleResult();
    }

    private void deleteIfRunning(String processInstanceId) {
        if (runtimeInstance(processInstanceId) != null) {
            runtime.deleteProcessInstance(processInstanceId, "m5-concurrency-test-cleanup");
        }
    }

    private ProcessDefinition deploy(String resourceName, String xml) {
        Deployment deployment = repository.createDeployment().addString(resourceName, xml).deploy();
        return repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    private static String processXml(String key, String taskId) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="https://github.com/akaryc1b/approval-platform/m5">
              <process id="%s" name="M5 concurrent migration evidence" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="to-review" sourceRef="start" targetRef="%s"/>
                <userTask id="%s" name="Review"/>
                <sequenceFlow id="to-end" sourceRef="%s" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(key, taskId, taskId, taskId);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record CommandResult(boolean succeeded, String errorType, String errorMessage) {
        private static CommandResult success() {
            return new CommandResult(true, null, null);
        }

        private static CommandResult failure(Throwable error) {
            return new CommandResult(false, error.getClass().getName(), error.getMessage());
        }
    }

    private record RaceResult(CommandResult first, CommandResult second) {
    }

    private record CompletionSnapshot(
        String runtimeDefinitionId,
        String historicDefinitionId,
        boolean historicEnded,
        String deleteReason,
        int activeTaskCount,
        Set<String> activeTaskDefinitionKeys,
        long finishedHistoricTaskCount
    ) {
    }

    private record DuplicateSnapshot(
        String runtimeDefinitionId,
        String historicDefinitionId,
        boolean historicEnded,
        int activeTaskCount,
        Set<String> activeTaskDefinitionKeys
    ) {
    }

    private enum CompletionRaceOutcome {
        BOTH_SUCCEEDED_TARGET_COMPLETED,
        COMPLETION_WON_SOURCE_COMPLETED,
        MIGRATION_WON_TARGET_ACTIVE_AFTER_COMPLETE_CONFLICT,
        UNEXPECTED
    }

    private enum DuplicateRaceOutcome {
        BOTH_MIGRATIONS_ACCEPTED,
        ONE_MIGRATION_WON,
        UNEXPECTED
    }
}

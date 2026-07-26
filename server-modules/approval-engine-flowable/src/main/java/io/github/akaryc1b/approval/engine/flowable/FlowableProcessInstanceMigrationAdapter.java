package io.github.akaryc1b.approval.engine.flowable;

import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.migration.ActivityMigrationMapping;
import org.flowable.engine.migration.ProcessInstanceMigrationBuilder;
import org.flowable.engine.migration.ProcessInstanceMigrationValidationResult;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Flowable 8 public-API adapter for one exact, governed process-instance migration. */
public final class FlowableProcessInstanceMigrationAdapter implements ProcessInstanceMigrationPort {

    private static final int MAX_ACTIVE_EVIDENCE = 64;

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final ManagementService managementService;
    private final ProcessMigrationService migrationService;

    public FlowableProcessInstanceMigrationAdapter(
        RepositoryService repositoryService,
        RuntimeService runtimeService,
        TaskService taskService,
        ManagementService managementService,
        ProcessMigrationService migrationService
    ) {
        this.repositoryService = Objects.requireNonNull(
            repositoryService,
            "repositoryService must not be null"
        );
        this.runtimeService = Objects.requireNonNull(runtimeService, "runtimeService must not be null");
        this.taskService = Objects.requireNonNull(taskService, "taskService must not be null");
        this.managementService = Objects.requireNonNull(
            managementService,
            "managementService must not be null"
        );
        this.migrationService = Objects.requireNonNull(
            migrationService,
            "migrationService must not be null"
        );
    }

    @Override
    public MigrationDispatchResult migrateOne(MigrationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SnapshotAssessment assessment = assess(command);
        if (!assessment.validationCodes().isEmpty()) {
            return new MigrationDispatchResult(
                DispatchDisposition.PRE_DISPATCH_REJECTED,
                false,
                false,
                assessment.snapshot(),
                assessment.validationCodes(),
                "engine migration was rejected before dispatch"
            );
        }

        ProcessInstanceMigrationBuilder builder = migrationService
            .createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(command.targetEngineDefinitionId());
        for (ActivityMapping mapping : command.activityMappings()) {
            builder.addActivityMigrationMapping(
                ActivityMigrationMapping.createMappingFor(
                    mapping.sourceActivityId(),
                    mapping.targetActivityId()
                )
            );
        }

        ProcessInstanceMigrationValidationResult validation;
        try {
            validation = builder.validateMigration(command.engineInstanceId());
        } catch (RuntimeException exception) {
            return new MigrationDispatchResult(
                DispatchDisposition.ENGINE_REJECTED,
                false,
                false,
                assessment.snapshot(),
                List.of("ENGINE_VALIDATION_EXCEPTION"),
                "public engine migration validation rejected the request"
            );
        }
        if (!validation.isMigrationValid()) {
            return new MigrationDispatchResult(
                DispatchDisposition.ENGINE_REJECTED,
                false,
                false,
                assessment.snapshot(),
                List.of("ENGINE_VALIDATION_REJECTED"),
                "public engine migration validation rejected the request"
            );
        }

        try {
            builder.migrate(command.engineInstanceId());
        } catch (RuntimeException exception) {
            throw new AmbiguousMigrationDispatchException(
                "ENGINE_CALL_INCOMPLETE",
                "engine migration call did not provide an authoritative response",
                true,
                exception
            );
        }
        return new MigrationDispatchResult(
            DispatchDisposition.CALL_RETURNED_AWAITING_VERIFICATION,
            true,
            true,
            assessment.snapshot(),
            List.of(),
            "engine migration call returned; exact verification is still required"
        );
    }

    private SnapshotAssessment assess(MigrationCommand command) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
            .processInstanceId(command.engineInstanceId())
            .singleResult();
        if (instance == null) {
            return new SnapshotAssessment(
                missingSnapshot(command),
                List.of("RUNTIME_NOT_FOUND")
            );
        }

        ProcessDefinition sourceDefinition = repositoryService.createProcessDefinitionQuery()
            .processDefinitionId(instance.getProcessDefinitionId())
            .singleResult();
        ProcessDefinition targetDefinition = repositoryService.createProcessDefinitionQuery()
            .processDefinitionId(command.targetEngineDefinitionId())
            .singleResult();
        List<String> activeActivities = bounded(runtimeService.getActiveActivityIds(instance.getId()));
        List<Task> activeTasks = taskService.createTaskQuery()
            .processInstanceId(instance.getId())
            .active()
            .list();
        boolean truncated = activeActivities.size() > MAX_ACTIVE_EVIDENCE
            || activeTasks.size() > MAX_ACTIVE_EVIDENCE;
        List<String> boundedActivities = activeActivities.stream()
            .limit(MAX_ACTIVE_EVIDENCE)
            .sorted()
            .toList();
        List<String> taskKeys = activeTasks.stream()
            .map(Task::getTaskDefinitionKey)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .limit(MAX_ACTIVE_EVIDENCE)
            .toList();
        long executableJobs = managementService.createJobQuery()
            .processInstanceId(instance.getId())
            .count();
        long timerJobs = managementService.createTimerJobQuery()
            .processInstanceId(instance.getId())
            .count();
        long suspendedJobs = managementService.createSuspendedJobQuery()
            .processInstanceId(instance.getId())
            .count();
        long deadLetterJobs = managementService.createDeadLetterJobQuery()
            .processInstanceId(instance.getId())
            .count();

        String observedDeployment = sourceDefinition == null ? null : sourceDefinition.getDeploymentId();
        BoundedRuntimeSnapshot snapshot = new BoundedRuntimeSnapshot(
            true,
            instance.getProcessDefinitionId(),
            observedDeployment,
            instance.isSuspended(),
            boundedActivities,
            taskKeys,
            executableJobs,
            timerJobs,
            suspendedJobs,
            deadLetterJobs,
            truncated,
            snapshotHash(
                command,
                instance.getProcessDefinitionId(),
                observedDeployment,
                instance.isSuspended(),
                boundedActivities,
                taskKeys,
                executableJobs,
                timerJobs,
                suspendedJobs,
                deadLetterJobs,
                truncated
            )
        );

        ArrayList<String> failures = new ArrayList<>();
        if (!command.tenantId().equals(instance.getTenantId())) {
            failures.add("TENANT_MISMATCH");
        }
        if (!command.sourceEngineDefinitionId().equals(instance.getProcessDefinitionId())) {
            failures.add("STALE_SOURCE_DEFINITION");
        }
        if (sourceDefinition == null) {
            failures.add("SOURCE_DEFINITION_NOT_FOUND");
        }
        if (targetDefinition == null) {
            failures.add("TARGET_DEFINITION_NOT_FOUND");
        } else {
            if (!command.tenantId().equals(targetDefinition.getTenantId())) {
                failures.add("TARGET_TENANT_MISMATCH");
            }
            if (!command.targetEngineDeploymentId().equals(targetDefinition.getDeploymentId())) {
                failures.add("TARGET_DEPLOYMENT_DRIFT");
            }
        }
        if (instance.isSuspended()) {
            failures.add("SUSPENDED_RUNTIME");
        }
        if (truncated) {
            failures.add("PRE_DISPATCH_SNAPSHOT_TRUNCATED");
        }
        if (boundedActivities.size() != 1) {
            failures.add("UNSUPPORTED_ACTIVE_ACTIVITY_SHAPE");
        }
        if (activeTasks.size() != 1 || taskKeys.size() != 1) {
            failures.add("UNSUPPORTED_ACTIVE_TASK_SHAPE");
        }
        if (boundedActivities.size() == 1 && taskKeys.size() == 1
            && !boundedActivities.getFirst().equals(taskKeys.getFirst())) {
            failures.add("STALE_TASK_KEY");
        }
        if (executableJobs != 0 || timerJobs != 0 || suspendedJobs != 0 || deadLetterJobs != 0) {
            failures.add("UNSAFE_JOB_OR_TIMER_STATE");
        }
        if (sourceDefinition != null && unsupportedModel(sourceDefinition.getId())) {
            failures.add("UNSUPPORTED_SOURCE_MODEL_SHAPE");
        }
        if (targetDefinition != null && unsupportedModel(targetDefinition.getId())) {
            failures.add("UNSUPPORTED_TARGET_MODEL_SHAPE");
        }
        validateActiveMapping(command, boundedActivities, failures);
        return new SnapshotAssessment(snapshot, failures.stream().distinct().sorted().toList());
    }

    private boolean unsupportedModel(String processDefinitionId) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        if (model == null || model.getMainProcess() == null) {
            return true;
        }
        if (!model.getMainProcess().findFlowElementsOfType(ParallelGateway.class, true).isEmpty()
            || !model.getMainProcess().findFlowElementsOfType(CallActivity.class, true).isEmpty()
            || !model.getMainProcess().findFlowElementsOfType(SubProcess.class, true).isEmpty()) {
            return true;
        }
        return model.getMainProcess().findFlowElementsOfType(Activity.class, true).stream()
            .anyMatch(activity -> activity.getLoopCharacteristics() != null);
    }

    private static void validateActiveMapping(
        MigrationCommand command,
        List<String> activeActivities,
        List<String> failures
    ) {
        if (activeActivities.size() != 1) {
            return;
        }
        String active = activeActivities.getFirst();
        long mappings = command.activityMappings().stream()
            .filter(mapping -> mapping.sourceActivityId().equals(active))
            .count();
        if (mappings > 1) {
            failures.add("AMBIGUOUS_ACTIVITY_MAPPING");
        }
        if (!command.activityMappings().isEmpty() && mappings == 0) {
            failures.add("STALE_ACTIVITY_MAPPING");
        }
    }

    private static List<String> bounded(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static BoundedRuntimeSnapshot missingSnapshot(MigrationCommand command) {
        return new BoundedRuntimeSnapshot(
            false,
            null,
            null,
            false,
            List.of(),
            List.of(),
            0,
            0,
            0,
            0,
            false,
            sha256("missing|" + command.tenantId() + '|' + command.engineInstanceId())
        );
    }

    private static String snapshotHash(
        MigrationCommand command,
        String definitionId,
        String deploymentId,
        boolean suspended,
        List<String> activities,
        List<String> taskKeys,
        long jobs,
        long timers,
        long suspendedJobs,
        long deadLetters,
        boolean truncated
    ) {
        return sha256(String.join(
            "|",
            "m5-engine-snapshot-v1",
            command.tenantId(),
            command.engineInstanceId(),
            definitionId,
            deploymentId == null ? "" : deploymentId,
            Boolean.toString(suspended),
            String.join(",", activities),
            String.join(",", taskKeys),
            Long.toString(jobs),
            Long.toString(timers),
            Long.toString(suspendedJobs),
            Long.toString(deadLetters),
            Boolean.toString(truncated)
        ));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record SnapshotAssessment(
        BoundedRuntimeSnapshot snapshot,
        List<String> validationCodes
    ) {
    }
}

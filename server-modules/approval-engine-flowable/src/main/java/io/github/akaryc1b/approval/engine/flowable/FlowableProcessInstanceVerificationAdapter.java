package io.github.akaryc1b.approval.engine.flowable;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.DefinitionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.JobEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.SubscriptionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.TaskEvidence;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Flowable 8 public-API bounded readback for one exact process instance. */
public final class FlowableProcessInstanceVerificationAdapter
    implements ProcessInstanceVerificationPort {

    private static final int MAX_ITEMS = 64;
    private static final int READ_LIMIT = MAX_ITEMS + 1;

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final ManagementService managementService;
    private final HistoryService historyService;

    public FlowableProcessInstanceVerificationAdapter(
        RepositoryService repositoryService,
        RuntimeService runtimeService,
        TaskService taskService,
        ManagementService managementService,
        HistoryService historyService
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
        this.historyService = Objects.requireNonNull(historyService, "historyService must not be null");
    }

    @Override
    public ApprovalMigrationEngineSnapshot readOne(VerificationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        try {
            return readBounded(command);
        } catch (VerificationReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VerificationReadException(
                "ENGINE_READ_FAILURE",
                "public engine verification read failed",
                exception
            );
        }
    }

    private ApprovalMigrationEngineSnapshot readBounded(VerificationCommand command) {
        ProcessInstance runtime = runtimeService.createProcessInstanceQuery()
            .processInstanceId(command.engineInstanceId())
            .singleResult();
        HistoricProcessInstance history = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(command.engineInstanceId())
            .singleResult();
        requireTenant(command.tenantId(), runtime, history);

        if (runtime == null) {
            return historyOnly(command, history);
        }

        ProcessDefinition runtimeDefinition = repositoryService.createProcessDefinitionQuery()
            .processDefinitionId(runtime.getProcessDefinitionId())
            .singleResult();
        String deploymentId = runtimeDefinition == null ? null : runtimeDefinition.getDeploymentId();

        List<String> activeActivitiesRaw = safe(runtimeService.getActiveActivityIds(runtime.getId()));
        List<Execution> executionsRaw = runtimeService.createExecutionQuery()
            .processInstanceId(runtime.getId())
            .listPage(0, READ_LIMIT);
        List<Task> tasksRaw = taskService.createTaskQuery()
            .processInstanceId(runtime.getId())
            .active()
            .listPage(0, READ_LIMIT);
        List<Job> executableJobs = managementService.createJobQuery()
            .processInstanceId(runtime.getId())
            .listPage(0, READ_LIMIT);
        List<Job> timerJobs = List.copyOf(managementService.createTimerJobQuery()
            .processInstanceId(runtime.getId())
            .listPage(0, READ_LIMIT));
        List<Job> suspendedJobs = List.copyOf(managementService.createSuspendedJobQuery()
            .processInstanceId(runtime.getId())
            .listPage(0, READ_LIMIT));
        List<Job> deadLetterJobs = List.copyOf(managementService.createDeadLetterJobQuery()
            .processInstanceId(runtime.getId())
            .listPage(0, READ_LIMIT));
        var subscriptionsRaw = runtimeService.createEventSubscriptionQuery()
            .processInstanceId(runtime.getId())
            .listPage(0, READ_LIMIT);
        List<HistoricTaskInstance> historicTasksRaw = historyService.createHistoricTaskInstanceQuery()
            .processInstanceId(runtime.getId())
            .listPage(0, READ_LIMIT);

        boolean truncated = exceeds(activeActivitiesRaw)
            || exceeds(executionsRaw) || exceeds(tasksRaw) || exceeds(executableJobs)
            || exceeds(timerJobs) || exceeds(suspendedJobs) || exceeds(deadLetterJobs)
            || exceeds(subscriptionsRaw) || exceeds(historicTasksRaw);

        List<Execution> executions = trim(executionsRaw);
        Map<String, String> definitionByExecution = new HashMap<>();
        List<DefinitionEvidence> executionEvidence = executions.stream()
            .map(execution -> {
                if (execution.getId() != null) {
                    definitionByExecution.put(execution.getId(), execution.getProcessDefinitionId());
                }
                return new DefinitionEvidence(
                    "EXECUTION",
                    hashId(execution.getId()),
                    execution.getProcessDefinitionId()
                );
            })
            .toList();
        List<TaskEvidence> taskEvidence = trim(tasksRaw).stream()
            .map(task -> new TaskEvidence(
                hashId(task.getId()),
                value(task.getTaskDefinitionKey(), "UNKNOWN_TASK_KEY"),
                task.getProcessDefinitionId(),
                task.isSuspended()
            ))
            .toList();

        ArrayList<JobEvidence> jobs = new ArrayList<>();
        appendJobs(jobs, trim(executableJobs), "EXECUTABLE", "PENDING");
        appendJobs(jobs, trim(timerJobs), "TIMER", "WAITING");
        appendJobs(jobs, trim(suspendedJobs), "SUSPENDED", "SUSPENDED");
        appendJobs(jobs, trim(deadLetterJobs), "DEAD_LETTER", "FAILED");
        if (jobs.size() > MAX_ITEMS) {
            truncated = true;
            jobs = new ArrayList<>(jobs.subList(0, MAX_ITEMS));
        }

        List<SubscriptionEvidence> subscriptions = trim(subscriptionsRaw).stream()
            .map(subscription -> new SubscriptionEvidence(
                hashId(subscription.getId()),
                value(subscription.getEventType(), "UNKNOWN_EVENT"),
                subscription.getActivityId(),
                definitionByExecution.get(subscription.getExecutionId())
            ))
            .toList();

        IdentityEvidence identityEvidence = identityEvidence(trim(tasksRaw));
        truncated = truncated || identityEvidence.truncated();
        VariableEvidence variableEvidence = variableEvidence(runtime.getId(), command.allowlistedVariableNames());
        truncated = truncated || variableEvidence.unsupportedValue();

        List<TaskEvidence> historicTasks = trim(historicTasksRaw).stream()
            .map(task -> new TaskEvidence(
                hashId(task.getId()),
                value(task.getTaskDefinitionKey(), "UNKNOWN_TASK_KEY"),
                task.getProcessDefinitionId(),
                false
            ))
            .toList();
        List<String> activeActivities = trim(activeActivitiesRaw).stream().sorted().toList();
        String deleteReason = bounded(history == null ? null : history.getDeleteReason(), 512);

        ApprovalMigrationEngineSnapshot unsigned = new ApprovalMigrationEngineSnapshot(
            true,
            null,
            true,
            runtime.getProcessDefinitionId(),
            deploymentId,
            runtime.isSuspended(),
            activeActivities,
            executionEvidence,
            taskEvidence,
            jobs,
            subscriptions,
            variableEvidence.hashes(),
            identityEvidence.hashes(),
            history != null,
            history == null ? null : history.getProcessDefinitionId(),
            history == null || history.getEndTime() == null ? null : history.getEndTime().toInstant(),
            deleteReason,
            historicTasks,
            truncated,
            "0".repeat(64)
        );
        return withHash(unsigned);
    }

    private ApprovalMigrationEngineSnapshot historyOnly(
        VerificationCommand command,
        HistoricProcessInstance history
    ) {
        if (history == null) {
            return withHash(new ApprovalMigrationEngineSnapshot(
                true, null, false, null, null, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, null, null, null, List.of(), false, "0".repeat(64)
            ));
        }
        List<HistoricTaskInstance> historicTasksRaw = historyService.createHistoricTaskInstanceQuery()
            .processInstanceId(command.engineInstanceId())
            .listPage(0, READ_LIMIT);
        boolean truncated = exceeds(historicTasksRaw);
        List<TaskEvidence> historicTasks = trim(historicTasksRaw).stream()
            .map(task -> new TaskEvidence(
                hashId(task.getId()),
                value(task.getTaskDefinitionKey(), "UNKNOWN_TASK_KEY"),
                task.getProcessDefinitionId(),
                false
            ))
            .toList();
        return withHash(new ApprovalMigrationEngineSnapshot(
            true,
            null,
            false,
            null,
            null,
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            true,
            history.getProcessDefinitionId(),
            history.getEndTime() == null ? null : history.getEndTime().toInstant(),
            bounded(history.getDeleteReason(), 512),
            historicTasks,
            truncated,
            "0".repeat(64)
        ));
    }

    private void requireTenant(
        String tenantId,
        ProcessInstance runtime,
        HistoricProcessInstance history
    ) {
        if (runtime != null && !tenantId.equals(value(runtime.getTenantId(), ""))) {
            throw new VerificationReadException(
                "TENANT_MISMATCH",
                "engine runtime verification tenant mismatch",
                null
            );
        }
        if (history != null && !tenantId.equals(value(history.getTenantId(), ""))) {
            throw new VerificationReadException(
                "TENANT_MISMATCH",
                "engine history verification tenant mismatch",
                null
            );
        }
    }

    private void appendJobs(
        List<JobEvidence> target,
        Collection<? extends Job> jobs,
        String kind,
        String state
    ) {
        jobs.stream()
            .map(job -> new JobEvidence(
                hashId(job.getId()),
                kind,
                state,
                job.getProcessDefinitionId(),
                job.getElementId()
            ))
            .forEach(target::add);
    }

    private IdentityEvidence identityEvidence(List<Task> tasks) {
        ArrayList<String> hashes = new ArrayList<>();
        boolean truncated = false;
        for (Task task : tasks) {
            List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
            for (IdentityLink link : links) {
                if (hashes.size() == MAX_ITEMS) {
                    truncated = true;
                    break;
                }
                hashes.add(sha256(String.join(
                    "|",
                    "identity-link-v1",
                    value(link.getType(), ""),
                    value(link.getUserId(), ""),
                    value(link.getGroupId(), "")
                )));
            }
            if (truncated) {
                break;
            }
        }
        hashes.sort(String::compareTo);
        return new IdentityEvidence(List.copyOf(hashes), truncated);
    }

    private VariableEvidence variableEvidence(String executionId, List<String> allowlistedNames) {
        if (allowlistedNames.isEmpty()) {
            return new VariableEvidence(List.of(), false);
        }
        Map<String, Object> variables = runtimeService.getVariables(executionId, allowlistedNames);
        ArrayList<String> hashes = new ArrayList<>();
        boolean unsupported = false;
        for (String name : allowlistedNames) {
            Object value = variables.get(name);
            if (value == null) {
                hashes.add(sha256("variable-v1|" + name + "|MISSING"));
            } else if (safeScalar(value)) {
                hashes.add(sha256(
                    "variable-v1|" + name + '|' + value.getClass().getName() + '|' + value
                ));
            } else {
                unsupported = true;
                hashes.add(sha256(
                    "variable-v1|" + name + "|UNSUPPORTED|" + value.getClass().getName()
                ));
            }
        }
        hashes.sort(String::compareTo);
        return new VariableEvidence(List.copyOf(hashes), unsupported);
    }

    private static boolean safeScalar(Object value) {
        return value instanceof CharSequence || value instanceof Number
            || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>
            || value instanceof java.util.UUID || value instanceof TemporalAccessor;
    }

    private static ApprovalMigrationEngineSnapshot withHash(ApprovalMigrationEngineSnapshot value) {
        String canonical = String.join(
            "|",
            "m5-exact-engine-snapshot-v1",
            Boolean.toString(value.readSucceeded()),
            value(value.readFailureCode(), ""),
            Boolean.toString(value.runtimePresent()),
            value(value.runtimeEngineDefinitionId(), ""),
            value(value.runtimeEngineDeploymentId(), ""),
            Boolean.toString(value.suspended()),
            value.activeActivityIds().toString(),
            value.executions().toString(),
            value.activeTasks().toString(),
            value.jobs().toString(),
            value.subscriptions().toString(),
            value.allowlistedVariableHashes().toString(),
            value.identityLinkHashes().toString(),
            Boolean.toString(value.historyPresent()),
            value(value.historicEngineDefinitionId(), ""),
            value.historicEndTime() == null ? "" : value.historicEndTime().toString(),
            value(value.boundedDeleteReason(), ""),
            value.historicTasks().toString(),
            Boolean.toString(value.truncated())
        );
        return new ApprovalMigrationEngineSnapshot(
            value.readSucceeded(),
            value.readFailureCode(),
            value.runtimePresent(),
            value.runtimeEngineDefinitionId(),
            value.runtimeEngineDeploymentId(),
            value.suspended(),
            value.activeActivityIds(),
            value.executions(),
            value.activeTasks(),
            value.jobs(),
            value.subscriptions(),
            value.allowlistedVariableHashes(),
            value.identityLinkHashes(),
            value.historyPresent(),
            value.historicEngineDefinitionId(),
            value.historicEndTime(),
            value.boundedDeleteReason(),
            value.historicTasks(),
            value.truncated(),
            sha256(canonical)
        );
    }

    private static String hashId(String value) {
        return sha256("engine-identity-v1|" + value(value, "MISSING"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean exceeds(Collection<?> values) {
        return values != null && values.size() > MAX_ITEMS;
    }

    private static <T> List<T> trim(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values.subList(0, Math.min(values.size(), MAX_ITEMS)));
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record IdentityEvidence(List<String> hashes, boolean truncated) {
    }

    private record VariableEvidence(List<String> hashes, boolean unsupportedValue) {
    }
}
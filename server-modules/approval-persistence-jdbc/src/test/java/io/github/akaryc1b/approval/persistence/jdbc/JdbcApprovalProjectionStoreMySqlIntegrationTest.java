package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.ProjectionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalProjectionStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    @Test
    void selectsMySqlFromTrustedMetadataAndRejectsMutationWithoutTransaction() {
        assertInstanceOf(JdbcMySqlApprovalProjectionStore.class, store);

        assertThrows(
            IllegalStateException.class,
            () -> store.lockDefinition(TENANT, DEFINITION_KEY, 1)
        );
        assertThrows(
            IllegalStateException.class,
            () -> store.lockBusinessKey(TENANT, "business-no-transaction")
        );
        assertThrows(
            IllegalStateException.class,
            () -> store.saveDefinition(definition(TENANT))
        );
        assertThrows(
            IllegalStateException.class,
            () -> store.createInstance(
                instance(TENANT, INSTANCE_ID, "engine-no-transaction", "business-no-tx"),
                List.of()
            )
        );
        assertThrows(
            IllegalStateException.class,
            () -> store.withdrawRunningInstance(
                TENANT,
                INSTANCE_ID,
                "Initiator-A",
                CHANGED_AT
            )
        );
    }

    @Test
    void roundTripsDefinitionInstanceTaskJsonUuidDecimalAndCanonicalTime() {
        PublishedDefinition rawDefinition = definition(TENANT);
        InstanceProjection rawInstance = instance(
            TENANT,
            INSTANCE_ID,
            "engine-instance-round-trip",
            "business-round-trip"
        );
        TaskProjection rawTask = task(
            TENANT,
            TASK_ID,
            INSTANCE_ID,
            "engine-task-round-trip",
            "Manager-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );

        inTransaction(() -> {
            store.lockDefinition(TENANT, DEFINITION_KEY, 1);
            store.saveDefinition(rawDefinition);
            store.lockBusinessKey(TENANT, rawInstance.businessKey());
            store.createInstance(rawInstance, List.of(rawTask));
        });

        PublishedDefinition expectedDefinition = canonical(rawDefinition);
        InstanceProjection expectedInstance = canonical(rawInstance);
        TaskProjection expectedTask = canonical(rawTask);
        assertEquals(
            expectedDefinition,
            store.findDefinition(TENANT, DEFINITION_KEY, 1).orElseThrow()
        );
        assertEquals(
            expectedInstance,
            store.findInstance(TENANT, INSTANCE_ID).orElseThrow()
        );
        assertEquals(
            expectedInstance,
            store.findByBusinessKey(TENANT, rawInstance.businessKey()).orElseThrow()
        );
        assertEquals(List.of(expectedTask), store.findTasks(TENANT, INSTANCE_ID));
        assertEquals(expectedTask, store.findTask(TENANT, TASK_ID).orElseThrow());
        assertFalse(store.findInstance(OTHER_TENANT, INSTANCE_ID).isPresent());
        assertFalse(store.findTask(OTHER_TENANT, TASK_ID).isPresent());
    }

    @Test
    void definitionLockSerializesFirstPublicationUntilCommit() throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        AtomicInteger publicationSideEffects = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> inTransaction(() -> {
                store.lockDefinition(TENANT, DEFINITION_KEY, 1);
                firstLocked.countDown();
                await(releaseFirst);
                if (store.findDefinition(TENANT, DEFINITION_KEY, 1).isPresent()) {
                    return false;
                }
                publicationSideEffects.incrementAndGet();
                store.saveDefinition(definition(TENANT));
                return true;
            }));
            Future<Boolean> second = executor.submit(() -> {
                await(firstLocked);
                secondAttempting.countDown();
                return inTransaction(() -> {
                    store.lockDefinition(TENANT, DEFINITION_KEY, 1);
                    if (store.findDefinition(TENANT, DEFINITION_KEY, 1).isPresent()) {
                        return false;
                    }
                    publicationSideEffects.incrementAndGet();
                    store.saveDefinition(definition(TENANT));
                    return true;
                });
            });

            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            assertTrue(secondAttempting.await(10, TimeUnit.SECONDS));
            assertThrows(
                TimeoutException.class,
                () -> second.get(500, TimeUnit.MILLISECONDS)
            );
            releaseFirst.countDown();

            assertTrue(first.get(20, TimeUnit.SECONDS));
            assertFalse(second.get(20, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }

        assertEquals(1, publicationSideEffects.get());
        assertEquals(1, count(
            "select count(*) from ap_definition_version "
                + "where tenant_id=? and definition_key=? and definition_version=1",
            TENANT,
            DEFINITION_KEY
        ));
    }

    @Test
    void businessLockRemainsHeldThroughRollbackAndThenAllowsExactCreation()
        throws Exception {
        saveDefinition(TENANT);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> first = executor.submit(() -> {
                try {
                    inTransaction(() -> {
                        store.lockBusinessKey(TENANT, "business-rollback");
                        store.createInstance(
                            instance(
                                TENANT,
                                INSTANCE_ID,
                                "engine-instance-rollback",
                                "business-rollback"
                            ),
                            List.of()
                        );
                        firstLocked.countDown();
                        await(releaseFirst);
                        throw new RollbackMarker();
                    });
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            Future<Boolean> second = executor.submit(() -> {
                await(firstLocked);
                secondAttempting.countDown();
                return inTransaction(() -> {
                    store.lockBusinessKey(TENANT, "business-rollback");
                    if (store.findByBusinessKey(TENANT, "business-rollback").isPresent()) {
                        return false;
                    }
                    store.createInstance(
                        instance(
                            TENANT,
                            OTHER_INSTANCE_ID,
                            "engine-instance-after-rollback",
                            "business-rollback"
                        ),
                        List.of()
                    );
                    return true;
                });
            });

            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            assertTrue(secondAttempting.await(10, TimeUnit.SECONDS));
            assertThrows(
                TimeoutException.class,
                () -> second.get(500, TimeUnit.MILLISECONDS)
            );
            releaseFirst.countDown();

            assertInstanceOf(RollbackMarker.class, first.get(20, TimeUnit.SECONDS));
            assertTrue(second.get(20, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }

        assertFalse(store.findInstance(TENANT, INSTANCE_ID).isPresent());
        assertEquals(
            OTHER_INSTANCE_ID,
            store.findByBusinessKey(TENANT, "business-rollback")
                .orElseThrow()
                .instanceId()
        );
    }

    @Test
    void delegatesSubMicrosecondClaimAndTransferThroughAcceptedTaskCas() {
        TaskProjection transferable = task(
            TENANT,
            TASK_ID,
            INSTANCE_ID,
            "engine-task-transfer",
            "Manager-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );
        TaskProjection claimable = task(
            TENANT,
            EXISTING_ACTIVE_TASK_ID,
            INSTANCE_ID,
            "engine-task-claim",
            "Finance-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );
        seedInstanceWithTasks(
            instance(TENANT, INSTANCE_ID, "engine-instance-task-cas", "business-task-cas"),
            List.of(transferable, claimable)
        );

        TaskProjection transferred = inTransaction(() -> store.transferPendingTask(
            TENANT,
            TASK_ID,
            "Manager-A",
            "Manager-B",
            CHANGED_AT
        ));
        assertEquals("Manager-B", transferred.assigneeId());
        assertEquals(2, transferred.version());
        assertEquals(canonicalInstant(CHANGED_AT), transferred.updatedAt());

        Instant subMicrosecondClaim = CHANGED_AT.plusSeconds(1).plusNanos(2);
        TaskProjection claimed = inTransaction(() -> store.claimPendingTask(
            TENANT,
            EXISTING_ACTIVE_TASK_ID,
            "Finance-A",
            subMicrosecondClaim
        ));
        assertEquals(TaskStatus.COMPLETING, claimed.status());
        assertEquals(2, claimed.version());
        assertEquals(canonicalInstant(subMicrosecondClaim), claimed.updatedAt());
    }

    @Test
    void completionCasSynchronizesActiveTasksAndRejectsStaleVersion() {
        TaskProjection completing = task(
            TENANT,
            TASK_ID,
            INSTANCE_ID,
            "engine-task-completing",
            "Manager-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );
        TaskProjection existingActive = task(
            TENANT,
            EXISTING_ACTIVE_TASK_ID,
            INSTANCE_ID,
            "engine-task-existing-active",
            "Finance-Old",
            TaskStatus.PENDING,
            7,
            CREATED_AT.plusSeconds(1)
        );
        TaskProjection stale = task(
            TENANT,
            STALE_TASK_ID,
            INSTANCE_ID,
            "engine-task-stale",
            "Finance-Stale",
            TaskStatus.PENDING,
            3,
            CREATED_AT.plusSeconds(2)
        );
        seedInstanceWithTasks(
            instance(TENANT, INSTANCE_ID, "engine-instance-complete", "business-complete"),
            List.of(completing, existingActive, stale)
        );
        TaskProjection claimed = inTransaction(() -> store.claimPendingTask(
            TENANT,
            TASK_ID,
            "Manager-A",
            CHANGED_AT
        ));

        TaskProjection refreshedExisting = new TaskProjection(
            REPLACEMENT_TASK_ID,
            INSTANCE_ID,
            TENANT,
            existingActive.engineTaskId(),
            "financeApproval",
            "财务复核",
            "Finance-New",
            TaskStatus.PENDING,
            1,
            CREATED_AT.plusSeconds(5),
            CHANGED_AT.plusSeconds(1),
            null
        );
        TaskProjection newTask = task(
            TENANT,
            NEW_TASK_ID,
            INSTANCE_ID,
            "engine-task-new",
            "Finance-New-2",
            TaskStatus.PENDING,
            1,
            CHANGED_AT.plusSeconds(1)
        );
        Instant completedAt = CHANGED_AT.plusSeconds(2).plusNanos(777);

        assertThrows(ProjectionConflictException.class, () -> inTransaction(() ->
            store.completeTaskAndSynchronize(
                TENANT,
                INSTANCE_ID,
                TASK_ID,
                claimed.version() - 1,
                List.of(refreshedExisting, newTask),
                InstanceStatus.RUNNING,
                completedAt
            )
        ));
        assertFalse(store.findTask(TENANT, NEW_TASK_ID).isPresent());
        assertEquals(
            TaskStatus.COMPLETING,
            store.findTask(TENANT, TASK_ID).orElseThrow().status()
        );
        assertEquals(1, store.findInstance(TENANT, INSTANCE_ID).orElseThrow().version());

        inTransaction(() -> store.completeTaskAndSynchronize(
            TENANT,
            INSTANCE_ID,
            TASK_ID,
            claimed.version(),
            List.of(refreshedExisting, newTask),
            InstanceStatus.RUNNING,
            completedAt
        ));

        TaskProjection completed = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, completed.status());
        assertEquals(3, completed.version());
        assertEquals(canonicalInstant(completedAt), completed.completedAt());

        TaskProjection updatedExisting = store.findTask(
            TENANT,
            EXISTING_ACTIVE_TASK_ID
        ).orElseThrow();
        assertEquals("Finance-New", updatedExisting.assigneeId());
        assertEquals("financeApproval", updatedExisting.taskDefinitionKey());
        assertEquals("财务复核", updatedExisting.name());
        assertEquals(7, updatedExisting.version());
        assertFalse(store.findTask(TENANT, REPLACEMENT_TASK_ID).isPresent());

        assertEquals(
            TaskStatus.CANCELED,
            store.findTask(TENANT, STALE_TASK_ID).orElseThrow().status()
        );
        assertEquals(4, store.findTask(TENANT, STALE_TASK_ID).orElseThrow().version());
        TaskProjection expectedNewTask = new TaskProjection(
            newTask.taskId(),
            newTask.instanceId(),
            newTask.tenantId(),
            newTask.engineTaskId(),
            newTask.taskDefinitionKey(),
            newTask.name(),
            newTask.assigneeId(),
            TaskStatus.PENDING,
            newTask.version(),
            canonicalInstant(newTask.createdAt()),
            canonicalInstant(completedAt),
            null
        );
        assertEquals(
            expectedNewTask,
            store.findTask(TENANT, NEW_TASK_ID).orElseThrow()
        );
        assertEquals(2, store.findInstance(TENANT, INSTANCE_ID).orElseThrow().version());
    }

    @Test
    void surroundingTransactionRollbackRestoresCompletionTasksAndInstance() {
        TaskProjection completing = task(
            TENANT,
            TASK_ID,
            INSTANCE_ID,
            "engine-task-outer-rollback",
            "Manager-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );
        seedInstanceWithTasks(
            instance(TENANT, INSTANCE_ID, "engine-instance-outer", "business-outer"),
            List.of(completing)
        );
        TaskProjection claimed = inTransaction(() -> store.claimPendingTask(
            TENANT,
            TASK_ID,
            "Manager-A",
            CHANGED_AT
        ));
        TaskProjection next = task(
            TENANT,
            NEW_TASK_ID,
            INSTANCE_ID,
            "engine-task-after-rollback",
            "Finance-A",
            TaskStatus.PENDING,
            1,
            CHANGED_AT.plusSeconds(1)
        );

        assertThrows(RollbackMarker.class, () -> inTransaction(() -> {
            store.completeTaskAndSynchronize(
                TENANT,
                INSTANCE_ID,
                TASK_ID,
                claimed.version(),
                List.of(next),
                InstanceStatus.COMPLETED,
                CHANGED_AT.plusSeconds(1)
            );
            throw new RollbackMarker();
        }));

        TaskProjection restored = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.COMPLETING, restored.status());
        assertEquals(claimed.version(), restored.version());
        assertFalse(store.findTask(TENANT, NEW_TASK_ID).isPresent());
        InstanceProjection instance = store.findInstance(TENANT, INSTANCE_ID).orElseThrow();
        assertEquals(InstanceStatus.RUNNING, instance.status());
        assertEquals(1, instance.version());
    }

    @Test
    void activeTaskOwnershipAndGlobalTaskIdentifierConflictsRollback() {
        saveDefinition(TENANT);
        saveDefinition(OTHER_TENANT);
        InstanceProjection primary = instance(
            TENANT,
            INSTANCE_ID,
            "engine-instance-primary",
            "business-primary"
        );
        InstanceProjection foreign = instance(
            TENANT,
            OTHER_INSTANCE_ID,
            "engine-instance-foreign",
            "business-foreign"
        );
        InstanceProjection crossTenant = instance(
            OTHER_TENANT,
            CROSS_TENANT_INSTANCE_ID,
            "engine-instance-cross-tenant",
            "business-cross-tenant"
        );
        TaskProjection primaryTask = task(
            TENANT,
            TASK_ID,
            INSTANCE_ID,
            "engine-task-primary",
            "Manager-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );
        TaskProjection foreignEngineOwner = task(
            TENANT,
            FOREIGN_ENGINE_TASK_ID,
            OTHER_INSTANCE_ID,
            "engine-task-foreign-owner",
            "Manager-X",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );
        TaskProjection crossTenantIdentifierOwner = task(
            OTHER_TENANT,
            GLOBAL_COLLISION_TASK_ID,
            CROSS_TENANT_INSTANCE_ID,
            "engine-task-cross-tenant",
            "Manager-Z",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );
        inTransaction(() -> {
            store.lockBusinessKey(TENANT, primary.businessKey());
            store.createInstance(primary, List.of(primaryTask));
            store.lockBusinessKey(TENANT, foreign.businessKey());
            store.createInstance(foreign, List.of(foreignEngineOwner));
            store.lockBusinessKey(OTHER_TENANT, crossTenant.businessKey());
            store.createInstance(crossTenant, List.of(crossTenantIdentifierOwner));
        });
        TaskProjection claimed = inTransaction(() -> store.claimPendingTask(
            TENANT,
            TASK_ID,
            "Manager-A",
            CHANGED_AT
        ));

        TaskProjection illegalEngineReuse = task(
            TENANT,
            NEW_TASK_ID,
            INSTANCE_ID,
            foreignEngineOwner.engineTaskId(),
            "Finance-A",
            TaskStatus.PENDING,
            1,
            CHANGED_AT.plusSeconds(1)
        );
        assertThrows(ProjectionConflictException.class, () -> inTransaction(() ->
            store.completeTaskAndSynchronize(
                TENANT,
                INSTANCE_ID,
                TASK_ID,
                claimed.version(),
                List.of(illegalEngineReuse),
                InstanceStatus.RUNNING,
                CHANGED_AT.plusSeconds(1)
            )
        ));
        assertEquals(
            TaskStatus.COMPLETING,
            store.findTask(TENANT, TASK_ID).orElseThrow().status()
        );

        TaskProjection illegalGlobalIdentifierReuse = task(
            TENANT,
            GLOBAL_COLLISION_TASK_ID,
            INSTANCE_ID,
            "engine-task-new-global-collision",
            "Finance-B",
            TaskStatus.PENDING,
            1,
            CHANGED_AT.plusSeconds(2)
        );
        assertThrows(ProjectionConflictException.class, () -> inTransaction(() ->
            store.completeTaskAndSynchronize(
                TENANT,
                INSTANCE_ID,
                TASK_ID,
                claimed.version(),
                List.of(illegalGlobalIdentifierReuse),
                InstanceStatus.RUNNING,
                CHANGED_AT.plusSeconds(2)
            )
        ));

        assertEquals(
            TaskStatus.COMPLETING,
            store.findTask(TENANT, TASK_ID).orElseThrow().status()
        );
        assertEquals(1, store.findInstance(TENANT, INSTANCE_ID).orElseThrow().version());
        assertEquals(
            TaskStatus.PENDING,
            store.findTask(TENANT, FOREIGN_ENGINE_TASK_ID).orElseThrow().status()
        );
        assertEquals(
            TaskStatus.PENDING,
            store.findTask(OTHER_TENANT, GLOBAL_COLLISION_TASK_ID)
                .orElseThrow()
                .status()
        );
    }

    @Test
    void controlledCancellationAndWithdrawalRemainTenantAndInitiatorFenced() {
        TaskProjection controlled = task(
            TENANT,
            TASK_ID,
            INSTANCE_ID,
            "engine-task-controlled",
            "Manager-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );
        TaskProjection remaining = task(
            TENANT,
            EXISTING_ACTIVE_TASK_ID,
            INSTANCE_ID,
            "engine-task-remaining",
            "Finance-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT.plusSeconds(1)
        );
        seedInstanceWithTasks(
            instance(TENANT, INSTANCE_ID, "engine-instance-control", "business-control"),
            List.of(controlled, remaining)
        );

        TaskProjection claimed = inTransaction(() -> store.claimPendingTaskForControl(
            TENANT,
            TASK_ID,
            CHANGED_AT
        ));
        inTransaction(() -> store.cancelClaimedTaskAndSynchronize(
            TENANT,
            INSTANCE_ID,
            TASK_ID,
            claimed.version(),
            List.of(remaining),
            CHANGED_AT.plusSeconds(1)
        ));
        assertEquals(
            TaskStatus.CANCELED,
            store.findTask(TENANT, TASK_ID).orElseThrow().status()
        );
        assertEquals(
            TaskStatus.PENDING,
            store.findTask(TENANT, EXISTING_ACTIVE_TASK_ID).orElseThrow().status()
        );
        assertEquals(2, store.findInstance(TENANT, INSTANCE_ID).orElseThrow().version());

        assertThrows(ProjectionConflictException.class, () -> inTransaction(() ->
            store.withdrawRunningInstance(
                OTHER_TENANT,
                INSTANCE_ID,
                "Initiator-A",
                CHANGED_AT.plusSeconds(2)
            )
        ));
        assertThrows(ProjectionConflictException.class, () -> inTransaction(() ->
            store.withdrawRunningInstance(
                TENANT,
                INSTANCE_ID,
                "initiator-a",
                CHANGED_AT.plusSeconds(2)
            )
        ));
        inTransaction(() -> store.withdrawRunningInstance(
            TENANT,
            INSTANCE_ID,
            "Initiator-A",
            CHANGED_AT.plusSeconds(2)
        ));

        InstanceProjection withdrawn = store.findInstance(TENANT, INSTANCE_ID).orElseThrow();
        assertEquals(InstanceStatus.WITHDRAWN, withdrawn.status());
        assertEquals(3, withdrawn.version());
        assertEquals(
            TaskStatus.CANCELED,
            store.findTask(TENANT, EXISTING_ACTIVE_TASK_ID).orElseThrow().status()
        );
        assertEquals(
            TaskStatus.CANCELED,
            store.findTask(TENANT, TASK_ID).orElseThrow().status()
        );
    }
}

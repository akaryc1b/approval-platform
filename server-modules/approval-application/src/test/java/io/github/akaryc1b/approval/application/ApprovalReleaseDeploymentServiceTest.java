package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalReleaseDeploymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final String TENANT = "tenant-a";
    private static final String DEFINITION_KEY = "purchase-payment";
    private static final String PREFLIGHT_HASH = "a".repeat(64);
    private static final String RETRY_PREFLIGHT_HASH = "b".repeat(64);
    private static final String RETRY_WARNING = "PREVIOUS_DEPLOYMENT_FAILED";

    private InMemoryDeploymentStore deployments;
    private RecordingEngine engine;
    private List<AuditEvent> audits;
    private ApprovalReleaseDeploymentService service;

    @BeforeEach
    void setUp() {
        deployments = new InMemoryDeploymentStore();
        engine = new RecordingEngine();
        audits = new ArrayList<>();
        service = service(releasePackage(), deployments, engine, audits::add);
    }

    @Test
    void deploysOnceAndSupportsRequestAndSemanticReplay() {
        var first = service.deploy(command("request-1", "key-1"));
        var requestReplay = service.deploy(command("request-2", "key-1"));
        var semanticReplay = service.deploy(command("request-3", "key-2"));

        assertEquals(ApprovalReleaseDeployment.Status.DEPLOYED, first.deployment().status());
        assertFalse(first.replayedExistingDeployment());
        assertEquals(first, requestReplay);
        assertTrue(semanticReplay.replayedExistingDeployment());
        assertEquals(first.deployment(), semanticReplay.deployment());
        assertEquals(1, engine.calls());
        assertEquals(1, audits.size());
        assertEquals(PREFLIGHT_HASH, audits.getFirst().attributes().get("preflightHash"));
        assertEquals("default", audits.getFirst().attributes().get("deploymentTarget"));
    }

    @Test
    void persistsFailureAndRequiresAcknowledgementBeforeRetry() {
        engine.failNext();

        var failed = service.deploy(command("request-failed", "key-failed"));
        assertThrows(
            ApprovalReleaseDeploymentService
                .ReleaseDeploymentWarningAcknowledgementRequired.class,
            () -> service.deploy(new ApprovalReleaseDeploymentService.DeployCommand(
                context("request-unacknowledged", "key-unacknowledged"),
                DEFINITION_KEY,
                1,
                "default",
                RETRY_PREFLIGHT_HASH,
                List.of()
            ))
        );
        var retried = service.deploy(retryCommand("request-retry", "key-retry"));

        assertEquals(ApprovalReleaseDeployment.Status.FAILED, failed.deployment().status());
        assertEquals("ENGINE_UNAVAILABLE", failed.deployment().lastErrorCode());
        assertEquals(1, failed.deployment().attemptCount());
        assertEquals(ApprovalReleaseDeployment.Status.DEPLOYED, retried.deployment().status());
        assertEquals(2, retried.deployment().attemptCount());
        assertEquals(2, engine.calls());
        assertEquals(2, audits.size());
        assertEquals(
            RETRY_WARNING,
            audits.getLast().attributes().get("acknowledgedWarningCodes")
        );
    }

    @Test
    void rejectsStalePreflightBeforeCallingTheEngine() {
        assertThrows(
            ApprovalReleaseDeploymentService.ReleaseDeploymentPreflightConflict.class,
            () -> service.deploy(new ApprovalReleaseDeploymentService.DeployCommand(
                context("request-stale", "key-stale"),
                DEFINITION_KEY,
                1,
                "default",
                "c".repeat(64),
                List.of()
            ))
        );

        assertEquals(0, engine.calls());
        assertTrue(deployments.find(TENANT, DEFINITION_KEY, 1).isEmpty());
        assertTrue(audits.isEmpty());
    }

    @Test
    void rejectsMissingOrDifferentReleasePackageIdentity() {
        ApprovalReleaseDeploymentService missing = service(
            null,
            new InMemoryDeploymentStore(),
            new RecordingEngine(),
            ignored -> { }
        );
        assertThrows(
            ApprovalReleaseDeploymentService.ReleasePackageNotFoundException.class,
            () -> missing.deploy(command("request-missing", "key-missing"))
        );

        deployments.save(pending("d".repeat(64)));
        assertThrows(
            ApprovalReleaseDeploymentService.ReleaseDeploymentConflictException.class,
            () -> service.deploy(command("request-conflict", "key-conflict"))
        );
        assertEquals(0, engine.calls());
    }

    private static ApprovalReleaseDeploymentService service(
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeploymentStore deploymentStore,
        ApprovalEngine approvalEngine,
        AuditEventSink auditEventSink
    ) {
        return new ApprovalReleaseDeploymentService(
            new InMemoryIdempotencyGuard(),
            new SingleReleaseStore(releasePackage),
            deploymentStore,
            request -> {
                ApprovalReleaseDeployment current = deploymentStore.find(
                    request.tenantId(),
                    request.definitionKey(),
                    request.releaseVersion()
                ).orElse(null);
                boolean retry = current != null
                    && current.status() == ApprovalReleaseDeployment.Status.FAILED;
                return new ApprovalReleaseDeploymentService.DeploymentPreflightDecision(
                    retry ? RETRY_PREFLIGHT_HASH : PREFLIGHT_HASH,
                    retry ? List.of(RETRY_WARNING) : List.of(),
                    true,
                    List.of()
                );
            },
            approvalEngine,
            auditEventSink,
            Clock.fixed(NOW, ZoneOffset.UTC),
            UUID::randomUUID
        );
    }

    private static ApprovalReleaseDeploymentService.DeployCommand command(
        String requestId,
        String idempotencyKey
    ) {
        return new ApprovalReleaseDeploymentService.DeployCommand(
            context(requestId, idempotencyKey),
            DEFINITION_KEY,
            1,
            "default",
            PREFLIGHT_HASH,
            List.of()
        );
    }

    private static ApprovalReleaseDeploymentService.DeployCommand retryCommand(
        String requestId,
        String idempotencyKey
    ) {
        return new ApprovalReleaseDeploymentService.DeployCommand(
            context(requestId, idempotencyKey),
            DEFINITION_KEY,
            1,
            "default",
            RETRY_PREFLIGHT_HASH,
            List.of(RETRY_WARNING)
        );
    }

    private static RequestContext context(String requestId, String idempotencyKey) {
        return new RequestContext(
            TENANT,
            "operator-a",
            requestId,
            idempotencyKey,
            requestId + "-trace"
        );
    }

    private static ApprovalReleasePackage releasePackage() {
        return new ApprovalReleasePackage(
            TENANT,
            DEFINITION_KEY,
            1,
            2,
            "1".repeat(64),
            1,
            "2".repeat(64),
            1,
            "3".repeat(64),
            1,
            "4".repeat(64),
            "1.2.0",
            "purchase-payment-v2.bpmn20.xml",
            "<definitions />",
            "5".repeat(64),
            "6".repeat(64),
            null,
            null,
            "7".repeat(64),
            "8".repeat(64),
            UUID.randomUUID(),
            "publisher-a",
            NOW
        );
    }

    private static ApprovalReleaseDeployment pending(String packageHash) {
        return new ApprovalReleaseDeployment(
            UUID.randomUUID(),
            TENANT,
            DEFINITION_KEY,
            1,
            packageHash,
            ApprovalReleaseDeployment.Status.PENDING,
            1,
            null,
            null,
            null,
            null,
            null,
            "operator-a",
            NOW,
            NOW,
            null
        );
    }

    private static final class InMemoryIdempotencyGuard implements IdempotencyGuard {
        private final Map<String, Object> results = new HashMap<>();
        private final Map<String, String> hashes = new HashMap<>();

        @Override
        public <T> T execute(
            RequestContext context,
            String operation,
            String requestHash,
            Class<T> resultType,
            Supplier<T> action
        ) {
            String key = context.tenantId() + ':' + operation + ':' + context.idempotencyKey();
            if (results.containsKey(key)) {
                if (!requestHash.equals(hashes.get(key))) {
                    throw new IdempotencyConflictException("request hash changed");
                }
                return resultType.cast(results.get(key));
            }
            T result = action.get();
            hashes.put(key, requestHash);
            results.put(key, result);
            return result;
        }
    }

    private record SingleReleaseStore(ApprovalReleasePackage releasePackage)
        implements ApprovalReleasePackageStore {

        @Override
        public void lockVersion(String tenantId, String definitionKey, int releaseVersion) {
        }

        @Override
        public Optional<ApprovalReleasePackage> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            if (releasePackage == null
                || !releasePackage.tenantId().equals(tenantId)
                || !releasePackage.definitionKey().equals(definitionKey)
                || releasePackage.releaseVersion() != releaseVersion) {
                return Optional.empty();
            }
            return Optional.of(releasePackage);
        }

        @Override
        public Optional<ApprovalReleasePackage> findLatest(
            String tenantId,
            String definitionKey
        ) {
            return find(tenantId, definitionKey, 1);
        }

        @Override
        public Optional<ApprovalReleasePackage> findByDraft(
            String tenantId,
            UUID draftId
        ) {
            return Optional.empty();
        }

        @Override
        public ReleasePage findReleases(ReleaseCriteria criteria) {
            List<ApprovalReleasePackage> items = find(
                criteria.tenantId(),
                criteria.definitionKey(),
                1
            ).stream().toList();
            return new ReleasePage(items, items.size(), criteria.limit(), criteria.offset());
        }

        @Override
        public void save(ApprovalReleasePackage packageToSave) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryDeploymentStore
        implements ApprovalReleaseDeploymentStore {

        private ApprovalReleaseDeployment current;

        @Override
        public void lock(String tenantId, String definitionKey, int releaseVersion) {
        }

        @Override
        public Optional<ApprovalReleaseDeployment> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            if (current == null
                || !current.tenantId().equals(tenantId)
                || !current.definitionKey().equals(definitionKey)
                || current.releaseVersion() != releaseVersion) {
                return Optional.empty();
            }
            return Optional.of(current);
        }

        @Override
        public void save(ApprovalReleaseDeployment deployment) {
            if (current != null) {
                throw new IllegalStateException("deployment already exists");
            }
            current = deployment;
        }

        @Override
        public boolean update(
            ApprovalReleaseDeployment deployment,
            int expectedAttemptCount
        ) {
            if (current == null || current.attemptCount() != expectedAttemptCount) {
                return false;
            }
            current = deployment;
            return true;
        }
    }

    private static final class RecordingEngine implements ApprovalEngine {
        private final AtomicInteger calls = new AtomicInteger();
        private boolean failNext;

        @Override
        public DeploymentResult deploy(DeployCommand command) {
            int call = calls.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new EngineOperationException(
                    "ENGINE_UNAVAILABLE",
                    "engine is unavailable"
                );
            }
            return new DeploymentResult(
                "deployment-" + call,
                "definition-" + call,
                command.definitionKey(),
                call
            );
        }

        @Override
        public StartResult start(StartCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskSnapshot> findActiveTasks(TaskQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskResult complete(CompleteTaskCommand command) {
            throw new UnsupportedOperationException();
        }

        void failNext() {
            failNext = true;
        }

        int calls() {
            return calls.get();
        }
    }
}

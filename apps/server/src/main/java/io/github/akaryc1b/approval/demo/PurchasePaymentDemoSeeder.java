package io.github.akaryc1b.approval.demo;

import io.github.akaryc1b.approval.application.ApprovalAttachmentService;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService.InstanceDetails;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService.PublishCommand;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService.PublishResult;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService.StartCommand;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService.StartResult;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore.AttachmentSummary;
import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.engine.ApprovalEngine;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Applies the explicitly enabled local scenario through existing application services.
 *
 * <p>The production PurchasePaymentApplicationService remains the controller authority. The
 * local-only start service uses its documented legacy effective-release bridge so the governed
 * scenario can run without inventing release rows or writing platform/Flowable tables directly.</p>
 */
public final class PurchasePaymentDemoSeeder {

    private static final String TRACE_ID = "demo-purchase-payment-seed-v1";
    private static final String MANAGER_TASK_KEY = "managerApproval";

    private final PurchasePaymentDemoScenario scenario;
    private final PurchasePaymentApplicationService publishingService;
    private final ApprovalEngine engine;
    private final ApprovalDslCompiler compiler;
    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalProjectionStore projections;
    private final AuditEventSink auditEvents;
    private final PurchasePaymentAssigneeResolver assigneeResolver;
    private final ApprovalBusinessEventOutbox businessEventOutbox;
    private final ApprovalAttachmentStore attachmentStore;
    private final ApprovalMessageStore messageStore;
    private final ApprovalCommentStore commentStore;
    private final Clock clock;

    public PurchasePaymentDemoSeeder(
        PurchasePaymentDemoScenario scenario,
        PurchasePaymentApplicationService publishingService,
        ApprovalEngine engine,
        ApprovalDslCompiler compiler,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        AuditEventSink auditEvents,
        PurchasePaymentAssigneeResolver assigneeResolver,
        ApprovalBusinessEventOutbox businessEventOutbox,
        ApprovalAttachmentStore attachmentStore,
        ApprovalMessageStore messageStore,
        ApprovalCommentStore commentStore,
        Clock clock
    ) {
        this.scenario = Objects.requireNonNull(scenario, "scenario must not be null");
        this.publishingService = Objects.requireNonNull(
            publishingService,
            "publishingService must not be null"
        );
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.compiler = Objects.requireNonNull(compiler, "compiler must not be null");
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.assigneeResolver = Objects.requireNonNull(
            assigneeResolver,
            "assigneeResolver must not be null"
        );
        this.businessEventOutbox = Objects.requireNonNull(
            businessEventOutbox,
            "businessEventOutbox must not be null"
        );
        this.attachmentStore = Objects.requireNonNull(
            attachmentStore,
            "attachmentStore must not be null"
        );
        this.messageStore = Objects.requireNonNull(messageStore, "messageStore must not be null");
        this.commentStore = Objects.requireNonNull(commentStore, "commentStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public synchronized PurchasePaymentDemoSeedState.SeedEvidence apply() {
        RequestContext publishContext = context(
            scenario.administratorId(),
            "demo-seed-publish-request-v1",
            "demo-seed-publish-v1"
        );
        PublishResult published = PurchasePaymentDemoRequestEvidenceScope.call(
            publishContext,
            () -> publishingService.publish(new PublishCommand(publishContext))
        );

        List<PurchasePaymentDemoSeedState.AttachmentEvidence> attachmentEvidence =
            uploadAttachments();
        List<String> attachmentIds = attachmentEvidence.stream()
            .map(value -> value.attachmentId().toString())
            .toList();

        PurchasePaymentApplicationService startService = localStartService();
        RequestContext startContext = context(
            scenario.assigneeRules().initiatorUserId().value(),
            "demo-seed-start-request-v1",
            "demo-seed-start-v1"
        );
        StartResult started = PurchasePaymentDemoRequestEvidenceScope.call(
            startContext,
            () -> startService.start(new StartCommand(
                startContext,
                scenario.request().businessKey(),
                scenario.request().amount(),
                scenario.request().supplier(),
                scenario.request().purchaseOrderReference(),
                attachmentIds,
                scenario.assigneeRules()
            ))
        );

        InstanceDetails details = startService.findInstance(
            scenario.tenantId(),
            started.instanceId()
        ).orElseThrow(() -> new IllegalStateException(
            "demo instance projection is missing after start"
        ));
        validateStartedScenario(started, details, attachmentIds);

        return new PurchasePaymentDemoSeedState.SeedEvidence(
            scenario.tenantId(),
            scenario.request().businessKey(),
            started.instanceId(),
            started.status(),
            published.definitionKey(),
            published.engineDefinitionId(),
            started.activeTasks().stream().map(TaskProjection::taskId).toList(),
            attachmentEvidence,
            started.startedAt()
        );
    }

    private List<PurchasePaymentDemoSeedState.AttachmentEvidence> uploadAttachments() {
        List<PurchasePaymentDemoSeedState.AttachmentEvidence> result = new ArrayList<>();
        for (PurchasePaymentDemoScenario.AttachmentFixture fixture : scenario.attachments()) {
            ApprovalAttachmentService service = new ApprovalAttachmentService(
                idempotencyGuard,
                attachmentStore,
                projections,
                messageStore,
                commentStore,
                clock,
                () -> fixture.attachmentId()
            );
            RequestContext attachmentContext = context(
                scenario.assigneeRules().initiatorUserId().value(),
                "demo-seed-attachment-" + fixture.logicalId() + "-request-v1",
                "demo-seed-attachment-" + fixture.logicalId() + "-v1"
            );
            AttachmentSummary summary = PurchasePaymentDemoRequestEvidenceScope.call(
                attachmentContext,
                () -> service.upload(new ApprovalAttachmentService.UploadCommand(
                    attachmentContext,
                    fixture.fileName(),
                    fixture.contentType(),
                    fixture.content()
                ))
            );
            if (!fixture.attachmentId().equals(summary.attachmentId())) {
                throw new IllegalStateException(
                    "idempotent attachment result did not preserve the fixed UUID"
                );
            }
            result.add(new PurchasePaymentDemoSeedState.AttachmentEvidence(
                fixture.logicalId(),
                summary.attachmentId(),
                summary.fileName(),
                summary.sha256(),
                summary.bound()
            ));
        }
        return List.copyOf(result);
    }

    private PurchasePaymentApplicationService localStartService() {
        return new PurchasePaymentApplicationService(
            engine,
            compiler,
            idempotencyGuard,
            projections,
            auditEvents,
            assigneeResolver,
            businessEventOutbox,
            clock,
            new DeterministicUuidSupplier(scenario.request().businessKey())
        );
    }

    private void validateStartedScenario(
        StartResult started,
        InstanceDetails details,
        List<String> attachmentIds
    ) {
        if (started.status() != InstanceStatus.RUNNING) {
            throw new IllegalStateException("demo instance must start in RUNNING status");
        }
        if (!scenario.request().businessKey().equals(details.instance().businessKey())) {
            throw new IllegalStateException("demo business key changed during start");
        }
        if (!attachmentIds.equals(details.instance().attachmentIds())) {
            throw new IllegalStateException("demo attachment IDs changed during start");
        }
        if (started.activeTasks().size() != 1) {
            throw new IllegalStateException("demo start must create exactly one active task");
        }
        TaskProjection managerTask = started.activeTasks().getFirst();
        String expectedManager = scenario.requireUser(
            scenario.assigneeRules().initiatorUserId().value()
        ).managerId();
        if (!MANAGER_TASK_KEY.equals(managerTask.taskDefinitionKey())) {
            throw new IllegalStateException("demo first task must be managerApproval");
        }
        if (!expectedManager.equals(managerTask.assigneeId())) {
            throw new IllegalStateException("demo first task must be assigned to the manager");
        }
        if (!details.tasks().equals(started.activeTasks())) {
            throw new IllegalStateException("demo task projection changed after start");
        }
    }

    private RequestContext context(
        String operatorId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            scenario.tenantId(),
            operatorId,
            requestId,
            idempotencyKey,
            TRACE_ID
        );
    }

    private static final class DeterministicUuidSupplier implements Supplier<UUID> {

        private final String namespace;
        private final AtomicInteger sequence = new AtomicInteger();

        private DeterministicUuidSupplier(String businessKey) {
            this.namespace = "approval-platform:demo:purchase-payment:" + businessKey + ':';
        }

        @Override
        public UUID get() {
            String name = namespace + sequence.getAndIncrement();
            return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        }
    }
}

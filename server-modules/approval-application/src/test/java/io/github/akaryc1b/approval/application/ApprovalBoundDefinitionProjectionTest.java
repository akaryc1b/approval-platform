package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Executes the real bound start callback; incompatible immutable evidence must fail before engine work. */
class ApprovalBoundDefinitionProjectionTest {
    private static final String TENANT = "bound-tenant";
    private static final String KEY = "purchase-payment";
    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void incompatibleExistingProjectionIsNeverOverwrittenOrStarted() {
        for (PublishedDefinition incompatible : List.of(
            definition("b".repeat(64), "deployment"), definition(HASH, "different-deployment"))) {
            Fixture fixture = new Fixture(Optional.of(packageEvidence(TENANT, HASH)), Optional.of(incompatible));
            assertThrows(ApprovalProjectionStore.ProjectionConflictException.class,
                () -> fixture.service().startBound(command(), release()));
            fixture.assertReachedLockedBoundaryOnly();
            assertEquals(1, fixture.definitionReads);
        }
    }

    @Test
    void missingOrMismatchedPackageIsRejectedBeforeProjectionOrEngine() {
        for (Optional<ApprovalReleasePackage> invalid : List.of(Optional.<ApprovalReleasePackage>empty(),
            Optional.of(packageEvidence(TENANT, "b".repeat(64))), Optional.of(packageEvidence("wrong-tenant", HASH)))) {
            Fixture fixture = new Fixture(invalid, Optional.empty());
            assertThrows(IllegalStateException.class, () -> fixture.service().startBound(command(), release()));
            fixture.assertReachedLockedBoundaryOnly();
            assertEquals(0, fixture.definitionReads);
        }
    }

    private static PurchasePaymentApplicationService.StartCommand command() {
        return new PurchasePaymentApplicationService.StartCommand(
            new RequestContext(TENANT, "employee", "request", "idempotency", "trace"), "business",
            new BigDecimal("1200"), "Supplier", "ORDER", List.of("attachment"),
            new AssigneeSnapshot("manager", "finance-reviewer", List.of("finance-approver"), Map.of()));
    }
    private static PublishedDefinition definition(String hash, String deployment) {
        return new PublishedDefinition(TENANT, KEY, 2, KEY, 2, "compiler", hash,
            deployment, "engine-definition", 2, "original-publisher", NOW);
    }
    private static ApprovalEffectiveRelease release() {
        return new ApprovalEffectiveRelease(TENANT, KEY, 2, 1, HASH, 2, HASH, 2, HASH, 2, HASH, 2, HASH,
            "compiler", HASH, HASH, HASH, "deployment", "engine-definition", 2, ApprovalEffectiveRelease.Status.ACTIVE,
            2, "activator", NOW, "isolated activation", "request", "trace");
    }
    private static ApprovalReleasePackage packageEvidence(String tenant, String packageHash) {
        // All unrelated identities/hashes match, so wrong-hash cases reach that precise branch.
        return new ApprovalReleasePackage(tenant, KEY, 2, 2, HASH, 2, HASH, 2, HASH, 2, HASH,
            "compiler", "purchase-payment.bpmn20.xml", "<definitions/>", HASH, HASH, null, null, HASH,
            packageHash, UUID.randomUUID(), "original-publisher", NOW);
    }

    private static final class Fixture {
        private final Optional<ApprovalReleasePackage> artifact;
        private final Optional<PublishedDefinition> existing;
        private int definitionLocks;
        private int definitionReads;
        private int packageReads;
        private int businessLocks;
        private int callbackExecutions;
        Fixture(Optional<ApprovalReleasePackage> artifact, Optional<PublishedDefinition> existing) {
            this.artifact = artifact; this.existing = existing;
        }
        PurchasePaymentApplicationService service() {
            ApprovalProjectionStore projections = port(ApprovalProjectionStore.class, (method, args) -> {
                if (method.equals("findByBusinessKey")) return Optional.empty();
                if (method.equals("lockBusinessKey")) { businessLocks++; return null; }
                if (method.equals("lockDefinition")) {
                    assertEquals(TENANT, args[0]); assertEquals(KEY, args[1]); assertEquals(2, args[2]);
                    definitionLocks++; return null;
                }
                if (method.equals("findDefinition")) {
                    assertEquals(1, definitionLocks); definitionReads++; return existing;
                }
                throw new AssertionError("unexpected projection mutation/call: " + method);
            });
            IdempotencyGuard idempotency = port(IdempotencyGuard.class, (method, args) -> {
                assertEquals("execute", method); callbackExecutions++;
                return ((Supplier<?>) args[4]).get();
            });
            ApprovalReleasePackageStore packages = port(ApprovalReleasePackageStore.class, (method, args) -> {
                assertEquals("find", method); assertEquals(TENANT, args[0]); assertEquals(KEY, args[1]); assertEquals(2, args[2]);
                assertEquals(1, definitionLocks); packageReads++; return artifact;
            });
            return new PurchasePaymentApplicationService(unexpected(ApprovalEngine.class),
                new ApprovalDslCompiler(new ApprovalDefinitionValidator()), idempotency, projections,
                unexpected(AuditEventSink.class), unexpected(PurchasePaymentAssigneeResolver.class),
                ApprovalBusinessEventOutbox.noOp(), unexpected(ApprovalEffectiveReleaseStore.class), packages,
                Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
        }
        void assertReachedLockedBoundaryOnly() {
            assertEquals(1, callbackExecutions); assertEquals(1, businessLocks);
            assertEquals(1, definitionLocks); assertEquals(1, packageReads);
            // Engine calls, projection writes, re-resolution and audit writes throw AssertionError above.
        }
    }
    private static <T> T unexpected(Class<T> type) {
        return port(type, (method, args) -> { throw new AssertionError("unexpected " + type.getSimpleName() + "." + method); });
    }
    private static <T> T port(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> handler.apply(method.getName(), args)));
    }
}

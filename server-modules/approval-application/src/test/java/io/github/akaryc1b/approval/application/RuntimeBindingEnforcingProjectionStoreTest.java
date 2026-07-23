package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeBindingEnforcingProjectionStoreTest {

    @Test
    void exactBindingAllowsReleaseBoundReplay() {
        var instance = RuntimeBindingTestFixtures.instance(true);
        var bindings = new RuntimeBindingTestFixtures.InMemoryRuntimeBindings();
        bindings.save(RuntimeBindingTestFixtures.binding(instance));
        var store = new RuntimeBindingEnforcingProjectionStore(
            RuntimeBindingTestFixtures.projectionStore(instance),
            bindings
        );

        assertEquals(
            Optional.of(instance),
            store.findByBusinessKey(instance.tenantId(), instance.businessKey())
        );
        assertEquals(
            Optional.of(instance),
            store.findInstance(instance.tenantId(), instance.instanceId())
        );
    }

    @Test
    void missingBindingFailsClosedForReleaseBoundInstance() {
        var instance = RuntimeBindingTestFixtures.instance(true);
        var store = new RuntimeBindingEnforcingProjectionStore(
            RuntimeBindingTestFixtures.projectionStore(instance),
            new RuntimeBindingTestFixtures.InMemoryRuntimeBindings()
        );

        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> store.findByBusinessKey(instance.tenantId(), instance.businessKey())
        );
        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> store.findInstance(instance.tenantId(), instance.instanceId())
        );
    }

    @Test
    void legacyInstanceDoesNotRequireRuntimeBinding() {
        var instance = RuntimeBindingTestFixtures.instance(false);
        var store = new RuntimeBindingEnforcingProjectionStore(
            RuntimeBindingTestFixtures.projectionStore(instance),
            new RuntimeBindingTestFixtures.InMemoryRuntimeBindings()
        );

        assertEquals(
            Optional.of(instance),
            store.findByBusinessKey(instance.tenantId(), instance.businessKey())
        );
    }
}

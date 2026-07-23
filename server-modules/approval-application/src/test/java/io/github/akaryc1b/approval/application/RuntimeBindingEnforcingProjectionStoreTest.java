package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeBindingEnforcingProjectionStoreTest {

    @Test
    void exactBindingAllowsReleaseBoundReplayAndRecordsSuccess() {
        var instance = RuntimeBindingTestFixtures.instance(true);
        var bindings = new RuntimeBindingTestFixtures.InMemoryRuntimeBindings();
        bindings.save(RuntimeBindingTestFixtures.binding(instance));
        List<String> metrics = new ArrayList<>();
        var store = store(instance, bindings, metrics);

        assertEquals(
            Optional.of(instance),
            store.findByBusinessKey(instance.tenantId(), instance.businessKey())
        );
        assertEquals(
            Optional.of(instance),
            store.findInstance(instance.tenantId(), instance.instanceId())
        );
        assertEquals(List.of("SUCCESS:NONE", "SUCCESS:NONE"), metrics);
    }

    @Test
    void missingBindingFailsClosedAndRecordsBoundedFailure() {
        var instance = RuntimeBindingTestFixtures.instance(true);
        var bindings = new RuntimeBindingTestFixtures.InMemoryRuntimeBindings();
        List<String> metrics = new ArrayList<>();
        var store = store(instance, bindings, metrics);

        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> store.findByBusinessKey(instance.tenantId(), instance.businessKey())
        );
        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> store.findInstance(instance.tenantId(), instance.instanceId())
        );
        assertEquals(
            List.of("FAILURE:MISSING_BINDING", "FAILURE:MISSING_BINDING"),
            metrics
        );
    }

    @Test
    void mismatchedBindingFailsClosedAndRecordsEvidenceMismatch() {
        var instance = RuntimeBindingTestFixtures.instance(true);
        ApprovalRuntimeBinding exact = RuntimeBindingTestFixtures.binding(instance);
        var bindings = new RuntimeBindingTestFixtures.InMemoryRuntimeBindings();
        bindings.save(new ApprovalRuntimeBinding(
            exact.tenantId(),
            exact.approvalInstanceId(),
            "different-business-key",
            exact.engineInstanceId(),
            exact.definitionKey(),
            exact.releaseVersion(),
            exact.releasePackageHash(),
            exact.definitionVersion(),
            exact.definitionHash(),
            exact.formPackageVersion(),
            exact.formPackageHash(),
            exact.formVersion(),
            exact.formHash(),
            exact.uiSchemaVersion(),
            exact.uiSchemaHash(),
            exact.compilerVersion(),
            exact.compiledArtifactHash(),
            exact.bpmnHash(),
            exact.deploymentMetadataHash(),
            exact.engineDeploymentId(),
            exact.engineDefinitionId(),
            exact.engineVersion(),
            exact.bindingEvidenceHash(),
            exact.boundBy(),
            exact.boundAt(),
            exact.requestId(),
            exact.traceId(),
            exact.auditChainReference()
        ));
        List<String> metrics = new ArrayList<>();
        var store = store(instance, bindings, metrics);

        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> store.findInstance(instance.tenantId(), instance.instanceId())
        );
        assertEquals(List.of("FAILURE:EVIDENCE_MISMATCH"), metrics);
    }

    @Test
    void legacyInstanceDoesNotRequireRuntimeBindingAndRecordsNotRequired() {
        var instance = RuntimeBindingTestFixtures.instance(false);
        var bindings = new RuntimeBindingTestFixtures.InMemoryRuntimeBindings();
        List<String> metrics = new ArrayList<>();
        var store = store(instance, bindings, metrics);

        assertEquals(
            Optional.of(instance),
            store.findByBusinessKey(instance.tenantId(), instance.businessKey())
        );
        assertEquals(List.of("NOT_REQUIRED:NONE"), metrics);
    }

    private static RuntimeBindingEnforcingProjectionStore store(
        ApprovalProjectionStore.InstanceProjection instance,
        RuntimeBindingTestFixtures.InMemoryRuntimeBindings bindings,
        List<String> metrics
    ) {
        return new RuntimeBindingEnforcingProjectionStore(
            RuntimeBindingTestFixtures.projectionStore(instance),
            bindings,
            (result, failureClass) -> metrics.add(result.name() + ':' + failureClass.name())
        );
    }
}

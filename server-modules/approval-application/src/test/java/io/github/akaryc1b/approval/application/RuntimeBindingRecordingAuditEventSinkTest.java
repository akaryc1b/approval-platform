package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeBindingRecordingAuditEventSinkTest {

    @Test
    void recordsExactBindingBeforeAppendingStartAudit() {
        var instance = RuntimeBindingTestFixtures.instance(true);
        var releasePackage = RuntimeBindingTestFixtures.releasePackage();
        var deployment = RuntimeBindingTestFixtures.deployment();
        var bindings = new RuntimeBindingTestFixtures.InMemoryRuntimeBindings();
        List<AuditEvent> appended = new ArrayList<>();
        var sink = new RuntimeBindingRecordingAuditEventSink(
            appended::add,
            RuntimeBindingTestFixtures.projectionStore(instance),
            RuntimeBindingTestFixtures.releasePackages(releasePackage),
            RuntimeBindingTestFixtures.deployments(deployment),
            bindings,
            new ApprovalReleasePackageHasher()
        );
        AuditEvent event = RuntimeBindingTestFixtures.startAudit(instance);

        sink.append(event);

        ApprovalRuntimeBinding binding = bindings.saved;
        assertEquals(instance.instanceId(), binding.approvalInstanceId());
        assertEquals(releasePackage.packageHash(), binding.releasePackageHash());
        assertEquals(deployment.engineDeploymentId(), binding.engineDeploymentId());
        assertEquals("audit-event:" + event.eventId(), binding.auditChainReference());
        assertTrue(binding.binds(releasePackage, deployment));
        assertEquals(64, binding.bindingEvidenceHash().length());
        assertEquals(List.of(event), appended);
    }

    @Test
    void mismatchedAuditEvidenceFailsClosedBeforeAnyAppend() {
        var instance = RuntimeBindingTestFixtures.instance(true);
        var attributes = new java.util.HashMap<>(
            RuntimeBindingTestFixtures.versionAttributes(instance)
        );
        attributes.put("engineDefinitionId", "wrong-definition");
        var bindings = new RuntimeBindingTestFixtures.InMemoryRuntimeBindings();
        List<AuditEvent> appended = new ArrayList<>();
        var sink = new RuntimeBindingRecordingAuditEventSink(
            appended::add,
            RuntimeBindingTestFixtures.projectionStore(instance),
            RuntimeBindingTestFixtures.releasePackages(
                RuntimeBindingTestFixtures.releasePackage()
            ),
            RuntimeBindingTestFixtures.deployments(RuntimeBindingTestFixtures.deployment()),
            bindings,
            new ApprovalReleasePackageHasher()
        );
        AuditEvent event = RuntimeBindingTestFixtures.startAudit(
            instance,
            Map.copyOf(attributes)
        );

        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> sink.append(event)
        );
        assertEquals(null, bindings.saved);
        assertTrue(appended.isEmpty());
    }
}

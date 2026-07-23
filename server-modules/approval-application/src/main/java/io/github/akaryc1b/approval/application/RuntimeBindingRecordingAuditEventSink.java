package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists immutable runtime release binding evidence from the authoritative instance-start audit
 * event. The delegate audit sink, projection store and binding store must join the transaction
 * opened by the surrounding command idempotency guard.
 */
public final class RuntimeBindingRecordingAuditEventSink implements AuditEventSink {

    private static final String INSTANCE_STARTED = "INSTANCE_STARTED";
    private static final String APPROVAL_INSTANCE = "APPROVAL_INSTANCE";

    private final AuditEventSink delegate;
    private final ApprovalProjectionStore projections;
    private final ApprovalReleasePackageStore releasePackages;
    private final ApprovalReleaseDeploymentStore deployments;
    private final ApprovalRuntimeBindingStore runtimeBindings;
    private final ApprovalReleasePackageHasher hasher;

    public RuntimeBindingRecordingAuditEventSink(
        AuditEventSink delegate,
        ApprovalProjectionStore projections,
        ApprovalReleasePackageStore releasePackages,
        ApprovalReleaseDeploymentStore deployments,
        ApprovalRuntimeBindingStore runtimeBindings,
        ApprovalReleasePackageHasher hasher
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.releasePackages = Objects.requireNonNull(
            releasePackages,
            "releasePackages must not be null"
        );
        this.deployments = Objects.requireNonNull(deployments, "deployments must not be null");
        this.runtimeBindings = Objects.requireNonNull(
            runtimeBindings,
            "runtimeBindings must not be null"
        );
        this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
    }

    @Override
    public void append(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!INSTANCE_STARTED.equals(event.action())) {
            delegate.append(event);
            return;
        }
        if (!APPROVAL_INSTANCE.equals(event.aggregateType())) {
            throw conflict("INSTANCE_STARTED audit aggregate type is not APPROVAL_INSTANCE");
        }

        UUID instanceId = parseInstanceId(event.aggregateId());
        ApprovalProjectionStore.InstanceProjection instance = projections.findInstance(
            event.tenantId(),
            instanceId
        ).orElseThrow(() -> conflict(
            "instance projection is missing before runtime binding evidence is recorded"
        ));
        requireAuditIdentity(event, instance);
        if (instance.releaseVersion() == null) {
            delegate.append(event);
            return;
        }

        ApprovalReleasePackage releasePackage = releasePackages.find(
            event.tenantId(),
            instance.definitionKey(),
            instance.releaseVersion()
        ).orElseThrow(() -> conflict(
            "immutable Release Package is missing for the release-bound instance"
        ));
        ApprovalReleaseDeployment deployment = deployments.find(
            event.tenantId(),
            instance.definitionKey(),
            instance.releaseVersion()
        ).orElseThrow(() -> conflict(
            "deployment projection is missing for the release-bound instance"
        ));
        requireExactEvidence(event.attributes(), instance, releasePackage, deployment);

        String auditReference = "audit-event:" + event.eventId();
        String bindingHash = bindingHash(
            event,
            instance,
            releasePackage,
            deployment,
            auditReference
        );
        ApprovalRuntimeBinding binding = new ApprovalRuntimeBinding(
            event.tenantId(),
            instance.instanceId(),
            instance.businessKey(),
            instance.engineInstanceId(),
            instance.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            releasePackage.definitionVersion(),
            releasePackage.definitionHash(),
            releasePackage.formPackageVersion(),
            releasePackage.formPackageHash(),
            releasePackage.formVersion(),
            releasePackage.formHash(),
            releasePackage.uiSchemaVersion(),
            releasePackage.uiSchemaHash(),
            releasePackage.compilerVersion(),
            releasePackage.compiledArtifactHash(),
            releasePackage.bpmnHash(),
            releasePackage.deploymentMetadataHash(),
            deployment.engineDeploymentId(),
            deployment.engineDefinitionId(),
            deployment.engineVersion(),
            bindingHash,
            event.operatorId(),
            event.occurredAt(),
            event.requestId(),
            event.traceId(),
            auditReference
        );
        if (!binding.binds(releasePackage, deployment)) {
            throw conflict("constructed runtime binding does not match immutable release evidence");
        }
        if (runtimeBindings.find(event.tenantId(), instance.instanceId()).isPresent()) {
            throw conflict("runtime binding already exists before INSTANCE_STARTED audit append");
        }

        runtimeBindings.save(binding);
        delegate.append(event);
    }

    private String bindingHash(
        AuditEvent event,
        ApprovalProjectionStore.InstanceProjection instance,
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment,
        String auditReference
    ) {
        return hasher.hashValues(
            event.tenantId(),
            instance.instanceId(),
            instance.businessKey(),
            instance.engineInstanceId(),
            instance.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            releasePackage.definitionVersion(),
            releasePackage.definitionHash(),
            releasePackage.formPackageVersion(),
            releasePackage.formPackageHash(),
            releasePackage.formVersion(),
            releasePackage.formHash(),
            releasePackage.uiSchemaVersion(),
            releasePackage.uiSchemaHash(),
            releasePackage.compilerVersion(),
            releasePackage.compiledArtifactHash(),
            releasePackage.bpmnHash(),
            releasePackage.deploymentMetadataHash(),
            deployment.engineDeploymentId(),
            deployment.engineDefinitionId(),
            deployment.engineVersion(),
            event.operatorId(),
            event.occurredAt(),
            event.requestId(),
            event.traceId(),
            auditReference
        );
    }

    private static void requireAuditIdentity(
        AuditEvent event,
        ApprovalProjectionStore.InstanceProjection instance
    ) {
        if (!event.tenantId().equals(instance.tenantId())
            || !event.operatorId().equals(instance.initiatorId())
            || !event.aggregateId().equals(instance.instanceId().toString())) {
            throw conflict("INSTANCE_STARTED audit identity does not match instance projection");
        }
    }

    private static void requireExactEvidence(
        Map<String, String> attributes,
        ApprovalProjectionStore.InstanceProjection instance,
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment
    ) {
        if (!releasePackage.tenantId().equals(instance.tenantId())
            || !releasePackage.definitionKey().equals(instance.definitionKey())
            || releasePackage.releaseVersion() != instance.releaseVersion()
            || !releasePackage.packageHash().equals(instance.releasePackageHash())
            || releasePackage.definitionVersion() != instance.definitionVersion()
            || !releasePackage.definitionHash().equals(instance.contentHash())
            || releasePackage.formPackageVersion() != instance.formPackageVersion()
            || !releasePackage.formPackageHash().equals(instance.formPackageHash())
            || releasePackage.formVersion() != instance.formVersion()
            || releasePackage.uiSchemaVersion() != instance.uiSchemaVersion()
            || !releasePackage.uiSchemaHash().equals(instance.uiSchemaHash())
            || !releasePackage.compilerVersion().equals(instance.compilerVersion())
            || !deployment.tenantId().equals(instance.tenantId())
            || !deployment.definitionKey().equals(instance.definitionKey())
            || deployment.releaseVersion() != instance.releaseVersion()
            || deployment.status() != ApprovalReleaseDeployment.Status.DEPLOYED
            || !deployment.releasePackageHash().equals(instance.releasePackageHash())
            || !deployment.engineDefinitionId().equals(instance.engineDefinitionId())) {
            throw conflict("instance projection does not match immutable release evidence");
        }
        requireAttribute(attributes, "releaseVersion", instance.releaseVersion().toString());
        requireAttribute(attributes, "releasePackageHash", instance.releasePackageHash());
        requireAttribute(
            attributes,
            "definitionVersion",
            Integer.toString(instance.definitionVersion())
        );
        requireAttribute(
            attributes,
            "formPackageVersion",
            instance.formPackageVersion().toString()
        );
        requireAttribute(attributes, "formPackageHash", instance.formPackageHash());
        requireAttribute(
            attributes,
            "uiSchemaVersion",
            instance.uiSchemaVersion().toString()
        );
        requireAttribute(attributes, "uiSchemaHash", instance.uiSchemaHash());
        requireAttribute(attributes, "engineDefinitionId", instance.engineDefinitionId());
    }

    private static void requireAttribute(
        Map<String, String> attributes,
        String name,
        String expected
    ) {
        if (!expected.equals(attributes.get(name))) {
            throw conflict("INSTANCE_STARTED audit attribute does not match: " + name);
        }
    }

    private static UUID parseInstanceId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw conflict("INSTANCE_STARTED aggregateId is not an approval instance UUID");
        }
    }

    private static ApprovalProjectionStore.ProjectionConflictException conflict(String message) {
        return new ApprovalProjectionStore.ProjectionConflictException(message);
    }
}

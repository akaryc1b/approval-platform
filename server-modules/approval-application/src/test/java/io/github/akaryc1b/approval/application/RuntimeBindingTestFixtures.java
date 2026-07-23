package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class RuntimeBindingTestFixtures {

    static final String TENANT = "tenant-a";
    static final String DEFINITION_KEY = "purchasePayment";
    static final UUID INSTANCE_ID = UUID.fromString(
        "71000000-0000-0000-0000-000000000001"
    );
    static final UUID EVENT_ID = UUID.fromString(
        "72000000-0000-0000-0000-000000000001"
    );
    static final Instant NOW = Instant.parse("2026-07-23T02:00:00Z");

    private RuntimeBindingTestFixtures() {
    }

    static ApprovalProjectionStore projectionStore(
        ApprovalProjectionStore.InstanceProjection instance
    ) {
        return proxy(ApprovalProjectionStore.class, (method, arguments) -> switch (method) {
            case "findInstance", "findByBusinessKey" -> Optional.of(instance);
            default -> throw unexpected("projection", method);
        });
    }

    static ApprovalReleasePackageStore releasePackages(ApprovalReleasePackage releasePackage) {
        return proxy(ApprovalReleasePackageStore.class, (method, arguments) -> switch (method) {
            case "find" -> Optional.of(releasePackage);
            default -> throw unexpected("package", method);
        });
    }

    static ApprovalReleaseDeploymentStore deployments(ApprovalReleaseDeployment deployment) {
        return proxy(ApprovalReleaseDeploymentStore.class, (method, arguments) -> switch (method) {
            case "find" -> Optional.of(deployment);
            default -> throw unexpected("deployment", method);
        });
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments)
        ));
    }

    static ApprovalProjectionStore.InstanceProjection instance(boolean releaseBound) {
        return new ApprovalProjectionStore.InstanceProjection(
            INSTANCE_ID, TENANT, "business-1", "engine-instance-1", DEFINITION_KEY, 2,
            DEFINITION_KEY, 3, "compiler-v1", hash('1'),
            releaseBound ? 4 : null, releaseBound ? hash('8') : null,
            releaseBound ? 5 : null, releaseBound ? hash('2') : null,
            releaseBound ? 6 : null, releaseBound ? hash('3') : null,
            releaseBound ? "engine-definition-1" : null, "initiator-a",
            new BigDecimal("100.00"), "Supplier A", "PO-1", List.of("attachment-1"),
            new ApprovalProjectionStore.AssigneeSnapshot(
                "manager-a", "finance-reviewer", List.of("finance-a"), Map.of()
            ),
            hash('9'), ApprovalProjectionStore.InstanceStatus.RUNNING, 1, NOW, NOW
        );
    }

    static ApprovalReleasePackage releasePackage() {
        return new ApprovalReleasePackage(
            TENANT, DEFINITION_KEY, 4, 2, hash('1'), 5, hash('2'), 3, hash('4'),
            6, hash('3'), "compiler-v1", "process.bpmn20.xml", "<definitions/>",
            hash('5'), hash('6'), null, null, hash('7'), hash('8'),
            UUID.fromString("73000000-0000-0000-0000-000000000001"),
            "publisher-a", NOW.minusSeconds(60)
        );
    }

    static ApprovalReleaseDeployment deployment() {
        return new ApprovalReleaseDeployment(
            UUID.fromString("74000000-0000-0000-0000-000000000001"),
            TENANT, DEFINITION_KEY, 4, hash('8'), ApprovalReleaseDeployment.Status.DEPLOYED,
            1, "engine-deployment-1", "engine-definition-1", 7, null, null,
            "deployer-a", NOW.minusSeconds(50), NOW.minusSeconds(40), NOW.minusSeconds(40)
        );
    }

    static AuditEvent startAudit(ApprovalProjectionStore.InstanceProjection instance) {
        return startAudit(instance, versionAttributes(instance));
    }

    static AuditEvent startAudit(
        ApprovalProjectionStore.InstanceProjection instance,
        Map<String, String> attributes
    ) {
        return new AuditEvent(
            EVENT_ID, TENANT, "initiator-a", "INSTANCE_STARTED", "APPROVAL_INSTANCE",
            INSTANCE_ID.toString(), "request-a", "trace-a", NOW, attributes
        );
    }

    static Map<String, String> versionAttributes(
        ApprovalProjectionStore.InstanceProjection instance
    ) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("definitionKey", instance.definitionKey());
        values.put("definitionVersion", Integer.toString(instance.definitionVersion()));
        values.put("formVersion", Integer.toString(instance.formVersion()));
        values.put("compilerVersion", instance.compilerVersion());
        values.put("contentHash", instance.contentHash());
        if (instance.releaseVersion() != null) {
            values.put("releaseVersion", instance.releaseVersion().toString());
            values.put("releasePackageHash", instance.releasePackageHash());
            values.put("formPackageVersion", instance.formPackageVersion().toString());
            values.put("formPackageHash", instance.formPackageHash());
            values.put("uiSchemaVersion", instance.uiSchemaVersion().toString());
            values.put("uiSchemaHash", instance.uiSchemaHash());
            values.put("engineDefinitionId", instance.engineDefinitionId());
        }
        return Map.copyOf(values);
    }

    static ApprovalRuntimeBinding binding(ApprovalProjectionStore.InstanceProjection instance) {
        ApprovalReleasePackage release = releasePackage();
        ApprovalReleaseDeployment deployment = deployment();
        return new ApprovalRuntimeBinding(
            TENANT, INSTANCE_ID, instance.businessKey(), instance.engineInstanceId(),
            DEFINITION_KEY, release.releaseVersion(), release.packageHash(),
            release.definitionVersion(), release.definitionHash(),
            release.formPackageVersion(), release.formPackageHash(), release.formVersion(),
            release.formHash(), release.uiSchemaVersion(), release.uiSchemaHash(),
            release.compilerVersion(), release.compiledArtifactHash(), release.bpmnHash(),
            release.deploymentMetadataHash(), deployment.engineDeploymentId(),
            deployment.engineDefinitionId(), deployment.engineVersion(), hash('a'),
            "initiator-a", NOW, "request-a", "trace-a", "audit-event:" + EVENT_ID
        );
    }

    static String hash(char value) {
        return Character.toString(value).repeat(64);
    }

    private static AssertionError unexpected(String type, String method) {
        return new AssertionError("unexpected " + type + " call: " + method);
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }

    static final class InMemoryRuntimeBindings implements ApprovalRuntimeBindingStore {
        ApprovalRuntimeBinding saved;

        @Override
        public Optional<ApprovalRuntimeBinding> find(String tenantId, UUID instanceId) {
            return saved != null
                && saved.tenantId().equals(tenantId)
                && saved.approvalInstanceId().equals(instanceId)
                ? Optional.of(saved)
                : Optional.empty();
        }

        @Override
        public Optional<ApprovalRuntimeBinding> findByEngineInstance(
            String tenantId,
            String engineInstanceId
        ) {
            return Optional.empty();
        }

        @Override
        public BindingPage findByRelease(BindingCriteria criteria) {
            return new BindingPage(List.of(), 0, criteria.limit(), criteria.offset());
        }

        @Override
        public long countReleaseUsage(String tenantId, String definitionKey, int releaseVersion) {
            return 0;
        }

        @Override
        public void save(ApprovalRuntimeBinding binding) {
            saved = binding;
        }
    }
}

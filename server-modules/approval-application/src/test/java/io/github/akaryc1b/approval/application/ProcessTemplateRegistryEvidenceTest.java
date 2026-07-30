package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ImportPlan;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.RegisteredComponent;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessTemplateRegistryEvidenceTest {

    private final ProcessTemplateCanonicalHasher hasher = new ProcessTemplateCanonicalHasher();
    private final ProcessTemplatePackageValidator validator =
        new ProcessTemplatePackageValidator(hasher);
    private final ProcessTemplateImportPreviewService preview =
        new ProcessTemplateImportPreviewService(validator, hasher);

    @Test
    void previewCarriesDeterministicServerRegistryEvidence() {
        TenantRegistrySnapshot registry = ProcessTemplateFixtures.completeRegistry();

        ImportPlan plan = preview.preview(
            ProcessTemplateFixtures.validPackage(),
            4096,
            ProcessTemplateFixtures.completeRequest(),
            registry
        );

        assertEquals("tenant-a", plan.registryEvidence().tenantId());
        assertEquals("1.4.0", plan.registryEvidence().platformProtocolVersion());
        assertEquals(hasher.registryHash(registry), plan.registryEvidence().contentHash());
        assertEquals(64, plan.registryEvidence().contentHash().length());
    }

    @Test
    void registryHashDoesNotDependOnSetOrComponentOrder() {
        RegisteredComponent primary = ProcessTemplateFixtures.completeRegistry().components().get(0);
        RegisteredComponent secondary = new RegisteredComponent(
            "secondarySelector",
            2,
            Set.of("TEXT", "NUMBER"),
            Set.of("selectionMode", "displayMode"),
            Set.of("PC", "H5"),
            "TEXT_READONLY"
        );
        TenantRegistrySnapshot first = registry(
            Set.of("amount", "currency"),
            Set.of("finance.submit.local", "finance.audit.local"),
            List.of(primary, secondary)
        );
        List<RegisteredComponent> reversedComponents = new ArrayList<>(first.components());
        Collections.reverse(reversedComponents);
        TenantRegistrySnapshot second = registry(
            Set.of("currency", "amount"),
            Set.of("finance.audit.local", "finance.submit.local"),
            reversedComponents
        );

        assertEquals(hasher.registryHash(first), hasher.registryHash(second));
    }

    @Test
    void unreferencedCapabilityDriftChangesEvidenceAndPlanHash() {
        TenantRegistrySnapshot baseline = ProcessTemplateFixtures.completeRegistry();
        TenantRegistrySnapshot drifted = registry(
            Set.of("amount", "unusedField"),
            baseline.connectorCapabilities(),
            baseline.components()
        );

        ImportPlan first = plan(baseline);
        ImportPlan second = plan(drifted);

        assertEquals(first.findings(), second.findings());
        assertEquals(first.rebindingRequirements(), second.rebindingRequirements());
        assertNotEquals(first.registryEvidence().contentHash(),
            second.registryEvidence().contentHash());
        assertNotEquals(first.planHash(), second.planHash());
    }

    @Test
    void registeredComponentContractDriftChangesEvidenceAndPlanHash() {
        RegisteredComponent original = ProcessTemplateFixtures.completeRegistry().components().get(0);
        RegisteredComponent driftedComponent = new RegisteredComponent(
            original.componentKey(),
            original.componentVersion(),
            original.supportedFieldTypes(),
            Set.of("selectionMode", "newServerProperty"),
            original.renderingSupport(),
            original.readonlyFallback()
        );
        TenantRegistrySnapshot drifted = registry(
            ProcessTemplateFixtures.completeRegistry().formFields(),
            ProcessTemplateFixtures.completeRegistry().connectorCapabilities(),
            List.of(driftedComponent)
        );

        ImportPlan first = plan(ProcessTemplateFixtures.completeRegistry());
        ImportPlan second = plan(drifted);

        assertEquals(first.findings(), second.findings());
        assertNotEquals(first.registryEvidence().contentHash(),
            second.registryEvidence().contentHash());
        assertNotEquals(first.planHash(), second.planHash());
    }

    @Test
    void rejectsUnsafeOrDuplicateServerRegistryContent() {
        RegisteredComponent component = ProcessTemplateFixtures.completeRegistry().components().get(0);
        TenantRegistrySnapshot duplicateComponents = registry(
            Set.of("amount"),
            Set.of("finance.submit.local"),
            List.of(component, component)
        );
        TenantRegistrySnapshot unsafeKey = registry(
            Set.of("../amount"),
            Set.of("finance.submit.local"),
            List.of(component)
        );

        assertThrows(ProcessTemplateException.class, () -> plan(duplicateComponents));
        assertThrows(ProcessTemplateException.class, () -> plan(unsafeKey));
    }

    private ImportPlan plan(TenantRegistrySnapshot registry) {
        return preview.preview(
            ProcessTemplateFixtures.validPackage(),
            4096,
            ProcessTemplateFixtures.completeRequest(),
            registry
        );
    }

    private static TenantRegistrySnapshot registry(
        Set<String> formFields,
        Set<String> connectorCapabilities,
        List<RegisteredComponent> components
    ) {
        TenantRegistrySnapshot baseline = ProcessTemplateFixtures.completeRegistry();
        return new TenantRegistrySnapshot(
            baseline.tenantId(),
            baseline.platformProtocolVersion(),
            formFields,
            connectorCapabilities,
            baseline.businessReferenceTypes(),
            baseline.organizationPlaceholders(),
            baseline.identityPlaceholders(),
            components
        );
    }
}

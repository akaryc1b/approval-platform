package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ProcessTemplateContracts.BindingKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PreviewRequest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantBinding;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ProcessTemplateTenantRegistryResolver.RegistryResolutionRequest;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTemplateLocalTenantRegistryResolverTest {

    private static final String FORM_HASH = "b".repeat(64);
    private static final String UI_HASH = "c".repeat(64);
    private static final String PACKAGE_HASH = "d".repeat(64);

    @Test
    void resolvesLocalFormFieldsHostComponentsAndConfiguredCapabilities() {
        StoreFixture stores = completeStores();
        ProcessTemplateLocalTenantRegistryResolver resolver = resolver(stores);
        PreviewRequest preview = preview(List.of(new TenantBinding(
            BindingKind.FORM_PACKAGE,
            "source-form",
            "tenant-a",
            "expenseImported",
            3
        )));

        var snapshot = resolver.resolve(new RegistryResolutionRequest(
            "tenant-a",
            templatePackage(),
            preview
        ));

        assertEquals(Set.of("amount", "supplier"), snapshot.formFields());
        assertEquals(Set.of("directory.read"), snapshot.connectorCapabilities());
        assertEquals(Set.of("purchase-order"), snapshot.businessReferenceTypes());
        assertEquals(Set.of("initiator.department"), snapshot.organizationPlaceholders());
        assertEquals(Set.of("initiator"), snapshot.identityPlaceholders());
        assertTrue(snapshot.components().stream().anyMatch(component ->
            component.componentKey().equals("TEXT")
                && component.renderingSupport().equals(Set.of("WEB", "H5", "WECHAT"))));
    }

    @Test
    void missingFormBindingProducesNonAuthoritativeEmptyFieldRegistry() {
        StoreFixture stores = completeStores();
        ProcessTemplateLocalTenantRegistryResolver resolver = resolver(stores);

        var snapshot = resolver.resolve(new RegistryResolutionRequest(
            "tenant-a",
            templatePackage(),
            preview(List.of())
        ));

        assertEquals(Set.of(), snapshot.formFields());
        assertTrue(snapshot.components().size() >= 10);
    }

    @Test
    void missingExactLocalPackageFailsClosed() {
        StoreFixture stores = completeStores();
        stores.packages.value = null;
        ProcessTemplateLocalTenantRegistryResolver resolver = resolver(stores);
        PreviewRequest preview = preview(List.of(new TenantBinding(
            BindingKind.FORM_PACKAGE,
            "source-form",
            "tenant-a",
            "expenseImported",
            3
        )));

        ProcessTemplateException.RegistryResolutionFailed failure = assertThrows(
            ProcessTemplateException.RegistryResolutionFailed.class,
            () -> resolver.resolve(new RegistryResolutionRequest(
                "tenant-a",
                templatePackage(),
                preview
            ))
        );

        assertEquals(
            "target tenant Form Package registry could not be resolved safely",
            failure.getMessage()
        );
    }

    @Test
    void crossTenantBindingIsRejectedBeforeStoreAccess() {
        StoreFixture stores = completeStores();
        ProcessTemplateLocalTenantRegistryResolver resolver = resolver(stores);
        PreviewRequest preview = preview(List.of(new TenantBinding(
            BindingKind.FORM_PACKAGE,
            "source-form",
            "tenant-b",
            "expenseImported",
            3
        )));

        assertThrows(ProcessTemplateException.CrossTenantBinding.class, () -> resolver.resolve(
            new RegistryResolutionRequest("tenant-a", templatePackage(), preview)
        ));
    }

    @Test
    void hostComponentDescriptorsAreDeterministicDataOnlyContracts() {
        ApprovalFormComponentRegistry registry = new ApprovalFormComponentRegistry();
        var descriptors = registry.registeredDescriptors();

        assertEquals(descriptors, registry.registeredDescriptors());
        assertEquals(
            descriptors.stream().map(ApprovalFormComponentRegistry.RegisteredDescriptor::componentType)
                .sorted().toList(),
            descriptors.stream().map(ApprovalFormComponentRegistry.RegisteredDescriptor::componentType)
                .toList()
        );
        assertTrue(descriptors.stream().allMatch(descriptor ->
            descriptor.componentVersion() == 1
                && !descriptor.supportedFieldTypes().isEmpty()
                && !descriptor.renderingSupport().isEmpty()
                && descriptor.readonlyFallback().equals("READONLY_TEXT")));
    }

    private static ProcessTemplateLocalTenantRegistryResolver resolver(StoreFixture stores) {
        return new ProcessTemplateLocalTenantRegistryResolver(
            stores.packages,
            stores.forms,
            stores.uiSchemas,
            new ApprovalFormComponentRegistry(),
            "1.0",
            Set.of("directory.read"),
            Set.of("purchase-order"),
            Set.of("initiator.department"),
            Set.of("initiator")
        );
    }

    private static PreviewRequest preview(List<TenantBinding> bindings) {
        return new PreviewRequest(
            "tenant-a",
            "expenseImported",
            5,
            "Imported expense",
            bindings
        );
    }

    private static TemplatePackage templatePackage() {
        return ProcessTemplateFixtures.validPackage();
    }

    private static StoreFixture completeStores() {
        FormDefinition form = new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "expenseImported",
            2,
            "Expense imported",
            List.of(
                new FormField("amount", FieldType.MONEY, "Amount", true, FieldConstraints.none()),
                new FormField("supplier", FieldType.TEXT, "Supplier", true, FieldConstraints.none())
            )
        );
        UiSchemaDefinition ui = new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            "expenseImported",
            2,
            4,
            "Expense imported UI",
            List.of(),
            List.of()
        );
        FormPackage formPackage = new FormPackage(
            "tenant-a",
            "expenseImported",
            3,
            2,
            FORM_HASH,
            4,
            UI_HASH,
            PACKAGE_HASH,
            UUID.fromString("00000000-0000-0000-0000-000000000010"),
            "publisher-1",
            Instant.parse("2026-07-23T00:00:00Z")
        );
        return new StoreFixture(
            new PackageStore(formPackage),
            new FormStore(new ApprovalFormStore.PublishedForm(
                "tenant-a", form, FORM_HASH, "publisher-1",
                Instant.parse("2026-07-23T00:00:00Z")
            )),
            new UiStore(new ApprovalUiSchemaStore.PublishedUiSchema(
                "tenant-a", ui, UI_HASH, "publisher-1",
                Instant.parse("2026-07-23T00:00:00Z")
            ))
        );
    }

    private record StoreFixture(PackageStore packages, FormStore forms, UiStore uiSchemas) {
    }

    private static final class PackageStore implements ApprovalFormPackageStore {
        private FormPackage value;

        private PackageStore(FormPackage value) {
            this.value = value;
        }

        @Override
        public void lockVersion(String tenantId, String formKey, int packageVersion) {
        }

        @Override
        public Optional<FormPackage> find(String tenantId, String formKey, int packageVersion) {
            if (value != null
                && value.tenantId().equals(tenantId)
                && value.formKey().equals(formKey)
                && value.packageVersion() == packageVersion) {
                return Optional.of(value);
            }
            return Optional.empty();
        }

        @Override
        public Optional<FormPackage> findByDraft(String tenantId, UUID draftId) {
            return Optional.empty();
        }

        @Override
        public void save(FormPackage formPackage) {
            value = formPackage;
        }
    }

    private static final class FormStore implements ApprovalFormStore {
        private PublishedForm value;

        private FormStore(PublishedForm value) {
            this.value = value;
        }

        @Override
        public void lockVersion(String tenantId, String formKey, int version) {
        }

        @Override
        public Optional<PublishedForm> find(String tenantId, String formKey, int version) {
            if (value.tenantId().equals(tenantId)
                && value.definition().formKey().equals(formKey)
                && value.definition().version() == version) {
                return Optional.of(value);
            }
            return Optional.empty();
        }

        @Override
        public void save(PublishedForm form) {
            value = form;
        }

        @Override
        public FormPage findForms(FormCriteria criteria) {
            return new FormPage(List.of(), 0, criteria.limit(), criteria.offset());
        }
    }

    private static final class UiStore implements ApprovalUiSchemaStore {
        private PublishedUiSchema value;

        private UiStore(PublishedUiSchema value) {
            this.value = value;
        }

        @Override
        public void lockVersion(
            String tenantId,
            String formKey,
            int formVersion,
            int uiSchemaVersion
        ) {
        }

        @Override
        public Optional<PublishedUiSchema> find(
            String tenantId,
            String formKey,
            int formVersion,
            int uiSchemaVersion
        ) {
            if (value.tenantId().equals(tenantId)
                && value.definition().formKey().equals(formKey)
                && value.definition().formVersion() == formVersion
                && value.definition().version() == uiSchemaVersion) {
                return Optional.of(value);
            }
            return Optional.empty();
        }

        @Override
        public Optional<PublishedUiSchema> findLatest(
            String tenantId,
            String formKey,
            int formVersion
        ) {
            return Optional.of(value);
        }

        @Override
        public void save(PublishedUiSchema schema) {
            value = schema;
        }
    }
}

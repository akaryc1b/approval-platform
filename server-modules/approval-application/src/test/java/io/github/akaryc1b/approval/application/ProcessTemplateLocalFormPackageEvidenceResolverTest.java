package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver.FormPackageEvidenceRequest;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessTemplateLocalFormPackageEvidenceResolverTest {

    private static final String FORM_HASH = "b".repeat(64);
    private static final String UI_HASH = "c".repeat(64);
    private static final String PACKAGE_HASH = "d".repeat(64);

    private final ProcessTemplateFormPackageEvidenceHasher hasher =
        new ProcessTemplateFormPackageEvidenceHasher();

    @Test
    void resolvesExactImmutableFormPackageEvidence() {
        StoreFixture stores = completeStores(FORM_HASH, UI_HASH);
        ProcessTemplateLocalFormPackageEvidenceResolver resolver = resolver(stores);

        var evidence = resolver.resolve(new FormPackageEvidenceRequest(
            "tenant-a",
            "expenseImported",
            3
        ));

        assertEquals("tenant-a", evidence.tenantId());
        assertEquals("expenseImported", evidence.definitionKey());
        assertEquals(3, evidence.packageVersion());
        assertEquals(2, evidence.formVersion());
        assertEquals(4, evidence.uiSchemaVersion());
        assertEquals(FORM_HASH, evidence.formHash());
        assertEquals(UI_HASH, evidence.uiSchemaHash());
        assertEquals(PACKAGE_HASH, evidence.packageHash());
        assertEquals(hasher.hash(evidence), evidence.contentHash());
    }

    @Test
    void missingFormPackageFailsClosedWithoutDatastoreDetail() {
        StoreFixture stores = completeStores(FORM_HASH, UI_HASH);
        stores.packages.value = null;
        ProcessTemplateLocalFormPackageEvidenceResolver resolver = resolver(stores);

        ProcessTemplateException failure = assertThrows(ProcessTemplateException.class,
            () -> resolver.resolve(new FormPackageEvidenceRequest(
                "tenant-a",
                "expenseImported",
                3
            )));

        assertEquals(
            "target Form Package evidence could not be resolved safely",
            failure.getMessage()
        );
        assertFalse(failure.getMessage().contains("not found"));
    }

    @Test
    void schemaHashMismatchFailsClosed() {
        StoreFixture stores = completeStores("e".repeat(64), UI_HASH);
        ProcessTemplateLocalFormPackageEvidenceResolver resolver = resolver(stores);

        ProcessTemplateException failure = assertThrows(ProcessTemplateException.class,
            () -> resolver.resolve(new FormPackageEvidenceRequest(
                "tenant-a",
                "expenseImported",
                3
            )));

        assertEquals(
            "target Form Package evidence could not be resolved safely",
            failure.getMessage()
        );
    }

    @Test
    void anotherTenantCannotResolveLocalFormPackage() {
        StoreFixture stores = completeStores(FORM_HASH, UI_HASH);
        ProcessTemplateLocalFormPackageEvidenceResolver resolver = resolver(stores);

        assertThrows(ProcessTemplateException.class, () -> resolver.resolve(
            new FormPackageEvidenceRequest("tenant-b", "expenseImported", 3)
        ));
    }

    private ProcessTemplateLocalFormPackageEvidenceResolver resolver(StoreFixture stores) {
        return new ProcessTemplateLocalFormPackageEvidenceResolver(
            stores.packages,
            stores.forms,
            stores.uiSchemas,
            hasher
        );
    }

    private static StoreFixture completeStores(String publishedFormHash, String publishedUiHash) {
        FormDefinition form = new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "expenseImported",
            2,
            "Expense imported",
            List.of()
        );
        UiSchemaDefinition uiSchema = new UiSchemaDefinition(
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
        ApprovalFormStore.PublishedForm publishedForm = new ApprovalFormStore.PublishedForm(
            "tenant-a",
            form,
            publishedFormHash,
            "publisher-1",
            Instant.parse("2026-07-23T00:00:00Z")
        );
        ApprovalUiSchemaStore.PublishedUiSchema publishedUi =
            new ApprovalUiSchemaStore.PublishedUiSchema(
                "tenant-a",
                uiSchema,
                publishedUiHash,
                "publisher-1",
                Instant.parse("2026-07-23T00:00:00Z")
            );
        return new StoreFixture(
            new FormPackageStoreStub(formPackage),
            new FormStoreStub(publishedForm),
            new UiSchemaStoreStub(publishedUi)
        );
    }

    private record StoreFixture(
        FormPackageStoreStub packages,
        FormStoreStub forms,
        UiSchemaStoreStub uiSchemas
    ) {
    }

    private static final class FormPackageStoreStub implements ApprovalFormPackageStore {
        private FormPackage value;

        private FormPackageStoreStub(FormPackage value) {
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

    private static final class FormStoreStub implements ApprovalFormStore {
        private PublishedForm value;

        private FormStoreStub(PublishedForm value) {
            this.value = value;
        }

        @Override
        public void lockVersion(String tenantId, String formKey, int version) {
        }

        @Override
        public Optional<PublishedForm> find(String tenantId, String formKey, int version) {
            if (value != null
                && value.tenantId().equals(tenantId)
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

    private static final class UiSchemaStoreStub implements ApprovalUiSchemaStore {
        private PublishedUiSchema value;

        private UiSchemaStoreStub(PublishedUiSchema value) {
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
            if (value != null
                && value.tenantId().equals(tenantId)
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
            return value == null ? Optional.empty() : Optional.of(value);
        }

        @Override
        public void save(PublishedUiSchema schema) {
            value = schema;
        }
    }
}

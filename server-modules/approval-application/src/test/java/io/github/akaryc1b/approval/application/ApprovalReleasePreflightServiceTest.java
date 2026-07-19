package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalReleasePreflightServiceTest {

    private static final String TENANT = "tenant-preflight";
    private static final String KEY = PurchasePaymentTemplate.DEFINITION_KEY;
    private static final String FORM_HASH = "1".repeat(64);
    private static final String UI_HASH = "2".repeat(64);
    private static final String FORM_PACKAGE_HASH = "3".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-19T05:00:00Z");
    private static final UUID DRAFT_ID = new UUID(0, 101);

    private InMemoryDefinitionStore definitions;
    private InMemoryReleaseStore releases;
    private InMemoryDeploymentStore deployments;
    private ApprovalReleasePreflightService service;
    private ApprovalDesignDraft draft;

    @BeforeEach
    void setUp() {
        var definition = PurchasePaymentTemplate.processDefinition();
        var form = PurchasePaymentTemplate.formDefinition();
        var ui = new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            KEY,
            form.version(),
            1,
            "Preflight UI",
            List.of(),
            List.of()
        );
        FormPackage formPackage = new FormPackage(
            TENANT,
            KEY,
            1,
            form.version(),
            FORM_HASH,
            ui.version(),
            UI_HASH,
            FORM_PACKAGE_HASH,
            new UUID(0, 100),
            "publisher",
            NOW
        );
        draft = new ApprovalDesignDraft(
            DRAFT_ID,
            TENANT,
            KEY,
            "Preflight draft",
            definition,
            new ApprovalDesignDraft.FormPackageReference(KEY, 1, FORM_PACKAGE_HASH),
            null,
            7,
            ApprovalDesignDraft.Status.VALIDATED,
            null,
            null,
            "designer",
            "designer",
            NOW,
            NOW
        );
        definitions = new InMemoryDefinitionStore();
        releases = new InMemoryReleaseStore();
        deployments = new InMemoryDeploymentStore();
        service = new ApprovalReleasePreflightService(
            new InMemoryDraftStore(draft),
            definitions,
            releases,
            deployments,
            new InMemoryFormPackageStore(formPackage),
            new InMemoryFormStore(form),
            new InMemoryUiStore(ui),
            new ApprovalDefinitionValidator(),
            new ApprovalDefinitionSimulator(),
            new ApprovalDslCompiler(),
            new ApprovalDefinitionHasher(),
            new ApprovalReleasePackageHasher()
        );
    }

    @Test
    void publicationHashIsDeterministicAndCarriesStableWarnings() {
        var request = publicationRequest(draft.revision());

        var first = service.preflightPublication(request);
        var second = service.preflightPublication(request);

        assertEquals(first.preflightHash(), second.preflightHash());
        assertTrue(first.publishable());
        assertTrue(first.deployable());
        assertTrue(first.compiler().successful());
        assertTrue(first.compiler().deterministic());
        assertEquals(KEY, first.deploymentCompatibility().processDefinitionKey());
        assertTrue(first.warningCodes().contains("MISSING_START_PERMISSION_CONTEXT"));
        assertTrue(first.infos().stream().anyMatch(value ->
            value.code().equals("SIMULATION_NOT_REQUESTED")
        ));
    }

    @Test
    void staleRevisionAndOccupiedDefinitionVersionBlockPublication() {
        definitions.value = new ApprovalDefinitionVersion(
            TENANT,
            KEY,
            draft.definition().version(),
            "f".repeat(64),
            1,
            FORM_PACKAGE_HASH,
            draft.definition(),
            new UUID(0, 999),
            "other-publisher",
            NOW
        );

        var report = service.preflightPublication(publicationRequest(draft.revision() - 1));

        assertFalse(report.publishable());
        assertFalse(report.deployable());
        assertTrue(hasError(report, "DRAFT_REVISION_STALE"));
        assertTrue(hasError(report, "DEFINITION_VERSION_CONFLICT"));
    }

    @Test
    void failedDeploymentIsRetryableButPendingDeploymentIsNot() {
        ApprovalReleasePackage release = releasePackage();
        releases.value = release;
        deployments.value = deployment(
            release,
            ApprovalReleaseDeployment.Status.FAILED
        );

        var failed = service.preflightDeployment(deploymentRequest());

        assertTrue(failed.publishable());
        assertTrue(failed.deployable());
        assertTrue(failed.warnings().stream().anyMatch(value ->
            value.code().equals("PREVIOUS_DEPLOYMENT_FAILED")
        ));

        deployments.value = deployment(
            release,
            ApprovalReleaseDeployment.Status.PENDING
        );
        var pending = service.preflightDeployment(deploymentRequest());

        assertFalse(pending.deployable());
        assertTrue(hasError(pending, "DEPLOYMENT_PENDING"));
    }

    @Test
    void deploymentPreflightRejectsTamperedBpmnAndPackageHash() {
        ApprovalReleasePackage release = releasePackage();
        releases.value = new ApprovalReleasePackage(
            release.tenantId(),
            release.definitionKey(),
            release.releaseVersion(),
            release.definitionVersion(),
            release.definitionHash(),
            release.formPackageVersion(),
            release.formPackageHash(),
            release.formVersion(),
            release.formHash(),
            release.uiSchemaVersion(),
            release.uiSchemaHash(),
            release.compilerVersion(),
            release.bpmnResourceName(),
            "<definitions>",
            release.compiledArtifactHash(),
            release.bpmnHash(),
            release.dmnArtifact(),
            release.dmnHash(),
            release.deploymentMetadataHash(),
            release.packageHash(),
            release.sourceDraftId(),
            release.publishedBy(),
            release.publishedAt()
        );

        var report = service.preflightDeployment(deploymentRequest());

        assertFalse(report.publishable());
        assertFalse(report.deployable());
        assertTrue(hasError(report, "BPMN_HASH_MISMATCH"));
        assertTrue(hasError(report, "RELEASE_PACKAGE_HASH_MISMATCH"));
        assertTrue(hasError(report, "BPMN_NOT_WELL_FORMED"));
    }

    private ApprovalReleasePreflightService.PublicationRequest publicationRequest(long revision) {
        return new ApprovalReleasePreflightService.PublicationRequest(
            TENANT,
            DRAFT_ID,
            revision,
            KEY,
            draft.definition().version(),
            1,
            "flowable-primary",
            null
        );
    }

    private ApprovalReleasePreflightService.DeploymentRequest deploymentRequest() {
        return new ApprovalReleasePreflightService.DeploymentRequest(
            TENANT,
            KEY,
            1,
            "flowable-primary"
        );
    }

    private ApprovalReleasePackage releasePackage() {
        ApprovalDefinitionHasher definitionHasher = new ApprovalDefinitionHasher();
        ApprovalReleasePackageHasher releaseHasher = new ApprovalReleasePackageHasher();
        var compiled = new ApprovalDslCompiler().compile(
            draft.definition(),
            PurchasePaymentTemplate.formDefinition()
        );
        String definitionHash = definitionHasher.hash(draft.definition());
        String bpmnHash = releaseHasher.artifactHash(compiled.bpmnXml());
        String metadataHash = releaseHasher.deploymentMetadataHash(
            compiled.compilerVersion(),
            compiled.resourceName(),
            bpmnHash
        );
        String packageHash = releaseHasher.hash(
            KEY,
            1,
            draft.definition().version(),
            definitionHash,
            1,
            FORM_PACKAGE_HASH,
            PurchasePaymentTemplate.formDefinition().version(),
            FORM_HASH,
            1,
            UI_HASH,
            compiled.compilerVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash(),
            bpmnHash,
            null,
            null,
            metadataHash
        );
        return new ApprovalReleasePackage(
            TENANT,
            KEY,
            1,
            draft.definition().version(),
            definitionHash,
            1,
            FORM_PACKAGE_HASH,
            PurchasePaymentTemplate.formDefinition().version(),
            FORM_HASH,
            1,
            UI_HASH,
            compiled.compilerVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash(),
            bpmnHash,
            null,
            null,
            metadataHash,
            packageHash,
            DRAFT_ID,
            "publisher",
            NOW
        );
    }

    private static ApprovalReleaseDeployment deployment(
        ApprovalReleasePackage release,
        ApprovalReleaseDeployment.Status status
    ) {
        boolean deployed = status == ApprovalReleaseDeployment.Status.DEPLOYED;
        boolean failed = status == ApprovalReleaseDeployment.Status.FAILED;
        return new ApprovalReleaseDeployment(
            new UUID(0, 202),
            TENANT,
            KEY,
            release.releaseVersion(),
            release.packageHash(),
            status,
            1,
            deployed ? "engine-deployment" : null,
            deployed ? "engine-definition" : null,
            deployed ? 1 : null,
            failed ? "ENGINE_FAILED" : null,
            failed ? "engine failed" : null,
            "deployer",
            NOW,
            NOW,
            deployed ? NOW : null
        );
    }

    private static boolean hasError(
        ApprovalReleasePreflightService.PreflightReport report,
        String code
    ) {
        return report.errors().stream().anyMatch(value -> value.code().equals(code));
    }

    private static final class InMemoryDraftStore implements ApprovalDesignDraftStore {
        private final ApprovalDesignDraft value;

        private InMemoryDraftStore(ApprovalDesignDraft value) {
            this.value = value;
        }

        @Override
        public Optional<ApprovalDesignDraft> find(String tenantId, UUID draftId) {
            return TENANT.equals(tenantId) && DRAFT_ID.equals(draftId)
                ? Optional.of(value)
                : Optional.empty();
        }

        @Override
        public void save(ApprovalDesignDraft value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DraftPage findDrafts(DraftCriteria criteria) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void lock(String tenantId, UUID draftId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean update(ApprovalDesignDraft value, long expectedRevision) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryDefinitionStore
        implements ApprovalDefinitionVersionStore {
        private ApprovalDefinitionVersion value;

        @Override
        public Optional<ApprovalDefinitionVersion> find(
            String tenantId,
            String definitionKey,
            int version
        ) {
            return Optional.ofNullable(value);
        }

        @Override
        public void lockVersion(String tenantId, String definitionKey, int version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ApprovalDefinitionVersion> findLatest(
            String tenantId,
            String definitionKey
        ) {
            return Optional.ofNullable(value);
        }

        @Override
        public Optional<ApprovalDefinitionVersion> findByDraft(String tenantId, UUID draftId) {
            return Optional.empty();
        }

        @Override
        public VersionPage findVersions(VersionCriteria criteria) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(ApprovalDefinitionVersion value) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryReleaseStore implements ApprovalReleasePackageStore {
        private ApprovalReleasePackage value;

        @Override
        public Optional<ApprovalReleasePackage> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            return Optional.ofNullable(value);
        }

        @Override
        public Optional<ApprovalReleasePackage> findByDraft(String tenantId, UUID draftId) {
            return value != null && value.sourceDraftId().equals(draftId)
                ? Optional.of(value)
                : Optional.empty();
        }

        @Override
        public void lockVersion(String tenantId, String definitionKey, int releaseVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ApprovalReleasePackage> findLatest(
            String tenantId,
            String definitionKey
        ) {
            return Optional.ofNullable(value);
        }

        @Override
        public ReleasePage findReleases(ReleaseCriteria criteria) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(ApprovalReleasePackage value) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryDeploymentStore
        implements ApprovalReleaseDeploymentStore {
        private ApprovalReleaseDeployment value;

        @Override
        public Optional<ApprovalReleaseDeployment> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            return Optional.ofNullable(value);
        }

        @Override
        public void lock(String tenantId, String definitionKey, int releaseVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(ApprovalReleaseDeployment value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean update(ApprovalReleaseDeployment value, int expectedAttemptCount) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryFormPackageStore implements ApprovalFormPackageStore {
        private final FormPackage value;

        private InMemoryFormPackageStore(FormPackage value) {
            this.value = value;
        }

        @Override
        public Optional<FormPackage> find(String tenantId, String formKey, int packageVersion) {
            return Optional.of(value);
        }

        @Override
        public void lockVersion(String tenantId, String formKey, int packageVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<FormPackage> findByDraft(String tenantId, UUID draftId) {
            return Optional.empty();
        }

        @Override
        public void save(FormPackage value) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryFormStore implements ApprovalFormStore {
        private final PublishedForm value;

        private InMemoryFormStore(io.github.akaryc1b.approval.domain.form.FormDefinition form) {
            this.value = new PublishedForm(TENANT, form, FORM_HASH, "publisher", NOW);
        }

        @Override
        public Optional<PublishedForm> find(String tenantId, String formKey, int version) {
            return Optional.of(value);
        }

        @Override
        public void lockVersion(String tenantId, String formKey, int version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(PublishedForm value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FormPage findForms(FormCriteria criteria) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryUiStore implements ApprovalUiSchemaStore {
        private final PublishedUiSchema value;

        private InMemoryUiStore(UiSchemaDefinition ui) {
            this.value = new PublishedUiSchema(TENANT, ui, UI_HASH, "publisher", NOW);
        }

        @Override
        public Optional<PublishedUiSchema> find(
            String tenantId,
            String formKey,
            int formVersion,
            int uiSchemaVersion
        ) {
            return Optional.of(value);
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
        public void lockVersion(
            String tenantId,
            String formKey,
            int formVersion,
            int uiSchemaVersion
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(PublishedUiSchema value) {
            throw new UnsupportedOperationException();
        }
    }
}

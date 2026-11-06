package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalDefinitionHasher;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands;
import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.FormPackageHasher;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.UiSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MigrationPrecisionReleaseFixture {
    private static final Instant NOW = ApprovalMigrationJdbcFixtures.NOW;
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    MigrationPrecisionReleaseFixture(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource; this.jdbc = new JdbcTemplate(dataSource); this.objectMapper = objectMapper;
    }
    List<ApprovalReleasePackage> publish(String tenant) {
        seedFormPackage(tenant, 1);
        var store = new JdbcApprovalReleasePackageStore(dataSource);
        var service = service(store); var preflight = preflight(store);
        var result = new java.util.ArrayList<ApprovalReleasePackage>();
        for (int version : List.of(1, 2)) {
            var context = new RequestContext(tenant, "publisher", "create-" + version, "create-" + version, null);
            var draft = service.createFromPurchasePaymentTemplate(new ApprovalDesignCommands.Create(context,
                PurchasePaymentTemplate.DEFINITION_KEY, "precision fixture", version, 1));
            var report = preflight.preflightPublication(new ApprovalReleasePreflightService.PublicationRequest(
                tenant, draft.draftId(), draft.revision(), PurchasePaymentTemplate.DEFINITION_KEY,
                version, version, "default", ApprovalDefinitionSimulator.Scenario.empty()));
            assertTrue(report.publishable());
            service.publish(new ApprovalDesignCommands.Publish(
                new RequestContext(tenant, "publisher", "publish-" + version, "publish-" + version, null),
                draft.draftId(), draft.revision(), version, version, "default", report.preflightHash(),
                report.warningCodes(), ApprovalDefinitionSimulator.Scenario.empty()));
            result.add(store.find(tenant, PurchasePaymentTemplate.DEFINITION_KEY, version).orElseThrow());
        }
        return List.copyOf(result);
    }
    private ApprovalReleasePreflightService preflight(
        ApprovalReleasePackageStore releaseStore
    ) {
        ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();
        return new ApprovalReleasePreflightService(
            new JdbcApprovalDesignDraftStore(dataSource, objectMapper),
            new JdbcApprovalDefinitionVersionStore(dataSource, objectMapper),
            releaseStore,
            new JdbcApprovalReleaseDeploymentStore(dataSource),
            new JdbcApprovalFormPackageStore(dataSource),
            new JdbcApprovalFormStore(dataSource, objectMapper),
            new JdbcApprovalUiSchemaStore(dataSource, objectMapper),
            validator,
            new ApprovalDefinitionSimulator(validator),
            new ApprovalDslCompiler(validator),
            new ApprovalDefinitionHasher(),
            new ApprovalReleasePackageHasher()
        );
    }

    private ApprovalDesignService service(ApprovalReleasePackageStore releaseStore) {
        ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();
        return new ApprovalDesignService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new DataSourceTransactionManager(dataSource),
                CLOCK
            ),
            new JdbcApprovalDesignDraftStore(dataSource, objectMapper),
            new JdbcApprovalDefinitionVersionStore(dataSource, objectMapper),
            new JdbcApprovalCompiledArtifactStore(dataSource),
            releaseStore,
            new JdbcApprovalFormPackageStore(dataSource),
            new JdbcApprovalFormStore(dataSource, objectMapper),
            new JdbcApprovalUiSchemaStore(dataSource, objectMapper),
            new JdbcAuditEventSink(dataSource, objectMapper),
            validator,
            new ApprovalDefinitionSimulator(validator),
            new ApprovalDslCompiler(validator),
            new ApprovalDefinitionHasher(),
            new ApprovalReleasePackageHasher(),
            CLOCK,
            UUID::randomUUID
        );
    }

    private void seedFormPackage(String tenantId, int packageVersion) {
        var form = PurchasePaymentTemplate.formDefinition();
        var uiSchema = PurchasePaymentTemplate.uiSchemaDefinition();
        String formHash = new FormSchemaHasher().hash(form);
        String uiHash = new UiSchemaHasher().hash(uiSchema);
        JdbcApprovalFormStore formStore = new JdbcApprovalFormStore(dataSource, objectMapper);
        JdbcApprovalUiSchemaStore uiStore = new JdbcApprovalUiSchemaStore(
            dataSource,
            objectMapper
        );
        formStore.save(new PublishedForm(tenantId, form, formHash, "publisher", NOW));
        uiStore.save(new PublishedUiSchema(tenantId, uiSchema, uiHash, "publisher", NOW));

        UUID formDraftId = UUID.randomUUID();
        jdbc.update(
            """
            insert into ap_form_design_draft (
                tenant_id, draft_id, form_key, name, form_version,
                ui_schema_version, form_schema_json, ui_schema_json,
                revision, status, created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb),
                      1, 'DRAFT', 'publisher', 'publisher', ?, ?)
            """,
            tenantId,
            formDraftId,
            form.formKey(),
            form.name(),
            form.version(),
            uiSchema.uiSchemaVersion(),
            writeJson(form),
            writeJson(uiSchema),
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
        String packageHash = new FormPackageHasher().hash(
            form.formKey(),
            packageVersion,
            form.version(),
            formHash,
            uiSchema.uiSchemaVersion(),
            uiHash
        );
        FormPackage formPackage = new FormPackage(
            tenantId,
            form.formKey(),
            packageVersion,
            form.version(),
            formHash,
            uiSchema.uiSchemaVersion(),
            uiHash,
            packageHash,
            formDraftId,
            "publisher",
            NOW
        );
        new JdbcApprovalFormPackageStore(dataSource).save(formPackage);
        jdbc.update(
            """
            update ap_form_design_draft
            set status = 'PUBLISHED', published_package_version = ?
            where tenant_id = ? and draft_id = ?
            """,
            packageVersion,
            tenantId,
            formDraftId
        );
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}

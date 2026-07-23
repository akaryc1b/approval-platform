package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalEffectiveRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

final class JdbcRuntimeBindingStartTestFixture {

    static final String TENANT = "tenant-runtime-binding-start";
    static final String DEFINITION_KEY = PurchasePaymentTemplate.DEFINITION_KEY;
    static final int RELEASE_VERSION = 1;
    static final int DEFINITION_VERSION = 2;
    static final int FORM_PACKAGE_VERSION = 3;
    static final int FORM_VERSION = 4;
    static final int UI_SCHEMA_VERSION = 5;
    static final int ENGINE_VERSION = 7;
    static final String DEFINITION_HASH = "1".repeat(64);
    static final String FORM_PACKAGE_HASH = "2".repeat(64);
    static final String FORM_HASH = "3".repeat(64);
    static final String UI_SCHEMA_HASH = "4".repeat(64);
    static final String COMPILED_HASH = "5".repeat(64);
    static final String BPMN_HASH = "6".repeat(64);
    static final String METADATA_HASH = "7".repeat(64);
    static final String PACKAGE_HASH = "8".repeat(64);
    static final String COMPILER_VERSION = "approval-dsl-compiler/1.0";
    static final String ENGINE_DEPLOYMENT_ID = "engine-deployment-runtime-1";
    static final String ENGINE_DEFINITION_ID = "engine-definition-runtime-1";
    static final Instant NOW = Instant.parse("2026-07-23T03:00:00Z");
    static final UUID INSTANCE_ID = UUID.fromString(
        "81000000-0000-0000-0000-000000000001"
    );

    private JdbcRuntimeBindingStartTestFixture() {
    }

    static void reset(JdbcTemplate jdbc) {
        jdbc.execute("""
            truncate table
                ap_process_runtime_binding,
                ap_process_release_lifecycle_history,
                ap_process_release_lifecycle,
                ap_approval_effective_release,
                ap_approval_release_activation_history,
                ap_approval_release_deployment,
                ap_approval_release_package,
                ap_definition_version,
                ap_audit_event,
                ap_audit_chain_state,
                ap_approval_task,
                ap_approval_instance,
                ap_command_idempotency
            cascade
            """);
    }

    static ApprovalReleasePackage seedReleaseEvidence(DataSource dataSource) {
        ApprovalReleasePackage releasePackage = releasePackage();
        new JdbcApprovalProjectionStore(
            dataSource,
            new ObjectMapper().findAndRegisterModules()
        ).saveDefinition(
            publishedDefinition()
        );
        insertPackage(dataSource, releasePackage);

        ApprovalProcessReleaseStore lifecycle = new JdbcApprovalProcessReleaseStore(dataSource);
        ApprovalProcessRelease.Transition publishedTransition = transition(
            State.DRAFT,
            State.PUBLISHED,
            1,
            "publish-runtime-release",
            "publisher-runtime",
            releasePackage.publishedAt()
        );
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            releasePackage,
            publishedTransition
        );
        lifecycle.savePublished(published, publishedTransition);
        ApprovalProcessRelease.Transition activeTransition = transition(
            State.PUBLISHED,
            State.ACTIVE,
            2,
            "activate-runtime-release",
            "operator-runtime",
            NOW.minusSeconds(90)
        );
        ApprovalProcessRelease active = published.transitioned(activeTransition);
        if (!lifecycle.transition(active, published.revision(), activeTransition)) {
            throw new AssertionError("runtime lifecycle activation fixture failed");
        }

        new JdbcApprovalReleaseDeploymentStore(dataSource).save(deployment());
        ApprovalEffectiveReleaseStore effective = new JdbcApprovalEffectiveReleaseStore(dataSource);
        effective.save(effectiveRelease(), activation());
        return releasePackage;
    }

    static ApprovalReleaseDeployment deployment() {
        return new ApprovalReleaseDeployment(
            UUID.fromString("82000000-0000-0000-0000-000000000001"),
            TENANT,
            DEFINITION_KEY,
            RELEASE_VERSION,
            PACKAGE_HASH,
            ApprovalReleaseDeployment.Status.DEPLOYED,
            1,
            ENGINE_DEPLOYMENT_ID,
            ENGINE_DEFINITION_ID,
            ENGINE_VERSION,
            null,
            null,
            "deployer-runtime",
            NOW.minusSeconds(110),
            NOW.minusSeconds(100),
            NOW.minusSeconds(100)
        );
    }

    private static PublishedDefinition publishedDefinition() {
        return new PublishedDefinition(
            TENANT,
            DEFINITION_KEY,
            DEFINITION_VERSION,
            DEFINITION_KEY,
            FORM_VERSION,
            COMPILER_VERSION,
            DEFINITION_HASH,
            ENGINE_DEPLOYMENT_ID,
            ENGINE_DEFINITION_ID,
            ENGINE_VERSION,
            "publisher-runtime",
            NOW.minusSeconds(120)
        );
    }

    private static ApprovalReleasePackage releasePackage() {
        return new ApprovalReleasePackage(
            TENANT,
            DEFINITION_KEY,
            RELEASE_VERSION,
            DEFINITION_VERSION,
            DEFINITION_HASH,
            FORM_PACKAGE_VERSION,
            FORM_PACKAGE_HASH,
            FORM_VERSION,
            FORM_HASH,
            UI_SCHEMA_VERSION,
            UI_SCHEMA_HASH,
            COMPILER_VERSION,
            "purchase-payment.bpmn20.xml",
            "<definitions />",
            COMPILED_HASH,
            BPMN_HASH,
            null,
            null,
            METADATA_HASH,
            PACKAGE_HASH,
            UUID.fromString("83000000-0000-0000-0000-000000000001"),
            "publisher-runtime",
            NOW.minusSeconds(120)
        );
    }

    private static ApprovalProcessRelease.Transition transition(
        State from,
        State to,
        long revision,
        String idempotencyKey,
        String operator,
        Instant happenedAt
    ) {
        return new ApprovalProcessRelease.Transition(
            UUID.randomUUID(),
            TENANT,
            DEFINITION_KEY,
            RELEASE_VERSION,
            PACKAGE_HASH,
            from,
            to,
            revision,
            to == State.PUBLISHED
                ? "Publish exact runtime release evidence"
                : "Activate exact runtime release evidence",
            idempotencyKey,
            operator,
            "request-" + revision,
            "trace-runtime",
            "audit-event:lifecycle-" + revision,
            happenedAt
        );
    }

    private static ApprovalEffectiveRelease effectiveRelease() {
        return new ApprovalEffectiveRelease(
            TENANT,
            DEFINITION_KEY,
            RELEASE_VERSION,
            null,
            PACKAGE_HASH,
            DEFINITION_VERSION,
            DEFINITION_HASH,
            FORM_PACKAGE_VERSION,
            FORM_PACKAGE_HASH,
            FORM_VERSION,
            FORM_HASH,
            UI_SCHEMA_VERSION,
            UI_SCHEMA_HASH,
            COMPILER_VERSION,
            COMPILED_HASH,
            BPMN_HASH,
            METADATA_HASH,
            ENGINE_DEPLOYMENT_ID,
            ENGINE_DEFINITION_ID,
            ENGINE_VERSION,
            ApprovalEffectiveRelease.Status.ACTIVE,
            1,
            "operator-runtime",
            NOW.minusSeconds(90),
            "Activate release for runtime binding test",
            "request-effective-runtime",
            "trace-runtime"
        );
    }

    private static ApprovalEffectiveRelease.Activation activation() {
        return new ApprovalEffectiveRelease.Activation(
            UUID.fromString("84000000-0000-0000-0000-000000000001"),
            TENANT,
            DEFINITION_KEY,
            RELEASE_VERSION,
            null,
            PACKAGE_HASH,
            DEFINITION_VERSION,
            FORM_PACKAGE_VERSION,
            COMPILER_VERSION,
            ENGINE_DEPLOYMENT_ID,
            ENGINE_DEFINITION_ID,
            ENGINE_VERSION,
            ApprovalEffectiveRelease.Action.ACTIVATE,
            1,
            "operator-runtime",
            NOW.minusSeconds(90),
            "Activate release for runtime binding test",
            "request-effective-runtime",
            "trace-runtime"
        );
    }

    private static void insertPackage(
        DataSource dataSource,
        ApprovalReleasePackage releasePackage
    ) {
        new JdbcTemplate(dataSource).execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.execute("""
                    insert into ap_approval_release_package (
                        tenant_id, definition_key, release_version,
                        definition_version, definition_hash,
                        form_package_version, form_package_hash,
                        form_version, form_hash, ui_schema_version, ui_schema_hash,
                        compiler_version, bpmn_resource_name, bpmn_artifact,
                        compiled_artifact_hash, bpmn_hash, dmn_artifact, dmn_hash,
                        deployment_metadata_hash, package_hash, source_draft_id,
                        published_by, published_at
                    ) values (
                        '%s', '%s', %d, %d, '%s', %d, '%s', %d, '%s', %d, '%s',
                        '%s', '%s', '<definitions />', '%s', '%s', null, null,
                        '%s', '%s', '%s'::uuid, '%s', timestamptz '%s'
                    )
                    """.formatted(
                    releasePackage.tenantId(),
                    releasePackage.definitionKey(),
                    releasePackage.releaseVersion(),
                    releasePackage.definitionVersion(),
                    releasePackage.definitionHash(),
                    releasePackage.formPackageVersion(),
                    releasePackage.formPackageHash(),
                    releasePackage.formVersion(),
                    releasePackage.formHash(),
                    releasePackage.uiSchemaVersion(),
                    releasePackage.uiSchemaHash(),
                    releasePackage.compilerVersion(),
                    releasePackage.bpmnResourceName(),
                    releasePackage.compiledArtifactHash(),
                    releasePackage.bpmnHash(),
                    releasePackage.deploymentMetadataHash(),
                    releasePackage.packageHash(),
                    releasePackage.sourceDraftId(),
                    releasePackage.publishedBy(),
                    releasePackage.publishedAt()
                ));
                statement.execute("set session_replication_role = origin");
            }
            return null;
        });
    }
}

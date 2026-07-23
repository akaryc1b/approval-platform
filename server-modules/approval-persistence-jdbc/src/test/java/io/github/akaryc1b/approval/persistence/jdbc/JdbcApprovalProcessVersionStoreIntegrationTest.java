package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalProcessVersionStoreIntegrationTest {

    private static final String DEFINITION_KEY = "purchasePayment";
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-22T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_process_version_store_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static JdbcApprovalProcessReleaseStore releases;
    private static JdbcApprovalRuntimeBindingStore bindings;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        releases = new JdbcApprovalProcessReleaseStore(dataSource);
        bindings = new JdbcApprovalRuntimeBindingStore(dataSource);
    }

    @Test
    void lifecycleStoreIsTenantScopedOptimisticAndAppendOnly() {
        String tenant = "tenant-lifecycle-store";
        ApprovalReleasePackage releaseOne = releasePackage(tenant, 1, "a", "01");
        ApprovalReleasePackage releaseTwo = releasePackage(tenant, 2, "3", "02");
        seedReleasePackage(releaseOne);
        seedReleasePackage(releaseTwo);

        ApprovalProcessRelease publishedOne = publish(releaseOne, "publish-one");
        ApprovalProcessRelease publishedTwo = publish(releaseTwo, "publish-two");

        assertTrue(releases.find(tenant, DEFINITION_KEY, 1).isPresent());
        assertTrue(releases.find("other-tenant", DEFINITION_KEY, 1).isEmpty());
        assertTrue(releases.findActive(tenant, DEFINITION_KEY).isEmpty());

        ApprovalProcessRelease.Transition activateOne = transition(
            releaseOne,
            State.PUBLISHED,
            State.ACTIVE,
            2,
            "activate-one",
            PUBLISHED_AT.plusSeconds(60)
        );
        ApprovalProcessRelease activeOne = publishedOne.transitioned(activateOne);
        Boolean activated = transactions.execute(status -> {
            releases.lock(tenant, DEFINITION_KEY);
            return releases.transition(activeOne, 1, activateOne);
        });
        assertEquals(Boolean.TRUE, activated);
        assertEquals(
            1,
            releases.findActive(tenant, DEFINITION_KEY).orElseThrow().releaseVersion()
        );
        assertFalse(releases.transition(activeOne, 1, activateOne));

        ApprovalProcessReleaseStore.TransitionPage history = releases.findHistory(
            new ApprovalProcessReleaseStore.TransitionCriteria(
                tenant,
                DEFINITION_KEY,
                1,
                20,
                0
            )
        );
        assertEquals(2, history.total());
        assertEquals(List.of(2L, 1L), history.items().stream()
            .map(ApprovalProcessRelease.Transition::revision)
            .toList());
        assertEquals(
            activateOne.transitionId(),
            releases.findTransitionByIdempotency(
                tenant,
                activateOne.idempotencyKey()
            ).orElseThrow().transitionId()
        );
        ApprovalProcessReleaseStore.ReleasePage activePage = releases.findReleases(
            new ApprovalProcessReleaseStore.ReleaseCriteria(
                tenant,
                DEFINITION_KEY,
                State.ACTIVE,
                20,
                0
            )
        );
        assertEquals(1, activePage.total());
        assertEquals(1, activePage.items().getFirst().releaseVersion());

        ApprovalProcessRelease.Transition activateTwo = transition(
            releaseTwo,
            State.PUBLISHED,
            State.ACTIVE,
            2,
            "activate-two",
            PUBLISHED_AT.plusSeconds(120)
        );
        ApprovalProcessRelease activeTwo = publishedTwo.transitioned(activateTwo);
        assertThrows(DataAccessException.class, () -> transactions.executeWithoutResult(status -> {
            releases.lock(tenant, DEFINITION_KEY);
            releases.transition(activeTwo, 1, activateTwo);
        }));
        assertEquals(
            State.PUBLISHED,
            releases.find(tenant, DEFINITION_KEY, 2).orElseThrow().lifecycleState()
        );

        assertThrows(DataAccessException.class, () -> jdbc.update(
            """
            update ap_process_release_lifecycle_history
            set reason = 'mutated'
            where tenant_id = ? and transition_id = ?
            """,
            tenant,
            activateOne.transitionId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            """
            delete from ap_process_release_lifecycle
            where tenant_id = ? and definition_key = ? and release_version = 1
            """,
            tenant,
            DEFINITION_KEY
        ));
    }

    @Test
    void runtimeBindingStoreIsExactTenantScopedAndImmutable() {
        String tenant = "tenant-runtime-binding-store";
        ApprovalReleasePackage releasePackage = releasePackage(tenant, 1, "7", "03");
        UUID instanceId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        seedReleasePackage(releasePackage);
        ApprovalProcessRelease published = publish(releasePackage, "publish-runtime");
        ApprovalProcessRelease.Transition activate = transition(
            releasePackage,
            State.PUBLISHED,
            State.ACTIVE,
            2,
            "activate-runtime",
            PUBLISHED_AT.plusSeconds(60)
        );
        transactions.executeWithoutResult(status -> {
            releases.lock(tenant, DEFINITION_KEY);
            assertTrue(releases.transition(published.transitioned(activate), 1, activate));
        });
        seedApprovalInstance(releasePackage, instanceId);

        ApprovalRuntimeBinding binding = runtimeBinding(releasePackage, instanceId);
        bindings.save(binding);

        assertEquals(
            binding,
            bindings.find(tenant, instanceId).orElseThrow()
        );
        assertEquals(
            binding,
            bindings.findByEngineInstance(tenant, binding.engineInstanceId()).orElseThrow()
        );
        assertTrue(bindings.find("other-tenant", instanceId).isEmpty());
        assertTrue(bindings.findByEngineInstance(
            "other-tenant",
            binding.engineInstanceId()
        ).isEmpty());
        assertEquals(1, bindings.countReleaseUsage(tenant, DEFINITION_KEY, 1));
        assertEquals(0, bindings.countReleaseUsage("other-tenant", DEFINITION_KEY, 1));

        ApprovalRuntimeBindingStore.BindingPage page = bindings.findByRelease(
            new ApprovalRuntimeBindingStore.BindingCriteria(
                tenant,
                DEFINITION_KEY,
                1,
                20,
                0
            )
        );
        assertEquals(1, page.total());
        assertEquals(instanceId, page.items().getFirst().approvalInstanceId());

        assertThrows(DataAccessException.class, () -> bindings.save(binding));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            """
            update ap_process_runtime_binding
            set request_id = 'mutated'
            where tenant_id = ? and approval_instance_id = ?
            """,
            tenant,
            instanceId
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            """
            delete from ap_process_runtime_binding
            where tenant_id = ? and approval_instance_id = ?
            """,
            tenant,
            instanceId
        ));

        ApprovalRuntimeBinding crossTenant = new ApprovalRuntimeBinding(
            "other-tenant",
            instanceId,
            binding.businessKey(),
            binding.engineInstanceId(),
            binding.definitionKey(),
            binding.releaseVersion(),
            binding.releasePackageHash(),
            binding.definitionVersion(),
            binding.definitionHash(),
            binding.formPackageVersion(),
            binding.formPackageHash(),
            binding.formVersion(),
            binding.formHash(),
            binding.uiSchemaVersion(),
            binding.uiSchemaHash(),
            binding.compilerVersion(),
            binding.compiledArtifactHash(),
            binding.bpmnHash(),
            binding.deploymentMetadataHash(),
            binding.engineDeploymentId(),
            binding.engineDefinitionId(),
            binding.engineVersion(),
            "8".repeat(64),
            binding.boundBy(),
            binding.boundAt(),
            "request-cross-tenant",
            binding.traceId(),
            "audit-cross-tenant"
        );
        assertThrows(DataAccessException.class, () -> bindings.save(crossTenant));
    }

    private static ApprovalProcessRelease publish(
        ApprovalReleasePackage releasePackage,
        String evidenceSuffix
    ) {
        ApprovalProcessRelease.Transition transition = transition(
            releasePackage,
            State.DRAFT,
            State.PUBLISHED,
            1,
            evidenceSuffix,
            releasePackage.publishedAt()
        );
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            releasePackage,
            transition
        );
        transactions.executeWithoutResult(status -> releases.savePublished(published, transition));
        return published;
    }

    private static ApprovalProcessRelease.Transition transition(
        ApprovalReleasePackage releasePackage,
        State from,
        State to,
        long revision,
        String evidenceSuffix,
        Instant happenedAt
    ) {
        return new ApprovalProcessRelease.Transition(
            UUID.nameUUIDFromBytes(
                (releasePackage.tenantId() + ':' + evidenceSuffix)
                    .getBytes(StandardCharsets.UTF_8)
            ),
            releasePackage.tenantId(),
            releasePackage.definitionKey(),
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            from,
            to,
            revision,
            evidenceSuffix,
            "idempotency-" + evidenceSuffix,
            releasePackage.publishedBy(),
            "request-" + evidenceSuffix,
            "trace-" + evidenceSuffix,
            "audit-" + evidenceSuffix,
            happenedAt
        );
    }

    private static ApprovalRuntimeBinding runtimeBinding(
        ApprovalReleasePackage releasePackage,
        UUID instanceId
    ) {
        return new ApprovalRuntimeBinding(
            releasePackage.tenantId(),
            instanceId,
            "BUSINESS-RUNTIME-1",
            "engine-instance-runtime-1",
            releasePackage.definitionKey(),
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
            "engine-deployment-runtime-1",
            "engine-definition-runtime-1",
            1,
            "2".repeat(64),
            "operator-runtime",
            PUBLISHED_AT.plusSeconds(120),
            "request-runtime-binding",
            "trace-runtime-binding",
            "audit-runtime-binding"
        );
    }

    private static ApprovalReleasePackage releasePackage(
        String tenant,
        int releaseVersion,
        String packageHashCharacter,
        String draftSuffix
    ) {
        return new ApprovalReleasePackage(
            tenant,
            DEFINITION_KEY,
            releaseVersion,
            releaseVersion,
            "b".repeat(64),
            1,
            "c".repeat(64),
            1,
            "d".repeat(64),
            1,
            "e".repeat(64),
            "compiler-v1",
            "purchase-payment.bpmn20.xml",
            "<definitions/>",
            "f".repeat(64),
            "0".repeat(64),
            null,
            null,
            "1".repeat(64),
            packageHashCharacter.repeat(64),
            UUID.fromString("40000000-0000-0000-0000-0000000000" + draftSuffix),
            "operator-release",
            PUBLISHED_AT.plusSeconds(releaseVersion - 1L)
        );
    }

    private static void seedReleasePackage(ApprovalReleasePackage releasePackage) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.execute("""
                    insert into ap_approval_release_package (
                        tenant_id, definition_key, release_version, definition_version,
                        definition_hash, form_package_version, form_package_hash,
                        form_version, form_hash, ui_schema_version, ui_schema_hash,
                        compiler_version, bpmn_resource_name, bpmn_artifact,
                        compiled_artifact_hash, bpmn_hash, dmn_artifact, dmn_hash,
                        deployment_metadata_hash, package_hash, source_draft_id,
                        published_by, published_at
                    ) values (
                        '%s', '%s', %d, %d,
                        '%s', %d, '%s',
                        %d, '%s', %d, '%s',
                        '%s', '%s', '<definitions/>',
                        '%s', '%s', null, null,
                        '%s', '%s', '%s'::uuid,
                        '%s', timestamptz '%s'
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

    private static void seedApprovalInstance(
        ApprovalReleasePackage releasePackage,
        UUID instanceId
    ) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.execute("""
                    insert into ap_approval_instance (
                        instance_id, tenant_id, business_key, engine_instance_id,
                        definition_key, definition_version, form_key, form_version,
                        compiler_version, content_hash, initiator_id, amount, supplier,
                        purchase_order_reference, attachment_ids_json,
                        assignee_snapshot_json, request_hash, status, version,
                        created_at, updated_at, release_version, release_package_hash,
                        form_package_version, form_package_hash,
                        ui_schema_version, ui_schema_hash, engine_definition_id
                    ) values (
                        '%s'::uuid, '%s', 'BUSINESS-RUNTIME-1',
                        'engine-instance-runtime-1', '%s', %d, '%s', %d,
                        '%s', '%s', 'operator-runtime', 100.00, 'supplier-runtime',
                        'PO-RUNTIME-1', '[]'::jsonb, '{}'::jsonb, '%s',
                        'RUNNING', 1, timestamptz '2026-07-22 00:02:00+00',
                        timestamptz '2026-07-22 00:02:00+00', %d, '%s',
                        %d, '%s', %d, '%s', 'engine-definition-runtime-1'
                    )
                    """.formatted(
                        instanceId,
                        releasePackage.tenantId(),
                        releasePackage.definitionKey(),
                        releasePackage.definitionVersion(),
                        releasePackage.definitionKey(),
                        releasePackage.formVersion(),
                        releasePackage.compilerVersion(),
                        releasePackage.definitionHash(),
                        "9".repeat(64),
                        releasePackage.releaseVersion(),
                        releasePackage.packageHash(),
                        releasePackage.formPackageVersion(),
                        releasePackage.formPackageHash(),
                        releasePackage.uiSchemaVersion(),
                        releasePackage.uiSchemaHash()
                    ));
                statement.execute("set session_replication_role = origin");
            }
            return null;
        });
    }
}

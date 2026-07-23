package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentCommand;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentStatus;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.InstanceDecision;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.ApprovalReleaseStructuralDiff;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.ApprovalMode;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.ApprovalStep;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.AssigneeResolver;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.AssigneeRule;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.EmptyAssigneePolicy;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.EndNode;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition.StartNode;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.definition.ApprovalRuntimeBinding;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalProcessReleaseMigrationAssessmentIntegrationTest {

    private static final String TENANT = "tenant-migration-assessment";
    private static final String DEFINITION_KEY = "purchasePayment";
    private static final Instant NOW = Instant.parse("2026-07-23T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID INSTANCE_ID = UUID.fromString(
        "85000000-0000-0000-0000-000000000001"
    );
    private static final UUID TASK_ID = UUID.fromString(
        "85000000-0000-0000-0000-000000000002"
    );
    private static final List<String> BUSINESS_TABLES = List.of(
        "ap_approval_instance",
        "ap_approval_task",
        "ap_task_collaboration_policy",
        "ap_task_collaboration_participant",
        "ap_process_runtime_binding",
        "ap_process_release_lifecycle",
        "ap_process_release_lifecycle_history",
        "ap_approval_effective_release",
        "ap_approval_release_activation_history",
        "ap_approval_release_deployment",
        "ap_approval_release_package",
        "ap_approval_definition",
        "ap_definition_version"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_migration_assessment_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private JdbcTransactionManager transactions;
    private ApprovalReleasePackage sourcePackage;
    private ApprovalReleasePackage targetPackage;

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
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_task_collaboration_participant,
                ap_task_collaboration_policy,
                ap_process_runtime_binding,
                ap_process_release_lifecycle_history,
                ap_process_release_lifecycle,
                ap_approval_effective_release,
                ap_approval_release_activation_history,
                ap_approval_release_deployment,
                ap_approval_release_package,
                ap_approval_definition,
                ap_definition_version,
                ap_approval_task,
                ap_approval_instance,
                ap_audit_event,
                ap_audit_chain_state,
                ap_command_idempotency
            cascade
            """);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactions = new JdbcTransactionManager(dataSource);
        seedPlatformState();
    }

    @Test
    void assessmentOnlyAddsImmutableAuditAndIdempotencyEvidence() {
        Map<String, String> before = businessSnapshot();
        ApprovalProcessReleaseMigrationAssessmentService service = service(
            new JdbcAuditEventSink(dataSource, objectMapper, transactions)
        );
        AssessmentCommand command = command("migration-assessment-key");

        var result = service.assess(command);

        assertEquals(AssessmentStatus.READY, result.status());
        assertTrue(result.detectOnly());
        assertTrue(result.complete());
        assertEquals(1, result.totalBindingCount());
        assertEquals(1, result.runningCount());
        assertEquals(1, result.eligibleCount());
        assertEquals(0, result.blockedCount());
        assertEquals(0, result.terminalCount());
        assertEquals(InstanceDecision.ELIGIBLE, result.instances().getFirst().decision());
        assertEquals(before, businessSnapshot());
        assertEquals(1, count("ap_audit_event"));
        assertEquals(1, count("ap_audit_chain_state"));
        assertEquals(1, count("ap_command_idempotency"));
        assertEquals(
            "PROCESS_RELEASE_MIGRATION_DRY_RUN_EXECUTED",
            jdbc.queryForObject("select action from ap_audit_event", String.class)
        );
        assertEquals(
            result.reportHash(),
            jdbc.queryForObject(
                "select attributes_json ->> 'reportHash' from ap_audit_event",
                String.class
            )
        );
        assertEquals(
            "true",
            jdbc.queryForObject(
                "select attributes_json ->> 'detectOnly' from ap_audit_event",
                String.class
            )
        );
        assertEquals(
            "COMPLETED",
            jdbc.queryForObject("select status from ap_command_idempotency", String.class)
        );

        var replay = service.assess(command);

        assertEquals(result, replay);
        assertEquals(before, businessSnapshot());
        assertEquals(1, count("ap_audit_event"));
        assertEquals(1, count("ap_audit_chain_state"));
        assertEquals(1, count("ap_command_idempotency"));
    }

    @Test
    void auditFailureRollsBackCommandEvidenceAndAllBusinessState() {
        Map<String, String> before = businessSnapshot();
        ApprovalProcessReleaseMigrationAssessmentService service = service(event -> {
            throw new IllegalStateException("audit unavailable");
        });

        assertThrows(
            IllegalStateException.class,
            () -> service.assess(command("migration-assessment-audit-failure"))
        );

        assertEquals(before, businessSnapshot());
        assertEquals(0, count("ap_audit_event"));
        assertEquals(0, count("ap_audit_chain_state"));
        assertEquals(0, count("ap_command_idempotency"));
    }

    private ApprovalProcessReleaseMigrationAssessmentService service(AuditEventSink auditEvents) {
        AtomicInteger sequence = new AtomicInteger();
        return new ApprovalProcessReleaseMigrationAssessmentService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                transactions,
                CLOCK
            ),
            new JdbcApprovalProcessReleaseStore(dataSource),
            new JdbcApprovalReleasePackageStore(dataSource),
            new JdbcApprovalReleaseDeploymentStore(dataSource),
            new JdbcApprovalDefinitionVersionStore(dataSource, objectMapper),
            new JdbcApprovalRuntimeBindingStore(dataSource),
            new JdbcApprovalProjectionStore(dataSource, objectMapper),
            auditEvents,
            new ApprovalReleaseStructuralDiff(),
            new ApprovalReleasePackageHasher(),
            CLOCK,
            () -> new UUID(0, sequence.incrementAndGet())
        );
    }

    private AssessmentCommand command(String idempotencyKey) {
        return new AssessmentCommand(
            new RequestContext(
                TENANT,
                "operator-migration-assessment",
                "request-" + idempotencyKey,
                idempotencyKey,
                "trace-migration-assessment"
            ),
            DEFINITION_KEY,
            1,
            2,
            100,
            0,
            "Assess in-flight release compatibility without mutation"
        );
    }

    private void seedPlatformState() throws Exception {
        ApprovalDefinitionVersion sourceDefinition = definition(1);
        ApprovalDefinitionVersion targetDefinition = definition(2);
        sourcePackage = releasePackage(1, sourceDefinition, "a", NOW.minusSeconds(600));
        targetPackage = releasePackage(2, targetDefinition, "b", NOW.minusSeconds(300));
        insertImmutableReleaseEvidence(
            sourceDefinition,
            targetDefinition,
            sourcePackage,
            targetPackage
        );

        JdbcApprovalProcessReleaseStore releases = new JdbcApprovalProcessReleaseStore(dataSource);
        ApprovalProcessRelease sourcePublished = publish(releases, sourcePackage, 1);
        ApprovalProcessRelease sourceActive = transition(
            releases,
            sourcePublished,
            State.ACTIVE,
            2,
            NOW.minusSeconds(500)
        );
        transition(releases, sourceActive, State.DEPRECATED, 3, NOW.minusSeconds(400));

        ApprovalProcessRelease targetPublished = publish(releases, targetPackage, 4);
        transition(releases, targetPublished, State.ACTIVE, 5, NOW.minusSeconds(200));

        new JdbcApprovalReleaseDeploymentStore(dataSource).save(new ApprovalReleaseDeployment(
            UUID.fromString("85000000-0000-0000-0000-000000000010"),
            TENANT,
            DEFINITION_KEY,
            targetPackage.releaseVersion(),
            targetPackage.packageHash(),
            ApprovalReleaseDeployment.Status.DEPLOYED,
            1,
            "engine-deployment-target",
            "engine-definition-target",
            2,
            null,
            null,
            "deployer-migration-assessment",
            NOW.minusSeconds(190),
            NOW.minusSeconds(180),
            NOW.minusSeconds(180)
        ));

        JdbcApprovalProjectionStore projections = new JdbcApprovalProjectionStore(
            dataSource,
            objectMapper
        );
        projections.saveDefinition(new PublishedDefinition(
            TENANT,
            DEFINITION_KEY,
            sourcePackage.definitionVersion(),
            DEFINITION_KEY,
            sourcePackage.formVersion(),
            sourcePackage.compilerVersion(),
            sourcePackage.definitionHash(),
            "engine-deployment-source",
            "engine-definition-source",
            1,
            sourcePackage.publishedBy(),
            sourcePackage.publishedAt()
        ));

        ApprovalRuntimeBinding binding = runtimeBinding(sourcePackage);
        projections.createInstance(instance(binding), List.of(task(binding)));
        new JdbcApprovalRuntimeBindingStore(dataSource).save(binding);
    }

    private static ApprovalProcessRelease publish(
        JdbcApprovalProcessReleaseStore store,
        ApprovalReleasePackage releasePackage,
        int sequence
    ) {
        ApprovalProcessRelease.Transition transition = new ApprovalProcessRelease.Transition(
            new UUID(1, sequence),
            TENANT,
            DEFINITION_KEY,
            releasePackage.releaseVersion(),
            releasePackage.packageHash(),
            State.DRAFT,
            State.PUBLISHED,
            1,
            "Publish immutable release package for migration assessment",
            "publish-migration-release-" + releasePackage.releaseVersion(),
            releasePackage.publishedBy(),
            "request-publish-" + releasePackage.releaseVersion(),
            "trace-migration-seed",
            "audit-event:publish-" + releasePackage.releaseVersion(),
            releasePackage.publishedAt()
        );
        ApprovalProcessRelease published = ApprovalProcessRelease.published(
            releasePackage,
            transition
        );
        store.savePublished(published, transition);
        return published;
    }

    private static ApprovalProcessRelease transition(
        JdbcApprovalProcessReleaseStore store,
        ApprovalProcessRelease current,
        State target,
        int sequence,
        Instant happenedAt
    ) {
        ApprovalProcessRelease.Transition transition = new ApprovalProcessRelease.Transition(
            new UUID(2, sequence),
            current.tenantId(),
            current.definitionKey(),
            current.releaseVersion(),
            current.releasePackageHash(),
            current.lifecycleState(),
            target,
            current.revision() + 1,
            "Transition release lifecycle for migration assessment",
            "transition-migration-release-" + current.releaseVersion() + '-' + target,
            "operator-migration-seed",
            "request-transition-" + sequence,
            "trace-migration-seed",
            "audit-event:transition-" + sequence,
            happenedAt
        );
        ApprovalProcessRelease next = current.transitioned(transition);
        assertTrue(store.transition(next, current.revision(), transition));
        return next;
    }

    private static ApprovalDefinitionVersion definition(int version) {
        List<ApprovalDefinition.ProcessNode> nodes = new ArrayList<>();
        nodes.add(new StartNode("start", "Start", "managerApproval"));
        nodes.add(new ApprovalStep(
            "managerApproval",
            "Manager approval",
            new AssigneeRule(
                AssigneeResolver.VARIABLE_USER,
                "manager",
                EmptyAssigneePolicy.FAIL
            ),
            ApprovalMode.single(),
            "end"
        ));
        nodes.add(new EndNode("end", "End"));
        ApprovalDefinition definition = new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            DEFINITION_KEY,
            version,
            "Purchase payment",
            "start",
            nodes
        );
        return new ApprovalDefinitionVersion(
            TENANT,
            DEFINITION_KEY,
            version,
            hash(version),
            version,
            hash(version + 2),
            definition,
            new UUID(3, version),
            "publisher-migration-assessment",
            version == 1 ? NOW.minusSeconds(600) : NOW.minusSeconds(300)
        );
    }

    private static ApprovalReleasePackage releasePackage(
        int releaseVersion,
        ApprovalDefinitionVersion definition,
        String packageDigit,
        Instant publishedAt
    ) {
        return new ApprovalReleasePackage(
            TENANT,
            DEFINITION_KEY,
            releaseVersion,
            definition.version(),
            definition.contentHash(),
            definition.formPackageVersion(),
            definition.formPackageHash(),
            definition.version(),
            hash(definition.version() + 4),
            definition.version(),
            hash(definition.version() + 6),
            "compiler-v1",
            "process.bpmn20.xml",
            "<definitions/>",
            hash(definition.version() + 8),
            hash(definition.version() + 9),
            null,
            null,
            hash(definition.version() + 1),
            packageDigit.repeat(64),
            new UUID(4, releaseVersion),
            "publisher-migration-assessment",
            publishedAt
        );
    }

    private static ApprovalRuntimeBinding runtimeBinding(ApprovalReleasePackage releasePackage) {
        return new ApprovalRuntimeBinding(
            TENANT,
            INSTANCE_ID,
            "business-migration-assessment",
            "engine-instance-migration-assessment",
            DEFINITION_KEY,
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
            "engine-deployment-source",
            "engine-definition-source",
            1,
            "e".repeat(64),
            "initiator-migration-assessment",
            NOW.minusSeconds(100),
            "request-runtime-binding",
            "trace-migration-seed",
            "audit-event:runtime-binding"
        );
    }

    private static ApprovalProjectionStore.InstanceProjection instance(
        ApprovalRuntimeBinding binding
    ) {
        return new ApprovalProjectionStore.InstanceProjection(
            binding.approvalInstanceId(),
            binding.tenantId(),
            binding.businessKey(),
            binding.engineInstanceId(),
            binding.definitionKey(),
            binding.definitionVersion(),
            DEFINITION_KEY,
            binding.formVersion(),
            binding.compilerVersion(),
            binding.definitionHash(),
            binding.releaseVersion(),
            binding.releasePackageHash(),
            binding.formPackageVersion(),
            binding.formPackageHash(),
            binding.uiSchemaVersion(),
            binding.uiSchemaHash(),
            binding.engineDefinitionId(),
            "initiator-migration-assessment",
            new BigDecimal("1000.00"),
            "Supplier Migration",
            "PO-MIGRATION-1",
            List.of("attachment-migration-1"),
            null,
            "request-hash-migration",
            ApprovalProjectionStore.InstanceStatus.RUNNING,
            1,
            NOW.minusSeconds(100),
            NOW.minusSeconds(100)
        );
    }

    private static ApprovalProjectionStore.TaskProjection task(ApprovalRuntimeBinding binding) {
        return new ApprovalProjectionStore.TaskProjection(
            TASK_ID,
            binding.approvalInstanceId(),
            binding.tenantId(),
            "engine-task-migration-assessment",
            "managerApproval",
            "Manager approval",
            "manager-migration-assessment",
            ApprovalProjectionStore.TaskStatus.PENDING,
            1,
            NOW.minusSeconds(90),
            NOW.minusSeconds(90),
            null
        );
    }


    private void insertImmutableReleaseEvidence(
        ApprovalDefinitionVersion sourceDefinition,
        ApprovalDefinitionVersion targetDefinition,
        ApprovalReleasePackage sourceRelease,
        ApprovalReleasePackage targetRelease
    ) throws Exception {
        ObjectMapper approvalMapper = ApprovalDefinitionJacksonSupport.configure(
            objectMapper.copy()
        );
        String sourceJson = approvalMapper.writeValueAsString(sourceDefinition.definition());
        String targetJson = approvalMapper.writeValueAsString(targetDefinition.definition());
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
            }
            try {
                insertDefinition(connection, sourceDefinition, sourceJson);
                insertDefinition(connection, targetDefinition, targetJson);
                insertPackage(connection, sourceRelease);
                insertPackage(connection, targetRelease);
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private static void insertDefinition(
        java.sql.Connection connection,
        ApprovalDefinitionVersion definition,
        String definitionJson
    ) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
            insert into ap_approval_definition (
                tenant_id, definition_key, definition_version, definition_hash,
                form_package_version, form_package_hash, approval_dsl_json,
                source_draft_id, published_by, published_at
            ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
            """)) {
            statement.setString(1, definition.tenantId());
            statement.setString(2, definition.definitionKey());
            statement.setInt(3, definition.version());
            statement.setString(4, definition.contentHash());
            statement.setInt(5, definition.formPackageVersion());
            statement.setString(6, definition.formPackageHash());
            statement.setString(7, definitionJson);
            statement.setObject(8, definition.sourceDraftId());
            statement.setString(9, definition.publishedBy());
            statement.setObject(10, offset(definition.publishedAt()));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void insertPackage(
        java.sql.Connection connection,
        ApprovalReleasePackage release
    ) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
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
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """)) {
            int index = 1;
            statement.setString(index++, release.tenantId());
            statement.setString(index++, release.definitionKey());
            statement.setInt(index++, release.releaseVersion());
            statement.setInt(index++, release.definitionVersion());
            statement.setString(index++, release.definitionHash());
            statement.setInt(index++, release.formPackageVersion());
            statement.setString(index++, release.formPackageHash());
            statement.setInt(index++, release.formVersion());
            statement.setString(index++, release.formHash());
            statement.setInt(index++, release.uiSchemaVersion());
            statement.setString(index++, release.uiSchemaHash());
            statement.setString(index++, release.compilerVersion());
            statement.setString(index++, release.bpmnResourceName());
            statement.setString(index++, release.bpmnArtifact());
            statement.setString(index++, release.compiledArtifactHash());
            statement.setString(index++, release.bpmnHash());
            statement.setString(index++, release.dmnArtifact());
            statement.setString(index++, release.dmnHash());
            statement.setString(index++, release.deploymentMetadataHash());
            statement.setString(index++, release.packageHash());
            statement.setObject(index++, release.sourceDraftId());
            statement.setString(index++, release.publishedBy());
            statement.setObject(index, offset(release.publishedAt()));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private Map<String, String> businessSnapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            snapshot.put(table, tableSnapshot(table));
        }
        return Map.copyOf(snapshot);
    }

    private String tableSnapshot(String table) {
        if (!BUSINESS_TABLES.contains(table)) {
            throw new IllegalArgumentException("unsupported snapshot table");
        }
        return jdbc.queryForObject(
            "select coalesce(jsonb_agg(row_data order by row_data::text), '[]'::jsonb)::text "
                + "from (select to_jsonb(t) as row_data from " + table + " t) rows",
            String.class
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static String hash(int value) {
        return Integer.toHexString(Math.floorMod(value, 16)).repeat(64);
    }
}

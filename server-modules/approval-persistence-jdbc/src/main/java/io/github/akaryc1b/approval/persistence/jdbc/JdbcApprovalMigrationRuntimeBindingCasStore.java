package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptTransition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationBindingCasConflictEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFenceEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationInstanceCompletionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationRuntimeBindingEvidence;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL D5 exact-target binding CAS, completion and reconciliation boundary. */
public final class JdbcApprovalMigrationRuntimeBindingCasStore
    implements ApprovalMigrationRuntimeBindingCasStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final JdbcApprovalMigrationProtocolStore protocol;
    private final JdbcApprovalInstanceCommandFence commandFence;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcApprovalMigrationRuntimeBindingCasStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        DataSource source = Objects.requireNonNull(dataSource, "dataSource must not be null");
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        jdbc = new NamedParameterJdbcTemplate(source);
        json = new JdbcApprovalMigrationJson(mapper);
        protocol = new JdbcApprovalMigrationProtocolStore(source, mapper, manager);
        commandFence = new JdbcApprovalInstanceCommandFence(source);
        transactions = new TransactionTemplate(manager);
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    @Override
    public BindingCasResult complete(CompletionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return transactions.execute(status -> completeOnce(request));
        } catch (BindingCasException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new BindingCasException("runtime binding CAS persistence conflict", exception);
        }
    }

    private BindingCasResult completeOnce(CompletionRequest request) {
        String requestHash = requestHash(request);
        Optional<ApprovalMigrationInstanceCompletionEvidence> priorCompletion = findCompletion(
            request.tenantId(),
            request.attemptId()
        );
        if (priorCompletion.isPresent()) {
            ApprovalMigrationInstanceCompletionEvidence completion = priorCompletion.orElseThrow();
            requireRequestReplay(completion.requestHash(), requestHash);
            ApprovalMigrationAttempt attempt = lockAttempt(request.tenantId(), request.attemptId());
            ApprovalMigrationRuntimeBindingEvidence binding = findBindingEvidence(
                request.tenantId(),
                completion.bindingEvidenceId()
            );
            return new BindingCasResult(
                BindingCasDisposition.REPLAYED_COMPLETION,
                attempt,
                binding,
                completion,
                null
            );
        }
        Optional<ApprovalMigrationBindingCasConflictEvidence> priorConflict = findConflict(
            request.tenantId(),
            request.attemptId()
        );
        if (priorConflict.isPresent()) {
            ApprovalMigrationBindingCasConflictEvidence conflict = priorConflict.orElseThrow();
            requireRequestReplay(conflict.requestHash(), requestHash);
            return new BindingCasResult(
                BindingCasDisposition.REPLAYED_CONFLICT,
                lockAttempt(request.tenantId(), request.attemptId()),
                null,
                null,
                conflict
            );
        }

        ApprovalMigrationAttempt attempt = lockAttempt(request.tenantId(), request.attemptId());
        requireAttempt(attempt, request);
        VerificationAuthority verification = lockVerification(request, attempt);
        PlanAuthority plan = lockPlan(attempt);

        commandFence.acquireMigrationLock(attempt.tenantId(), attempt.approvalInstanceId());
        ApprovalMigrationCommandFence fence = lockFence(attempt.tenantId(), attempt.attemptId());
        requireFence(fence, request, verification);
        InstanceProjection instance = lockInstance(attempt);
        Optional<BindingState> bindingCandidate = lockBinding(attempt);

        if (!casAuthorityMatches(bindingCandidate.orElse(null), instance, attempt, plan, request)) {
            return recordConflict(
                request,
                requestHash,
                attempt,
                verification,
                plan,
                bindingCandidate.orElse(null)
            );
        }

        BindingState binding = bindingCandidate.orElseThrow();
        TargetRelease target = lockTarget(plan);
        UUID completionId = nextIdentifier("completionId");
        String auditReference = "migration-verification:" + verification.evidence().verificationId();
        String targetBindingHash = targetBindingHash(
            binding,
            target,
            attempt,
            verification,
            request,
            auditReference
        );

        int updatedBinding = updateBinding(
            binding,
            target,
            attempt,
            verification,
            request,
            targetBindingHash,
            auditReference
        );
        if (updatedBinding != 1) {
            return recordConflict(request, requestHash, attempt, verification, plan, binding);
        }
        ApprovalMigrationRuntimeBindingEvidence bindingEvidence = findBindingEvidenceByRevision(
            attempt.tenantId(),
            attempt.approvalInstanceId(),
            binding.bindingRevision() + 1
        );
        updateInstance(instance, binding, target, request);

        String completionHash = completionHash(
            completionId,
            attempt,
            verification,
            bindingEvidence,
            binding,
            plan,
            requestHash
        );
        ApprovalMigrationInstanceCompletionEvidence completion = new ApprovalMigrationInstanceCompletionEvidence(
            completionId,
            attempt.tenantId(),
            attempt.intentId(),
            attempt.attemptId(),
            attempt.approvalInstanceId(),
            verification.evidence().verificationId(),
            bindingEvidence.bindingEvidenceId(),
            bindingEvidence.bindingRevision(),
            attempt.revision(),
            fence.revision(),
            requestHash,
            binding.bindingEvidenceHash(),
            bindingEvidence.bindingEvidenceHash(),
            plan.sourceReleaseVersion(),
            plan.sourcePackageHash(),
            attempt.sourceEngineDefinitionId(),
            plan.targetReleaseVersion(),
            plan.targetPackageHash(),
            plan.targetEngineDeploymentId(),
            plan.targetEngineDefinitionId(),
            verification.evidence().verificationEvidenceHash(),
            completionHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
        insertCompletion(completion, request.workerId());

        ApprovalMigrationAttempt succeeded = attempt.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.SUCCEEDED,
            EngineOutcome.CONFIRMED,
            null,
            null,
            attempt.engineRequestReference(),
            FailureClass.NONE,
            null,
            request.happenedAt()
        ));
        ApprovalMigrationAttempt storedAttempt = protocol.transitionAttempt(
            succeeded,
            attempt.revision(),
            attemptEvent(attempt, succeeded, request.requestId(), request.traceId())
        );
        releaseFence(fence, request.workerId(), request.happenedAt());
        appendAudit(
            attempt.tenantId(),
            request.workerId(),
            "PROCESS_MIGRATION_INSTANCE_COMPLETED",
            attempt.attemptId().toString(),
            request.requestId(),
            request.traceId(),
            request.happenedAt(),
            Map.of(
                "completionId", completion.completionId().toString(),
                "verificationId", completion.verificationId().toString(),
                "bindingRevision", Long.toString(completion.bindingRevision()),
                "targetBindingEvidenceHash", completion.targetBindingEvidenceHash()
            )
        );
        return new BindingCasResult(
            BindingCasDisposition.COMPLETED,
            storedAttempt,
            bindingEvidence,
            completion,
            null
        );
    }

    private BindingCasResult recordConflict(
        CompletionRequest request,
        String requestHash,
        ApprovalMigrationAttempt attempt,
        VerificationAuthority verification,
        PlanAuthority plan,
        BindingState observed
    ) {
        UUID conflictId = nextIdentifier("bindingCasConflictId");
        String conflictHash = conflictHash(
            conflictId,
            request,
            requestHash,
            attempt,
            verification,
            plan,
            observed
        );
        ApprovalMigrationBindingCasConflictEvidence conflict =
            new ApprovalMigrationBindingCasConflictEvidence(
                conflictId,
                attempt.tenantId(),
                attempt.intentId(),
                attempt.attemptId(),
                attempt.approvalInstanceId(),
                verification.evidence().verificationId(),
                request.workerId(),
                attempt.revision(),
                request.expectedFenceRevision(),
                request.expectedBindingRevision(),
                attempt.expectedBindingEvidenceHash(),
                plan.sourceReleaseVersion(),
                plan.sourcePackageHash(),
                attempt.sourceEngineDefinitionId(),
                observed == null ? null : observed.bindingRevision(),
                observed == null ? null : observed.bindingEvidenceHash(),
                observed == null ? null : observed.releaseVersion(),
                observed == null ? null : observed.releasePackageHash(),
                observed == null ? null : observed.engineDefinitionId(),
                verification.evidence().verificationEvidenceHash(),
                requestHash,
                conflictHash,
                request.happenedAt(),
                request.requestId(),
                request.traceId()
            );
        insertConflict(conflict);
        ApprovalMigrationAttempt reconciling = attempt.transitioned(
            new ApprovalMigrationAttemptTransition(
                AttemptStatus.RECONCILING,
                EngineOutcome.VERIFICATION_MISMATCH,
                null,
                null,
                attempt.engineRequestReference(),
                FailureClass.RECONCILIATION_REQUIRED,
                "D5 runtime binding CAS conflict requires reconciliation",
                request.happenedAt()
            )
        );
        ApprovalMigrationAttempt stored = protocol.transitionAttempt(
            reconciling,
            attempt.revision(),
            attemptEvent(attempt, reconciling, request.requestId(), request.traceId())
        );
        appendAudit(
            attempt.tenantId(),
            request.workerId(),
            "PROCESS_MIGRATION_BINDING_CAS_CONFLICT_RECORDED",
            attempt.attemptId().toString(),
            request.requestId(),
            request.traceId(),
            request.happenedAt(),
            Map.of(
                "conflictId", conflict.conflictId().toString(),
                "verificationId", conflict.verificationId().toString(),
                "requestHash", requestHash,
                "conflictEvidenceHash", conflict.conflictEvidenceHash()
            )
        );
        return new BindingCasResult(
            BindingCasDisposition.RECONCILIATION_REQUIRED,
            stored,
            null,
            null,
            conflict
        );
    }

    private ApprovalMigrationAttempt lockAttempt(String tenantId, UUID attemptId) {
        return jdbc.query("""
            select payload_json::text from ap_process_migration_attempt
            where tenant_id=:tenantId and attempt_id=:attemptId for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationAttempt.class))
            .stream().findFirst().orElseThrow(() -> conflict("migration attempt does not exist"));
    }

    private VerificationAuthority lockVerification(
        CompletionRequest request,
        ApprovalMigrationAttempt attempt
    ) {
        return jdbc.query("""
            select payload_json::text,worker_id,expected_attempt_revision,
                   expected_fence_revision,verification_evidence_hash
            from ap_process_migration_exact_verification
            where tenant_id=:tenantId and verification_id=:verificationId
              and attempt_id=:attemptId for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", request.tenantId())
                .addValue("verificationId", request.verificationId())
                .addValue("attemptId", request.attemptId()),
            (row, number) -> new VerificationAuthority(
                json.read(row.getString("payload_json"), ApprovalMigrationExactVerification.class),
                row.getString("worker_id"),
                row.getLong("expected_attempt_revision"),
                row.getLong("expected_fence_revision"),
                row.getString("verification_evidence_hash")
            )).stream().findFirst()
            .filter(value -> value.evidence().exactTargetRuntime()
                && value.expectedAttemptRevision() == attempt.revision()
                && value.expectedFenceRevision() == request.expectedFenceRevision()
                && value.workerId().equals(request.workerId())
                && value.verificationEvidenceHash().equals(
                    value.evidence().verificationEvidenceHash()
                ))
            .orElseThrow(() -> conflict("exact target verification authority is missing"));
    }

    private PlanAuthority lockPlan(ApprovalMigrationAttempt attempt) {
        return jdbc.query("""
            select p.definition_key,p.source_release_version,p.source_package_hash,
                   p.target_release_version,p.target_package_hash,
                   p.target_engine_deployment_id,p.target_engine_definition_id,p.target_engine_version
            from ap_process_migration_intent i
            join ap_process_migration_plan p
              on p.tenant_id=i.tenant_id and p.plan_id=i.plan_id and p.plan_hash=i.plan_hash
            join ap_process_migration_plan_consumption c
              on c.tenant_id=i.tenant_id and c.intent_id=i.intent_id and c.plan_id=i.plan_id
            where i.tenant_id=:tenantId and i.intent_id=:intentId
              and i.status='RUNNING' and p.status='CONSUMED'
            for update of i,p
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("intentId", attempt.intentId()),
            (row, number) -> new PlanAuthority(
                row.getString("definition_key"),
                row.getInt("source_release_version"),
                row.getString("source_package_hash"),
                row.getInt("target_release_version"),
                row.getString("target_package_hash"),
                row.getString("target_engine_deployment_id"),
                row.getString("target_engine_definition_id"),
                row.getInt("target_engine_version")
            )).stream().findFirst()
            .filter(value -> value.targetEngineDefinitionId().equals(
                attempt.targetEngineDefinitionId()
            ))
            .orElseThrow(() -> conflict("migration plan target authority is stale"));
    }

    private ApprovalMigrationCommandFence lockFence(String tenantId, UUID attemptId) {
        return jdbc.query("""
            select payload_json::text from ap_approval_instance_command_fence
            where tenant_id=:tenantId and attempt_id=:attemptId and status='ACTIVE'
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationCommandFence.class))
            .stream().findFirst().orElseThrow(() -> conflict("active migration fence is missing"));
    }

    private InstanceProjection lockInstance(ApprovalMigrationAttempt attempt) {
        return jdbc.query("""
            select tenant_id,instance_id,business_key,engine_instance_id,definition_key,
                   definition_version,content_hash,form_key,form_version,compiler_version,
                   release_version,release_package_hash,form_package_version,form_package_hash,
                   ui_schema_version,ui_schema_hash,engine_definition_id,status,version
            from ap_approval_instance
            where tenant_id=:tenantId and instance_id=:instanceId for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("instanceId", attempt.approvalInstanceId()),
            (row, number) -> new InstanceProjection(
                row.getString("tenant_id"),
                row.getObject("instance_id", UUID.class),
                row.getString("business_key"),
                row.getString("engine_instance_id"),
                row.getString("definition_key"),
                row.getInt("definition_version"),
                row.getString("content_hash"),
                row.getString("form_key"),
                row.getInt("form_version"),
                row.getString("compiler_version"),
                row.getObject("release_version", Integer.class),
                row.getString("release_package_hash"),
                row.getObject("form_package_version", Integer.class),
                row.getString("form_package_hash"),
                row.getObject("ui_schema_version", Integer.class),
                row.getString("ui_schema_hash"),
                row.getString("engine_definition_id"),
                row.getString("status"),
                row.getLong("version")
            )).stream().findFirst()
            .orElseThrow(() -> conflict("approval instance does not exist"));
    }

    private Optional<BindingState> lockBinding(ApprovalMigrationAttempt attempt) {
        return jdbc.query("""
            select * from ap_process_runtime_binding
            where tenant_id=:tenantId and approval_instance_id=:instanceId for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("instanceId", attempt.approvalInstanceId()),
            (row, number) -> binding(row)).stream().findFirst();
    }

    private TargetRelease lockTarget(PlanAuthority plan) {
        return jdbc.query("""
            select package.definition_key,package.release_version,package.package_hash,
                   package.definition_version,package.definition_hash,
                   package.form_package_version,package.form_package_hash,
                   package.form_version,package.form_hash,
                   package.ui_schema_version,package.ui_schema_hash,
                   package.compiler_version,package.compiled_artifact_hash,
                   package.bpmn_hash,package.deployment_metadata_hash,
                   deployment.engine_deployment_id,deployment.engine_definition_id,
                   deployment.engine_version
            from ap_approval_release_package package
            join ap_approval_release_deployment deployment
              on deployment.tenant_id=package.tenant_id
             and deployment.definition_key=package.definition_key
             and deployment.release_version=package.release_version
             and deployment.release_package_hash=package.package_hash
            where package.tenant_id=:tenantId
              and package.definition_key=:definitionKey
              and package.release_version=:releaseVersion
              and package.package_hash=:packageHash
              and deployment.engine_deployment_id=:deploymentId
              and deployment.engine_definition_id=:definitionId
              and deployment.engine_version=:engineVersion
              and deployment.status='DEPLOYED'
            for update of package,deployment
            """, new MapSqlParameterSource()
                .addValue("tenantId", plan.tenantId())
                .addValue("definitionKey", plan.definitionKey())
                .addValue("releaseVersion", plan.targetReleaseVersion())
                .addValue("packageHash", plan.targetPackageHash())
                .addValue("deploymentId", plan.targetEngineDeploymentId())
                .addValue("definitionId", plan.targetEngineDefinitionId())
                .addValue("engineVersion", plan.targetEngineVersion()),
            (row, number) -> new TargetRelease(
                row.getString("definition_key"),
                row.getInt("release_version"),
                row.getString("package_hash"),
                row.getInt("definition_version"),
                row.getString("definition_hash"),
                row.getInt("form_package_version"),
                row.getString("form_package_hash"),
                row.getInt("form_version"),
                row.getString("form_hash"),
                row.getInt("ui_schema_version"),
                row.getString("ui_schema_hash"),
                row.getString("compiler_version"),
                row.getString("compiled_artifact_hash"),
                row.getString("bpmn_hash"),
                row.getString("deployment_metadata_hash"),
                row.getString("engine_deployment_id"),
                row.getString("engine_definition_id"),
                row.getInt("engine_version")
            )).stream().findFirst()
            .orElseThrow(() -> conflict("exact target package and deployment are missing"));
    }

    private boolean casAuthorityMatches(
        BindingState binding,
        InstanceProjection instance,
        ApprovalMigrationAttempt attempt,
        PlanAuthority plan,
        CompletionRequest request
    ) {
        return binding != null
            && binding.tenantId().equals(attempt.tenantId())
            && binding.approvalInstanceId().equals(attempt.approvalInstanceId())
            && binding.businessKey().equals(instance.businessKey())
            && binding.engineInstanceId().equals(attempt.engineInstanceId())
            && binding.definitionKey().equals(plan.definitionKey())
            && binding.bindingRevision() == request.expectedBindingRevision()
            && binding.bindingEvidenceHash().equals(attempt.expectedBindingEvidenceHash())
            && binding.releaseVersion() == plan.sourceReleaseVersion()
            && binding.releasePackageHash().equals(plan.sourcePackageHash())
            && binding.engineDefinitionId().equals(attempt.sourceEngineDefinitionId())
            && instance.status().equals("RUNNING")
            && instance.releaseVersion() != null
            && instance.releaseVersion() == plan.sourceReleaseVersion()
            && plan.sourcePackageHash().equals(instance.releasePackageHash())
            && attempt.sourceEngineDefinitionId().equals(instance.engineDefinitionId())
            && instance.version() > 0;
    }

    private int updateBinding(
        BindingState source,
        TargetRelease target,
        ApprovalMigrationAttempt attempt,
        VerificationAuthority verification,
        CompletionRequest request,
        String targetBindingHash,
        String auditReference
    ) {
        return jdbc.update("""
            update ap_process_runtime_binding set
             release_version=:targetReleaseVersion,
             release_package_hash=:targetPackageHash,
             definition_version=:definitionVersion,
             definition_hash=:definitionHash,
             form_package_version=:formPackageVersion,
             form_package_hash=:formPackageHash,
             form_version=:formVersion,
             form_hash=:formHash,
             ui_schema_version=:uiSchemaVersion,
             ui_schema_hash=:uiSchemaHash,
             compiler_version=:compilerVersion,
             compiled_artifact_hash=:compiledArtifactHash,
             bpmn_hash=:bpmnHash,
             deployment_metadata_hash=:deploymentMetadataHash,
             engine_deployment_id=:engineDeploymentId,
             engine_definition_id=:engineDefinitionId,
             engine_version=:engineVersion,
             binding_evidence_hash=:targetBindingHash,
             binding_revision=binding_revision+1,
             last_migration_attempt_id=:attemptId,
             last_verification_id=:verificationId,
             last_verification_evidence_hash=:verificationHash,
             bound_by=:workerId,
             bound_at=:happenedAt,
             request_id=:requestId,
             trace_id=:traceId,
             audit_chain_reference=:auditReference
            where tenant_id=:tenantId and approval_instance_id=:instanceId
             and binding_revision=:expectedBindingRevision
             and binding_evidence_hash=:sourceBindingHash
             and release_version=:sourceReleaseVersion
             and release_package_hash=:sourcePackageHash
             and engine_definition_id=:sourceDefinitionId
            """, new MapSqlParameterSource()
                .addValue("targetReleaseVersion", target.releaseVersion())
                .addValue("targetPackageHash", target.packageHash())
                .addValue("definitionVersion", target.definitionVersion())
                .addValue("definitionHash", target.definitionHash())
                .addValue("formPackageVersion", target.formPackageVersion())
                .addValue("formPackageHash", target.formPackageHash())
                .addValue("formVersion", target.formVersion())
                .addValue("formHash", target.formHash())
                .addValue("uiSchemaVersion", target.uiSchemaVersion())
                .addValue("uiSchemaHash", target.uiSchemaHash())
                .addValue("compilerVersion", target.compilerVersion())
                .addValue("compiledArtifactHash", target.compiledArtifactHash())
                .addValue("bpmnHash", target.bpmnHash())
                .addValue("deploymentMetadataHash", target.deploymentMetadataHash())
                .addValue("engineDeploymentId", target.engineDeploymentId())
                .addValue("engineDefinitionId", target.engineDefinitionId())
                .addValue("engineVersion", target.engineVersion())
                .addValue("targetBindingHash", targetBindingHash)
                .addValue("attemptId", attempt.attemptId())
                .addValue("verificationId", verification.evidence().verificationId())
                .addValue("verificationHash", verification.evidence().verificationEvidenceHash())
                .addValue("workerId", request.workerId())
                .addValue("happenedAt", offset(request.happenedAt()))
                .addValue("requestId", request.requestId())
                .addValue("traceId", request.traceId())
                .addValue("auditReference", auditReference)
                .addValue("tenantId", source.tenantId())
                .addValue("instanceId", source.approvalInstanceId())
                .addValue("expectedBindingRevision", request.expectedBindingRevision())
                .addValue("sourceBindingHash", source.bindingEvidenceHash())
                .addValue("sourceReleaseVersion", source.releaseVersion())
                .addValue("sourcePackageHash", source.releasePackageHash())
                .addValue("sourceDefinitionId", source.engineDefinitionId()));
    }

    private void updateInstance(
        InstanceProjection instance,
        BindingState source,
        TargetRelease target,
        CompletionRequest request
    ) {
        int updated = jdbc.update("""
            update ap_approval_instance set
             definition_version=:definitionVersion,
             content_hash=:definitionHash,
             form_version=:formVersion,
             compiler_version=:compilerVersion,
             release_version=:releaseVersion,
             release_package_hash=:packageHash,
             form_package_version=:formPackageVersion,
             form_package_hash=:formPackageHash,
             ui_schema_version=:uiSchemaVersion,
             ui_schema_hash=:uiSchemaHash,
             engine_definition_id=:engineDefinitionId,
             version=version+1,
             updated_at=:happenedAt
            where tenant_id=:tenantId and instance_id=:instanceId
             and status='RUNNING' and version=:expectedVersion
             and definition_version=:sourceDefinitionVersion
             and content_hash=:sourceDefinitionHash
             and release_version=:sourceReleaseVersion
             and release_package_hash=:sourcePackageHash
             and form_package_version=:sourceFormPackageVersion
             and form_package_hash=:sourceFormPackageHash
             and form_version=:sourceFormVersion
             and ui_schema_version=:sourceUiSchemaVersion
             and ui_schema_hash=:sourceUiSchemaHash
             and compiler_version=:sourceCompilerVersion
             and engine_definition_id=:sourceEngineDefinitionId
            """, new MapSqlParameterSource()
                .addValue("definitionVersion", target.definitionVersion())
                .addValue("definitionHash", target.definitionHash())
                .addValue("formVersion", target.formVersion())
                .addValue("compilerVersion", target.compilerVersion())
                .addValue("releaseVersion", target.releaseVersion())
                .addValue("packageHash", target.packageHash())
                .addValue("formPackageVersion", target.formPackageVersion())
                .addValue("formPackageHash", target.formPackageHash())
                .addValue("uiSchemaVersion", target.uiSchemaVersion())
                .addValue("uiSchemaHash", target.uiSchemaHash())
                .addValue("engineDefinitionId", target.engineDefinitionId())
                .addValue("happenedAt", offset(request.happenedAt()))
                .addValue("tenantId", instance.tenantId())
                .addValue("instanceId", instance.instanceId())
                .addValue("expectedVersion", instance.version())
                .addValue("sourceDefinitionVersion", source.definitionVersion())
                .addValue("sourceDefinitionHash", source.definitionHash())
                .addValue("sourceReleaseVersion", source.releaseVersion())
                .addValue("sourcePackageHash", source.releasePackageHash())
                .addValue("sourceFormPackageVersion", source.formPackageVersion())
                .addValue("sourceFormPackageHash", source.formPackageHash())
                .addValue("sourceFormVersion", source.formVersion())
                .addValue("sourceUiSchemaVersion", source.uiSchemaVersion())
                .addValue("sourceUiSchemaHash", source.uiSchemaHash())
                .addValue("sourceCompilerVersion", source.compilerVersion())
                .addValue("sourceEngineDefinitionId", source.engineDefinitionId()));
        if (updated != 1) {
            throw conflict("approval instance release projection changed during binding CAS");
        }
    }

    private String targetBindingHash(
        BindingState source,
        TargetRelease target,
        ApprovalMigrationAttempt attempt,
        VerificationAuthority verification,
        CompletionRequest request,
        String auditReference
    ) {
        return jdbc.queryForObject("""
            select encode(sha256(convert_to(concat_ws(chr(31),
             'm5-runtime-binding-v44',:tenantId,:instanceId,:businessKey,:engineInstanceId,
             :definitionKey,:releaseVersion,:packageHash,:definitionVersion,:definitionHash,
             :formPackageVersion,:formPackageHash,:formVersion,:formHash,
             :uiSchemaVersion,:uiSchemaHash,:compilerVersion,:compiledArtifactHash,
             :bpmnHash,:deploymentMetadataHash,:engineDeploymentId,:engineDefinitionId,
             :engineVersion,:bindingRevision,:attemptId,:verificationId,:verificationHash,
             :workerId,cast(:happenedAt as timestamptz)::text,:requestId,coalesce(:traceId,''),
             :auditReference
            ),'UTF8')),'hex')
            """, new MapSqlParameterSource()
                .addValue("tenantId", source.tenantId())
                .addValue("instanceId", source.approvalInstanceId().toString())
                .addValue("businessKey", source.businessKey())
                .addValue("engineInstanceId", source.engineInstanceId())
                .addValue("definitionKey", source.definitionKey())
                .addValue("releaseVersion", Integer.toString(target.releaseVersion()))
                .addValue("packageHash", target.packageHash())
                .addValue("definitionVersion", Integer.toString(target.definitionVersion()))
                .addValue("definitionHash", target.definitionHash())
                .addValue("formPackageVersion", Integer.toString(target.formPackageVersion()))
                .addValue("formPackageHash", target.formPackageHash())
                .addValue("formVersion", Integer.toString(target.formVersion()))
                .addValue("formHash", target.formHash())
                .addValue("uiSchemaVersion", Integer.toString(target.uiSchemaVersion()))
                .addValue("uiSchemaHash", target.uiSchemaHash())
                .addValue("compilerVersion", target.compilerVersion())
                .addValue("compiledArtifactHash", target.compiledArtifactHash())
                .addValue("bpmnHash", target.bpmnHash())
                .addValue("deploymentMetadataHash", target.deploymentMetadataHash())
                .addValue("engineDeploymentId", target.engineDeploymentId())
                .addValue("engineDefinitionId", target.engineDefinitionId())
                .addValue("engineVersion", Integer.toString(target.engineVersion()))
                .addValue("bindingRevision", Long.toString(source.bindingRevision() + 1))
                .addValue("attemptId", attempt.attemptId().toString())
                .addValue("verificationId", verification.evidence().verificationId().toString())
                .addValue("verificationHash", verification.evidence().verificationEvidenceHash())
                .addValue("workerId", request.workerId())
                .addValue("happenedAt", offset(request.happenedAt()))
                .addValue("requestId", request.requestId())
                .addValue("traceId", request.traceId())
                .addValue("auditReference", auditReference), String.class);
    }

    private ApprovalMigrationRuntimeBindingEvidence findBindingEvidenceByRevision(
        String tenantId,
        UUID instanceId,
        long revision
    ) {
        return jdbc.query("""
            select * from ap_process_runtime_binding_evidence
            where tenant_id=:tenantId and approval_instance_id=:instanceId
              and binding_revision=:revision
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("instanceId", instanceId)
                .addValue("revision", revision),
            (row, number) -> bindingEvidence(row)).stream().findFirst()
            .orElseThrow(() -> conflict("runtime binding revision evidence is missing"));
    }

    private ApprovalMigrationRuntimeBindingEvidence findBindingEvidence(
        String tenantId,
        UUID evidenceId
    ) {
        return jdbc.query("""
            select * from ap_process_runtime_binding_evidence
            where tenant_id=:tenantId and binding_evidence_id=:evidenceId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("evidenceId", evidenceId),
            (row, number) -> bindingEvidence(row)).stream().findFirst()
            .orElseThrow(() -> conflict("runtime binding evidence does not exist"));
    }

    private Optional<ApprovalMigrationInstanceCompletionEvidence> findCompletion(
        String tenantId,
        UUID attemptId
    ) {
        return jdbc.query("""
            select payload_json::text from ap_process_migration_instance_completion
            where tenant_id=:tenantId and attempt_id=:attemptId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationInstanceCompletionEvidence.class
            )).stream().findFirst();
    }

    private Optional<ApprovalMigrationBindingCasConflictEvidence> findConflict(
        String tenantId,
        UUID attemptId
    ) {
        return jdbc.query("""
            select payload_json::text from ap_process_migration_binding_cas_conflict
            where tenant_id=:tenantId and attempt_id=:attemptId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationBindingCasConflictEvidence.class
            )).stream().findFirst();
    }

    private void insertCompletion(
        ApprovalMigrationInstanceCompletionEvidence value,
        String workerId
    ) {
        jdbc.update("""
            insert into ap_process_migration_instance_completion (
             tenant_id,completion_id,intent_id,attempt_id,approval_instance_id,
             verification_id,binding_evidence_id,binding_revision,
             expected_attempt_revision,expected_fence_revision,worker_id,request_hash,
             source_binding_evidence_hash,target_binding_evidence_hash,
             source_release_version,source_package_hash,source_engine_definition_id,
             target_release_version,target_package_hash,target_engine_deployment_id,
             target_engine_definition_id,verification_evidence_hash,completion_evidence_hash,
             completed_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:completionId,:intentId,:attemptId,:instanceId,
             :verificationId,:bindingEvidenceId,:bindingRevision,
             :attemptRevision,:fenceRevision,:workerId,:requestHash,
             :sourceBindingHash,:targetBindingHash,:sourceReleaseVersion,:sourcePackageHash,
             :sourceDefinitionId,:targetReleaseVersion,:targetPackageHash,:targetDeploymentId,
             :targetDefinitionId,:verificationHash,:completionHash,
             :completedAt,:requestId,:traceId,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", value.tenantId())
                .addValue("completionId", value.completionId())
                .addValue("intentId", value.intentId())
                .addValue("attemptId", value.attemptId())
                .addValue("instanceId", value.approvalInstanceId())
                .addValue("verificationId", value.verificationId())
                .addValue("bindingEvidenceId", value.bindingEvidenceId())
                .addValue("bindingRevision", value.bindingRevision())
                .addValue("attemptRevision", value.expectedAttemptRevision())
                .addValue("fenceRevision", value.expectedFenceRevision())
                .addValue("workerId", workerId)
                .addValue("requestHash", value.requestHash())
                .addValue("sourceBindingHash", value.sourceBindingEvidenceHash())
                .addValue("targetBindingHash", value.targetBindingEvidenceHash())
                .addValue("sourceReleaseVersion", value.sourceReleaseVersion())
                .addValue("sourcePackageHash", value.sourcePackageHash())
                .addValue("sourceDefinitionId", value.sourceEngineDefinitionId())
                .addValue("targetReleaseVersion", value.targetReleaseVersion())
                .addValue("targetPackageHash", value.targetPackageHash())
                .addValue("targetDeploymentId", value.targetEngineDeploymentId())
                .addValue("targetDefinitionId", value.targetEngineDefinitionId())
                .addValue("verificationHash", value.verificationEvidenceHash())
                .addValue("completionHash", value.completionEvidenceHash())
                .addValue("completedAt", offset(value.completedAt()))
                .addValue("requestId", value.requestId())
                .addValue("traceId", value.traceId())
                .addValue("payload", json.write(value)));
    }

    private void insertConflict(ApprovalMigrationBindingCasConflictEvidence value) {
        jdbc.update("""
            insert into ap_process_migration_binding_cas_conflict (
             tenant_id,conflict_id,intent_id,attempt_id,approval_instance_id,verification_id,
             worker_id,expected_attempt_revision,expected_fence_revision,
             expected_binding_revision,expected_binding_evidence_hash,
             expected_source_release_version,expected_source_package_hash,
             expected_source_engine_definition_id,observed_binding_revision,
             observed_binding_evidence_hash,observed_release_version,observed_package_hash,
             observed_engine_definition_id,verification_evidence_hash,request_hash,
             conflict_evidence_hash,recorded_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:conflictId,:intentId,:attemptId,:instanceId,:verificationId,
             :workerId,:attemptRevision,:fenceRevision,:bindingRevision,:bindingHash,
             :sourceReleaseVersion,:sourcePackageHash,:sourceDefinitionId,
             :observedBindingRevision,:observedBindingHash,:observedReleaseVersion,
             :observedPackageHash,:observedDefinitionId,:verificationHash,:requestHash,
             :conflictHash,:recordedAt,:requestId,:traceId,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", value.tenantId())
                .addValue("conflictId", value.conflictId())
                .addValue("intentId", value.intentId())
                .addValue("attemptId", value.attemptId())
                .addValue("instanceId", value.approvalInstanceId())
                .addValue("verificationId", value.verificationId())
                .addValue("workerId", value.workerId())
                .addValue("attemptRevision", value.expectedAttemptRevision())
                .addValue("fenceRevision", value.expectedFenceRevision())
                .addValue("bindingRevision", value.expectedBindingRevision())
                .addValue("bindingHash", value.expectedBindingEvidenceHash())
                .addValue("sourceReleaseVersion", value.expectedSourceReleaseVersion())
                .addValue("sourcePackageHash", value.expectedSourcePackageHash())
                .addValue("sourceDefinitionId", value.expectedSourceEngineDefinitionId())
                .addValue("observedBindingRevision", value.observedBindingRevision())
                .addValue("observedBindingHash", value.observedBindingEvidenceHash())
                .addValue("observedReleaseVersion", value.observedReleaseVersion())
                .addValue("observedPackageHash", value.observedPackageHash())
                .addValue("observedDefinitionId", value.observedEngineDefinitionId())
                .addValue("verificationHash", value.verificationEvidenceHash())
                .addValue("requestHash", value.requestHash())
                .addValue("conflictHash", value.conflictEvidenceHash())
                .addValue("recordedAt", offset(value.recordedAt()))
                .addValue("requestId", value.requestId())
                .addValue("traceId", value.traceId())
                .addValue("payload", json.write(value)));
    }

    private void releaseFence(
        ApprovalMigrationCommandFence current,
        String workerId,
        Instant happenedAt
    ) {
        ApprovalMigrationCommandFence released = current.released(workerId, happenedAt);
        int updated = jdbc.update("""
            update ap_approval_instance_command_fence set
             status=:status,revision=:revision,lease_owner=:leaseOwner,lease_until=:leaseUntil,
             updated_at=:updatedAt,released_at=:releasedAt,payload_json=cast(:payload as jsonb)
            where tenant_id=:tenantId and fence_id=:fenceId
             and status='ACTIVE' and revision=:expectedRevision
            """, new MapSqlParameterSource()
                .addValue("status", released.status().name())
                .addValue("revision", released.revision())
                .addValue("leaseOwner", released.leaseOwner())
                .addValue("leaseUntil", offset(released.leaseUntil()))
                .addValue("updatedAt", offset(released.updatedAt()))
                .addValue("releasedAt", offset(released.releasedAt()))
                .addValue("payload", json.write(released))
                .addValue("tenantId", current.tenantId())
                .addValue("fenceId", current.fenceId())
                .addValue("expectedRevision", current.revision()));
        if (updated != 1) {
            throw conflict("migration command fence changed before release");
        }
        ApprovalMigrationCommandFenceEvent event = ApprovalMigrationCommandFenceEvent.from(
            nextIdentifier("fenceEventId"),
            current,
            released,
            workerId
        );
        jdbc.update("""
            insert into ap_approval_instance_command_fence_event (
             tenant_id,event_id,fence_id,approval_instance_id,attempt_id,revision,
             from_status,to_status,lease_actor,lease_owner,lease_until,happened_at,
             request_id,trace_id,payload_json
            ) values (
             :tenantId,:eventId,:fenceId,:instanceId,:attemptId,:revision,
             :fromStatus,:toStatus,:leaseActor,:leaseOwner,:leaseUntil,:happenedAt,
             :requestId,:traceId,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", event.eventId())
                .addValue("fenceId", event.fenceId())
                .addValue("instanceId", event.approvalInstanceId())
                .addValue("attemptId", event.attemptId())
                .addValue("revision", event.revision())
                .addValue("fromStatus", event.fromStatus().name())
                .addValue("toStatus", event.toStatus().name())
                .addValue("leaseActor", event.leaseActor())
                .addValue("leaseOwner", event.leaseOwner())
                .addValue("leaseUntil", offset(event.leaseUntil()))
                .addValue("happenedAt", offset(event.happenedAt()))
                .addValue("requestId", event.requestId())
                .addValue("traceId", event.traceId())
                .addValue("payload", json.write(event)));
    }

    private ApprovalMigrationAttemptEvent attemptEvent(
        ApprovalMigrationAttempt current,
        ApprovalMigrationAttempt next,
        String requestId,
        String traceId
    ) {
        return new ApprovalMigrationAttemptEvent(
            nextIdentifier("attemptEventId"),
            next.tenantId(),
            next.attemptId(),
            next.revision(),
            current.status(),
            next.status(),
            next.engineOutcome(),
            next.failureClass(),
            next.errorSummary(),
            next.updatedAt(),
            requestId,
            traceId
        );
    }

    private void appendAudit(
        String tenantId,
        String operatorId,
        String action,
        String aggregateId,
        String requestId,
        String traceId,
        Instant happenedAt,
        Map<String, String> attributes
    ) {
        auditEvents.append(new AuditEvent(
            nextIdentifier("auditEventId"),
            tenantId,
            operatorId,
            action,
            "APPROVAL_MIGRATION_ATTEMPT",
            aggregateId,
            requestId,
            traceId,
            happenedAt,
            attributes
        ));
    }

    private static void requireAttempt(
        ApprovalMigrationAttempt attempt,
        CompletionRequest request
    ) {
        if (attempt.status() != AttemptStatus.VERIFYING
            || attempt.revision() != request.expectedAttemptRevision()) {
            throw conflict("attempt is not the exact VERIFYING revision");
        }
    }

    private static void requireFence(
        ApprovalMigrationCommandFence fence,
        CompletionRequest request,
        VerificationAuthority verification
    ) {
        if (fence.status() != ApprovalMigrationCommandFence.FenceStatus.ACTIVE
            || fence.revision() != request.expectedFenceRevision()
            || fence.revision() != verification.expectedFenceRevision()
            || !fence.leaseOwner().equals(request.workerId())
            || !fence.leaseUntil().isAfter(request.happenedAt())) {
            throw conflict("binding CAS command fence authority is stale");
        }
    }

    private static String completionHash(
        UUID completionId,
        ApprovalMigrationAttempt attempt,
        VerificationAuthority verification,
        ApprovalMigrationRuntimeBindingEvidence binding,
        BindingState source,
        PlanAuthority plan,
        String requestHash
    ) {
        return sha256(String.join(
            "\u001f",
            "m5-instance-completion-v44",
            completionId.toString(),
            attempt.tenantId(),
            attempt.intentId().toString(),
            attempt.attemptId().toString(),
            attempt.approvalInstanceId().toString(),
            verification.evidence().verificationId().toString(),
            binding.bindingEvidenceId().toString(),
            Long.toString(binding.bindingRevision()),
            requestHash,
            source.bindingEvidenceHash(),
            binding.bindingEvidenceHash(),
            Integer.toString(plan.sourceReleaseVersion()),
            plan.sourcePackageHash(),
            attempt.sourceEngineDefinitionId(),
            Integer.toString(plan.targetReleaseVersion()),
            plan.targetPackageHash(),
            plan.targetEngineDeploymentId(),
            plan.targetEngineDefinitionId(),
            verification.evidence().verificationEvidenceHash()
        ));
    }

    private static String conflictHash(
        UUID conflictId,
        CompletionRequest request,
        String requestHash,
        ApprovalMigrationAttempt attempt,
        VerificationAuthority verification,
        PlanAuthority plan,
        BindingState observed
    ) {
        return sha256(String.join(
            "\u001f",
            "m5-binding-cas-conflict-v44",
            conflictId.toString(),
            attempt.tenantId(),
            attempt.intentId().toString(),
            attempt.attemptId().toString(),
            attempt.approvalInstanceId().toString(),
            verification.evidence().verificationId().toString(),
            request.workerId(),
            Long.toString(attempt.revision()),
            Long.toString(request.expectedFenceRevision()),
            Long.toString(request.expectedBindingRevision()),
            attempt.expectedBindingEvidenceHash(),
            Integer.toString(plan.sourceReleaseVersion()),
            plan.sourcePackageHash(),
            attempt.sourceEngineDefinitionId(),
            observed == null ? "" : Long.toString(observed.bindingRevision()),
            observed == null ? "" : observed.bindingEvidenceHash(),
            observed == null ? "" : Integer.toString(observed.releaseVersion()),
            observed == null ? "" : observed.releasePackageHash(),
            observed == null ? "" : observed.engineDefinitionId(),
            verification.evidence().verificationEvidenceHash(),
            requestHash
        ));
    }

    private static String requestHash(CompletionRequest request) {
        return sha256(String.join(
            "|",
            "m5-runtime-binding-cas-request-v1",
            request.tenantId(),
            request.attemptId().toString(),
            request.verificationId().toString(),
            request.workerId(),
            Long.toString(request.expectedAttemptRevision()),
            Long.toString(request.expectedFenceRevision()),
            Long.toString(request.expectedBindingRevision()),
            request.requestId()
        ));
    }

    private static void requireRequestReplay(String stored, String requested) {
        if (!stored.equals(requested)) {
            throw conflict("changed-payload runtime binding CAS replay is forbidden");
        }
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(identifiers.get(), "generated " + name + " must not be null");
    }

    private static ApprovalMigrationRuntimeBindingEvidence bindingEvidence(ResultSet row)
        throws SQLException {
        return new ApprovalMigrationRuntimeBindingEvidence(
            row.getObject("binding_evidence_id", UUID.class),
            row.getString("tenant_id"),
            row.getObject("approval_instance_id", UUID.class),
            row.getLong("binding_revision"),
            row.getObject("attempt_id", UUID.class),
            row.getObject("verification_id", UUID.class),
            row.getString("previous_binding_evidence_hash"),
            row.getString("binding_evidence_hash"),
            row.getInt("release_version"),
            row.getString("release_package_hash"),
            row.getString("engine_deployment_id"),
            row.getString("engine_definition_id"),
            row.getInt("engine_version"),
            row.getString("evidence_hash"),
            instant(row, "recorded_at"),
            row.getString("request_id"),
            row.getString("trace_id")
        );
    }

    private static BindingState binding(ResultSet row) throws SQLException {
        return new BindingState(
            row.getString("tenant_id"),
            row.getObject("approval_instance_id", UUID.class),
            row.getString("business_key"),
            row.getString("engine_instance_id"),
            row.getString("definition_key"),
            row.getInt("release_version"),
            row.getString("release_package_hash"),
            row.getInt("definition_version"),
            row.getString("definition_hash"),
            row.getInt("form_package_version"),
            row.getString("form_package_hash"),
            row.getInt("form_version"),
            row.getString("form_hash"),
            row.getInt("ui_schema_version"),
            row.getString("ui_schema_hash"),
            row.getString("compiler_version"),
            row.getString("compiled_artifact_hash"),
            row.getString("bpmn_hash"),
            row.getString("deployment_metadata_hash"),
            row.getString("engine_deployment_id"),
            row.getString("engine_definition_id"),
            row.getInt("engine_version"),
            row.getString("binding_evidence_hash"),
            row.getLong("binding_revision")
        );
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(java.time.ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        return row.getObject(name, OffsetDateTime.class).toInstant();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static BindingCasException conflict(String message) {
        return new BindingCasException(message);
    }

    private record VerificationAuthority(
        ApprovalMigrationExactVerification evidence,
        String workerId,
        long expectedAttemptRevision,
        long expectedFenceRevision,
        String verificationEvidenceHash
    ) {
    }

    private record PlanAuthority(
        String definitionKey,
        int sourceReleaseVersion,
        String sourcePackageHash,
        int targetReleaseVersion,
        String targetPackageHash,
        String targetEngineDeploymentId,
        String targetEngineDefinitionId,
        int targetEngineVersion
    ) {
        String tenantId() {
            throw new UnsupportedOperationException("tenant is supplied by the attempt");
        }
    }

    private record InstanceProjection(
        String tenantId,
        UUID instanceId,
        String businessKey,
        String engineInstanceId,
        String definitionKey,
        int definitionVersion,
        String definitionHash,
        String formKey,
        int formVersion,
        String compilerVersion,
        Integer releaseVersion,
        String releasePackageHash,
        Integer formPackageVersion,
        String formPackageHash,
        Integer uiSchemaVersion,
        String uiSchemaHash,
        String engineDefinitionId,
        String status,
        long version
    ) {
    }

    private record BindingState(
        String tenantId,
        UUID approvalInstanceId,
        String businessKey,
        String engineInstanceId,
        String definitionKey,
        int releaseVersion,
        String releasePackageHash,
        int definitionVersion,
        String definitionHash,
        int formPackageVersion,
        String formPackageHash,
        int formVersion,
        String formHash,
        int uiSchemaVersion,
        String uiSchemaHash,
        String compilerVersion,
        String compiledArtifactHash,
        String bpmnHash,
        String deploymentMetadataHash,
        String engineDeploymentId,
        String engineDefinitionId,
        int engineVersion,
        String bindingEvidenceHash,
        long bindingRevision
    ) {
    }

    private record TargetRelease(
        String definitionKey,
        int releaseVersion,
        String packageHash,
        int definitionVersion,
        String definitionHash,
        int formPackageVersion,
        String formPackageHash,
        int formVersion,
        String formHash,
        int uiSchemaVersion,
        String uiSchemaHash,
        String compilerVersion,
        String compiledArtifactHash,
        String bpmnHash,
        String deploymentMetadataHash,
        String engineDeploymentId,
        String engineDefinitionId,
        int engineVersion
    ) {
    }
}

package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidence.AdvisoryCounts;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.KnowledgeSourceVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ModelVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.OutputSchemaVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PromptTemplateVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ProviderVersion;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** MySQL 8.4 P4 hash-only evidence, replay and retention-tombstone boundary. */
public final class JdbcMySqlApprovalAssistanceDurableEvidenceStore
    implements ApprovalAssistanceDurableEvidenceStore {

    private static final String ZERO_HASH = "0".repeat(64);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Supplier<UUID> eventIdentifiers;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;

    public JdbcMySqlApprovalAssistanceDurableEvidenceStore(
        DataSource dataSource,
        PlatformTransactionManager transactionManager,
        Supplier<UUID> eventIdentifiers
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalAssistanceDurableEvidenceStore requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.transactions = new TransactionTemplate(manager);
        this.eventIdentifiers = Objects.requireNonNull(
            eventIdentifiers,
            "eventIdentifiers must not be null"
        );
        this.locks = new JdbcMySqlTransactionLockManager(source);
    }

    @Override
    public StoreResult store(ApprovalAssistanceDurableEvidence evidence) {
        ApprovalAssistanceDurableEvidence exact = requireCanonicalEvidence(evidence);
        try {
            return Objects.requireNonNull(
                transactions.execute(status -> storeOnce(exact)),
                "store transaction must return a result"
            );
        } catch (DataAccessException exception) {
            throw new EvidencePersistenceException(
                "approval-assistance evidence persistence failed",
                exception
            );
        }
    }

    @Override
    public TombstoneResult tombstone(TombstoneCommand command) {
        TombstoneCommand exact = canonicalCommand(command);
        try {
            return Objects.requireNonNull(
                transactions.execute(status -> tombstoneOnce(exact)),
                "tombstone transaction must return a result"
            );
        } catch (DataAccessException exception) {
            throw new EvidencePersistenceException(
                "approval-assistance tombstone persistence failed",
                exception
            );
        }
    }

    @Override
    public Optional<EvidenceSnapshot> find(String tenantId, UUID evidenceId) {
        String tenant = requireText(tenantId, "tenantId", 128);
        UUID exactId = Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        try {
            List<SnapshotAuthority> rows = jdbc.query("""
                select e.*,s.revision,s.state,s.delete_reason,s.tombstoned_at,
                       s.deletion_request_hash,s.tombstone_hash,s.current_event_hash,
                       ev.event_type as authority_event_type,
                       ev.predecessor_hash as authority_predecessor_hash,
                       ev.event_hash as authority_event_hash,
                       ev.happened_at as authority_happened_at,
                       ev.delete_reason as authority_delete_reason,
                       ev.deletion_request_hash as authority_deletion_request_hash,
                       ev.tombstone_hash as authority_tombstone_hash
                from ap_ai_approval_assistance_evidence e
                join ap_ai_approval_assistance_evidence_state s
                  on s.tenant_id=e.tenant_id and s.evidence_id=e.evidence_id
                join ap_ai_approval_assistance_evidence_event ev
                  on ev.tenant_id=s.tenant_id and ev.evidence_id=s.evidence_id
                 and ev.revision=s.revision and ev.event_hash=s.current_event_hash
                where e.tenant_id=:tenantId and e.evidence_id=:evidenceId
                """, new MapSqlParameterSource()
                    .addValue("tenantId", tenant)
                    .addValue("evidenceId", values.bindUuid(exactId)),
                (row, number) -> snapshotAuthority(row)
            );
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            if (rows.size() != 1) {
                throw authorityDiverged();
            }
            SnapshotAuthority authority = rows.getFirst();
            requireEventStateAuthority(authority);
            return Optional.of(authority.snapshot());
        } catch (DataAccessException exception) {
            throw new EvidencePersistenceException(
                "approval-assistance evidence read failed",
                exception
            );
        }
    }

    private StoreResult storeOnce(ApprovalAssistanceDurableEvidence exact) {
        acquireIdentityLocks(exact);
        List<StoredState> collisions = findCollisions(exact);
        if (!collisions.isEmpty()) {
            return classifyExisting(exact, collisions);
        }

        int inserted = insertEvidence(exact);
        if (inserted != 1) {
            throw new EvidencePersistenceException(
                "MySQL evidence insert did not create exactly one row"
            );
        }

        UUID eventId = nextEventId();
        String eventHash = storedEventHash(eventId, exact);
        insertStoredEvent(eventId, exact, eventHash);
        requireEventStateAuthority(
            exact.tenantId(),
            exact.evidenceId(),
            1,
            EvidenceState.ACTIVE,
            eventHash,
            null,
            null,
            null,
            exact.recordedAt()
        );
        return new StoreResult(
            StoreDisposition.STORED,
            exact.evidenceId(),
            1,
            EvidenceState.ACTIVE,
            exact.evidenceHash(),
            eventHash
        );
    }

    private StoreResult classifyExisting(
        ApprovalAssistanceDurableEvidence candidate,
        List<StoredState> collisions
    ) {
        StoredState existing = collisions.stream()
            .min(Comparator.comparing(value -> !value.evidenceId().equals(
                candidate.evidenceId()
            )))
            .orElseThrow();
        boolean exact = collisions.size() == 1
            && existing.evidenceId().equals(candidate.evidenceId())
            && existing.requestEvidenceHash().equals(candidate.requestEvidenceHash())
            && existing.evidenceHash().equals(candidate.evidenceHash())
            && existing.state() == EvidenceState.ACTIVE
            && existing.revision() == 1;
        return new StoreResult(
            exact ? StoreDisposition.REPLAYED : StoreDisposition.CONFLICT,
            existing.evidenceId(),
            existing.revision(),
            existing.state(),
            existing.evidenceHash(),
            existing.currentEventHash()
        );
    }

    private TombstoneResult tombstoneOnce(TombstoneCommand command) {
        locks.acquire(identityScope(command.tenantId(), command.evidenceId()));
        Optional<LockedState> locked = lockState(
            command.tenantId(),
            command.evidenceId()
        );
        if (locked.isEmpty()) {
            return new TombstoneResult(
                TombstoneDisposition.NOT_FOUND,
                command.evidenceId(),
                0,
                null,
                null,
                null,
                null,
                null,
                null
            );
        }

        LockedState current = locked.orElseThrow();
        if (current.state() == EvidenceState.TOMBSTONED) {
            boolean replay = current.deleteReason() == command.reason()
                && current.deletionRequestHash().equals(command.deletionRequestHash());
            if (replay) {
                return completedTombstone(TombstoneDisposition.REPLAYED, current);
            }
            return incompleteTombstone(TombstoneDisposition.CONFLICT, current);
        }
        if (command.expectedRevision() != current.revision()) {
            return incompleteTombstone(TombstoneDisposition.REVISION_CONFLICT, current);
        }
        if (command.requestedAt().isBefore(current.recordedAt())) {
            return incompleteTombstone(TombstoneDisposition.CONFLICT, current);
        }
        if (!command.reason().permitsEarlyDeletion()
            && command.requestedAt().isBefore(current.retentionUntil())) {
            return incompleteTombstone(TombstoneDisposition.RETENTION_BLOCKED, current);
        }

        UUID eventId = nextEventId();
        String tombstoneHash = tombstoneHash(command, current);
        String eventHash = tombstoneEventHash(
            eventId,
            command,
            current,
            tombstoneHash
        );
        insertTombstoneEvent(
            eventId,
            command,
            current,
            tombstoneHash,
            eventHash
        );
        requireEventStateAuthority(
            command.tenantId(),
            command.evidenceId(),
            2,
            EvidenceState.TOMBSTONED,
            eventHash,
            command.reason(),
            command.deletionRequestHash(),
            tombstoneHash,
            command.requestedAt()
        );
        return new TombstoneResult(
            TombstoneDisposition.TOMBSTONED,
            command.evidenceId(),
            2,
            EvidenceState.TOMBSTONED,
            command.reason(),
            command.requestedAt(),
            command.deletionRequestHash(),
            tombstoneHash,
            eventHash
        );
    }

    private TombstoneResult completedTombstone(
        TombstoneDisposition disposition,
        LockedState current
    ) {
        return new TombstoneResult(
            disposition,
            current.evidenceId(),
            current.revision(),
            current.state(),
            current.deleteReason(),
            current.tombstonedAt(),
            current.deletionRequestHash(),
            current.tombstoneHash(),
            current.currentEventHash()
        );
    }

    private TombstoneResult incompleteTombstone(
        TombstoneDisposition disposition,
        LockedState current
    ) {
        return new TombstoneResult(
            disposition,
            current.evidenceId(),
            current.revision(),
            current.state(),
            null,
            null,
            null,
            null,
            null
        );
    }

    private void acquireIdentityLocks(ApprovalAssistanceDurableEvidence exact) {
        List<String> scopes = new ArrayList<>(List.of(
            identityScope(exact.tenantId(), exact.evidenceId()),
            "ai-evidence:request:" + exact.tenantId() + ':' + exact.requestEvidenceHash(),
            "ai-evidence:content:" + exact.tenantId() + ':' + exact.evidenceHash()
        ));
        scopes.sort(String::compareTo);
        scopes.forEach(locks::acquire);
    }

    private static String identityScope(String tenantId, UUID evidenceId) {
        return "ai-evidence:id:" + tenantId + ':' + evidenceId;
    }

    private List<StoredState> findCollisions(
        ApprovalAssistanceDurableEvidence evidence
    ) {
        return jdbc.query("""
            select e.evidence_id,e.request_evidence_hash,e.evidence_hash,
                   s.revision,s.state,s.current_event_hash
            from ap_ai_approval_assistance_evidence e
            join ap_ai_approval_assistance_evidence_state s
              on s.tenant_id=e.tenant_id and s.evidence_id=e.evidence_id
            where e.tenant_id=:tenantId and (
              e.evidence_id=:evidenceId
              or e.request_evidence_hash=:requestEvidenceHash
              or e.evidence_hash=:evidenceHash
            )
            """,
            evidenceIdentityParameters(evidence),
            (row, number) -> new StoredState(
                values.uuid(row, "evidence_id"),
                hash(row, "request_evidence_hash"),
                hash(row, "evidence_hash"),
                row.getLong("revision"),
                EvidenceState.valueOf(row.getString("state")),
                hash(row, "current_event_hash")
            )
        );
    }

    private Optional<LockedState> lockState(
        String tenantId,
        UUID evidenceId
    ) {
        return jdbc.query("""
            select e.evidence_id,e.evidence_hash,e.recorded_at,e.retention_until,
                   s.revision,s.state,s.delete_reason,s.tombstoned_at,
                   s.deletion_request_hash,s.tombstone_hash,s.current_event_hash
            from ap_ai_approval_assistance_evidence e
            join ap_ai_approval_assistance_evidence_state s
              on s.tenant_id=e.tenant_id and s.evidence_id=e.evidence_id
            where e.tenant_id=:tenantId and e.evidence_id=:evidenceId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("evidenceId", values.bindUuid(evidenceId)),
            (row, number) -> new LockedState(
                values.uuid(row, "evidence_id"),
                hash(row, "evidence_hash"),
                values.instant(row, "recorded_at"),
                values.instant(row, "retention_until"),
                row.getLong("revision"),
                EvidenceState.valueOf(row.getString("state")),
                nullableEnum(row, "delete_reason", DeleteReason.class),
                values.nullableInstant(row, "tombstoned_at"),
                nullableHash(row, "deletion_request_hash"),
                nullableHash(row, "tombstone_hash"),
                hash(row, "current_event_hash")
            )
        ).stream().findFirst();
    }

    private int insertEvidence(ApprovalAssistanceDurableEvidence evidence) {
        return jdbc.update("""
            insert into ap_ai_approval_assistance_evidence (
              tenant_id,evidence_id,request_evidence_hash,subject_evidence_hash,
              resource_evidence_hash,projection_evidence_hash,execution_evidence_hash,
              route_evidence_hash,version_evidence_hash,outcome_evidence_hash,
              use_case,classification,provider_id,provider_version,model_provider_id,
              model_id,model_version,prompt_template_id,prompt_template_version,
              prompt_template_hash,knowledge_source_id,knowledge_source_version,
              knowledge_source_hash,knowledge_contains_customer_data,policy_id,
              policy_version,policy_hash,output_schema_id,output_schema_version,
              provider_attempts,provider_invocation_started,retry_attempted,
              post_invocation_fallback_attempted,kill_switch_generation,
              advisory_result_present,observation_count,risk_signal_count,
              missing_material_count,recommendation_count,evidence_reference_count,
              limitation_count,confidence_score,confidence_band,requested_at,
              recorded_at,retention_until,evidence_hash
            ) values (
              :tenantId,:evidenceId,:requestEvidenceHash,:subjectEvidenceHash,
              :resourceEvidenceHash,:projectionEvidenceHash,:executionEvidenceHash,
              :routeEvidenceHash,:versionEvidenceHash,:outcomeEvidenceHash,
              :useCase,:classification,:providerId,:providerVersion,:modelProviderId,
              :modelId,:modelVersion,:promptTemplateId,:promptTemplateVersion,
              :promptTemplateHash,:knowledgeSourceId,:knowledgeSourceVersion,
              :knowledgeSourceHash,:knowledgeContainsCustomerData,:policyId,
              :policyVersion,:policyHash,:outputSchemaId,:outputSchemaVersion,
              :providerAttempts,:providerInvocationStarted,:retryAttempted,
              :postInvocationFallbackAttempted,:killSwitchGeneration,
              :advisoryResultPresent,:observationCount,:riskSignalCount,
              :missingMaterialCount,:recommendationCount,:evidenceReferenceCount,
              :limitationCount,:confidenceScore,:confidenceBand,:requestedAt,
              :recordedAt,:retentionUntil,:evidenceHash
            )
            """, evidenceParameters(evidence));
    }

    private void insertStoredEvent(
        UUID eventId,
        ApprovalAssistanceDurableEvidence evidence,
        String eventHash
    ) {
        int inserted = jdbc.update("""
            insert into ap_ai_approval_assistance_evidence_event (
              tenant_id,event_id,evidence_id,revision,event_type,predecessor_hash,
              event_hash,happened_at,delete_reason,deletion_request_hash,tombstone_hash
            ) values (
              :tenantId,:eventId,:evidenceId,1,'STORED',:predecessorHash,
              :eventHash,:happenedAt,null,null,null
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", evidence.tenantId())
                .addValue("eventId", values.bindUuid(eventId))
                .addValue("evidenceId", values.bindUuid(evidence.evidenceId()))
                .addValue("predecessorHash", ZERO_HASH)
                .addValue("eventHash", eventHash)
                .addValue("happenedAt", values.bindInstant(evidence.recordedAt())));
        if (inserted != 1) {
            throw new EvidencePersistenceException(
                "MySQL stored event did not create exactly one row"
            );
        }
    }

    private void insertTombstoneEvent(
        UUID eventId,
        TombstoneCommand command,
        LockedState current,
        String tombstoneHash,
        String eventHash
    ) {
        int inserted = jdbc.update("""
            insert into ap_ai_approval_assistance_evidence_event (
              tenant_id,event_id,evidence_id,revision,event_type,predecessor_hash,
              event_hash,happened_at,delete_reason,deletion_request_hash,tombstone_hash
            ) values (
              :tenantId,:eventId,:evidenceId,2,'TOMBSTONED',:predecessorHash,
              :eventHash,:happenedAt,:deleteReason,:deletionRequestHash,:tombstoneHash
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", command.tenantId())
                .addValue("eventId", values.bindUuid(eventId))
                .addValue("evidenceId", values.bindUuid(command.evidenceId()))
                .addValue("predecessorHash", current.currentEventHash())
                .addValue("eventHash", eventHash)
                .addValue("happenedAt", values.bindInstant(command.requestedAt()))
                .addValue("deleteReason", command.reason().name())
                .addValue("deletionRequestHash", command.deletionRequestHash())
                .addValue("tombstoneHash", tombstoneHash));
        if (inserted != 1) {
            throw new EvidencePersistenceException(
                "MySQL tombstone event did not create exactly one row"
            );
        }
    }

    private MapSqlParameterSource evidenceIdentityParameters(
        ApprovalAssistanceDurableEvidence evidence
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", evidence.tenantId())
            .addValue("evidenceId", values.bindUuid(evidence.evidenceId()))
            .addValue("requestEvidenceHash", evidence.requestEvidenceHash())
            .addValue("evidenceHash", evidence.evidenceHash());
    }

    private MapSqlParameterSource evidenceParameters(
        ApprovalAssistanceDurableEvidence evidence
    ) {
        AiVersionReferences versions = evidence.versions();
        AdvisoryCounts counts = evidence.advisoryCounts();
        return evidenceIdentityParameters(evidence)
            .addValue("subjectEvidenceHash", evidence.subjectEvidenceHash())
            .addValue("resourceEvidenceHash", evidence.resourceEvidenceHash())
            .addValue("projectionEvidenceHash", evidence.projectionEvidenceHash())
            .addValue("executionEvidenceHash", evidence.executionEvidenceHash())
            .addValue("routeEvidenceHash", evidence.routeEvidenceHash())
            .addValue("versionEvidenceHash", evidence.versionEvidenceHash())
            .addValue("outcomeEvidenceHash", evidence.outcomeEvidenceHash())
            .addValue("useCase", evidence.useCase().name())
            .addValue("classification", evidence.classification().name())
            .addValue("providerId", versions.provider().providerId())
            .addValue("providerVersion", versions.provider().version())
            .addValue("modelProviderId", versions.model().providerId())
            .addValue("modelId", versions.model().modelId())
            .addValue("modelVersion", versions.model().version())
            .addValue("promptTemplateId", versions.promptTemplate().templateId())
            .addValue("promptTemplateVersion", versions.promptTemplate().version())
            .addValue("promptTemplateHash", versions.promptTemplate().contentHash())
            .addValue("knowledgeSourceId", versions.knowledgeSource().sourceId())
            .addValue("knowledgeSourceVersion", versions.knowledgeSource().version())
            .addValue("knowledgeSourceHash", versions.knowledgeSource().contentHash())
            .addValue(
                "knowledgeContainsCustomerData",
                versions.knowledgeSource().containsCustomerData()
            )
            .addValue("policyId", versions.policy().policyId())
            .addValue("policyVersion", versions.policy().version())
            .addValue("policyHash", versions.policy().contentHash())
            .addValue("outputSchemaId", versions.outputSchema().schemaId())
            .addValue("outputSchemaVersion", versions.outputSchema().version())
            .addValue("providerAttempts", evidence.providerAttempts())
            .addValue("providerInvocationStarted", evidence.providerInvocationStarted())
            .addValue("retryAttempted", evidence.retryAttempted())
            .addValue(
                "postInvocationFallbackAttempted",
                evidence.postInvocationFallbackAttempted()
            )
            .addValue("killSwitchGeneration", evidence.killSwitchGeneration())
            .addValue("advisoryResultPresent", evidence.advisoryResultPresent())
            .addValue("observationCount", counts.observationCount())
            .addValue("riskSignalCount", counts.riskSignalCount())
            .addValue("missingMaterialCount", counts.missingMaterialCount())
            .addValue("recommendationCount", counts.recommendationCount())
            .addValue("evidenceReferenceCount", counts.evidenceReferenceCount())
            .addValue("limitationCount", counts.limitationCount())
            .addValue("confidenceScore", evidence.confidenceScore())
            .addValue(
                "confidenceBand",
                evidence.confidenceBand() == null ? null : evidence.confidenceBand().name()
            )
            .addValue("requestedAt", values.bindInstant(evidence.requestedAt()))
            .addValue("recordedAt", values.bindInstant(evidence.recordedAt()))
            .addValue("retentionUntil", values.bindInstant(evidence.retentionUntil()));
    }

    private void requireEventStateAuthority(
        String tenantId,
        UUID evidenceId,
        long revision,
        EvidenceState state,
        String eventHash,
        DeleteReason deleteReason,
        String deletionRequestHash,
        String tombstoneHash,
        Instant happenedAt
    ) {
        List<SnapshotAuthority> rows = jdbc.query("""
            select e.*,s.revision,s.state,s.delete_reason,s.tombstoned_at,
                   s.deletion_request_hash,s.tombstone_hash,s.current_event_hash,
                   ev.event_type as authority_event_type,
                   ev.predecessor_hash as authority_predecessor_hash,
                   ev.event_hash as authority_event_hash,
                   ev.happened_at as authority_happened_at,
                   ev.delete_reason as authority_delete_reason,
                   ev.deletion_request_hash as authority_deletion_request_hash,
                   ev.tombstone_hash as authority_tombstone_hash
            from ap_ai_approval_assistance_evidence e
            join ap_ai_approval_assistance_evidence_state s
              on s.tenant_id=e.tenant_id and s.evidence_id=e.evidence_id
            join ap_ai_approval_assistance_evidence_event ev
              on ev.tenant_id=s.tenant_id and ev.evidence_id=s.evidence_id
             and ev.revision=s.revision and ev.event_hash=s.current_event_hash
            where e.tenant_id=:tenantId and e.evidence_id=:evidenceId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("evidenceId", values.bindUuid(evidenceId)),
            (row, number) -> snapshotAuthority(row)
        );
        if (rows.size() != 1) {
            throw authorityDiverged();
        }
        SnapshotAuthority authority = rows.getFirst();
        requireEventStateAuthority(authority);
        EvidenceSnapshot snapshot = authority.snapshot();
        if (snapshot.revision() != revision
            || snapshot.state() != state
            || !authority.currentEventHash().equals(eventHash)
            || !authority.eventHash().equals(eventHash)
            || !authority.happenedAt().equals(happenedAt)
            || snapshot.deleteReason() != deleteReason
            || !Objects.equals(snapshot.deletionRequestHash(), deletionRequestHash)
            || !Objects.equals(snapshot.tombstoneHash(), tombstoneHash)) {
            throw authorityDiverged();
        }
    }

    private void requireEventStateAuthority(SnapshotAuthority authority) {
        EvidenceSnapshot snapshot = authority.snapshot();
        boolean active = snapshot.state() == EvidenceState.ACTIVE;
        String expectedType = active ? "STORED" : "TOMBSTONED";
        if (!authority.currentEventHash().equals(authority.eventHash())
            || !expectedType.equals(authority.eventType())
            || !authority.happenedAt().equals(
                active ? snapshot.evidence().recordedAt() : snapshot.tombstonedAt()
            )
            || (active && !ZERO_HASH.equals(authority.predecessorHash()))
            || !Objects.equals(snapshot.deleteReason(), authority.eventDeleteReason())
            || !Objects.equals(
                snapshot.deletionRequestHash(),
                authority.eventDeletionRequestHash()
            )
            || !Objects.equals(snapshot.tombstoneHash(), authority.eventTombstoneHash())) {
            throw authorityDiverged();
        }
        if (!active) {
            String storedEventHash = jdbc.queryForObject("""
                select event_hash
                from ap_ai_approval_assistance_evidence_event
                where tenant_id=:tenantId and evidence_id=:evidenceId
                  and revision=1 and event_type='STORED'
                """, new MapSqlParameterSource()
                    .addValue("tenantId", snapshot.evidence().tenantId())
                    .addValue(
                        "evidenceId",
                        values.bindUuid(snapshot.evidence().evidenceId())
                    ),
                String.class
            );
            if (!Objects.equals(hash(storedEventHash), authority.predecessorHash())) {
                throw authorityDiverged();
            }
        }
    }

    private SnapshotAuthority snapshotAuthority(ResultSet row) throws SQLException {
        EvidenceSnapshot snapshot = new EvidenceSnapshot(
            evidence(row),
            row.getLong("revision"),
            EvidenceState.valueOf(row.getString("state")),
            nullableEnum(row, "delete_reason", DeleteReason.class),
            values.nullableInstant(row, "tombstoned_at"),
            nullableHash(row, "deletion_request_hash"),
            nullableHash(row, "tombstone_hash")
        );
        return new SnapshotAuthority(
            snapshot,
            hash(row, "current_event_hash"),
            row.getString("authority_event_type"),
            hash(row, "authority_predecessor_hash"),
            hash(row, "authority_event_hash"),
            values.instant(row, "authority_happened_at"),
            nullableEnum(row, "authority_delete_reason", DeleteReason.class),
            nullableHash(row, "authority_deletion_request_hash"),
            nullableHash(row, "authority_tombstone_hash")
        );
    }

    private ApprovalAssistanceDurableEvidence evidence(ResultSet row)
        throws SQLException {
        AiVersionReferences versions = new AiVersionReferences(
            new ProviderVersion(
                row.getString("provider_id"),
                row.getString("provider_version")
            ),
            new ModelVersion(
                row.getString("model_provider_id"),
                row.getString("model_id"),
                row.getString("model_version")
            ),
            new PromptTemplateVersion(
                row.getString("prompt_template_id"),
                row.getString("prompt_template_version"),
                row.getString("prompt_template_hash")
            ),
            new KnowledgeSourceVersion(
                row.getString("knowledge_source_id"),
                row.getString("knowledge_source_version"),
                row.getString("knowledge_source_hash"),
                row.getBoolean("knowledge_contains_customer_data")
            ),
            new PolicyVersion(
                row.getString("policy_id"),
                row.getString("policy_version"),
                row.getString("policy_hash")
            ),
            new OutputSchemaVersion(
                row.getString("output_schema_id"),
                row.getInt("output_schema_version")
            )
        );
        AdvisoryCounts counts = new AdvisoryCounts(
            row.getInt("observation_count"),
            row.getInt("risk_signal_count"),
            row.getInt("missing_material_count"),
            row.getInt("recommendation_count"),
            row.getInt("evidence_reference_count"),
            row.getInt("limitation_count")
        );
        Number confidenceValue = (Number) row.getObject("confidence_score");
        Double confidence = confidenceValue == null ? null : confidenceValue.doubleValue();
        String band = row.getString("confidence_band");
        return new ApprovalAssistanceDurableEvidence(
            values.uuid(row, "evidence_id"),
            row.getString("tenant_id"),
            hash(row, "request_evidence_hash"),
            hash(row, "subject_evidence_hash"),
            hash(row, "resource_evidence_hash"),
            hash(row, "projection_evidence_hash"),
            hash(row, "execution_evidence_hash"),
            nullableHash(row, "route_evidence_hash"),
            hash(row, "version_evidence_hash"),
            hash(row, "outcome_evidence_hash"),
            UseCase.valueOf(row.getString("use_case")),
            AiOutcomeClassification.valueOf(row.getString("classification")),
            versions,
            row.getInt("provider_attempts"),
            row.getBoolean("provider_invocation_started"),
            row.getBoolean("retry_attempted"),
            row.getBoolean("post_invocation_fallback_attempted"),
            row.getLong("kill_switch_generation"),
            row.getBoolean("advisory_result_present"),
            counts,
            confidence,
            band == null ? null : AiAdvisoryResult.ConfidenceBand.valueOf(band),
            values.instant(row, "requested_at"),
            values.instant(row, "recorded_at"),
            values.instant(row, "retention_until"),
            hash(row, "evidence_hash")
        );
    }

    private UUID nextEventId() {
        return Objects.requireNonNull(
            eventIdentifiers.get(),
            "event identifier supplier must not return null"
        );
    }

    private static ApprovalAssistanceDurableEvidence requireCanonicalEvidence(
        ApprovalAssistanceDurableEvidence evidence
    ) {
        ApprovalAssistanceDurableEvidence exact = Objects.requireNonNull(
            evidence,
            "evidence must not be null"
        );
        if (!canonicalInstant(exact.requestedAt()).equals(exact.requestedAt())
            || !canonicalInstant(exact.recordedAt()).equals(exact.recordedAt())
            || !canonicalInstant(exact.retentionUntil()).equals(exact.retentionUntil())) {
            throw new IllegalArgumentException(
                "MySQL durable evidence times must use canonical database microseconds"
            );
        }
        return exact;
    }

    private static TombstoneCommand canonicalCommand(TombstoneCommand command) {
        TombstoneCommand exact = Objects.requireNonNull(
            command,
            "command must not be null"
        );
        Instant canonical = canonicalInstant(exact.requestedAt());
        if (canonical.equals(exact.requestedAt())) {
            return exact;
        }
        return new TombstoneCommand(
            exact.tenantId(),
            exact.evidenceId(),
            exact.expectedRevision(),
            exact.reason(),
            canonical,
            exact.deletionRequestHash()
        );
    }

    private static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(value, "instant must not be null")
        );
    }

    private static String storedEventHash(
        UUID eventId,
        ApprovalAssistanceDurableEvidence evidence
    ) {
        return hashValues(
            "M6-E-P4-STORED-EVENT-V1",
            eventId.toString(),
            evidence.tenantId(),
            evidence.evidenceId().toString(),
            "1",
            ZERO_HASH,
            evidence.evidenceHash(),
            evidence.recordedAt().toString()
        );
    }

    private static String tombstoneHash(
        TombstoneCommand command,
        LockedState current
    ) {
        return hashValues(
            "M6-E-P4-TOMBSTONE-V1",
            command.tenantId(),
            command.evidenceId().toString(),
            Long.toString(current.revision()),
            "2",
            current.evidenceHash(),
            command.reason().name(),
            command.requestedAt().toString(),
            command.deletionRequestHash()
        );
    }

    private static String tombstoneEventHash(
        UUID eventId,
        TombstoneCommand command,
        LockedState current,
        String tombstoneHash
    ) {
        return hashValues(
            "M6-E-P4-TOMBSTONE-EVENT-V1",
            eventId.toString(),
            command.tenantId(),
            command.evidenceId().toString(),
            "2",
            current.currentEventHash(),
            tombstoneHash,
            command.reason().name(),
            command.requestedAt().toString(),
            command.deletionRequestHash()
        );
    }

    private static String hashValues(String domain, String... values) {
        MessageDigest digest = digest();
        updateFramed(digest, domain);
        for (String value : values) {
            updateFramed(digest, value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void updateFramed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String hash(ResultSet row, String column) throws SQLException {
        return hash(row.getString(column));
    }

    private static String hash(String value) {
        return Objects.requireNonNull(value, "hash value must not be null").trim();
    }

    private static String nullableHash(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? null : value.trim();
    }

    private static <E extends Enum<E>> E nullableEnum(
        ResultSet row,
        String column,
        Class<E> type
    ) throws SQLException {
        String value = row.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static EvidencePersistenceException authorityDiverged() {
        return new EvidencePersistenceException(
            "MySQL evidence event/state authority diverged"
        );
    }

    private record StoredState(
        UUID evidenceId,
        String requestEvidenceHash,
        String evidenceHash,
        long revision,
        EvidenceState state,
        String currentEventHash
    ) {
    }

    private record LockedState(
        UUID evidenceId,
        String evidenceHash,
        Instant recordedAt,
        Instant retentionUntil,
        long revision,
        EvidenceState state,
        DeleteReason deleteReason,
        Instant tombstonedAt,
        String deletionRequestHash,
        String tombstoneHash,
        String currentEventHash
    ) {
    }

    private record SnapshotAuthority(
        EvidenceSnapshot snapshot,
        String currentEventHash,
        String eventType,
        String predecessorHash,
        String eventHash,
        Instant happenedAt,
        DeleteReason eventDeleteReason,
        String eventDeletionRequestHash,
        String eventTombstoneHash
    ) {
    }

    public static final class EvidencePersistenceException extends RuntimeException {

        public EvidencePersistenceException(String message) {
            super(message);
        }

        public EvidencePersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

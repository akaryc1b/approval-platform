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
import org.springframework.jdbc.core.RowMapper;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL P4 hash-only evidence, replay and retention-tombstone boundary. */
public final class JdbcApprovalAssistanceDurableEvidenceStore
    implements ApprovalAssistanceDurableEvidenceStore {

    private static final String ZERO_HASH = "0".repeat(64);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Supplier<UUID> eventIdentifiers;

    public JdbcApprovalAssistanceDurableEvidenceStore(
        DataSource dataSource,
        PlatformTransactionManager transactionManager,
        Supplier<UUID> eventIdentifiers
    ) {
        DataSource source = Objects.requireNonNull(dataSource, "dataSource must not be null");
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.transactions = new TransactionTemplate(manager);
        this.eventIdentifiers = Objects.requireNonNull(
            eventIdentifiers,
            "eventIdentifiers must not be null"
        );
    }

    @Override
    public StoreResult store(ApprovalAssistanceDurableEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        try {
            return Objects.requireNonNull(
                transactions.execute(status -> storeOnce(evidence)),
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
        Objects.requireNonNull(command, "command must not be null");
        try {
            return Objects.requireNonNull(
                transactions.execute(status -> tombstoneOnce(command)),
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
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        try {
            return jdbc.query("""
                select e.*,s.revision,s.state,s.delete_reason,s.tombstoned_at,
                       s.deletion_request_hash,s.tombstone_hash
                from ap_ai_approval_assistance_evidence e
                join ap_ai_approval_assistance_evidence_state s
                  on s.tenant_id=e.tenant_id and s.evidence_id=e.evidence_id
                where e.tenant_id=:tenantId and e.evidence_id=:evidenceId
                """, new MapSqlParameterSource()
                    .addValue("tenantId", tenant)
                    .addValue("evidenceId", evidenceId),
                SNAPSHOT_MAPPER
            ).stream().findFirst();
        } catch (DataAccessException exception) {
            throw new EvidencePersistenceException(
                "approval-assistance evidence read failed",
                exception
            );
        }
    }

    private StoreResult storeOnce(ApprovalAssistanceDurableEvidence evidence) {
        List<StoredState> collisions = findCollisions(evidence, true);
        if (!collisions.isEmpty()) {
            return classifyExisting(evidence, collisions);
        }

        int inserted = insertEvidence(evidence);
        if (inserted == 0) {
            List<StoredState> raced = findCollisions(evidence, true);
            if (raced.isEmpty()) {
                throw new EvidencePersistenceException(
                    "evidence insert conflicted without a readable owner"
                );
            }
            return classifyExisting(evidence, raced);
        }

        UUID eventId = nextEventId();
        String eventHash = storedEventHash(eventId, evidence);
        insertStoredEvent(eventId, evidence, eventHash);
        insertActiveState(evidence, eventHash);
        return new StoreResult(
            StoreDisposition.STORED,
            evidence.evidenceId(),
            1,
            EvidenceState.ACTIVE,
            evidence.evidenceHash(),
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
        Optional<LockedState> locked = lockState(command.tenantId(), command.evidenceId());
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
        insertTombstoneEvent(eventId, command, current, eventHash);
        int updated = updateTombstoneState(
            command,
            current,
            tombstoneHash,
            eventHash
        );
        if (updated != 1) {
            throw new EvidencePersistenceException(
                "approval-assistance tombstone CAS lost after row lock"
            );
        }
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

    private List<StoredState> findCollisions(
        ApprovalAssistanceDurableEvidence evidence,
        boolean lock
    ) {
        String lockClause = lock ? " for update of e,s" : "";
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
            """ + lockClause,
            evidenceIdentityParameters(evidence),
            (row, number) -> new StoredState(
                row.getObject("evidence_id", UUID.class),
                hash(row, "request_evidence_hash"),
                hash(row, "evidence_hash"),
                row.getLong("revision"),
                EvidenceState.valueOf(row.getString("state")),
                hash(row, "current_event_hash")
            )
        );
    }

    private Optional<LockedState> lockState(String tenantId, UUID evidenceId) {
        return jdbc.query("""
            select e.evidence_id,e.evidence_hash,e.recorded_at,e.retention_until,
                   s.revision,s.state,s.delete_reason,s.tombstoned_at,
                   s.deletion_request_hash,s.tombstone_hash,s.current_event_hash
            from ap_ai_approval_assistance_evidence e
            join ap_ai_approval_assistance_evidence_state s
              on s.tenant_id=e.tenant_id and s.evidence_id=e.evidence_id
            where e.tenant_id=:tenantId and e.evidence_id=:evidenceId
            for update of e,s
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("evidenceId", evidenceId),
            (row, number) -> new LockedState(
                row.getObject("evidence_id", UUID.class),
                hash(row, "evidence_hash"),
                instant(row, "recorded_at"),
                instant(row, "retention_until"),
                row.getLong("revision"),
                EvidenceState.valueOf(row.getString("state")),
                nullableEnum(row, "delete_reason", DeleteReason.class),
                nullableInstant(row, "tombstoned_at"),
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
            ) on conflict do nothing
            """, evidenceParameters(evidence));
    }

    private void insertStoredEvent(
        UUID eventId,
        ApprovalAssistanceDurableEvidence evidence,
        String eventHash
    ) {
        jdbc.update("""
            insert into ap_ai_approval_assistance_evidence_event (
              tenant_id,event_id,evidence_id,revision,event_type,predecessor_hash,
              event_hash,happened_at,delete_reason,deletion_request_hash
            ) values (
              :tenantId,:eventId,:evidenceId,1,'STORED',:predecessorHash,
              :eventHash,:happenedAt,null,null
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", evidence.tenantId())
                .addValue("eventId", eventId)
                .addValue("evidenceId", evidence.evidenceId())
                .addValue("predecessorHash", ZERO_HASH)
                .addValue("eventHash", eventHash)
                .addValue("happenedAt", timestamp(evidence.recordedAt())));
    }

    private void insertActiveState(
        ApprovalAssistanceDurableEvidence evidence,
        String eventHash
    ) {
        jdbc.update("""
            insert into ap_ai_approval_assistance_evidence_state (
              tenant_id,evidence_id,revision,state,delete_reason,tombstoned_at,
              deletion_request_hash,tombstone_hash,current_event_hash,updated_at
            ) values (
              :tenantId,:evidenceId,1,'ACTIVE',null,null,null,null,:eventHash,:updatedAt
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", evidence.tenantId())
                .addValue("evidenceId", evidence.evidenceId())
                .addValue("eventHash", eventHash)
                .addValue("updatedAt", timestamp(evidence.recordedAt())));
    }

    private void insertTombstoneEvent(
        UUID eventId,
        TombstoneCommand command,
        LockedState current,
        String eventHash
    ) {
        jdbc.update("""
            insert into ap_ai_approval_assistance_evidence_event (
              tenant_id,event_id,evidence_id,revision,event_type,predecessor_hash,
              event_hash,happened_at,delete_reason,deletion_request_hash
            ) values (
              :tenantId,:eventId,:evidenceId,2,'TOMBSTONED',:predecessorHash,
              :eventHash,:happenedAt,:deleteReason,:deletionRequestHash
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", command.tenantId())
                .addValue("eventId", eventId)
                .addValue("evidenceId", command.evidenceId())
                .addValue("predecessorHash", current.currentEventHash())
                .addValue("eventHash", eventHash)
                .addValue("happenedAt", timestamp(command.requestedAt()))
                .addValue("deleteReason", command.reason().name())
                .addValue("deletionRequestHash", command.deletionRequestHash()));
    }

    private int updateTombstoneState(
        TombstoneCommand command,
        LockedState current,
        String tombstoneHash,
        String eventHash
    ) {
        return jdbc.update("""
            update ap_ai_approval_assistance_evidence_state
            set revision=2,state='TOMBSTONED',delete_reason=:deleteReason,
                tombstoned_at=:tombstonedAt,deletion_request_hash=:deletionRequestHash,
                tombstone_hash=:tombstoneHash,current_event_hash=:eventHash,
                updated_at=:tombstonedAt
            where tenant_id=:tenantId and evidence_id=:evidenceId
              and revision=:expectedRevision and state='ACTIVE'
              and current_event_hash=:expectedEventHash
            """, new MapSqlParameterSource()
                .addValue("tenantId", command.tenantId())
                .addValue("evidenceId", command.evidenceId())
                .addValue("expectedRevision", command.expectedRevision())
                .addValue("expectedEventHash", current.currentEventHash())
                .addValue("deleteReason", command.reason().name())
                .addValue("tombstonedAt", timestamp(command.requestedAt()))
                .addValue("deletionRequestHash", command.deletionRequestHash())
                .addValue("tombstoneHash", tombstoneHash)
                .addValue("eventHash", eventHash));
    }

    private MapSqlParameterSource evidenceIdentityParameters(
        ApprovalAssistanceDurableEvidence evidence
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", evidence.tenantId())
            .addValue("evidenceId", evidence.evidenceId())
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
            .addValue("requestedAt", timestamp(evidence.requestedAt()))
            .addValue("recordedAt", timestamp(evidence.recordedAt()))
            .addValue("retentionUntil", timestamp(evidence.retentionUntil()));
    }

    private UUID nextEventId() {
        return Objects.requireNonNull(
            eventIdentifiers.get(),
            "event identifier supplier must not return null"
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

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String hash(ResultSet row, String column) throws SQLException {
        return row.getString(column).trim();
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

    private static final RowMapper<EvidenceSnapshot> SNAPSHOT_MAPPER =
        (row, number) -> new EvidenceSnapshot(
            evidence(row),
            row.getLong("revision"),
            EvidenceState.valueOf(row.getString("state")),
            nullableEnum(row, "delete_reason", DeleteReason.class),
            nullableInstant(row, "tombstoned_at"),
            nullableHash(row, "deletion_request_hash"),
            nullableHash(row, "tombstone_hash")
        );

    private static ApprovalAssistanceDurableEvidence evidence(ResultSet row)
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
        Double confidence = (Double) row.getObject("confidence_score");
        String band = row.getString("confidence_band");
        return new ApprovalAssistanceDurableEvidence(
            row.getObject("evidence_id", UUID.class),
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
            instant(row, "requested_at"),
            instant(row, "recorded_at"),
            instant(row, "retention_until"),
            hash(row, "evidence_hash")
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

    public static final class EvidencePersistenceException extends RuntimeException {

        public EvidencePersistenceException(String message) {
            super(message);
        }

        public EvidencePersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

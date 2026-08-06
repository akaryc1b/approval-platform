package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.LineageOutcome;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.LineageSnapshot;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.LineageStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.RegistrationCommand;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.RegistrationDisposition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.RegistrationResult;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.TransitionCommand;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.TransitionDisposition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.TransitionResult;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL P4 implementation for hash-only controlled-automation lineage. */
public final class JdbcControlledAutomationLineageStore
    implements ControlledAutomationLineageStore {

    private static final String ZERO_HASH = "0".repeat(64);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Supplier<UUID> eventIdentifiers;

    public JdbcControlledAutomationLineageStore(
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
    public RegistrationResult register(RegistrationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        try {
            return Objects.requireNonNull(
                transactions.execute(status -> registerOnce(command)),
                "registration transaction must return a result"
            );
        } catch (DataAccessException exception) {
            throw new LineagePersistenceException(
                "controlled-automation lineage registration failed",
                exception
            );
        }
    }

    @Override
    public TransitionResult transition(TransitionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        try {
            return Objects.requireNonNull(
                transactions.execute(status -> transitionOnce(command)),
                "transition transaction must return a result"
            );
        } catch (DataAccessException exception) {
            throw new LineagePersistenceException(
                "controlled-automation lineage transition failed",
                exception
            );
        }
    }

    @Override
    public Optional<LineageSnapshot> find(
        String tenantEvidenceHash,
        String operatorEvidenceHash,
        UUID proposalId
    ) {
        String tenantHash = requireHash(tenantEvidenceHash, "tenantEvidenceHash");
        String operatorHash = requireHash(operatorEvidenceHash, "operatorEvidenceHash");
        Objects.requireNonNull(proposalId, "proposalId must not be null");
        try {
            return jdbc.query("""
                select *
                from ap_ai_controlled_automation_lineage
                where tenant_evidence_hash=:tenantEvidenceHash
                  and operator_evidence_hash=:operatorEvidenceHash
                  and proposal_id=:proposalId
                """, new MapSqlParameterSource()
                    .addValue("tenantEvidenceHash", tenantHash)
                    .addValue("operatorEvidenceHash", operatorHash)
                    .addValue("proposalId", proposalId),
                SNAPSHOT_MAPPER
            ).stream().findFirst();
        } catch (DataAccessException exception) {
            throw new LineagePersistenceException(
                "controlled-automation lineage read failed",
                exception
            );
        }
    }

    private RegistrationResult registerOnce(RegistrationCommand command) {
        UUID eventId = nextEventId();
        String eventHash = registrationEventHash(eventId, command);
        int inserted = insertLineage(command, eventHash);
        if (inserted == 1) {
            insertRegistrationEvent(eventId, command, eventHash);
            LineageSnapshot snapshot = findInternal(
                command.tenantEvidenceHash(),
                command.proposalId(),
                false
            ).orElseThrow(() -> new LineagePersistenceException(
                "stored controlled-automation lineage is unreadable"
            ));
            return new RegistrationResult(RegistrationDisposition.REGISTERED, snapshot);
        }

        List<LineageSnapshot> collisions = findRegistrationCollisions(command);
        if (collisions.isEmpty()) {
            throw new LineagePersistenceException(
                "lineage registration conflicted without a readable owner"
            );
        }
        LineageSnapshot existing = collisions.stream()
            .filter(snapshot -> snapshot.proposalId().equals(command.proposalId()))
            .findFirst()
            .orElse(collisions.getFirst());
        RegistrationDisposition disposition = exactRegistration(existing, command)
            ? RegistrationDisposition.REPLAYED
            : RegistrationDisposition.CONFLICT;
        return new RegistrationResult(disposition, existing);
    }

    private TransitionResult transitionOnce(TransitionCommand command) {
        Optional<StoredEvent> prior = findEventByIdempotencyKey(
            command.tenantEvidenceHash(),
            command.idempotencyKeyHash()
        );
        if (prior.isPresent()) {
            return classifyPriorEvent(command, prior.orElseThrow());
        }

        Optional<LineageSnapshot> locked = findInternal(
            command.tenantEvidenceHash(),
            command.proposalId(),
            true
        );
        if (locked.isEmpty()) {
            return new TransitionResult(TransitionDisposition.NOT_FOUND, Optional.empty());
        }
        LineageSnapshot current = locked.orElseThrow();

        Optional<StoredEvent> committedWhileWaiting = findEventByIdempotencyKey(
            command.tenantEvidenceHash(),
            command.idempotencyKeyHash()
        );
        if (committedWhileWaiting.isPresent()) {
            return classifyPriorEvent(command, committedWhileWaiting.orElseThrow());
        }

        if (!current.operatorEvidenceHash().equals(command.operatorEvidenceHash())) {
            return result(TransitionDisposition.IDENTITY_MISMATCH, current);
        }
        if (current.status().terminal()) {
            return result(TransitionDisposition.STATE_CONFLICT, current);
        }
        if (current.revision() != command.expectedRevision()) {
            return result(TransitionDisposition.REVISION_CONFLICT, current);
        }
        if (current.status() != command.expectedStatus()
            || command.occurredAt().isBefore(current.updatedAt())
            || (command.targetStatus() != LineageStatus.CANCELLED
                && !command.occurredAt().isBefore(current.expiresAt()))) {
            return result(TransitionDisposition.STATE_CONFLICT, current);
        }

        UUID eventId = nextEventId();
        String eventHash = transitionEventHash(eventId, current, command);
        int eventInserted = insertTransitionEvent(eventId, current, command, eventHash);
        if (eventInserted == 0) {
            StoredEvent raced = findEventByIdempotencyKey(
                command.tenantEvidenceHash(),
                command.idempotencyKeyHash()
            ).orElseThrow(() -> new LineagePersistenceException(
                "transition idempotency conflict has no readable event"
            ));
            return classifyPriorEvent(command, raced);
        }

        int updated = updateLineage(current, command, eventHash);
        if (updated != 1) {
            throw new LineagePersistenceException(
                "controlled-automation lineage CAS lost after row lock"
            );
        }
        LineageSnapshot applied = findInternal(
            command.tenantEvidenceHash(),
            command.proposalId(),
            false
        ).orElseThrow(() -> new LineagePersistenceException(
            "transitioned controlled-automation lineage is unreadable"
        ));
        return result(TransitionDisposition.APPLIED, applied);
    }

    private TransitionResult classifyPriorEvent(
        TransitionCommand command,
        StoredEvent event
    ) {
        boolean exact = event.proposalId().equals(command.proposalId())
            && event.operatorEvidenceHash().equals(command.operatorEvidenceHash())
            && event.operationHash().equals(command.transitionHash())
            && event.resultEvidenceHash().equals(command.resultEvidenceHash())
            && event.idempotencyPayloadHash().equals(command.idempotencyPayloadHash())
            && event.toStatus() == command.targetStatus()
            && event.outcome() == command.outcome()
            && event.commandAttempts() == command.commandAttempts()
            && !event.automaticRetryAllowed()
            && event.happenedAt().equals(command.occurredAt());
        Optional<LineageSnapshot> snapshot = findInternal(
            command.tenantEvidenceHash(),
            event.proposalId(),
            false
        );
        return new TransitionResult(
            exact ? TransitionDisposition.REPLAYED : TransitionDisposition.IDEMPOTENCY_CONFLICT,
            snapshot
        );
    }

    private int insertLineage(RegistrationCommand command, String eventHash) {
        return jdbc.update("""
            insert into ap_ai_controlled_automation_lineage (
             tenant_evidence_hash,proposal_id,confirmation_id,operator_evidence_hash,
             proposal_lineage_hash,confirmation_evidence_hash,canonical_action_type,
             resource_evidence_hash,whitelist_version,policy_version,
             registration_idempotency_key_hash,registration_idempotency_payload_hash,
             registration_evidence_hash,revision,status,outcome,command_attempts,
             automatic_retry_allowed,confirmed_at,expires_at,updated_at,
             current_evidence_hash,current_event_hash
            ) values (
             :tenantEvidenceHash,:proposalId,:confirmationId,:operatorEvidenceHash,
             :proposalLineageHash,:confirmationEvidenceHash,:canonicalActionType,
             :resourceEvidenceHash,:whitelistVersion,:policyVersion,
             :idempotencyKeyHash,:idempotencyPayloadHash,:registrationEvidenceHash,
             1,'CONFIRMED','NONE',0,false,:confirmedAt,:expiresAt,:confirmedAt,
             :registrationEvidenceHash,:eventHash
            ) on conflict do nothing
            """, registrationParameters(command)
                .addValue("eventHash", eventHash));
    }

    private void insertRegistrationEvent(
        UUID eventId,
        RegistrationCommand command,
        String eventHash
    ) {
        int inserted = jdbc.update("""
            insert into ap_ai_controlled_automation_lineage_event (
             tenant_evidence_hash,event_id,proposal_id,revision,event_type,
             operator_evidence_hash,from_status,to_status,outcome,operation_hash,
             result_evidence_hash,idempotency_key_hash,idempotency_payload_hash,
             command_attempts,automatic_retry_allowed,predecessor_hash,event_hash,happened_at
            ) values (
             :tenantEvidenceHash,:eventId,:proposalId,1,'REGISTERED',
             :operatorEvidenceHash,null,'CONFIRMED','NONE',:registrationEvidenceHash,
             :registrationEvidenceHash,:idempotencyKeyHash,:idempotencyPayloadHash,
             0,false,:predecessorHash,:eventHash,:confirmedAt
            )
            """, registrationParameters(command)
                .addValue("eventId", eventId)
                .addValue("predecessorHash", ZERO_HASH)
                .addValue("eventHash", eventHash));
        if (inserted != 1) {
            throw new LineagePersistenceException(
                "controlled-automation registration event was not inserted"
            );
        }
    }

    private int insertTransitionEvent(
        UUID eventId,
        LineageSnapshot current,
        TransitionCommand command,
        String eventHash
    ) {
        return jdbc.update("""
            insert into ap_ai_controlled_automation_lineage_event (
             tenant_evidence_hash,event_id,proposal_id,revision,event_type,
             operator_evidence_hash,from_status,to_status,outcome,operation_hash,
             result_evidence_hash,idempotency_key_hash,idempotency_payload_hash,
             command_attempts,automatic_retry_allowed,predecessor_hash,event_hash,happened_at
            ) values (
             :tenantEvidenceHash,:eventId,:proposalId,:revision,'TERMINATED',
             :operatorEvidenceHash,:fromStatus,:toStatus,:outcome,:operationHash,
             :resultEvidenceHash,:idempotencyKeyHash,:idempotencyPayloadHash,
             :commandAttempts,false,:predecessorHash,:eventHash,:happenedAt
            ) on conflict do nothing
            """, transitionParameters(command)
                .addValue("eventId", eventId)
                .addValue("revision", current.revision() + 1)
                .addValue("fromStatus", current.status().name())
                .addValue("toStatus", command.targetStatus().name())
                .addValue("outcome", command.outcome().name())
                .addValue("operationHash", command.transitionHash())
                .addValue("predecessorHash", current.currentEventHash())
                .addValue("eventHash", eventHash));
    }

    private int updateLineage(
        LineageSnapshot current,
        TransitionCommand command,
        String eventHash
    ) {
        return jdbc.update("""
            update ap_ai_controlled_automation_lineage
            set revision=:nextRevision,status=:targetStatus,outcome=:outcome,
                command_attempts=:commandAttempts,automatic_retry_allowed=false,
                updated_at=:happenedAt,current_evidence_hash=:transitionHash,
                current_event_hash=:eventHash
            where tenant_evidence_hash=:tenantEvidenceHash
              and proposal_id=:proposalId
              and operator_evidence_hash=:operatorEvidenceHash
              and revision=:expectedRevision
              and status=:expectedStatus
            """, transitionParameters(command)
                .addValue("nextRevision", current.revision() + 1)
                .addValue("targetStatus", command.targetStatus().name())
                .addValue("outcome", command.outcome().name())
                .addValue("transitionHash", command.transitionHash())
                .addValue("eventHash", eventHash));
    }

    private List<LineageSnapshot> findRegistrationCollisions(RegistrationCommand command) {
        return jdbc.query("""
            select *
            from ap_ai_controlled_automation_lineage
            where tenant_evidence_hash=:tenantEvidenceHash
              and (proposal_id=:proposalId
               or registration_idempotency_key_hash=:idempotencyKeyHash)
            order by case when proposal_id=:proposalId then 0 else 1 end
            for update
            """, registrationParameters(command), SNAPSHOT_MAPPER);
    }

    private Optional<LineageSnapshot> findInternal(
        String tenantEvidenceHash,
        UUID proposalId,
        boolean lock
    ) {
        String suffix = lock ? " for update" : "";
        return jdbc.query("""
            select *
            from ap_ai_controlled_automation_lineage
            where tenant_evidence_hash=:tenantEvidenceHash and proposal_id=:proposalId
            """ + suffix, new MapSqlParameterSource()
                .addValue("tenantEvidenceHash", tenantEvidenceHash)
                .addValue("proposalId", proposalId),
            SNAPSHOT_MAPPER
        ).stream().findFirst();
    }

    private Optional<StoredEvent> findEventByIdempotencyKey(
        String tenantEvidenceHash,
        String idempotencyKeyHash
    ) {
        return jdbc.query("""
            select proposal_id,operator_evidence_hash,to_status,outcome,operation_hash,
                   result_evidence_hash,idempotency_payload_hash,command_attempts,
                   automatic_retry_allowed,happened_at
            from ap_ai_controlled_automation_lineage_event
            where tenant_evidence_hash=:tenantEvidenceHash
              and idempotency_key_hash=:idempotencyKeyHash
            """, new MapSqlParameterSource()
                .addValue("tenantEvidenceHash", tenantEvidenceHash)
                .addValue("idempotencyKeyHash", idempotencyKeyHash),
            EVENT_MAPPER
        ).stream().findFirst();
    }

    private static boolean exactRegistration(
        LineageSnapshot snapshot,
        RegistrationCommand command
    ) {
        return snapshot.proposalId().equals(command.proposalId())
            && snapshot.confirmationId().equals(command.confirmationId())
            && snapshot.tenantEvidenceHash().equals(command.tenantEvidenceHash())
            && snapshot.operatorEvidenceHash().equals(command.operatorEvidenceHash())
            && snapshot.proposalLineageHash().equals(command.proposalLineageHash())
            && snapshot.confirmationEvidenceHash().equals(command.confirmationEvidenceHash())
            && snapshot.canonicalActionType().equals(command.canonicalActionType())
            && snapshot.resourceEvidenceHash().equals(command.resourceEvidenceHash())
            && snapshot.whitelistVersion().equals(command.whitelistVersion())
            && snapshot.policyVersion().equals(command.policyVersion())
            && snapshot.registrationIdempotencyKeyHash().equals(command.idempotencyKeyHash())
            && snapshot.registrationIdempotencyPayloadHash().equals(
                command.idempotencyPayloadHash()
            )
            && snapshot.confirmedAt().equals(command.confirmedAt())
            && snapshot.expiresAt().equals(command.expiresAt());
    }

    private static MapSqlParameterSource registrationParameters(RegistrationCommand command) {
        return new MapSqlParameterSource()
            .addValue("tenantEvidenceHash", command.tenantEvidenceHash())
            .addValue("proposalId", command.proposalId())
            .addValue("confirmationId", command.confirmationId())
            .addValue("operatorEvidenceHash", command.operatorEvidenceHash())
            .addValue("proposalLineageHash", command.proposalLineageHash())
            .addValue("confirmationEvidenceHash", command.confirmationEvidenceHash())
            .addValue("canonicalActionType", command.canonicalActionType())
            .addValue("resourceEvidenceHash", command.resourceEvidenceHash())
            .addValue("whitelistVersion", command.whitelistVersion())
            .addValue("policyVersion", command.policyVersion())
            .addValue("idempotencyKeyHash", command.idempotencyKeyHash())
            .addValue("idempotencyPayloadHash", command.idempotencyPayloadHash())
            .addValue("registrationEvidenceHash", command.evidenceHash())
            .addValue("confirmedAt", Timestamp.from(command.confirmedAt()))
            .addValue("expiresAt", Timestamp.from(command.expiresAt()));
    }

    private static MapSqlParameterSource transitionParameters(TransitionCommand command) {
        return new MapSqlParameterSource()
            .addValue("tenantEvidenceHash", command.tenantEvidenceHash())
            .addValue("operatorEvidenceHash", command.operatorEvidenceHash())
            .addValue("proposalId", command.proposalId())
            .addValue("expectedRevision", command.expectedRevision())
            .addValue("expectedStatus", command.expectedStatus().name())
            .addValue("resultEvidenceHash", command.resultEvidenceHash())
            .addValue("idempotencyKeyHash", command.idempotencyKeyHash())
            .addValue("idempotencyPayloadHash", command.idempotencyPayloadHash())
            .addValue("happenedAt", Timestamp.from(command.occurredAt()))
            .addValue("commandAttempts", command.commandAttempts());
    }

    private UUID nextEventId() {
        return Objects.requireNonNull(eventIdentifiers.get(), "eventId must not be null");
    }

    private static TransitionResult result(
        TransitionDisposition disposition,
        LineageSnapshot snapshot
    ) {
        return new TransitionResult(disposition, Optional.of(snapshot));
    }

    private static String registrationEventHash(UUID eventId, RegistrationCommand command) {
        return hashTuple(
            "controlled-automation-lineage-event-v1",
            eventId.toString(),
            command.tenantEvidenceHash(),
            command.proposalId().toString(),
            "1",
            "REGISTERED",
            ZERO_HASH,
            command.evidenceHash(),
            command.idempotencyKeyHash(),
            command.idempotencyPayloadHash(),
            command.confirmedAt().toString()
        );
    }

    private static String transitionEventHash(
        UUID eventId,
        LineageSnapshot current,
        TransitionCommand command
    ) {
        return hashTuple(
            "controlled-automation-lineage-event-v1",
            eventId.toString(),
            command.tenantEvidenceHash(),
            command.proposalId().toString(),
            Long.toString(current.revision() + 1),
            "TERMINATED",
            current.currentEventHash(),
            command.transitionHash(),
            command.resultEvidenceHash(),
            command.idempotencyKeyHash(),
            command.idempotencyPayloadHash(),
            command.targetStatus().name(),
            command.outcome().name(),
            Integer.toString(command.commandAttempts()),
            command.occurredAt().toString()
        );
    }

    private static String hashTuple(String version, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, version);
            for (String value : values) {
                update(digest, value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value, "hash value must not be null")
            .getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String requireHash(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
        }
        return value;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static final RowMapper<LineageSnapshot> SNAPSHOT_MAPPER = (resultSet, row) ->
        new LineageSnapshot(
            resultSet.getObject("proposal_id", UUID.class),
            resultSet.getObject("confirmation_id", UUID.class),
            resultSet.getString("tenant_evidence_hash"),
            resultSet.getString("operator_evidence_hash"),
            resultSet.getString("proposal_lineage_hash"),
            resultSet.getString("confirmation_evidence_hash"),
            resultSet.getString("canonical_action_type"),
            resultSet.getString("resource_evidence_hash"),
            resultSet.getString("whitelist_version"),
            resultSet.getString("policy_version"),
            resultSet.getString("registration_idempotency_key_hash"),
            resultSet.getString("registration_idempotency_payload_hash"),
            resultSet.getLong("revision"),
            LineageStatus.valueOf(resultSet.getString("status")),
            LineageOutcome.valueOf(resultSet.getString("outcome")),
            resultSet.getInt("command_attempts"),
            resultSet.getBoolean("automatic_retry_allowed"),
            instant(resultSet, "confirmed_at"),
            instant(resultSet, "expires_at"),
            instant(resultSet, "updated_at"),
            resultSet.getString("current_evidence_hash"),
            resultSet.getString("current_event_hash")
        );

    private static final RowMapper<StoredEvent> EVENT_MAPPER = (resultSet, row) ->
        new StoredEvent(
            resultSet.getObject("proposal_id", UUID.class),
            resultSet.getString("operator_evidence_hash"),
            LineageStatus.valueOf(resultSet.getString("to_status")),
            LineageOutcome.valueOf(resultSet.getString("outcome")),
            resultSet.getString("operation_hash"),
            resultSet.getString("result_evidence_hash"),
            resultSet.getString("idempotency_payload_hash"),
            resultSet.getInt("command_attempts"),
            resultSet.getBoolean("automatic_retry_allowed"),
            instant(resultSet, "happened_at")
        );

    private record StoredEvent(
        UUID proposalId,
        String operatorEvidenceHash,
        LineageStatus toStatus,
        LineageOutcome outcome,
        String operationHash,
        String resultEvidenceHash,
        String idempotencyPayloadHash,
        int commandAttempts,
        boolean automaticRetryAllowed,
        Instant happenedAt
    ) {
    }

    public static final class LineagePersistenceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public LineagePersistenceException(String message) {
            super(message);
        }

        public LineagePersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

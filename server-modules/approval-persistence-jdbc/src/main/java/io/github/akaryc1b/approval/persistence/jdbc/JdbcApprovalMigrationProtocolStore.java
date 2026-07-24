package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationVerification;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL facade for M5-B migration persistence; it performs no engine operation. */
public final class JdbcApprovalMigrationProtocolStore implements ApprovalMigrationProtocolStore {

    private final JdbcApprovalMigrationIntentStore intents;
    private final JdbcApprovalMigrationAttemptStore attempts;
    private final JdbcApprovalMigrationEvidenceStore evidence;

    public JdbcApprovalMigrationProtocolStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        JdbcApprovalMigrationJson json = new JdbcApprovalMigrationJson(objectMapper);
        intents = new JdbcApprovalMigrationIntentStore(dataSource, json, transactionManager);
        attempts = new JdbcApprovalMigrationAttemptStore(dataSource, json, transactionManager);
        evidence = new JdbcApprovalMigrationEvidenceStore(dataSource, json, transactionManager);
    }

    @Override
    public IntentCreationResult createIntent(ApprovalMigrationIntent value, ApprovalMigrationIntentEvent event) {
        return intents.create(value, event);
    }

    @Override
    public Optional<ApprovalMigrationIntent> findIntent(String tenantId, UUID intentId) {
        return intents.find(tenantId, intentId);
    }

    @Override
    public Optional<ApprovalMigrationIntent> findIntentByIdempotencyKey(String tenantId, String key) {
        return intents.findByIdempotencyKey(tenantId, key);
    }

    @Override
    public ApprovalMigrationIntent transitionIntent(
        ApprovalMigrationIntent next,
        long expectedRevision,
        ApprovalMigrationIntentEvent event
    ) {
        return intents.transition(next, expectedRevision, event);
    }

    @Override
    public List<ApprovalMigrationIntentEvent> findIntentEvents(String tenantId, UUID intentId) {
        return intents.events(tenantId, intentId);
    }

    @Override
    public AttemptCreationResult createAttempt(
        ApprovalMigrationAttempt value,
        ApprovalMigrationAttemptEvent event
    ) {
        return attempts.create(value, event);
    }

    @Override
    public Optional<ApprovalMigrationAttempt> findAttempt(String tenantId, UUID attemptId) {
        return attempts.find(tenantId, attemptId);
    }

    @Override
    public List<ApprovalMigrationAttempt> findAttempts(String tenantId, UUID intentId) {
        return attempts.findByIntent(tenantId, intentId);
    }

    @Override
    public ApprovalMigrationAttempt transitionAttempt(
        ApprovalMigrationAttempt next,
        long expectedRevision,
        ApprovalMigrationAttemptEvent event
    ) {
        return attempts.transition(next, expectedRevision, event);
    }

    @Override
    public ApprovalMigrationAttempt transitionAttempt(
        ApprovalMigrationAttempt next,
        long expectedRevision,
        String leaseActor,
        ApprovalMigrationAttemptEvent event
    ) {
        return attempts.transition(next, expectedRevision, leaseActor, event);
    }

    @Override
    public List<ApprovalMigrationAttemptEvent> findAttemptEvents(String tenantId, UUID attemptId) {
        return attempts.events(tenantId, attemptId);
    }

    @Override
    public void appendVerification(ApprovalMigrationVerification value) {
        evidence.appendVerification(value);
    }

    @Override
    public List<ApprovalMigrationVerification> findVerifications(String tenantId, UUID attemptId) {
        return evidence.verifications(tenantId, attemptId);
    }

    @Override
    public void appendReconciliation(ApprovalMigrationReconciliation value) {
        evidence.appendReconciliation(value);
    }

    @Override
    public List<ApprovalMigrationReconciliation> findReconciliations(String tenantId, UUID attemptId) {
        return evidence.reconciliations(tenantId, attemptId);
    }
}

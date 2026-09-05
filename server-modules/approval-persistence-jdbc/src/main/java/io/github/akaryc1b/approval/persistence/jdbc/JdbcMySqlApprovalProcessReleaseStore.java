package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalProcessRelease;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseLifecycle.State;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** MySQL 8.4 process release lifecycle and append-only transition evidence store. */
public final class JdbcMySqlApprovalProcessReleaseStore
    implements ApprovalProcessReleaseStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;

    public JdbcMySqlApprovalProcessReleaseStore(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalProcessReleaseStore requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.locks = new JdbcMySqlTransactionLockManager(source);
    }

    @Override
    public void lock(String tenantId, String definitionKey) {
        locks.acquire(
            "approval-process-release:"
                + requireText(tenantId, "tenantId")
                + ':'
                + requireText(definitionKey, "definitionKey")
        );
    }

    @Override
    public Optional<ApprovalProcessRelease> find(
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        return queryRelease(
            "tenant_id = :tenantId and definition_key = :definitionKey "
                + "and release_version = :releaseVersion",
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("definitionKey", requireText(definitionKey, "definitionKey"))
                .addValue("releaseVersion", requirePositive(releaseVersion, "releaseVersion")),
            ""
        );
    }

    @Override
    public Optional<ApprovalProcessRelease> findActive(
        String tenantId,
        String definitionKey
    ) {
        return queryRelease(
            "tenant_id = :tenantId and definition_key = :definitionKey "
                + "and lifecycle_state = 'ACTIVE'",
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("definitionKey", requireText(definitionKey, "definitionKey")),
            ""
        );
    }

    @Override
    public Optional<ApprovalProcessRelease.Transition> findTransitionByIdempotency(
        String tenantId,
        String idempotencyKey
    ) {
        return jdbc.query(
            selectTransition()
                + " where tenant_id = :tenantId and idempotency_key = :idempotencyKey",
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue(
                    "idempotencyKey",
                    requireText(idempotencyKey, "idempotencyKey")
                ),
            (resultSet, rowNumber) -> transition(resultSet)
        ).stream().findFirst();
    }

    @Override
    public ReleasePage findReleases(ReleaseCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        List<String> predicates = new ArrayList<>();
        predicates.add("tenant_id = :tenantId");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        if (criteria.definitionKey() != null) {
            predicates.add("definition_key = :definitionKey");
            parameters.addValue("definitionKey", criteria.definitionKey());
        }
        if (criteria.lifecycleState() != null) {
            predicates.add("lifecycle_state = :lifecycleState");
            parameters.addValue("lifecycleState", criteria.lifecycleState().name());
        }
        String where = String.join(" and ", predicates);
        Long total = jdbc.queryForObject(
            "select count(*) from ap_process_release_lifecycle where " + where,
            parameters,
            Long.class
        );
        List<ApprovalProcessRelease> items = jdbc.query(
            selectRelease()
                + " where "
                + where
                + " order by definition_key, release_version desc"
                + " limit :limit offset :offset",
            parameters,
            (resultSet, rowNumber) -> release(resultSet)
        );
        return new ReleasePage(
            items,
            total == null ? 0 : total,
            criteria.limit(),
            criteria.offset()
        );
    }

    @Override
    public TransitionPage findHistory(TransitionCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("definitionKey", criteria.definitionKey())
            .addValue("releaseVersion", criteria.releaseVersion())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_process_release_lifecycle_history
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and release_version = :releaseVersion
            """,
            parameters,
            Long.class
        );
        List<ApprovalProcessRelease.Transition> items = jdbc.query(
            selectTransition()
                + " where tenant_id = :tenantId"
                + " and definition_key = :definitionKey"
                + " and release_version = :releaseVersion"
                + " order by revision desc, transition_id desc"
                + " limit :limit offset :offset",
            parameters,
            (resultSet, rowNumber) -> transition(resultSet)
        );
        return new TransitionPage(
            items,
            total == null ? 0 : total,
            criteria.limit(),
            criteria.offset()
        );
    }

    @Override
    public void savePublished(
        ApprovalProcessRelease release,
        ApprovalProcessRelease.Transition transition
    ) {
        ApprovalProcessRelease exact = Objects.requireNonNull(
            release,
            "release must not be null"
        );
        ApprovalProcessRelease.Transition evidence = Objects.requireNonNull(
            transition,
            "transition must not be null"
        );
        requireMatchingEvidence(exact, evidence);
        if (exact.lifecycleState() != State.PUBLISHED
            || evidence.fromState() != State.DRAFT
            || evidence.toState() != State.PUBLISHED
            || exact.revision() != evidence.revision()) {
            throw new IllegalArgumentException(
                "new process release requires matching DRAFT -> PUBLISHED evidence"
            );
        }
        int inserted = jdbc.update(
            """
            insert into ap_process_release_lifecycle (
                tenant_id, definition_key, release_version, release_package_hash,
                lifecycle_state, revision, published_by, published_at,
                activated_at, deprecated_at, retired_at,
                last_transition_by, last_transition_at, last_transition_reason,
                last_idempotency_key, last_request_id, last_trace_id,
                last_audit_chain_reference
            ) values (
                :tenantId, :definitionKey, :releaseVersion, :releasePackageHash,
                :lifecycleState, :revision, :publishedBy, :publishedAt,
                :activatedAt, :deprecatedAt, :retiredAt,
                :lastTransitionBy, :lastTransitionAt, :lastTransitionReason,
                :lastIdempotencyKey, :lastRequestId, :lastTraceId,
                :lastAuditChainReference
            )
            """,
            releaseParameters(exact)
        );
        if (inserted != 1) {
            throw new IllegalStateException("process release lifecycle was not inserted");
        }
        insertTransition(evidence);
    }

    @Override
    public boolean transition(
        ApprovalProcessRelease release,
        long expectedRevision,
        ApprovalProcessRelease.Transition transition
    ) {
        ApprovalProcessRelease exact = Objects.requireNonNull(
            release,
            "release must not be null"
        );
        ApprovalProcessRelease.Transition evidence = Objects.requireNonNull(
            transition,
            "transition must not be null"
        );
        requireMatchingEvidence(exact, evidence);
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        if (exact.revision() != expectedRevision + 1
            || exact.lifecycleState() != evidence.toState()
            || exact.revision() != evidence.revision()) {
            throw new IllegalArgumentException("release and transition revisions do not match");
        }
        int updated = jdbc.update(
            """
            update ap_process_release_lifecycle
            set lifecycle_state = :lifecycleState,
                revision = :revision,
                activated_at = :activatedAt,
                deprecated_at = :deprecatedAt,
                retired_at = :retiredAt,
                last_transition_by = :lastTransitionBy,
                last_transition_at = :lastTransitionAt,
                last_transition_reason = :lastTransitionReason,
                last_idempotency_key = :lastIdempotencyKey,
                last_request_id = :lastRequestId,
                last_trace_id = :lastTraceId,
                last_audit_chain_reference = :lastAuditChainReference
            where tenant_id = :tenantId
              and definition_key = :definitionKey
              and release_version = :releaseVersion
              and revision = :expectedRevision
            """,
            releaseParameters(exact).addValue("expectedRevision", expectedRevision)
        );
        if (updated == 0) {
            return false;
        }
        if (updated != 1) {
            throw new IllegalStateException("process release lifecycle update was not singular");
        }
        insertTransition(evidence);
        return true;
    }

    private Optional<ApprovalProcessRelease> queryRelease(
        String predicate,
        MapSqlParameterSource parameters,
        String suffix
    ) {
        return jdbc.query(
            selectRelease() + " where " + predicate + suffix,
            parameters,
            (resultSet, rowNumber) -> release(resultSet)
        ).stream().findFirst();
    }

    private void insertTransition(ApprovalProcessRelease.Transition transition) {
        int inserted = jdbc.update(
            """
            insert into ap_process_release_lifecycle_history (
                transition_id, tenant_id, definition_key, release_version,
                release_package_hash, from_state, to_state, revision,
                reason, idempotency_key, operator_id, request_id, trace_id,
                audit_chain_reference, happened_at
            ) values (
                :transitionId, :tenantId, :definitionKey, :releaseVersion,
                :releasePackageHash, :fromState, :toState, :revision,
                :reason, :idempotencyKey, :operatorId, :requestId, :traceId,
                :auditChainReference, :happenedAt
            )
            """,
            transitionParameters(transition)
        );
        if (inserted != 1) {
            throw new IllegalStateException("process release transition was not inserted");
        }
    }

    private MapSqlParameterSource releaseParameters(ApprovalProcessRelease value) {
        return new MapSqlParameterSource()
            .addValue("tenantId", value.tenantId())
            .addValue("definitionKey", value.definitionKey())
            .addValue("releaseVersion", value.releaseVersion())
            .addValue("releasePackageHash", value.releasePackageHash())
            .addValue("lifecycleState", value.lifecycleState().name())
            .addValue("revision", value.revision())
            .addValue("publishedBy", value.publishedBy())
            .addValue("publishedAt", bindInstant(value.publishedAt()))
            .addValue("activatedAt", bindNullableInstant(value.activatedAt()))
            .addValue("deprecatedAt", bindNullableInstant(value.deprecatedAt()))
            .addValue("retiredAt", bindNullableInstant(value.retiredAt()))
            .addValue("lastTransitionBy", value.lastTransitionBy())
            .addValue("lastTransitionAt", bindInstant(value.lastTransitionAt()))
            .addValue("lastTransitionReason", value.lastTransitionReason())
            .addValue("lastIdempotencyKey", value.lastIdempotencyKey())
            .addValue("lastRequestId", value.lastRequestId())
            .addValue("lastTraceId", value.lastTraceId())
            .addValue("lastAuditChainReference", value.lastAuditChainReference());
    }

    private MapSqlParameterSource transitionParameters(
        ApprovalProcessRelease.Transition value
    ) {
        return new MapSqlParameterSource()
            .addValue("transitionId", values.bindUuid(value.transitionId()))
            .addValue("tenantId", value.tenantId())
            .addValue("definitionKey", value.definitionKey())
            .addValue("releaseVersion", value.releaseVersion())
            .addValue("releasePackageHash", value.releasePackageHash())
            .addValue("fromState", value.fromState().name())
            .addValue("toState", value.toState().name())
            .addValue("revision", value.revision())
            .addValue("reason", value.reason())
            .addValue("idempotencyKey", value.idempotencyKey())
            .addValue("operatorId", value.operatorId())
            .addValue("requestId", value.requestId())
            .addValue("traceId", value.traceId())
            .addValue("auditChainReference", value.auditChainReference())
            .addValue("happenedAt", bindInstant(value.happenedAt()));
    }

    private ApprovalProcessRelease release(ResultSet resultSet) throws SQLException {
        return new ApprovalProcessRelease(
            resultSet.getString("tenant_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("release_version"),
            resultSet.getString("release_package_hash"),
            State.valueOf(resultSet.getString("lifecycle_state")),
            resultSet.getLong("revision"),
            resultSet.getString("published_by"),
            values.instant(resultSet, "published_at"),
            values.nullableInstant(resultSet, "activated_at"),
            values.nullableInstant(resultSet, "deprecated_at"),
            values.nullableInstant(resultSet, "retired_at"),
            resultSet.getString("last_transition_by"),
            values.instant(resultSet, "last_transition_at"),
            resultSet.getString("last_transition_reason"),
            resultSet.getString("last_idempotency_key"),
            resultSet.getString("last_request_id"),
            resultSet.getString("last_trace_id"),
            resultSet.getString("last_audit_chain_reference")
        );
    }

    private ApprovalProcessRelease.Transition transition(ResultSet resultSet)
        throws SQLException {
        return new ApprovalProcessRelease.Transition(
            values.uuid(resultSet, "transition_id"),
            resultSet.getString("tenant_id"),
            resultSet.getString("definition_key"),
            resultSet.getInt("release_version"),
            resultSet.getString("release_package_hash"),
            State.valueOf(resultSet.getString("from_state")),
            State.valueOf(resultSet.getString("to_state")),
            resultSet.getLong("revision"),
            resultSet.getString("reason"),
            resultSet.getString("idempotency_key"),
            resultSet.getString("operator_id"),
            resultSet.getString("request_id"),
            resultSet.getString("trace_id"),
            resultSet.getString("audit_chain_reference"),
            values.instant(resultSet, "happened_at")
        );
    }

    private static void requireMatchingEvidence(
        ApprovalProcessRelease release,
        ApprovalProcessRelease.Transition transition
    ) {
        if (!release.tenantId().equals(transition.tenantId())
            || !release.definitionKey().equals(transition.definitionKey())
            || release.releaseVersion() != transition.releaseVersion()
            || !release.releasePackageHash().equals(transition.releasePackageHash())
            || release.lifecycleState() != transition.toState()
            || release.revision() != transition.revision()
            || !release.lastTransitionBy().equals(transition.operatorId())
            || !release.lastTransitionAt().equals(transition.happenedAt())
            || !release.lastTransitionReason().equals(transition.reason())
            || !release.lastIdempotencyKey().equals(transition.idempotencyKey())
            || !release.lastRequestId().equals(transition.requestId())
            || !Objects.equals(release.lastTraceId(), transition.traceId())
            || !release.lastAuditChainReference().equals(
                transition.auditChainReference()
            )) {
            throw new IllegalArgumentException(
                "release lifecycle state and transition evidence do not match"
            );
        }
    }

    private static String selectRelease() {
        return """
            select tenant_id, definition_key, release_version, release_package_hash,
                   lifecycle_state, revision, published_by, published_at,
                   activated_at, deprecated_at, retired_at,
                   last_transition_by, last_transition_at, last_transition_reason,
                   last_idempotency_key, last_request_id, last_trace_id,
                   last_audit_chain_reference
            from ap_process_release_lifecycle
            """;
    }

    private static String selectTransition() {
        return """
            select transition_id, tenant_id, definition_key, release_version,
                   release_package_hash, from_state, to_state, revision,
                   reason, idempotency_key, operator_id, request_id, trace_id,
                   audit_chain_reference, happened_at
            from ap_process_release_lifecycle_history
            """;
    }

    private Object bindInstant(Instant value) {
        return values.bindInstant(canonicalInstant(value));
    }

    private Object bindNullableInstant(Instant value) {
        return value == null ? null : bindInstant(value);
    }

    private static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(
            Objects.requireNonNull(value, "instant must not be null")
        );
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null");
        if (exact.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}

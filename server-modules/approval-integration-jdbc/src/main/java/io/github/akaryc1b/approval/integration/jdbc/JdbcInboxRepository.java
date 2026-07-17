package io.github.akaryc1b.approval.integration.jdbc;

import io.github.akaryc1b.approval.integration.inbox.InboxMessageKey;
import io.github.akaryc1b.approval.integration.inbox.InboxRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

public final class JdbcInboxRepository implements InboxRepository {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcInboxRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
    }

    @Override
    public BeginResult begin(
        InboxMessageKey key,
        String payloadHash,
        Instant now,
        String workerId,
        Duration leaseDuration
    ) {
        Objects.requireNonNull(key, "key must not be null");
        payloadHash = requireText(payloadHash, "payloadHash");
        Objects.requireNonNull(now, "now must not be null");
        workerId = requireText(workerId, "workerId");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");

        var parameters = parameters(key)
            .addValue("payloadHash", payloadHash)
            .addValue("now", offset(now))
            .addValue("workerId", workerId)
            .addValue("lockedUntil", offset(now.plus(leaseDuration)));
        int inserted = jdbc.update("""
            insert into ap_inbox (
                tenant_id, consumer_key, message_id, payload_hash,
                status, attempts, locked_by, locked_until,
                received_at, updated_at
            ) values (
                :tenantId, :consumerKey, :messageId, :payloadHash,
                'PROCESSING', 1, :workerId, :lockedUntil,
                :now, :now
            )
            on conflict (tenant_id, consumer_key, message_id) do nothing
            """, parameters);
        if (inserted == 1) {
            return new BeginResult(BeginStatus.ACQUIRED, 1);
        }
        return inspectOrReacquire(key, payloadHash, now, workerId, leaseDuration);
    }

    @Override
    public boolean complete(InboxMessageKey key, String workerId, Instant completedAt) {
        String sql = """
            update ap_inbox
            set status = 'COMPLETED',
                completed_at = :completedAt,
                updated_at = :completedAt,
                locked_by = null,
                locked_until = null,
                last_error = null
            where tenant_id = :tenantId
              and consumer_key = :consumerKey
              and message_id = :messageId
              and status = 'PROCESSING'
              and locked_by = :workerId
            """;
        return jdbc.update(sql, parameters(key)
            .addValue("workerId", workerId)
            .addValue("completedAt", offset(completedAt))) == 1;
    }

    @Override
    public boolean fail(
        InboxMessageKey key,
        String workerId,
        String errorMessage,
        Instant failedAt
    ) {
        String sql = """
            update ap_inbox
            set status = 'FAILED',
                last_error = :lastError,
                updated_at = :failedAt,
                locked_by = null,
                locked_until = null
            where tenant_id = :tenantId
              and consumer_key = :consumerKey
              and message_id = :messageId
              and status = 'PROCESSING'
              and locked_by = :workerId
            """;
        return jdbc.update(sql, parameters(key)
            .addValue("workerId", workerId)
            .addValue("lastError", truncate(errorMessage))
            .addValue("failedAt", offset(failedAt))) == 1;
    }

    private BeginResult inspectOrReacquire(
        InboxMessageKey key,
        String payloadHash,
        Instant now,
        String workerId,
        Duration leaseDuration
    ) {
        CurrentMessage current = load(key);
        BeginResult terminal = terminalResult(current, payloadHash, now);
        if (terminal != null) {
            return terminal;
        }
        List<Integer> attempts = jdbc.query("""
            update ap_inbox
            set status = 'PROCESSING',
                attempts = attempts + 1,
                locked_by = :workerId,
                locked_until = :lockedUntil,
                updated_at = :now,
                last_error = null
            where tenant_id = :tenantId
              and consumer_key = :consumerKey
              and message_id = :messageId
              and payload_hash = :payloadHash
              and status <> 'COMPLETED'
              and (
                  status = 'FAILED'
                  or locked_until is null
                  or locked_until <= :now
              )
            returning attempts
            """, parameters(key)
            .addValue("payloadHash", payloadHash)
            .addValue("workerId", workerId)
            .addValue("lockedUntil", offset(now.plus(leaseDuration)))
            .addValue("now", offset(now)),
            (resultSet, rowNumber) -> resultSet.getInt("attempts"));
        if (!attempts.isEmpty()) {
            return new BeginResult(BeginStatus.ACQUIRED, attempts.getFirst());
        }
        current = load(key);
        BeginResult result = terminalResult(current, payloadHash, now);
        return result == null
            ? new BeginResult(BeginStatus.IN_PROGRESS, current.attempts())
            : result;
    }

    private CurrentMessage load(InboxMessageKey key) {
        List<CurrentMessage> messages = jdbc.query("""
            select payload_hash, status, attempts, locked_until
            from ap_inbox
            where tenant_id = :tenantId
              and consumer_key = :consumerKey
              and message_id = :messageId
            """, parameters(key), (resultSet, rowNumber) -> {
                OffsetDateTime lockedUntil = resultSet.getObject("locked_until", OffsetDateTime.class);
                return new CurrentMessage(
                    resultSet.getString("payload_hash"),
                    resultSet.getString("status"),
                    resultSet.getInt("attempts"),
                    lockedUntil == null ? null : lockedUntil.toInstant()
                );
            });
        if (messages.isEmpty()) {
            throw new IllegalStateException("Inbox message disappeared during begin");
        }
        return messages.getFirst();
    }

    private static BeginResult terminalResult(
        CurrentMessage current,
        String payloadHash,
        Instant now
    ) {
        if (!current.payloadHash().equals(payloadHash)) {
            return new BeginResult(BeginStatus.PAYLOAD_MISMATCH, current.attempts());
        }
        if (current.status().equals("COMPLETED")) {
            return new BeginResult(BeginStatus.ALREADY_COMPLETED, current.attempts());
        }
        if (current.status().equals("PROCESSING")
            && current.lockedUntil() != null
            && current.lockedUntil().isAfter(now)) {
            return new BeginResult(BeginStatus.IN_PROGRESS, current.attempts());
        }
        return null;
    }

    private static MapSqlParameterSource parameters(InboxMessageKey key) {
        Objects.requireNonNull(key, "key must not be null");
        return new MapSqlParameterSource()
            .addValue("tenantId", key.tenantId())
            .addValue("consumerKey", key.consumerKey())
            .addValue("messageId", key.messageId());
    }

    private static OffsetDateTime offset(Instant instant) {
        return Objects.requireNonNull(instant, "instant must not be null").atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record CurrentMessage(
        String payloadHash,
        String status,
        int attempts,
        Instant lockedUntil
    ) {
    }
}

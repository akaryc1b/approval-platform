package io.github.akaryc1b.approval.integration.jdbc;

import io.github.akaryc1b.approval.integration.inbox.InboxMessageKey;
import io.github.akaryc1b.approval.integration.inbox.InboxRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class JdbcInboxRepository implements InboxRepository {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcIntegrationDatabaseDialect database;
    private final JdbcInboxDialect dialect;

    public JdbcInboxRepository(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.database = JdbcIntegrationDatabaseDialect.resolve(source);
        this.dialect = JdbcInboxDialect.forDatabase(database);
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
        requirePositive(leaseDuration, "leaseDuration");

        MapSqlParameterSource parameters = parameters(key)
            .addValue("payloadHash", payloadHash)
            .addValue("now", database.bindInstant(now))
            .addValue("workerId", workerId)
            .addValue("lockedUntil", database.bindInstant(now.plus(leaseDuration)));
        int inserted;
        try {
            inserted = jdbc.update(dialect.admissionSql(), parameters);
        } catch (RuntimeException exception) {
            if (!dialect.isExpectedDuplicateAdmission(exception)) {
                throw exception;
            }
            inserted = 0;
        }
        if (inserted == 1) {
            return new BeginResult(BeginStatus.ACQUIRED, 1);
        }
        if (inserted != 0) {
            throw new IllegalStateException("unexpected Inbox admission row count");
        }
        return inspectOrReacquire(key, payloadHash, now, workerId, leaseDuration);
    }

    @Override
    public boolean complete(InboxMessageKey key, String workerId, Instant completedAt) {
        String exactWorker = requireText(workerId, "workerId");
        Instant exactCompletedAt = Objects.requireNonNull(
            completedAt,
            "completedAt must not be null"
        );
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
              and locked_until > :completedAt
            """;
        return jdbc.update(sql, parameters(key)
            .addValue("workerId", exactWorker)
            .addValue("completedAt", database.bindInstant(exactCompletedAt))) == 1;
    }

    @Override
    public boolean fail(
        InboxMessageKey key,
        String workerId,
        String errorMessage,
        Instant failedAt
    ) {
        String exactWorker = requireText(workerId, "workerId");
        Instant exactFailedAt = Objects.requireNonNull(failedAt, "failedAt must not be null");
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
              and locked_until > :failedAt
            """;
        return jdbc.update(sql, parameters(key)
            .addValue("workerId", exactWorker)
            .addValue("lastError", truncate(errorMessage))
            .addValue("failedAt", database.bindInstant(exactFailedAt))) == 1;
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

        MapSqlParameterSource parameters = parameters(key)
            .addValue("payloadHash", payloadHash)
            .addValue("workerId", workerId)
            .addValue("lockedUntil", database.bindInstant(now.plus(leaseDuration)))
            .addValue("now", database.bindInstant(now));
        if (dialect.reacquisitionReturnsAttempts()) {
            List<Integer> attempts = jdbc.query(
                dialect.reacquisitionSql(),
                parameters,
                (resultSet, rowNumber) -> resultSet.getInt("attempts")
            );
            if (attempts.size() == 1) {
                return new BeginResult(BeginStatus.ACQUIRED, attempts.getFirst());
            }
            if (attempts.size() > 1) {
                throw new IllegalStateException(
                    "unexpected Inbox reacquisition result count"
                );
            }
        } else {
            int reacquired = jdbc.update(dialect.reacquisitionSql(), parameters);
            if (reacquired == 1) {
                CurrentMessage acquired = load(key);
                if (!"PROCESSING".equals(acquired.status())
                    || !workerId.equals(acquired.lockedBy())
                    || acquired.lockedUntil() == null
                    || !acquired.lockedUntil().isAfter(now)) {
                    throw new IllegalStateException(
                        "Inbox message reacquisition lost worker lease ownership"
                    );
                }
                return new BeginResult(BeginStatus.ACQUIRED, acquired.attempts());
            }
            if (reacquired != 0) {
                throw new IllegalStateException(
                    "unexpected Inbox reacquisition row count"
                );
            }
        }

        current = load(key);
        BeginResult result = terminalResult(current, payloadHash, now);
        return result == null
            ? new BeginResult(BeginStatus.IN_PROGRESS, current.attempts())
            : result;
    }

    private CurrentMessage load(InboxMessageKey key) {
        List<CurrentMessage> messages = jdbc.query(
            """
            select payload_hash, status, attempts, locked_by, locked_until
            from ap_inbox
            where tenant_id = :tenantId
              and consumer_key = :consumerKey
              and message_id = :messageId
            """,
            parameters(key),
            this::currentMessage
        );
        if (messages.isEmpty()) {
            throw new IllegalStateException("Inbox message disappeared during begin");
        }
        if (messages.size() != 1) {
            throw new IllegalStateException("Inbox identity resolved multiple rows");
        }
        return messages.getFirst();
    }

    private CurrentMessage currentMessage(ResultSet resultSet, int rowNumber)
        throws SQLException {
        return new CurrentMessage(
            resultSet.getString("payload_hash"),
            resultSet.getString("status"),
            resultSet.getInt("attempts"),
            resultSet.getString("locked_by"),
            database.nullableInstant(resultSet, "locked_until")
        );
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

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH
            ? value
            : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private record CurrentMessage(
        String payloadHash,
        String status,
        int attempts,
        String lockedBy,
        Instant lockedUntil
    ) {
    }
}

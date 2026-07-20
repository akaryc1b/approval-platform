package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL notification preferences, intent queue and delivery evidence. */
public final class JdbcApprovalNotificationStore implements ApprovalNotificationStore {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public JdbcApprovalNotificationStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transactions = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    @Override
    public Optional<PreferenceBundle> findPreferences(String tenantId, String userId) {
        MapSqlParameterSource parameters = identityParameters(tenantId, userId);
        List<PreferenceBundle> settings = jdbc.query(
            """
            select * from ap_notification_user_setting
            where tenant_id = :tenantId and user_id = :userId
            """,
            parameters,
            settingsMapper()
        );
        if (settings.isEmpty()) {
            return Optional.empty();
        }
        PreferenceBundle setting = settings.getFirst();
        List<NotificationPreference> preferences = jdbc.query(
            """
            select event_type, channel, enabled
            from ap_notification_preference
            where tenant_id = :tenantId and user_id = :userId
            order by event_type, channel
            """,
            parameters,
            (resultSet, rowNumber) -> new NotificationPreference(
                NotificationEventType.valueOf(resultSet.getString("event_type")),
                NotificationChannel.valueOf(resultSet.getString("channel")),
                resultSet.getBoolean("enabled")
            )
        );
        return Optional.of(new PreferenceBundle(
            setting.tenantId(),
            setting.userId(),
            setting.timezone(),
            setting.quietHoursEnabled(),
            setting.quietHoursStart(),
            setting.quietHoursEnd(),
            setting.emergencyBypass(),
            setting.digestEnabled(),
            setting.version(),
            setting.createdAt(),
            setting.updatedAt(),
            preferences
        ));
    }

    @Override
    public PreferenceBundle savePreferences(PreferenceBundle bundle, long expectedVersion) {
        Objects.requireNonNull(bundle, "bundle must not be null");
        PreferenceBundle saved = transactions.execute(status -> {
            long newVersion = expectedVersion + 1;
            int changed;
            if (expectedVersion == 0) {
                changed = jdbc.update(
                    """
                    insert into ap_notification_user_setting (
                        tenant_id, user_id, timezone, quiet_hours_enabled,
                        quiet_hours_start, quiet_hours_end, emergency_bypass,
                        digest_enabled, version, created_at, updated_at
                    ) values (
                        :tenantId, :userId, :timezone, :quietHoursEnabled,
                        :quietHoursStart, :quietHoursEnd, :emergencyBypass,
                        :digestEnabled, :version, :createdAt, :updatedAt
                    ) on conflict (tenant_id, user_id) do nothing
                    """,
                    settingsParameters(bundle, newVersion)
                );
            } else {
                changed = jdbc.update(
                    """
                    update ap_notification_user_setting
                    set timezone = :timezone,
                        quiet_hours_enabled = :quietHoursEnabled,
                        quiet_hours_start = :quietHoursStart,
                        quiet_hours_end = :quietHoursEnd,
                        emergency_bypass = :emergencyBypass,
                        digest_enabled = :digestEnabled,
                        version = :newVersion,
                        updated_at = :updatedAt
                    where tenant_id = :tenantId
                      and user_id = :userId
                      and version = :expectedVersion
                    """,
                    settingsParameters(bundle, newVersion)
                        .addValue("newVersion", newVersion)
                        .addValue("expectedVersion", expectedVersion)
                );
            }
            if (changed != 1) {
                throw new NotificationConflictException(
                    "notification preferences changed concurrently"
                );
            }
            MapSqlParameterSource identity = identityParameters(bundle.tenantId(), bundle.userId());
            jdbc.update(
                """
                delete from ap_notification_preference
                where tenant_id = :tenantId and user_id = :userId
                """,
                identity
            );
            for (NotificationPreference preference : bundle.preferences()) {
                jdbc.update(
                    """
                    insert into ap_notification_preference (
                        tenant_id, user_id, event_type, channel, enabled, settings_version
                    ) values (
                        :tenantId, :userId, :eventType, :channel, :enabled, :settingsVersion
                    )
                    """,
                    new MapSqlParameterSource()
                        .addValue("tenantId", bundle.tenantId())
                        .addValue("userId", bundle.userId())
                        .addValue("eventType", preference.eventType().name())
                        .addValue("channel", preference.channel().name())
                        .addValue("enabled", preference.enabled())
                        .addValue("settingsVersion", newVersion)
                );
            }
            return findPreferences(bundle.tenantId(), bundle.userId())
                .orElseThrow(() -> new IllegalStateException(
                    "saved notification preferences disappeared"
                ));
        });
        return Objects.requireNonNull(saved, "saved notification preferences must not be null");
    }

    @Override
    public int enqueue(List<NotificationIntent> intents) {
        if (intents == null || intents.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (NotificationIntent intent : intents) {
            inserted += jdbc.update(
                """
                insert into ap_notification_intent (
                    intent_id, tenant_id, event_type, channel, recipient_id, sender_id,
                    instance_id, task_id, aggregate_type, aggregate_id,
                    template_key, template_version, title, body, metadata_json,
                    business_event_key, urgent, status, attempt_count, max_attempts,
                    next_attempt_at, delivered_at, read_at,
                    last_error_code, last_error_message, locked_by, locked_until,
                    created_at, updated_at, version
                ) values (
                    :intentId, :tenantId, :eventType, :channel, :recipientId, :senderId,
                    :instanceId, :taskId, :aggregateType, :aggregateId,
                    :templateKey, :templateVersion, :title, :body, cast(:metadataJson as jsonb),
                    :businessEventKey, :urgent, :status, :attemptCount, :maxAttempts,
                    :nextAttemptAt, :deliveredAt, :readAt,
                    :lastErrorCode, :lastErrorMessage, :lockedBy, :lockedUntil,
                    :createdAt, :updatedAt, :version
                ) on conflict (tenant_id, business_event_key, recipient_id, channel) do nothing
                """,
                intentParameters(intent)
            );
        }
        return inserted;
    }

    @Override
    public List<NotificationIntent> claimDue(
        Instant now,
        int limit,
        String workerId,
        Instant lockedUntil
    ) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return List.copyOf(jdbc.query(
            """
            with candidates as (
                select tenant_id, intent_id
                from ap_notification_intent
                where (
                    status in ('PENDING', 'RETRY') and next_attempt_at <= :now
                ) or (
                    status = 'PROCESSING' and locked_until < :now
                )
                order by next_attempt_at, created_at, intent_id
                for update skip locked
                limit :limit
            )
            update ap_notification_intent intent
            set status = 'PROCESSING',
                locked_by = :workerId,
                locked_until = :lockedUntil,
                updated_at = :now,
                version = intent.version + 1
            from candidates
            where intent.tenant_id = candidates.tenant_id
              and intent.intent_id = candidates.intent_id
            returning intent.*
            """,
            new MapSqlParameterSource()
                .addValue("now", offset(now))
                .addValue("limit", limit)
                .addValue("workerId", requireText(workerId, "workerId"))
                .addValue("lockedUntil", offset(lockedUntil)),
            intentMapper()
        ));
    }

    @Override
    public NotificationIntent markDelivered(
        String tenantId,
        UUID intentId,
        long expectedVersion,
        UUID attemptId,
        Instant startedAt,
        Instant deliveredAt,
        String providerMessageId
    ) {
        return terminalUpdate(
            tenantId,
            intentId,
            expectedVersion,
            attemptId,
            startedAt,
            deliveredAt,
            true,
            false,
            deliveredAt,
            providerMessageId,
            null,
            null
        );
    }

    @Override
    public NotificationIntent markFailed(
        String tenantId,
        UUID intentId,
        long expectedVersion,
        UUID attemptId,
        Instant startedAt,
        Instant completedAt,
        boolean retryable,
        Instant nextAttemptAt,
        String errorCode,
        String errorMessage
    ) {
        return terminalUpdate(
            tenantId,
            intentId,
            expectedVersion,
            attemptId,
            startedAt,
            completedAt,
            false,
            retryable,
            nextAttemptAt,
            null,
            requireText(errorCode, "errorCode"),
            requireText(errorMessage, "errorMessage")
        );
    }

    @Override
    public NotificationHistoryPage findHistory(NotificationHistoryCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("recipientId", criteria.recipientId())
            .addValue("unreadOnly", criteria.unreadOnly())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        Long total = jdbc.queryForObject(
            """
            select count(*)
            from ap_notification_intent
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and (:unreadOnly = false or read_at is null)
            """,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        List<NotificationIntent> items = matched == 0 ? List.of() : jdbc.query(
            """
            select * from ap_notification_intent
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and (:unreadOnly = false or read_at is null)
            order by created_at desc, intent_id desc
            limit :limit offset :offset
            """,
            parameters,
            intentMapper()
        );
        return new NotificationHistoryPage(
            items,
            matched,
            criteria.limit(),
            criteria.offset()
        );
    }

    @Override
    public long countUnread(String tenantId, String recipientId) {
        Long count = jdbc.queryForObject(
            """
            select count(*) from ap_notification_intent
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and read_at is null
            """,
            identityParameters(tenantId, recipientId),
            Long.class
        );
        return count == null ? 0 : count;
    }

    @Override
    public Optional<NotificationIntent> markRead(
        String tenantId,
        String recipientId,
        UUID intentId,
        Instant readAt
    ) {
        List<NotificationIntent> rows = jdbc.query(
            """
            update ap_notification_intent
            set read_at = coalesce(read_at, :readAt),
                updated_at = greatest(updated_at, :readAt),
                version = case when read_at is null then version + 1 else version end
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and intent_id = :intentId
            returning *
            """,
            identityParameters(tenantId, recipientId)
                .addValue("intentId", Objects.requireNonNull(intentId, "intentId must not be null"))
                .addValue("readAt", offset(readAt)),
            intentMapper()
        );
        return rows.stream().findFirst();
    }

    @Override
    public int markAllRead(String tenantId, String recipientId, Instant readAt) {
        return jdbc.update(
            """
            update ap_notification_intent
            set read_at = :readAt,
                updated_at = greatest(updated_at, :readAt),
                version = version + 1
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and read_at is null
            """,
            identityParameters(tenantId, recipientId).addValue("readAt", offset(readAt))
        );
    }

    @Override
    public NotificationIntent replayDeadLetter(
        String tenantId,
        String recipientId,
        UUID intentId,
        Instant nextAttemptAt
    ) {
        List<NotificationIntent> rows = jdbc.query(
            """
            update ap_notification_intent
            set status = 'PENDING',
                attempt_count = 0,
                next_attempt_at = :nextAttemptAt,
                delivered_at = null,
                last_error_code = null,
                last_error_message = null,
                locked_by = null,
                locked_until = null,
                updated_at = :nextAttemptAt,
                version = version + 1
            where tenant_id = :tenantId
              and recipient_id = :recipientId
              and intent_id = :intentId
              and status = 'DEAD_LETTER'
            returning *
            """,
            identityParameters(tenantId, recipientId)
                .addValue("intentId", Objects.requireNonNull(intentId, "intentId must not be null"))
                .addValue("nextAttemptAt", offset(nextAttemptAt)),
            intentMapper()
        );
        return rows.stream().findFirst().orElseThrow(() -> new NotificationConflictException(
            "only a dead-letter notification can be replayed"
        ));
    }

    @Override
    public List<DeliveryAttempt> findAttempts(
        String tenantId,
        String recipientId,
        UUID intentId
    ) {
        return List.copyOf(jdbc.query(
            """
            select attempt.*
            from ap_notification_delivery_attempt attempt
            join ap_notification_intent intent
              on intent.tenant_id = attempt.tenant_id
             and intent.intent_id = attempt.intent_id
            where intent.tenant_id = :tenantId
              and intent.recipient_id = :recipientId
              and intent.intent_id = :intentId
            order by attempt.attempt_number, attempt.started_at
            """,
            identityParameters(tenantId, recipientId)
                .addValue("intentId", Objects.requireNonNull(intentId, "intentId must not be null")),
            attemptMapper()
        ));
    }

    private NotificationIntent terminalUpdate(
        String tenantId,
        UUID intentId,
        long expectedVersion,
        UUID attemptId,
        Instant startedAt,
        Instant completedAt,
        boolean successful,
        boolean retryable,
        Instant nextAttemptAt,
        String providerMessageId,
        String errorCode,
        String errorMessage
    ) {
        NotificationIntent updated = transactions.execute(status -> {
            NotificationIntent current = requireIntent(tenantId, intentId);
            if (current.version() != expectedVersion || current.status() != NotificationStatus.PROCESSING) {
                throw new NotificationConflictException("notification intent changed concurrently");
            }
            int attemptNumber = current.attemptCount() + 1;
            jdbc.update(
                """
                insert into ap_notification_delivery_attempt (
                    attempt_id, tenant_id, intent_id, attempt_number,
                    started_at, completed_at, successful, retryable,
                    provider_message_id, error_code, error_message
                ) values (
                    :attemptId, :tenantId, :intentId, :attemptNumber,
                    :startedAt, :completedAt, :successful, :retryable,
                    :providerMessageId, :errorCode, :errorMessage
                )
                """,
                new MapSqlParameterSource()
                    .addValue("attemptId", Objects.requireNonNull(attemptId, "attemptId must not be null"))
                    .addValue("tenantId", current.tenantId())
                    .addValue("intentId", current.intentId())
                    .addValue("attemptNumber", attemptNumber)
                    .addValue("startedAt", offset(startedAt))
                    .addValue("completedAt", offset(completedAt))
                    .addValue("successful", successful)
                    .addValue("retryable", retryable)
                    .addValue("providerMessageId", normalizeOptional(providerMessageId))
                    .addValue("errorCode", normalizeOptional(errorCode))
                    .addValue("errorMessage", normalizeOptional(errorMessage))
            );
            NotificationStatus target = successful
                ? NotificationStatus.DELIVERED
                : retryable ? NotificationStatus.RETRY : NotificationStatus.DEAD_LETTER;
            int changed = jdbc.update(
                """
                update ap_notification_intent
                set status = :status,
                    attempt_count = attempt_count + 1,
                    next_attempt_at = :nextAttemptAt,
                    delivered_at = :deliveredAt,
                    last_error_code = :errorCode,
                    last_error_message = :errorMessage,
                    locked_by = null,
                    locked_until = null,
                    updated_at = :completedAt,
                    version = version + 1
                where tenant_id = :tenantId
                  and intent_id = :intentId
                  and status = 'PROCESSING'
                  and version = :expectedVersion
                """,
                new MapSqlParameterSource()
                    .addValue("status", target.name())
                    .addValue("nextAttemptAt", offset(nextAttemptAt))
                    .addValue("deliveredAt", successful ? offset(completedAt) : null)
                    .addValue("errorCode", normalizeOptional(errorCode))
                    .addValue("errorMessage", normalizeOptional(errorMessage))
                    .addValue("completedAt", offset(completedAt))
                    .addValue("tenantId", current.tenantId())
                    .addValue("intentId", current.intentId())
                    .addValue("expectedVersion", expectedVersion)
            );
            if (changed != 1) {
                throw new NotificationConflictException("notification intent changed concurrently");
            }
            return requireIntent(current.tenantId(), current.intentId());
        });
        return Objects.requireNonNull(updated, "updated notification intent must not be null");
    }

    private NotificationIntent requireIntent(String tenantId, UUID intentId) {
        return jdbc.query(
            """
            select * from ap_notification_intent
            where tenant_id = :tenantId and intent_id = :intentId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("intentId", Objects.requireNonNull(intentId, "intentId must not be null")),
            intentMapper()
        ).stream().findFirst().orElseThrow(() -> new NotificationNotFoundException(
            "notification intent was not found"
        ));
    }

    private MapSqlParameterSource settingsParameters(PreferenceBundle bundle, long version) {
        return new MapSqlParameterSource()
            .addValue("tenantId", bundle.tenantId())
            .addValue("userId", bundle.userId())
            .addValue("timezone", bundle.timezone())
            .addValue("quietHoursEnabled", bundle.quietHoursEnabled())
            .addValue("quietHoursStart", bundle.quietHoursStart())
            .addValue("quietHoursEnd", bundle.quietHoursEnd())
            .addValue("emergencyBypass", bundle.emergencyBypass())
            .addValue("digestEnabled", bundle.digestEnabled())
            .addValue("version", version)
            .addValue("createdAt", offset(bundle.createdAt()))
            .addValue("updatedAt", offset(bundle.updatedAt()));
    }

    private MapSqlParameterSource intentParameters(NotificationIntent intent) {
        return new MapSqlParameterSource()
            .addValue("intentId", intent.intentId())
            .addValue("tenantId", intent.tenantId())
            .addValue("eventType", intent.eventType().name())
            .addValue("channel", intent.channel().name())
            .addValue("recipientId", intent.recipientId())
            .addValue("senderId", intent.senderId())
            .addValue("instanceId", intent.instanceId())
            .addValue("taskId", intent.taskId())
            .addValue("aggregateType", intent.aggregateType())
            .addValue("aggregateId", intent.aggregateId())
            .addValue("templateKey", intent.templateKey())
            .addValue("templateVersion", intent.templateVersion())
            .addValue("title", intent.title())
            .addValue("body", intent.body())
            .addValue("metadataJson", encode(intent.metadata()))
            .addValue("businessEventKey", intent.businessEventKey())
            .addValue("urgent", intent.urgent())
            .addValue("status", intent.status().name())
            .addValue("attemptCount", intent.attemptCount())
            .addValue("maxAttempts", intent.maxAttempts())
            .addValue("nextAttemptAt", offset(intent.nextAttemptAt()))
            .addValue("deliveredAt", nullableOffset(intent.deliveredAt()))
            .addValue("readAt", nullableOffset(intent.readAt()))
            .addValue("lastErrorCode", intent.lastErrorCode())
            .addValue("lastErrorMessage", intent.lastErrorMessage())
            .addValue("lockedBy", intent.lockedBy())
            .addValue("lockedUntil", nullableOffset(intent.lockedUntil()))
            .addValue("createdAt", offset(intent.createdAt()))
            .addValue("updatedAt", offset(intent.updatedAt()))
            .addValue("version", intent.version());
    }

    private static MapSqlParameterSource identityParameters(String tenantId, String userId) {
        return new MapSqlParameterSource()
            .addValue("tenantId", requireText(tenantId, "tenantId"))
            .addValue("userId", requireText(userId, "userId"))
            .addValue("recipientId", requireText(userId, "recipientId"));
    }

    private RowMapper<PreferenceBundle> settingsMapper() {
        return (resultSet, rowNumber) -> new PreferenceBundle(
            resultSet.getString("tenant_id"),
            resultSet.getString("user_id"),
            resultSet.getString("timezone"),
            resultSet.getBoolean("quiet_hours_enabled"),
            nullableTime(resultSet, "quiet_hours_start"),
            nullableTime(resultSet, "quiet_hours_end"),
            resultSet.getBoolean("emergency_bypass"),
            resultSet.getBoolean("digest_enabled"),
            resultSet.getLong("version"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            List.of()
        );
    }

    private RowMapper<NotificationIntent> intentMapper() {
        return (resultSet, rowNumber) -> new NotificationIntent(
            resultSet.getObject("intent_id", UUID.class),
            resultSet.getString("tenant_id"),
            NotificationEventType.valueOf(resultSet.getString("event_type")),
            NotificationChannel.valueOf(resultSet.getString("channel")),
            resultSet.getString("recipient_id"),
            resultSet.getString("sender_id"),
            resultSet.getObject("instance_id", UUID.class),
            resultSet.getObject("task_id", UUID.class),
            resultSet.getString("aggregate_type"),
            resultSet.getString("aggregate_id"),
            resultSet.getString("template_key"),
            resultSet.getInt("template_version"),
            resultSet.getString("title"),
            resultSet.getString("body"),
            decode(resultSet.getString("metadata_json")),
            resultSet.getString("business_event_key"),
            resultSet.getBoolean("urgent"),
            NotificationStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("attempt_count"),
            resultSet.getInt("max_attempts"),
            instant(resultSet, "next_attempt_at"),
            nullableInstant(resultSet, "delivered_at"),
            nullableInstant(resultSet, "read_at"),
            resultSet.getString("last_error_code"),
            resultSet.getString("last_error_message"),
            resultSet.getString("locked_by"),
            nullableInstant(resultSet, "locked_until"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            resultSet.getLong("version")
        );
    }

    private static RowMapper<DeliveryAttempt> attemptMapper() {
        return (resultSet, rowNumber) -> new DeliveryAttempt(
            resultSet.getObject("attempt_id", UUID.class),
            resultSet.getObject("intent_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getInt("attempt_number"),
            instant(resultSet, "started_at"),
            instant(resultSet, "completed_at"),
            resultSet.getBoolean("successful"),
            resultSet.getBoolean("retryable"),
            resultSet.getString("provider_message_id"),
            resultSet.getString("error_code"),
            resultSet.getString("error_message")
        );
    }

    private String encode(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode notification metadata", exception);
        }
    }

    private Map<String, String> decode(String value) {
        try {
            return objectMapper.readValue(value, STRING_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("unable to decode notification metadata", exception);
        }
    }

    private static LocalTime nullableTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, LocalTime.class);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant value) {
        return Objects.requireNonNull(value, "instant must not be null").atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime nullableOffset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

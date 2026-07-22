package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarPage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarVersion;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.CalendarVersionRow;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.calendarIdentityMapper;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.calendarVersionRowMapper;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.offset;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.params;

final class JdbcApprovalCalendarStore extends JdbcApprovalSlaStoreSupport {

    JdbcApprovalCalendarStore(DataSource dataSource, PlatformTransactionManager transactionManager) {
        super(dataSource, transactionManager);
    }

    public Optional<CalendarIdentity> findCalendar(String tenantId, UUID calendarId) {
        return jdbc.query(
            "select * from ap_work_calendar where tenant_id=:tenantId and calendar_id=:calendarId",
            params("tenantId", tenantId, "calendarId", calendarId),
            calendarIdentityMapper()
        ).stream().findFirst();
    }
    public Optional<CalendarIdentity> findCalendarByKey(String tenantId, String calendarKey) {
        return jdbc.query(
            "select * from ap_work_calendar where tenant_id=:tenantId and calendar_key=:calendarKey",
            params("tenantId", tenantId, "calendarKey", calendarKey),
            calendarIdentityMapper()
        ).stream().findFirst();
    }
    public CalendarIdentity createCalendar(CalendarIdentity calendar) {
        int inserted = jdbc.update(
            """
            insert into ap_work_calendar (
                calendar_id,tenant_id,calendar_key,display_name,time_zone,status,active_version,
                created_by,created_at,updated_at,version
            ) values (
                :calendarId,:tenantId,:calendarKey,:displayName,:timeZone,:status,:activeVersion,
                :createdBy,:createdAt,:updatedAt,:version
            ) on conflict (tenant_id,calendar_key) do nothing
            """,
            calendarIdentityParameters(calendar)
        );
        if (inserted != 1) {
            throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar key already exists");
        }
        return findCalendar(calendar.tenantId(), calendar.calendarId())
            .orElseThrow(() -> new IllegalStateException("created calendar disappeared"));
    }
    public CalendarVersion saveCalendarVersion(CalendarVersion version, long expectedCalendarVersion) {
        return required(transactions.execute(status -> {
            CalendarIdentity identity = lockCalendar(version.tenantId(), version.calendarId());
            if (identity.version() != expectedCalendarVersion) {
                throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar identity changed concurrently");
            }
            Optional<CalendarVersion> existing = findCalendarVersion(
                version.tenantId(), version.calendarId(), version.calendarVersion()
            );
            if (existing.filter(CalendarVersion::immutable).isPresent()) {
                throw conflict("APPROVAL_CALENDAR_ALREADY_PUBLISHED", "published calendar version is immutable");
            }
            if (existing.isPresent()) {
                jdbc.update(
                    """
                    delete from ap_work_calendar_interval
                    where tenant_id=:tenantId and calendar_id=:calendarId and calendar_version=:calendarVersion
                    """,
                    calendarVersionIdentity(version)
                );
                jdbc.update(
                    """
                    delete from ap_work_calendar_date_override
                    where tenant_id=:tenantId and calendar_id=:calendarId and calendar_version=:calendarVersion
                    """,
                    calendarVersionIdentity(version)
                );
                int changed = jdbc.update(
                    """
                    update ap_work_calendar_version set time_zone=:timeZone,effective_from=:effectiveFrom,
                        effective_to=:effectiveTo,content_hash=:contentHash,status='DRAFT',immutable=false,
                        published_by=null,published_at=null,updated_at=:updatedAt
                    where tenant_id=:tenantId and calendar_id=:calendarId
                      and calendar_version=:calendarVersion and immutable=false
                    """,
                    calendarVersionParameters(version)
                );
                if (changed != 1) {
                    throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar draft changed concurrently");
                }
            } else {
                int inserted = jdbc.update(
                    """
                    insert into ap_work_calendar_version (
                        calendar_id,tenant_id,calendar_version,time_zone,effective_from,effective_to,
                        content_hash,status,immutable,published_by,published_at,created_at,updated_at
                    ) values (
                        :calendarId,:tenantId,:calendarVersion,:timeZone,:effectiveFrom,:effectiveTo,
                        :contentHash,'DRAFT',false,null,null,:createdAt,:updatedAt
                    )
                    """,
                    calendarVersionParameters(version)
                );
                if (inserted != 1) {
                    throw new IllegalStateException("calendar draft was not inserted");
                }
            }
            persistCalendarContent(version);
            updateCalendarVersion(identity, version.updatedAt());
            return findCalendarVersion(version.tenantId(), version.calendarId(), version.calendarVersion())
                .orElseThrow(() -> new IllegalStateException("saved calendar version disappeared"));
        }));
    }
    public Optional<CalendarVersion> findCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion
    ) {
        List<CalendarVersionRow> rows = jdbc.query(
            """
            select * from ap_work_calendar_version
            where tenant_id=:tenantId and calendar_id=:calendarId and calendar_version=:calendarVersion
            """,
            params(
                "tenantId", tenantId,
                "calendarId", calendarId,
                "calendarVersion", calendarVersion
            ),
            calendarVersionRowMapper()
        );
        return rows.stream().findFirst().map(row -> row.toVersion(loadCalendarSnapshot(row)));
    }
    public CalendarVersion publishCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion,
        String publishedBy,
        Instant publishedAt,
        long expectedCalendarVersion
    ) {
        return required(transactions.execute(status -> {
            CalendarIdentity identity = lockCalendar(tenantId, calendarId);
            CalendarVersion existing = findCalendarVersion(tenantId, calendarId, calendarVersion)
                .orElseThrow(() -> notFound("APPROVAL_CALENDAR_NOT_FOUND", "calendar version was not found"));
            if (existing.immutable()) {
                return existing;
            }
            if (identity.version() != expectedCalendarVersion) {
                throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar identity changed concurrently");
            }
            int changed = jdbc.update(
                """
                update ap_work_calendar_version
                set status='PUBLISHED',immutable=true,published_by=:publishedBy,
                    published_at=:publishedAt,updated_at=:publishedAt
                where tenant_id=:tenantId and calendar_id=:calendarId
                  and calendar_version=:calendarVersion and status='DRAFT' and immutable=false
                """,
                params(
                    "tenantId", tenantId,
                    "calendarId", calendarId,
                    "calendarVersion", calendarVersion,
                    "publishedBy", publishedBy,
                    "publishedAt", offset(publishedAt)
                )
            );
            if (changed != 1) {
                throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar version cannot be published");
            }
            updateCalendarVersion(identity, publishedAt);
            return findCalendarVersion(tenantId, calendarId, calendarVersion).orElseThrow();
        }));
    }
    public CalendarIdentity activateCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion,
        String activatedBy,
        Instant activatedAt,
        long expectedCalendarVersion
    ) {
        return required(transactions.execute(status -> {
            advisoryLock("calendar-active:" + tenantId + ':' + calendarId);
            CalendarIdentity identity = lockCalendar(tenantId, calendarId);
            if (Objects.equals(identity.activeVersion(), calendarVersion)
                && identity.status() == CalendarStatus.ACTIVE) {
                return identity;
            }
            if (identity.version() != expectedCalendarVersion) {
                throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar identity changed concurrently");
            }
            CalendarVersion version = findCalendarVersion(tenantId, calendarId, calendarVersion)
                .orElseThrow(() -> notFound("APPROVAL_CALENDAR_NOT_FOUND", "calendar version was not found"));
            if (!version.immutable()) {
                throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar version must be published");
            }
            jdbc.update(
                """
                update ap_work_calendar_version set status='INACTIVE',updated_at=:activatedAt
                where tenant_id=:tenantId and calendar_id=:calendarId and status='ACTIVE'
                """,
                params("tenantId", tenantId, "calendarId", calendarId, "activatedAt", offset(activatedAt))
            );
            int versionChanged = jdbc.update(
                """
                update ap_work_calendar_version set status='ACTIVE',updated_at=:activatedAt
                where tenant_id=:tenantId and calendar_id=:calendarId and calendar_version=:calendarVersion
                  and immutable=true and status in ('PUBLISHED','INACTIVE','ACTIVE')
                """,
                params(
                    "tenantId", tenantId,
                    "calendarId", calendarId,
                    "calendarVersion", calendarVersion,
                    "activatedAt", offset(activatedAt)
                )
            );
            if (versionChanged != 1) {
                throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar version cannot be activated");
            }
            int changed = jdbc.update(
                """
                update ap_work_calendar
                set status='ACTIVE',active_version=:calendarVersion,updated_at=:activatedAt,version=version+1
                where tenant_id=:tenantId and calendar_id=:calendarId and version=:expectedVersion
                """,
                params(
                    "tenantId", tenantId,
                    "calendarId", calendarId,
                    "calendarVersion", calendarVersion,
                    "activatedAt", offset(activatedAt),
                    "expectedVersion", expectedCalendarVersion
                )
            );
            if (changed != 1) {
                throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar activation changed concurrently");
            }
            return findCalendar(tenantId, calendarId).orElseThrow();
        }));
    }
    public CalendarPage findCalendars(String tenantId, int limit, int offsetValue) {
        page(limit, offsetValue);
        long total = count(
            "select count(*) from ap_work_calendar where tenant_id=:tenantId",
            params("tenantId", tenantId)
        );
        List<CalendarIdentity> items = jdbc.query(
            """
            select * from ap_work_calendar where tenant_id=:tenantId
            order by updated_at desc,calendar_id limit :limit offset :offset
            """,
            params("tenantId", tenantId, "limit", limit, "offset", offsetValue),
            calendarIdentityMapper()
        );
        return new CalendarPage(items, total, limit, offsetValue);
    }
}

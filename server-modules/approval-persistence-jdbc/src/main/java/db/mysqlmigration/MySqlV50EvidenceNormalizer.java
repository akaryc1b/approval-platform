package db.mysqlmigration;

/** Restores governed comment, audit and migration-evidence invariants. */
final class MySqlV50EvidenceNormalizer {

    private MySqlV50EvidenceNormalizer() {
    }

    static String normalize(String command) {
        String normalized = normalizeCommentLifecycle(command);
        normalized = normalizeAuditIntegrity(normalized);
        return normalizeMigrationExecutionNullability(normalized);
    }

    private static String normalizeCommentLifecycle(String command) {
        if (!command.stripLeading().startsWith(
            "alter table ap_approval_comment\n    add column status"
        )) {
            return command;
        }
        String normalized = replace(
            command,
            "add column updated_at datetime(6),",
            "add column updated_at datetime(6) not null,",
            "comment updated-at nullability"
        );
        return replace(
            normalized,
            "add column version bigint not null default 1",
            """
            add column version bigint not null default 1,
                add constraint uk_approval_comment_tenant_comment
                    unique (tenant_id, comment_id),
                add constraint chk_approval_comment_status
                    check (status in ('ACTIVE', 'DELETED')),
                add constraint chk_approval_comment_visibility
                    check (visibility in ('PARTICIPANTS', 'MENTIONED_ONLY')),
                add constraint chk_approval_comment_revision
                    check (current_revision > 0),
                add constraint chk_approval_comment_version
                    check (version > 0),
                add constraint chk_approval_comment_deleted_metadata
                    check (
                        (
                            status = 'ACTIVE'
                            and deleted_at is null
                            and deleted_by is null
                            and delete_reason is null
                        )
                        or (
                            status = 'DELETED'
                            and deleted_at is not null
                            and deleted_by is not null
                            and delete_reason is not null
                        )
                    )
            """.strip(),
            "comment lifecycle constraints"
        );
    }

    private static String normalizeAuditIntegrity(String command) {
        if (!command.stripLeading().startsWith(
            "alter table ap_audit_event\n    add column schema_name"
        )) {
            return command;
        }
        String normalized = replace(
            command,
            "add column schema_name varchar(128),",
            "add column schema_name varchar(128) not null,",
            "audit schema name nullability"
        );
        normalized = replace(
            normalized,
            "add column schema_version int,",
            "add column schema_version int not null,",
            "audit schema version nullability"
        );
        normalized = replace(
            normalized,
            "add column tenant_sequence bigint,",
            "add column tenant_sequence bigint not null,",
            "audit sequence nullability"
        );
        normalized = replace(
            normalized,
            "add column previous_hash varchar(64),",
            "add column previous_hash varchar(64) not null,",
            "audit previous hash nullability"
        );
        normalized = replace(
            normalized,
            "add column payload_hash varchar(64),",
            "add column payload_hash varchar(64) not null,",
            "audit payload hash nullability"
        );
        return replace(
            normalized,
            "add column current_hash varchar(64)",
            """
            add column current_hash varchar(64) not null,
                add constraint chk_audit_event_schema_version
                    check (schema_version >= 0),
                add constraint chk_audit_event_tenant_sequence
                    check (tenant_sequence > 0),
                add constraint chk_audit_event_previous_hash
                    check (regexp_like(previous_hash, '^[0-9a-f]{64}$', 'c')),
                add constraint chk_audit_event_payload_hash
                    check (regexp_like(payload_hash, '^[0-9a-f]{64}$', 'c')),
                add constraint chk_audit_event_current_hash
                    check (regexp_like(current_hash, '^[0-9a-f]{64}$', 'c')),
                add constraint uk_audit_event_tenant_sequence
                    unique (tenant_id, tenant_sequence),
                add constraint uk_audit_event_tenant_hash
                    unique (tenant_id, current_hash)
            """.strip(),
            "audit integrity constraints"
        );
    }

    private static String normalizeMigrationExecutionNullability(String command) {
        if (command.stripLeading().startsWith(
            "alter table ap_process_migration_attempt\n add column lease_actor"
        )) {
            return replace(
                command,
                "add column failure_class varchar(32),",
                "add column failure_class varchar(32) not null,",
                "migration attempt failure class nullability"
            );
        }
        if (command.stripLeading().startsWith(
            "alter table ap_process_migration_attempt_event\n add column engine_outcome"
        )) {
            String normalized = replace(
                command,
                "add column engine_outcome varchar(32),",
                "add column engine_outcome varchar(32) not null,",
                "migration event outcome nullability"
            );
            return replace(
                normalized,
                "add column failure_class varchar(32),",
                "add column failure_class varchar(32) not null,",
                "migration event failure class nullability"
            );
        }
        return command;
    }

    private static String replace(
        String command,
        String expected,
        String replacement,
        String boundary
    ) {
        return MySqlV50Normalizer.requireReplace(
            command,
            expected,
            replacement,
            boundary
        );
    }
}

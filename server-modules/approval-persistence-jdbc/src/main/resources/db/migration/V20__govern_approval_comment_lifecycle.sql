alter table ap_approval_comment
    add column status varchar(32) not null default 'ACTIVE',
    add column visibility varchar(32) not null default 'PARTICIPANTS',
    add column current_revision integer not null default 1,
    add column updated_at timestamptz,
    add column deleted_at timestamptz,
    add column deleted_by varchar(256),
    add column delete_reason varchar(2000),
    add column version bigint not null default 1;

update ap_approval_comment
set updated_at = created_at
where updated_at is null;

alter table ap_approval_comment
    alter column updated_at set not null,
    add constraint uk_approval_comment_tenant_comment unique (tenant_id, comment_id),
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
        );

create table ap_approval_comment_revision (
    tenant_id varchar(128) not null,
    comment_id uuid not null,
    revision_number integer not null,
    revision_type varchar(32) not null,
    body varchar(4000) not null,
    mention_ids_json jsonb not null,
    attachment_ids_json jsonb not null,
    visibility varchar(32) not null,
    operator_id varchar(256) not null,
    reason varchar(2000),
    occurred_at timestamptz not null,
    primary key (tenant_id, comment_id, revision_number),
    constraint fk_approval_comment_revision_comment
        foreign key (tenant_id, comment_id)
        references ap_approval_comment (tenant_id, comment_id),
    constraint chk_approval_comment_revision_number
        check (revision_number > 0),
    constraint chk_approval_comment_revision_type
        check (revision_type in ('CREATE', 'EDIT', 'DELETE')),
    constraint chk_approval_comment_revision_visibility
        check (visibility in ('PARTICIPANTS', 'MENTIONED_ONLY')),
    constraint chk_approval_comment_revision_reason
        check (revision_type <> 'DELETE' or reason is not null)
);

insert into ap_approval_comment_revision (
    tenant_id,
    comment_id,
    revision_number,
    revision_type,
    body,
    mention_ids_json,
    attachment_ids_json,
    visibility,
    operator_id,
    reason,
    occurred_at
)
select
    comment.tenant_id,
    comment.comment_id,
    1,
    'CREATE',
    comment.body,
    comment.mention_ids_json,
    comment.attachment_ids_json,
    'PARTICIPANTS',
    comment.author_id,
    'Migrated legacy comment evidence',
    comment.created_at
from ap_approval_comment comment;

create index idx_approval_comment_visibility
    on ap_approval_comment (tenant_id, instance_id, visibility, created_at, comment_id);

create index idx_approval_comment_revision_history
    on ap_approval_comment_revision (tenant_id, comment_id, revision_number);

create index idx_approval_comment_revision_occurred
    on ap_approval_comment_revision (tenant_id, occurred_at desc, comment_id);

create index idx_approval_comment_mentions_gin
    on ap_approval_comment using gin (mention_ids_json);

create index idx_approval_comment_attachments_gin
    on ap_approval_comment using gin (attachment_ids_json);

create index idx_approval_comment_revision_attachments_gin
    on ap_approval_comment_revision using gin (attachment_ids_json);

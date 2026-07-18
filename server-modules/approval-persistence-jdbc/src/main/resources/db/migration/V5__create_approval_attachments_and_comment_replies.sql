create table if not exists ap_approval_attachment (
    attachment_id uuid primary key,
    tenant_id varchar(128) not null,
    uploader_id varchar(256) not null,
    instance_id uuid references ap_approval_instance(instance_id),
    file_name varchar(255) not null,
    content_type varchar(160) not null,
    size_bytes bigint not null,
    sha256 char(64) not null,
    content bytea not null,
    created_at timestamptz not null,
    bound_at timestamptz,
    constraint ap_approval_attachment_size_check
        check (size_bytes > 0 and size_bytes <= 10485760),
    constraint ap_approval_attachment_binding_check
        check (
            (instance_id is null and bound_at is null)
            or (instance_id is not null and bound_at is not null)
        )
);

create index if not exists ap_approval_attachment_uploader_idx
    on ap_approval_attachment (tenant_id, uploader_id, created_at desc);
create index if not exists ap_approval_attachment_instance_idx
    on ap_approval_attachment (tenant_id, instance_id, created_at);
create index if not exists ap_approval_attachment_hash_idx
    on ap_approval_attachment (tenant_id, sha256, size_bytes);

alter table ap_approval_comment
    add column if not exists parent_comment_id uuid;

alter table ap_approval_comment
    drop constraint if exists ap_approval_comment_parent_fk;

alter table ap_approval_comment
    add constraint ap_approval_comment_parent_fk
        foreign key (parent_comment_id)
        references ap_approval_comment(comment_id);

create index if not exists ap_approval_comment_parent_idx
    on ap_approval_comment (tenant_id, instance_id, parent_comment_id, created_at, comment_id);

alter table ap_approval_message
    drop constraint if exists ap_approval_message_type_check;

alter table ap_approval_message
    add constraint ap_approval_message_type_check
        check (message_type in ('URGE', 'COPY', 'MENTION'));

create table if not exists ap_approval_comment (
    comment_id uuid primary key,
    tenant_id varchar(128) not null,
    instance_id uuid not null references ap_approval_instance(instance_id),
    author_id varchar(256) not null,
    body varchar(4000) not null,
    mention_ids_json jsonb not null,
    attachment_ids_json jsonb not null,
    created_at timestamptz not null
);

create index if not exists ap_approval_comment_instance_idx
    on ap_approval_comment (tenant_id, instance_id, created_at, comment_id);
create index if not exists ap_approval_comment_author_idx
    on ap_approval_comment (tenant_id, author_id, created_at desc);

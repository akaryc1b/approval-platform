create table if not exists ap_approval_message (
    message_id uuid primary key,
    tenant_id varchar(128) not null,
    recipient_id varchar(256) not null,
    sender_id varchar(256) not null,
    instance_id uuid not null references ap_approval_instance(instance_id),
    task_id uuid references ap_approval_task(task_id),
    message_type varchar(24) not null,
    title varchar(256) not null,
    body varchar(2000) not null,
    metadata_json jsonb not null,
    dedup_key varchar(512) not null,
    created_at timestamptz not null,
    read_at timestamptz,
    unique (tenant_id, dedup_key),
    constraint ap_approval_message_type_check
        check (message_type in ('URGE', 'COPY'))
);

create index if not exists ap_approval_message_recipient_idx
    on ap_approval_message (tenant_id, recipient_id, read_at, created_at desc);
create index if not exists ap_approval_message_instance_idx
    on ap_approval_message (tenant_id, instance_id, created_at desc);
create index if not exists ap_approval_message_sender_idx
    on ap_approval_message (tenant_id, sender_id, instance_id, created_at desc);

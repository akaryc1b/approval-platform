create table if not exists ap_outbox (
    id uuid primary key,
    tenant_id varchar(128) not null,
    connector_key varchar(128) not null,
    request_id varchar(128) not null,
    trace_id varchar(128),
    event_id uuid not null,
    event_type varchar(128) not null,
    aggregate_type varchar(128) not null,
    aggregate_id varchar(256) not null,
    occurred_at timestamptz not null,
    idempotency_key varchar(256) not null,
    payload_json jsonb not null,
    status varchar(32) not null,
    attempts integer not null default 0,
    available_at timestamptz not null,
    locked_by varchar(128),
    locked_until timestamptz,
    provider_request_id varchar(256),
    response_code integer,
    last_error text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    delivered_at timestamptz,
    dead_at timestamptz,
    constraint ap_outbox_status_check check (
        status in ('PENDING', 'IN_FLIGHT', 'DELIVERED', 'DEAD')
    ),
    constraint ap_outbox_attempts_check check (attempts >= 0),
    constraint ap_outbox_idempotency_unique unique (
        tenant_id,
        connector_key,
        idempotency_key
    )
);

create index if not exists ap_outbox_due_idx
    on ap_outbox (available_at, created_at)
    where status = 'PENDING';

create index if not exists ap_outbox_lock_idx
    on ap_outbox (locked_until)
    where status = 'IN_FLIGHT';

create table if not exists ap_inbox (
    tenant_id varchar(128) not null,
    consumer_key varchar(128) not null,
    message_id varchar(256) not null,
    payload_hash varchar(128) not null,
    status varchar(32) not null,
    attempts integer not null default 0,
    locked_by varchar(128),
    locked_until timestamptz,
    received_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    last_error text,
    primary key (tenant_id, consumer_key, message_id),
    constraint ap_inbox_status_check check (
        status in ('PROCESSING', 'COMPLETED', 'FAILED')
    ),
    constraint ap_inbox_attempts_check check (attempts >= 0)
);

create index if not exists ap_inbox_lock_idx
    on ap_inbox (locked_until)
    where status = 'PROCESSING';

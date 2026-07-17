create table if not exists ap_command_idempotency (
    tenant_id varchar(128) not null,
    operation varchar(160) not null,
    idempotency_key varchar(256) not null,
    request_hash char(64) not null,
    request_id varchar(256) not null,
    trace_id varchar(256),
    result_type varchar(512),
    result_json jsonb,
    status varchar(24) not null,
    created_at timestamptz not null,
    completed_at timestamptz,
    primary key (tenant_id, operation, idempotency_key),
    constraint ap_command_idempotency_status_check
        check (status in ('IN_PROGRESS', 'COMPLETED'))
);

create table if not exists ap_definition_version (
    tenant_id varchar(128) not null,
    definition_key varchar(160) not null,
    definition_version integer not null,
    form_key varchar(160) not null,
    form_version integer not null,
    compiler_version varchar(64) not null,
    content_hash char(64) not null,
    deployment_id varchar(128) not null,
    engine_definition_id varchar(256) not null,
    engine_version integer not null,
    published_by varchar(256) not null,
    published_at timestamptz not null,
    primary key (tenant_id, definition_key, definition_version),
    unique (tenant_id, engine_definition_id)
);

create table if not exists ap_approval_instance (
    instance_id uuid primary key,
    tenant_id varchar(128) not null,
    business_key varchar(256) not null,
    engine_instance_id varchar(128) not null,
    definition_key varchar(160) not null,
    definition_version integer not null,
    form_key varchar(160) not null,
    form_version integer not null,
    compiler_version varchar(64) not null,
    content_hash char(64) not null,
    initiator_id varchar(256) not null,
    amount numeric(24, 6) not null,
    supplier varchar(200) not null,
    purchase_order_reference varchar(100) not null,
    attachment_ids_json jsonb not null,
    assignee_snapshot_json jsonb not null,
    request_hash char(64) not null,
    status varchar(24) not null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (tenant_id, business_key),
    unique (tenant_id, engine_instance_id),
    constraint ap_approval_instance_status_check
        check (status in ('RUNNING', 'COMPLETED', 'REJECTED', 'WITHDRAWN')),
    constraint ap_approval_instance_definition_fk
        foreign key (tenant_id, definition_key, definition_version)
        references ap_definition_version (tenant_id, definition_key, definition_version)
);

create table if not exists ap_approval_task (
    task_id uuid primary key,
    instance_id uuid not null references ap_approval_instance(instance_id),
    tenant_id varchar(128) not null,
    engine_task_id varchar(128) not null,
    task_definition_key varchar(160) not null,
    task_name varchar(256) not null,
    assignee_id varchar(256) not null,
    status varchar(24) not null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    unique (tenant_id, engine_task_id),
    constraint ap_approval_task_status_check
        check (status in ('PENDING', 'COMPLETING', 'COMPLETED', 'CANCELED'))
);

create index if not exists ap_approval_task_assignee_idx
    on ap_approval_task (tenant_id, assignee_id, status, created_at);
create index if not exists ap_approval_task_instance_idx
    on ap_approval_task (tenant_id, instance_id, status, created_at);

create table if not exists ap_audit_event (
    event_id uuid primary key,
    tenant_id varchar(128) not null,
    operator_id varchar(256) not null,
    action varchar(160) not null,
    aggregate_type varchar(160) not null,
    aggregate_id varchar(256) not null,
    request_id varchar(256) not null,
    trace_id varchar(256),
    occurred_at timestamptz not null,
    attributes_json jsonb not null
);

create index if not exists ap_audit_event_aggregate_idx
    on ap_audit_event (tenant_id, aggregate_type, aggregate_id, occurred_at);
create index if not exists ap_audit_event_request_idx
    on ap_audit_event (tenant_id, request_id);

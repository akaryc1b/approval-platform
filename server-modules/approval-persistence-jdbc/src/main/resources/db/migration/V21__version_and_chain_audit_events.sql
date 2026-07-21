create extension if not exists pgcrypto;

alter table ap_audit_event
    add column schema_name varchar(128),
    add column schema_version integer,
    add column tenant_sequence bigint,
    add column previous_hash varchar(64),
    add column payload_hash varchar(64),
    add column current_hash varchar(64);

update ap_audit_event
set schema_name = case
        when action like 'AUDIT\_%' escape '\' then 'approval.audit.operation'
        when action like '%DELEGATION%' then 'approval.delegation'
        when action like '%HANDOVER%' then 'approval.employee-handover'
        when action like '%COLLABORATION%'
          or action like '%ADD_SIGN%'
          or action like '%REMOVE_SIGN%'
          or action like '%VOTE%'
          or action like '%WEIGHT%'
            then 'approval.collaboration'
        when action like '%NOTIFICATION%'
          or action like '%DELIVERY%'
          or action like '%DEAD_LETTER%'
            then 'approval.notification'
        when action like '%COMMENT%' then 'approval.comment'
        when action like 'TASK\_%' escape '\' then 'approval.task-lifecycle'
        when action like 'INSTANCE\_%' escape '\'
          or action like 'PROCESS\_%' escape '\'
            then 'approval.process-lifecycle'
        else 'approval.generic'
    end,
    schema_version = 0;

create or replace function ap_audit_payload_hash(
    p_event_id uuid,
    p_tenant_id varchar,
    p_operator_id varchar,
    p_action varchar,
    p_aggregate_type varchar,
    p_aggregate_id varchar,
    p_schema_name varchar,
    p_schema_version integer,
    p_request_id varchar,
    p_trace_id varchar,
    p_occurred_at timestamptz,
    p_attributes jsonb
) returns varchar
language sql
immutable
as $$
    select encode(
        digest(
            convert_to(
                jsonb_build_object(
                    'eventId', p_event_id::text,
                    'tenantId', p_tenant_id,
                    'operatorId', p_operator_id,
                    'action', p_action,
                    'aggregateType', p_aggregate_type,
                    'aggregateId', p_aggregate_id,
                    'schemaName', p_schema_name,
                    'schemaVersion', p_schema_version,
                    'requestId', p_request_id,
                    'traceId', p_trace_id,
                    'occurredAt', to_char(
                        p_occurred_at at time zone 'UTC',
                        'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
                    ),
                    'attributes', coalesce(p_attributes, '{}'::jsonb)
                )::text,
                'UTF8'
            ),
            'sha256'
        ),
        'hex'
    );
$$;

create or replace function ap_audit_chain_hash(
    p_previous_hash varchar,
    p_payload_hash varchar
) returns varchar
language sql
immutable
strict
as $$
    select encode(
        digest(
            convert_to(p_previous_hash || ':' || p_payload_hash, 'UTF8'),
            'sha256'
        ),
        'hex'
    );
$$;

create table ap_audit_chain_state (
    tenant_id varchar(128) primary key,
    last_sequence bigint not null,
    last_hash varchar(64) not null,
    updated_at timestamptz not null,
    constraint chk_audit_chain_state_sequence check (last_sequence >= 0),
    constraint chk_audit_chain_state_hash check (last_hash ~ '^[0-9a-f]{64}$')
);

do $$
declare
    tenant_record record;
    event_record record;
    next_sequence bigint;
    previous_hash_value varchar(64);
    payload_hash_value varchar(64);
    current_hash_value varchar(64);
begin
    for tenant_record in
        select distinct tenant_id
        from ap_audit_event
        order by tenant_id
    loop
        next_sequence := 0;
        previous_hash_value := repeat('0', 64);
        for event_record in
            select
                event_id,
                tenant_id,
                operator_id,
                action,
                aggregate_type,
                aggregate_id,
                schema_name,
                schema_version,
                request_id,
                trace_id,
                occurred_at,
                attributes_json
            from ap_audit_event
            where tenant_id = tenant_record.tenant_id
            order by occurred_at, event_id
        loop
            next_sequence := next_sequence + 1;
            payload_hash_value := ap_audit_payload_hash(
                event_record.event_id,
                event_record.tenant_id,
                event_record.operator_id,
                event_record.action,
                event_record.aggregate_type,
                event_record.aggregate_id,
                event_record.schema_name,
                event_record.schema_version,
                event_record.request_id,
                event_record.trace_id,
                event_record.occurred_at,
                event_record.attributes_json
            );
            current_hash_value := ap_audit_chain_hash(
                previous_hash_value,
                payload_hash_value
            );
            update ap_audit_event
            set tenant_sequence = next_sequence,
                previous_hash = previous_hash_value,
                payload_hash = payload_hash_value,
                current_hash = current_hash_value
            where event_id = event_record.event_id;
            previous_hash_value := current_hash_value;
        end loop;
        insert into ap_audit_chain_state (
            tenant_id,
            last_sequence,
            last_hash,
            updated_at
        ) values (
            tenant_record.tenant_id,
            next_sequence,
            previous_hash_value,
            now()
        );
    end loop;
end $$;

alter table ap_audit_event
    alter column schema_name set not null,
    alter column schema_version set not null,
    alter column tenant_sequence set not null,
    alter column previous_hash set not null,
    alter column payload_hash set not null,
    alter column current_hash set not null,
    add constraint chk_audit_event_schema_version check (schema_version >= 0),
    add constraint chk_audit_event_tenant_sequence check (tenant_sequence > 0),
    add constraint chk_audit_event_previous_hash check (previous_hash ~ '^[0-9a-f]{64}$'),
    add constraint chk_audit_event_payload_hash check (payload_hash ~ '^[0-9a-f]{64}$'),
    add constraint chk_audit_event_current_hash check (current_hash ~ '^[0-9a-f]{64}$'),
    add constraint uk_audit_event_tenant_sequence unique (tenant_id, tenant_sequence),
    add constraint uk_audit_event_tenant_hash unique (tenant_id, current_hash);

create index idx_audit_event_tenant_time_action
    on ap_audit_event (tenant_id, occurred_at desc, action, tenant_sequence desc);

create index idx_audit_event_tenant_operator_time
    on ap_audit_event (tenant_id, operator_id, occurred_at desc, tenant_sequence desc);

create index idx_audit_event_tenant_aggregate
    on ap_audit_event (
        tenant_id,
        aggregate_type,
        aggregate_id,
        occurred_at desc,
        tenant_sequence desc
    );

create index idx_audit_event_tenant_request
    on ap_audit_event (tenant_id, request_id, tenant_sequence desc);

create index idx_audit_event_tenant_trace
    on ap_audit_event (tenant_id, trace_id, tenant_sequence desc)
    where trace_id is not null;

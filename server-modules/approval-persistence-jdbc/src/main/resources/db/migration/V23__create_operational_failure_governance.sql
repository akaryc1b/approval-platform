alter table ap_outbox
    add column replay_count integer not null default 0,
    add column last_replayed_at timestamptz,
    add column last_replayed_by varchar(256),
    add column last_replay_request_id varchar(512),
    add constraint chk_outbox_replay_count check (replay_count >= 0),
    add constraint chk_outbox_replay_metadata check (
        (
            replay_count = 0
            and last_replayed_at is null
            and last_replayed_by is null
            and last_replay_request_id is null
        )
        or (
            replay_count > 0
            and last_replayed_at is not null
            and last_replayed_by is not null
            and last_replay_request_id is not null
        )
    );

create table ap_outbox_delivery_attempt (
    attempt_id uuid primary key,
    tenant_id varchar(128) not null,
    outbox_id uuid not null,
    attempt_number integer not null,
    connector_key varchar(128) not null,
    worker_id varchar(256) not null,
    started_at timestamptz not null,
    completed_at timestamptz not null,
    successful boolean not null,
    retryable boolean not null,
    provider_request_id varchar(512),
    response_code integer,
    error_code varchar(128),
    error_message varchar(2000),
    next_attempt_at timestamptz,
    constraint fk_outbox_delivery_attempt_message
        foreign key (outbox_id) references ap_outbox (id) on delete cascade,
    constraint uk_outbox_delivery_attempt_number
        unique (tenant_id, outbox_id, attempt_number),
    constraint chk_outbox_delivery_attempt_number check (attempt_number > 0),
    constraint chk_outbox_delivery_attempt_times check (completed_at >= started_at),
    constraint chk_outbox_delivery_attempt_result check (
        (
            successful
            and not retryable
            and error_code is null
            and error_message is null
            and next_attempt_at is null
        )
        or (
            not successful
            and error_code is not null
            and error_message is not null
        )
    )
);

create index idx_outbox_failure_queue
    on ap_outbox (tenant_id, status, updated_at desc, id)
    where status = 'DEAD';

create index idx_outbox_connector_failure
    on ap_outbox (tenant_id, connector_key, status, updated_at desc)
    where status in ('PENDING', 'IN_FLIGHT', 'DEAD');

create index idx_outbox_attempt_history
    on ap_outbox_delivery_attempt (
        tenant_id,
        outbox_id,
        attempt_number,
        started_at
    );

create or replace function ap_record_outbox_delivery_attempt()
returns trigger
language plpgsql
as $$
declare
    v_attempt_number integer;
    v_successful boolean;
    v_retryable boolean;
    v_error_code varchar(128);
    v_error_message varchar(2000);
begin
    if old.status <> 'IN_FLIGHT'
       or new.status not in ('PENDING', 'DELIVERED', 'DEAD') then
        return new;
    end if;

    v_attempt_number := greatest(new.attempts, old.attempts + 1);
    v_successful := new.status = 'DELIVERED';
    v_retryable := new.status = 'PENDING';

    if not v_successful then
        v_error_code := case
            when new.status = 'DEAD' then 'OUTBOX_DEAD'
            else 'OUTBOX_RETRY'
        end;
        v_error_message := coalesce(new.last_error, 'Outbox delivery failed');
    end if;

    insert into ap_outbox_delivery_attempt (
        attempt_id,
        tenant_id,
        outbox_id,
        attempt_number,
        connector_key,
        worker_id,
        started_at,
        completed_at,
        successful,
        retryable,
        provider_request_id,
        response_code,
        error_code,
        error_message,
        next_attempt_at
    ) values (
        gen_random_uuid(),
        new.tenant_id,
        new.id,
        v_attempt_number,
        new.connector_key,
        old.locked_by,
        old.updated_at,
        new.updated_at,
        v_successful,
        v_retryable,
        new.provider_request_id,
        new.response_code,
        v_error_code,
        v_error_message,
        case when v_retryable then new.available_at else null end
    );

    return new;
end;
$$;

create trigger trg_record_outbox_delivery_attempt
after update on ap_outbox
for each row
execute function ap_record_outbox_delivery_attempt();

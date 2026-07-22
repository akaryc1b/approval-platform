create table ap_sla_execution_intent (
    intent_id uuid not null,
    tenant_id varchar(128) not null,
    sla_instance_id uuid not null,
    approval_instance_id uuid not null,
    task_id uuid,
    collaboration_participant_id uuid,
    policy_id uuid not null,
    policy_version integer not null,
    calendar_id uuid,
    calendar_version integer,
    source_intent_id uuid,
    action_type varchar(32) not null,
    action_sequence integer not null,
    scheduled_at timestamptz not null,
    available_at timestamptz not null,
    status varchar(32) not null,
    lease_owner varchar(200),
    lease_until timestamptz,
    attempt_count integer not null,
    max_attempts integer not null,
    next_attempt_at timestamptz not null,
    idempotency_key varchar(200) not null,
    payload_json jsonb not null,
    responsible_user_id varchar(200) not null,
    request_id varchar(128) not null,
    trace_id varchar(128),
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    dead_at timestamptz,
    cancelled_at timestamptz,
    last_error_code varchar(128),
    last_error_summary varchar(1000),
    primary key (tenant_id, intent_id),
    constraint fk_sla_execution_intent_instance
        foreign key (tenant_id, sla_instance_id)
        references ap_sla_instance (tenant_id, sla_instance_id),
    constraint fk_sla_execution_intent_approval_instance
        foreign key (tenant_id, approval_instance_id)
        references ap_approval_instance (tenant_id, instance_id),
    constraint fk_sla_execution_intent_task
        foreign key (tenant_id, task_id)
        references ap_approval_task (tenant_id, task_id),
    constraint fk_sla_execution_intent_collaboration_participant
        foreign key (tenant_id, collaboration_participant_id)
        references ap_task_collaboration_participant (tenant_id, participant_id),
    constraint fk_sla_execution_intent_policy_version
        foreign key (tenant_id, policy_id, policy_version)
        references ap_sla_policy_version (tenant_id, policy_id, policy_version),
    constraint fk_sla_execution_intent_calendar_version
        foreign key (tenant_id, calendar_id, calendar_version)
        references ap_work_calendar_version (tenant_id, calendar_id, calendar_version),
    constraint fk_sla_execution_intent_source
        foreign key (tenant_id, source_intent_id)
        references ap_sla_execution_intent (tenant_id, intent_id),
    constraint chk_sla_execution_intent_action
        check (action_type in ('REMINDER', 'OVERDUE', 'ESCALATION', 'AUTOMATIC_ACTION')),
    constraint chk_sla_execution_intent_status
        check (status in ('READY', 'CLAIMED', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD', 'CANCELLED')),
    constraint chk_sla_execution_intent_sequence
        check (action_sequence > 0),
    constraint chk_sla_execution_intent_attempts
        check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts),
    constraint chk_sla_execution_intent_schedule
        check (available_at >= scheduled_at and next_attempt_at >= available_at),
    constraint chk_sla_execution_intent_calendar_binding
        check (
            (calendar_id is null and calendar_version is null)
            or (calendar_id is not null and calendar_version is not null)
        ),
    constraint chk_sla_execution_intent_target_identity
        check (
            (task_id is null and collaboration_participant_id is null)
            or (task_id is not null and collaboration_participant_id is null)
            or (task_id is not null and collaboration_participant_id is not null)
        ),
    constraint chk_sla_execution_intent_lease
        check (
            (status = 'CLAIMED' and lease_owner is not null and lease_until is not null)
            or (status <> 'CLAIMED' and lease_owner is null and lease_until is null)
        ),
    constraint chk_sla_execution_intent_terminal_evidence
        check (
            (status = 'SUCCEEDED' and completed_at is not null and dead_at is null and cancelled_at is null)
            or (status = 'DEAD' and completed_at is null and dead_at is not null and cancelled_at is null)
            or (status = 'CANCELLED' and completed_at is null and dead_at is null and cancelled_at is not null)
            or (status in ('READY', 'CLAIMED', 'RETRY_WAIT')
                and completed_at is null and dead_at is null and cancelled_at is null)
        ),
    constraint chk_sla_execution_intent_text
        check (
            idempotency_key <> '' and responsible_user_id <> '' and request_id <> ''
        ),
    constraint chk_sla_execution_intent_payload
        check (jsonb_typeof(payload_json) = 'object'),
    constraint chk_sla_execution_intent_version
        check (version > 0 and updated_at >= created_at)
);

create unique index uk_sla_execution_intent_idempotency
    on ap_sla_execution_intent (tenant_id, idempotency_key);

create unique index uk_sla_execution_intent_action_sequence
    on ap_sla_execution_intent (tenant_id, sla_instance_id, action_type, action_sequence);

create index idx_sla_execution_intent_ready_poll
    on ap_sla_execution_intent (
        tenant_id,
        next_attempt_at,
        scheduled_at,
        intent_id
    ) where status in ('READY', 'RETRY_WAIT');

create index idx_sla_execution_intent_expired_lease
    on ap_sla_execution_intent (tenant_id, lease_until, intent_id)
    where status = 'CLAIMED';

create index idx_sla_execution_intent_dead_management
    on ap_sla_execution_intent (tenant_id, dead_at desc, intent_id)
    where status = 'DEAD';

create index idx_sla_execution_intent_sla_history
    on ap_sla_execution_intent (tenant_id, sla_instance_id, created_at, intent_id);

create index idx_sla_execution_intent_request
    on ap_sla_execution_intent (tenant_id, request_id, created_at desc, intent_id);

create index idx_sla_execution_intent_management
    on ap_sla_execution_intent (
        tenant_id,
        status,
        action_type,
        scheduled_at,
        intent_id
    );

create index idx_sla_execution_intent_responsible_active
    on ap_sla_execution_intent (
        tenant_id,
        responsible_user_id,
        scheduled_at,
        intent_id
    ) where status in ('READY', 'RETRY_WAIT', 'CLAIMED');

create table ap_sla_execution_attempt (
    attempt_id uuid not null,
    tenant_id varchar(128) not null,
    intent_id uuid not null,
    attempt_number integer not null,
    worker_id varchar(200) not null,
    claimed_at timestamptz not null,
    started_at timestamptz not null,
    finished_at timestamptz not null,
    result varchar(32) not null,
    error_code varchar(128),
    error_summary varchar(1000),
    request_id varchar(128) not null,
    trace_id varchar(128),
    primary key (tenant_id, attempt_id),
    constraint fk_sla_execution_attempt_intent
        foreign key (tenant_id, intent_id)
        references ap_sla_execution_intent (tenant_id, intent_id),
    constraint uk_sla_execution_attempt_number
        unique (tenant_id, intent_id, attempt_number),
    constraint chk_sla_execution_attempt_number check (attempt_number > 0),
    constraint chk_sla_execution_attempt_result
        check (result in ('SUCCEEDED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')),
    constraint chk_sla_execution_attempt_time
        check (started_at >= claimed_at and finished_at >= started_at),
    constraint chk_sla_execution_attempt_text
        check (worker_id <> '' and request_id <> ''),
    constraint chk_sla_execution_attempt_error
        check (
            (result = 'SUCCEEDED' and error_code is null and error_summary is null)
            or (result <> 'SUCCEEDED' and error_code is not null and error_summary is not null)
        )
);

create index idx_sla_execution_attempt_history
    on ap_sla_execution_attempt (
        tenant_id,
        intent_id,
        attempt_number,
        started_at
    );

create index idx_sla_execution_attempt_request
    on ap_sla_execution_attempt (
        tenant_id,
        request_id,
        started_at desc,
        attempt_id
    );

create table ap_sla_execution_replay (
    replay_id uuid not null,
    tenant_id varchar(128) not null,
    original_intent_id uuid not null,
    new_intent_id uuid not null,
    original_error_code varchar(128),
    original_error_summary varchar(1000),
    replay_reason varchar(512) not null,
    replay_idempotency_key varchar(200) not null,
    requested_by varchar(200) not null,
    requested_at timestamptz not null,
    audit_chain_reference varchar(256) not null,
    request_id varchar(128) not null,
    trace_id varchar(128),
    primary key (tenant_id, replay_id),
    constraint fk_sla_execution_replay_original
        foreign key (tenant_id, original_intent_id)
        references ap_sla_execution_intent (tenant_id, intent_id),
    constraint fk_sla_execution_replay_new
        foreign key (tenant_id, new_intent_id)
        references ap_sla_execution_intent (tenant_id, intent_id),
    constraint uk_sla_execution_replay_idempotency
        unique (tenant_id, replay_idempotency_key),
    constraint chk_sla_execution_replay_lineage
        check (original_intent_id <> new_intent_id),
    constraint chk_sla_execution_replay_text
        check (
            replay_reason <> '' and replay_idempotency_key <> ''
            and requested_by <> '' and audit_chain_reference <> '' and request_id <> ''
        )
);

create index idx_sla_execution_replay_original
    on ap_sla_execution_replay (
        tenant_id,
        original_intent_id,
        requested_at desc,
        replay_id
    );

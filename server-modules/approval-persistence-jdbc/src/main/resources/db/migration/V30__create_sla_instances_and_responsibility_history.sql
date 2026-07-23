create unique index if not exists uk_approval_instance_tenant_instance
    on ap_approval_instance (tenant_id, instance_id);

create unique index if not exists uk_approval_task_tenant_task
    on ap_approval_task (tenant_id, task_id);

create table ap_sla_instance (
    sla_instance_id uuid not null,
    tenant_id varchar(128) not null,
    approval_instance_id uuid not null,
    task_id uuid,
    collaboration_participant_id uuid,
    definition_key varchar(100) not null,
    task_definition_key varchar(100),
    target_type varchar(64) not null,
    policy_id uuid not null,
    policy_version integer not null,
    calendar_id uuid,
    calendar_version integer,
    time_zone varchar(100) not null,
    responsible_user_id varchar(200) not null,
    original_responsible_user_id varchar(200) not null,
    started_at timestamptz not null,
    due_at timestamptz not null,
    next_reminder_at timestamptz,
    overdue_at timestamptz not null,
    paused_at timestamptz,
    pause_reason varchar(512),
    accumulated_paused_millis bigint not null,
    terminal_at timestamptz,
    terminal_reason varchar(64),
    status varchar(32) not null,
    last_action_sequence bigint not null,
    request_id varchar(128) not null,
    trace_id varchar(128),
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (tenant_id, sla_instance_id),
    constraint fk_sla_instance_approval_instance
        foreign key (tenant_id, approval_instance_id)
        references ap_approval_instance (tenant_id, instance_id),
    constraint fk_sla_instance_task
        foreign key (tenant_id, task_id)
        references ap_approval_task (tenant_id, task_id),
    constraint fk_sla_instance_collaboration_participant
        foreign key (tenant_id, collaboration_participant_id)
        references ap_task_collaboration_participant (tenant_id, participant_id),
    constraint fk_sla_instance_policy_version
        foreign key (tenant_id, policy_id, policy_version)
        references ap_sla_policy_version (tenant_id, policy_id, policy_version),
    constraint fk_sla_instance_calendar_version
        foreign key (tenant_id, calendar_id, calendar_version)
        references ap_work_calendar_version (tenant_id, calendar_id, calendar_version),
    constraint chk_sla_instance_target_type
        check (target_type in ('PROCESS', 'TASK', 'COLLABORATION_PARTICIPANT')),
    constraint chk_sla_instance_target_identity
        check (
            (target_type = 'PROCESS' and task_id is null and collaboration_participant_id is null)
            or
            (target_type = 'TASK' and task_id is not null and collaboration_participant_id is null)
            or
            (target_type = 'COLLABORATION_PARTICIPANT'
                and task_id is not null and collaboration_participant_id is not null)
        ),
    constraint chk_sla_instance_policy_version check (policy_version > 0),
    constraint chk_sla_instance_calendar_binding
        check (
            (calendar_id is null and calendar_version is null)
            or (calendar_id is not null and calendar_version is not null)
        ),
    constraint chk_sla_instance_time_order
        check (due_at >= started_at and overdue_at >= due_at),
    constraint chk_sla_instance_pause_duration check (accumulated_paused_millis >= 0),
    constraint chk_sla_instance_status check (status in ('ACTIVE', 'PAUSED', 'TERMINAL')),
    constraint chk_sla_instance_pause_metadata
        check (
            (status = 'PAUSED' and paused_at is not null and pause_reason is not null)
            or (status <> 'PAUSED' and paused_at is null and pause_reason is null)
        ),
    constraint chk_sla_instance_terminal_reason
        check (
            terminal_reason is null
            or terminal_reason in (
                'TASK_COMPLETED', 'TASK_CANCELED', 'INSTANCE_COMPLETED',
                'INSTANCE_REJECTED', 'INSTANCE_WITHDRAWN',
                'COLLABORATION_DECIDED', 'COLLABORATION_REMOVED',
                'COLLABORATION_CANCELED'
            )
        ),
    constraint chk_sla_instance_terminal_metadata
        check (
            (status = 'TERMINAL' and terminal_at is not null and terminal_reason is not null)
            or (status <> 'TERMINAL' and terminal_at is null and terminal_reason is null)
        ),
    constraint chk_sla_instance_sequences check (last_action_sequence >= 0 and version > 0),
    constraint chk_sla_instance_timestamps check (updated_at >= created_at)
);

create unique index uk_sla_instance_process_target
    on ap_sla_instance (tenant_id, approval_instance_id)
    where target_type = 'PROCESS';

create unique index uk_sla_instance_task_target
    on ap_sla_instance (tenant_id, task_id)
    where target_type = 'TASK';

create unique index uk_sla_instance_collaboration_target
    on ap_sla_instance (tenant_id, collaboration_participant_id)
    where target_type = 'COLLABORATION_PARTICIPANT';

create index idx_sla_instance_responsible_active_due
    on ap_sla_instance (tenant_id, responsible_user_id, due_at, sla_instance_id)
    where status = 'ACTIVE';

create index idx_sla_instance_active_due
    on ap_sla_instance (tenant_id, due_at, sla_instance_id)
    where status = 'ACTIVE';

create index idx_sla_instance_approval_instance
    on ap_sla_instance (tenant_id, approval_instance_id, created_at, sla_instance_id);

create index idx_sla_instance_task
    on ap_sla_instance (tenant_id, task_id, created_at, sla_instance_id)
    where task_id is not null;

create index idx_sla_instance_request_id
    on ap_sla_instance (tenant_id, request_id, created_at desc, sla_instance_id);

create index idx_sla_instance_overdue
    on ap_sla_instance (tenant_id, overdue_at, sla_instance_id)
    where status = 'ACTIVE';

create table ap_sla_responsibility_change (
    responsibility_change_id uuid not null,
    tenant_id varchar(128) not null,
    sla_instance_id uuid not null,
    previous_responsible_user_id varchar(200) not null,
    new_responsible_user_id varchar(200) not null,
    source varchar(64) not null,
    reason varchar(512) not null,
    changed_by varchar(200) not null,
    changed_at timestamptz not null,
    request_id varchar(128) not null,
    trace_id varchar(128),
    primary key (tenant_id, responsibility_change_id),
    constraint fk_sla_responsibility_change_instance
        foreign key (tenant_id, sla_instance_id)
        references ap_sla_instance (tenant_id, sla_instance_id),
    constraint chk_sla_responsibility_change_source
        check (source in (
            'DELEGATION', 'HANDOVER', 'MANUAL_TRANSFER',
            'COLLABORATION_ADD', 'COLLABORATION_REMOVE', 'ADMIN_CORRECTION'
        )),
    constraint chk_sla_responsibility_change_users
        check (previous_responsible_user_id <> '' and new_responsible_user_id <> ''),
    constraint chk_sla_responsibility_change_request check (request_id <> '')
);

create index idx_sla_responsibility_history
    on ap_sla_responsibility_change (
        tenant_id,
        sla_instance_id,
        changed_at desc,
        responsibility_change_id
    );

create index idx_sla_responsibility_request
    on ap_sla_responsibility_change (
        tenant_id,
        request_id,
        changed_at desc,
        responsibility_change_id
    );

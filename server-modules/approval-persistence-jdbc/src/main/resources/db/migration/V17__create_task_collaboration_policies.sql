create table ap_task_collaboration_policy (
    policy_id uuid not null,
    tenant_id varchar(128) not null,
    task_id uuid not null,
    instance_id uuid not null,
    engine_task_id varchar(256) not null,
    engine_instance_id varchar(256) not null,
    definition_key varchar(256) not null,
    task_definition_key varchar(256) not null,
    task_name varchar(512) not null,
    owner_assignee_id varchar(256) not null,
    collaboration_mode varchar(32) not null,
    status varchar(32) not null,
    reason varchar(2000) not null,
    created_by varchar(256) not null,
    created_at timestamptz not null,
    terminal_by varchar(256),
    terminal_at timestamptz,
    terminal_reason varchar(2000),
    version bigint not null,
    primary key (tenant_id, policy_id),
    constraint fk_task_collaboration_task
        foreign key (tenant_id, task_id)
        references ap_approval_task (tenant_id, task_id),
    constraint chk_task_collaboration_mode
        check (collaboration_mode in ('ALL', 'ANY')),
    constraint chk_task_collaboration_status
        check (status in ('ACTIVE', 'SATISFIED', 'REJECTED', 'CANCELED')),
    constraint chk_task_collaboration_terminal_metadata
        check (
            (
                status = 'ACTIVE'
                and terminal_by is null
                and terminal_at is null
                and terminal_reason is null
            )
            or (
                status <> 'ACTIVE'
                and terminal_by is not null
                and terminal_at is not null
                and terminal_reason is not null
            )
        ),
    constraint chk_task_collaboration_version
        check (version > 0)
);

create unique index uk_task_collaboration_active
    on ap_task_collaboration_policy (tenant_id, task_id)
    where status = 'ACTIVE';

create index idx_task_collaboration_instance
    on ap_task_collaboration_policy (tenant_id, instance_id, created_at desc);

create table ap_task_collaboration_participant (
    participant_id uuid not null,
    tenant_id varchar(128) not null,
    policy_id uuid not null,
    participant_user_id varchar(256) not null,
    identity_source varchar(128) not null,
    identity_object_type varchar(128) not null,
    identity_external_value varchar(256) not null,
    status varchar(32) not null,
    added_by varchar(256) not null,
    added_at timestamptz not null,
    decision_comment varchar(2000),
    decided_at timestamptz,
    removed_by varchar(256),
    removed_at timestamptz,
    removal_reason varchar(2000),
    canceled_at timestamptz,
    version bigint not null,
    primary key (tenant_id, participant_id),
    constraint fk_task_collaboration_participant_policy
        foreign key (tenant_id, policy_id)
        references ap_task_collaboration_policy (tenant_id, policy_id),
    constraint uk_task_collaboration_participant_user
        unique (tenant_id, policy_id, participant_user_id),
    constraint chk_task_collaboration_participant_status
        check (status in ('PENDING', 'APPROVED', 'REJECTED', 'REMOVED', 'CANCELED')),
    constraint chk_task_collaboration_participant_terminal
        check (
            (
                status = 'PENDING'
                and decision_comment is null
                and decided_at is null
                and removed_by is null
                and removed_at is null
                and removal_reason is null
                and canceled_at is null
            )
            or (
                status in ('APPROVED', 'REJECTED')
                and decision_comment is not null
                and decided_at is not null
                and removed_by is null
                and removed_at is null
                and removal_reason is null
                and canceled_at is null
            )
            or (
                status = 'REMOVED'
                and decision_comment is null
                and decided_at is null
                and removed_by is not null
                and removed_at is not null
                and removal_reason is not null
                and canceled_at is null
            )
            or (
                status = 'CANCELED'
                and decision_comment is null
                and decided_at is null
                and removed_by is null
                and removed_at is null
                and removal_reason is null
                and canceled_at is not null
            )
        ),
    constraint chk_task_collaboration_participant_version
        check (version > 0)
);

create index idx_task_collaboration_participant_pending
    on ap_task_collaboration_participant (
        tenant_id,
        participant_user_id,
        status,
        added_at desc
    );

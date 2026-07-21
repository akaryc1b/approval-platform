create table ap_principal_handover (
    handover_id uuid not null,
    tenant_id varchar(128) not null,
    connector_key varchar(128) not null,
    principal_id varchar(256) not null,
    principal_source varchar(128) not null,
    principal_object_type varchar(128) not null,
    principal_external_value varchar(256) not null,
    successor_id varchar(256) not null,
    successor_source varchar(128) not null,
    successor_object_type varchar(128) not null,
    successor_external_value varchar(256) not null,
    reason varchar(2000) not null,
    status varchar(32) not null,
    created_by varchar(256) not null,
    created_at timestamptz not null,
    revoked_by varchar(256),
    revoked_at timestamptz,
    revoke_reason varchar(2000),
    version bigint not null,
    primary key (tenant_id, handover_id),
    constraint chk_principal_handover_distinct_users
        check (principal_id <> successor_id),
    constraint chk_principal_handover_status
        check (status in ('ACTIVE', 'REVOKED')),
    constraint chk_principal_handover_revocation
        check (
            (
                status = 'ACTIVE'
                and revoked_by is null
                and revoked_at is null
                and revoke_reason is null
            )
            or (
                status = 'REVOKED'
                and revoked_by is not null
                and revoked_at is not null
                and revoke_reason is not null
            )
        ),
    constraint chk_principal_handover_version
        check (version > 0)
);

create unique index uk_principal_handover_active
    on ap_principal_handover (tenant_id, principal_id)
    where status = 'ACTIVE';

create index idx_principal_handover_successor
    on ap_principal_handover (tenant_id, successor_id, status, created_at desc);

create table ap_task_handover_assignment (
    assignment_id uuid not null,
    tenant_id varchar(128) not null,
    engine_task_id varchar(256) not null,
    engine_instance_id varchar(256) not null,
    definition_key varchar(256) not null,
    task_definition_key varchar(256) not null,
    principal_assignee_id varchar(256) not null,
    successor_assignee_id varchar(256) not null,
    handover_id uuid not null,
    status varchar(32) not null,
    assigned_at timestamptz not null,
    completed_by varchar(256),
    completed_at timestamptz,
    superseded_assignee_id varchar(256),
    superseded_at timestamptz,
    canceled_at timestamptz,
    version bigint not null,
    primary key (tenant_id, assignment_id),
    constraint uk_task_handover_engine_task
        unique (tenant_id, engine_task_id),
    constraint fk_task_handover_policy
        foreign key (tenant_id, handover_id)
        references ap_principal_handover (tenant_id, handover_id),
    constraint chk_task_handover_distinct_assignees
        check (principal_assignee_id <> successor_assignee_id),
    constraint chk_task_handover_status
        check (status in ('ACTIVE', 'COMPLETED', 'SUPERSEDED', 'CANCELED')),
    constraint chk_task_handover_terminal_metadata
        check (
            (
                status = 'ACTIVE'
                and completed_by is null
                and completed_at is null
                and superseded_assignee_id is null
                and superseded_at is null
                and canceled_at is null
            )
            or (
                status = 'COMPLETED'
                and completed_by is not null
                and completed_at is not null
                and superseded_assignee_id is null
                and superseded_at is null
                and canceled_at is null
            )
            or (
                status = 'SUPERSEDED'
                and completed_by is null
                and completed_at is null
                and superseded_assignee_id is not null
                and superseded_at is not null
                and canceled_at is null
            )
            or (
                status = 'CANCELED'
                and completed_by is null
                and completed_at is null
                and superseded_assignee_id is null
                and superseded_at is null
                and canceled_at is not null
            )
        ),
    constraint chk_task_handover_version
        check (version > 0)
);

create index idx_task_handover_engine_instance
    on ap_task_handover_assignment (tenant_id, engine_instance_id, status);

create index idx_task_handover_principal
    on ap_task_handover_assignment (
        tenant_id,
        principal_assignee_id,
        assigned_at desc
    );

create index idx_task_handover_successor
    on ap_task_handover_assignment (
        tenant_id,
        successor_assignee_id,
        assigned_at desc
    );

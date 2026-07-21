create table ap_task_delegation_assignment (
    assignment_id uuid not null,
    tenant_id varchar(128) not null,
    engine_task_id varchar(256) not null,
    engine_instance_id varchar(256) not null,
    definition_key varchar(256) not null,
    task_definition_key varchar(256) not null,
    principal_assignee_id varchar(256) not null,
    delegate_assignee_id varchar(256) not null,
    delegation_rule_id uuid not null,
    delegation_scope varchar(32) not null,
    status varchar(32) not null,
    assigned_at timestamptz not null,
    completed_by varchar(256),
    completed_at timestamptz,
    superseded_assignee_id varchar(256),
    superseded_at timestamptz,
    canceled_at timestamptz,
    version bigint not null,
    primary key (tenant_id, assignment_id),
    constraint uk_task_delegation_engine_task
        unique (tenant_id, engine_task_id),
    constraint fk_task_delegation_rule
        foreign key (tenant_id, delegation_rule_id)
        references ap_delegation_rule (tenant_id, rule_id),
    constraint chk_task_delegation_distinct_assignees
        check (principal_assignee_id <> delegate_assignee_id),
    constraint chk_task_delegation_scope
        check (delegation_scope in ('ALL', 'DEFINITION')),
    constraint chk_task_delegation_status
        check (status in ('ACTIVE', 'COMPLETED', 'SUPERSEDED', 'CANCELED')),
    constraint chk_task_delegation_terminal_metadata
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
    constraint chk_task_delegation_version
        check (version > 0)
);

create index idx_task_delegation_engine_instance
    on ap_task_delegation_assignment (
        tenant_id,
        engine_instance_id,
        status
    );

create index idx_task_delegation_principal
    on ap_task_delegation_assignment (
        tenant_id,
        principal_assignee_id,
        assigned_at desc
    );

create index idx_task_delegation_delegate
    on ap_task_delegation_assignment (
        tenant_id,
        delegate_assignee_id,
        assigned_at desc
    );

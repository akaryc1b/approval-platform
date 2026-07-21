create table ap_sla_policy (
    policy_id uuid not null,
    tenant_id varchar(128) not null,
    policy_key varchar(100) not null,
    display_name varchar(200) not null,
    status varchar(32) not null,
    active_version integer,
    created_by varchar(200) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null,
    primary key (tenant_id, policy_id),
    constraint uk_sla_policy_key unique (tenant_id, policy_key),
    constraint chk_sla_policy_key
        check (policy_key ~ '^[A-Za-z][A-Za-z0-9._:-]{1,99}$'),
    constraint chk_sla_policy_status
        check (status in ('DRAFT', 'PUBLISHED', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    constraint chk_sla_policy_active_version check (active_version is null or active_version > 0),
    constraint chk_sla_policy_version check (version > 0),
    constraint chk_sla_policy_timestamps check (updated_at >= created_at)
);

create index idx_sla_policy_list
    on ap_sla_policy (tenant_id, updated_at desc, policy_id);

create table ap_sla_policy_version (
    policy_id uuid not null,
    tenant_id varchar(128) not null,
    policy_version integer not null,
    definition_key varchar(100) not null,
    release_version integer,
    task_definition_key varchar(100),
    target_type varchar(64) not null,
    duration_mode varchar(32) not null,
    duration_millis bigint not null,
    calendar_id uuid,
    calendar_version integer,
    calendar_content_hash char(64),
    time_zone varchar(100) not null,
    first_reminder_offset_millis bigint,
    repeat_reminder_interval_millis bigint,
    maximum_reminder_count integer not null,
    overdue_offset_millis bigint,
    escalation_strategy varchar(64),
    escalation_target varchar(256),
    automatic_action_policy varchar(64) not null,
    pause_rules_json jsonb not null,
    content_hash char(64) not null,
    status varchar(32) not null,
    immutable boolean not null,
    published_by varchar(200),
    published_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (tenant_id, policy_id, policy_version),
    constraint fk_sla_policy_version_identity
        foreign key (tenant_id, policy_id)
        references ap_sla_policy (tenant_id, policy_id),
    constraint fk_sla_policy_calendar_version
        foreign key (tenant_id, calendar_id, calendar_version)
        references ap_work_calendar_version (tenant_id, calendar_id, calendar_version),
    constraint chk_sla_policy_version_number check (policy_version > 0),
    constraint chk_sla_policy_release_version check (release_version is null or release_version > 0),
    constraint chk_sla_policy_target_type
        check (target_type in ('PROCESS', 'TASK', 'COLLABORATION_PARTICIPANT')),
    constraint chk_sla_policy_task_target
        check (
            (target_type = 'PROCESS' and task_definition_key is null)
            or (target_type in ('TASK', 'COLLABORATION_PARTICIPANT'))
        ),
    constraint chk_sla_policy_duration_mode
        check (duration_mode in ('NATURAL_TIME', 'WORKING_TIME')),
    constraint chk_sla_policy_duration check (duration_millis > 0 and duration_millis <= 3162240000000),
    constraint chk_sla_policy_calendar_binding
        check (
            (
                duration_mode = 'NATURAL_TIME'
                and calendar_id is null
                and calendar_version is null
                and calendar_content_hash is null
                and time_zone = 'UTC'
            )
            or
            (
                duration_mode = 'WORKING_TIME'
                and calendar_id is not null
                and calendar_version is not null
                and calendar_content_hash ~ '^[0-9a-f]{64}$'
                and time_zone <> ''
            )
        ),
    constraint chk_sla_policy_reminders
        check (
            maximum_reminder_count between 0 and 100
            and (first_reminder_offset_millis is null or first_reminder_offset_millis >= 0)
            and (repeat_reminder_interval_millis is null or repeat_reminder_interval_millis >= 0)
            and (overdue_offset_millis is null or overdue_offset_millis >= 0)
            and (
                maximum_reminder_count <= 1
                or repeat_reminder_interval_millis is not null
                    and repeat_reminder_interval_millis > 0
            )
        ),
    constraint chk_sla_policy_escalation_strategy
        check (escalation_strategy is null or escalation_strategy in ('MANAGER', 'USER', 'ROLE', 'DEPARTMENT_ADMIN')),
    constraint chk_sla_policy_escalation_target
        check (
            (escalation_strategy is null and escalation_target is null)
            or (escalation_strategy in ('MANAGER', 'DEPARTMENT_ADMIN') and escalation_target is null)
            or (escalation_strategy in ('USER', 'ROLE') and escalation_target is not null)
        ),
    constraint chk_sla_policy_automatic_action
        check (automatic_action_policy in ('NONE', 'AUTO_TRANSFER', 'AUTO_APPROVE', 'AUTO_REJECT')),
    constraint chk_sla_policy_pause_rules_json
        check (jsonb_typeof(pause_rules_json) = 'object' and pg_column_size(pause_rules_json) <= 16384),
    constraint chk_sla_policy_hash check (content_hash ~ '^[0-9a-f]{64}$'),
    constraint chk_sla_policy_version_status
        check (status in ('DRAFT', 'PUBLISHED', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    constraint chk_sla_policy_publication
        check (
            (not immutable and published_by is null and published_at is null and status = 'DRAFT')
            or
            (immutable and published_by is not null and published_at is not null
                and status in ('PUBLISHED', 'ACTIVE', 'INACTIVE', 'ARCHIVED'))
        ),
    constraint chk_sla_policy_timestamps check (updated_at >= created_at)
);

alter table ap_sla_policy
    add constraint fk_sla_policy_active_version
    foreign key (tenant_id, policy_id, active_version)
    references ap_sla_policy_version (tenant_id, policy_id, policy_version);

create unique index uk_sla_policy_active_target
    on ap_sla_policy_version (
        tenant_id,
        definition_key,
        coalesce(release_version, -1),
        coalesce(task_definition_key, ''),
        target_type
    )
    where status = 'ACTIVE';

create index idx_sla_policy_active_lookup
    on ap_sla_policy_version (
        tenant_id,
        definition_key,
        target_type,
        task_definition_key,
        release_version,
        policy_id,
        policy_version
    )
    where status = 'ACTIVE';

create index idx_sla_policy_version_status
    on ap_sla_policy_version (tenant_id, policy_id, status, policy_version desc);

create or replace function ap_reject_immutable_sla_policy_version_change()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' and old.immutable then
        raise exception using
            errcode = '55000',
            message = 'APPROVAL_SLA_POLICY_ALREADY_PUBLISHED: published SLA policy is immutable';
    end if;
    if tg_op = 'UPDATE' and old.immutable and (
        new.tenant_id is distinct from old.tenant_id
        or new.policy_id is distinct from old.policy_id
        or new.policy_version is distinct from old.policy_version
        or new.definition_key is distinct from old.definition_key
        or new.release_version is distinct from old.release_version
        or new.task_definition_key is distinct from old.task_definition_key
        or new.target_type is distinct from old.target_type
        or new.duration_mode is distinct from old.duration_mode
        or new.duration_millis is distinct from old.duration_millis
        or new.calendar_id is distinct from old.calendar_id
        or new.calendar_version is distinct from old.calendar_version
        or new.calendar_content_hash is distinct from old.calendar_content_hash
        or new.time_zone is distinct from old.time_zone
        or new.first_reminder_offset_millis is distinct from old.first_reminder_offset_millis
        or new.repeat_reminder_interval_millis is distinct from old.repeat_reminder_interval_millis
        or new.maximum_reminder_count is distinct from old.maximum_reminder_count
        or new.overdue_offset_millis is distinct from old.overdue_offset_millis
        or new.escalation_strategy is distinct from old.escalation_strategy
        or new.escalation_target is distinct from old.escalation_target
        or new.automatic_action_policy is distinct from old.automatic_action_policy
        or new.pause_rules_json is distinct from old.pause_rules_json
        or new.content_hash is distinct from old.content_hash
        or new.immutable is distinct from old.immutable
        or new.published_by is distinct from old.published_by
        or new.published_at is distinct from old.published_at
        or new.created_at is distinct from old.created_at
    ) then
        raise exception using
            errcode = '55000',
            message = 'APPROVAL_SLA_POLICY_ALREADY_PUBLISHED: published SLA policy content is immutable';
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

create trigger trg_reject_immutable_sla_policy_version_change
before update or delete on ap_sla_policy_version
for each row execute function ap_reject_immutable_sla_policy_version_change();

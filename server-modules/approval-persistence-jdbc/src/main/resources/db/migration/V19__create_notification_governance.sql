create table ap_notification_user_setting (
    tenant_id varchar(128) not null,
    user_id varchar(256) not null,
    timezone varchar(128) not null,
    quiet_hours_enabled boolean not null,
    quiet_hours_start time,
    quiet_hours_end time,
    emergency_bypass boolean not null,
    digest_enabled boolean not null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (tenant_id, user_id),
    constraint chk_notification_quiet_hours check (
        (quiet_hours_enabled and quiet_hours_start is not null and quiet_hours_end is not null
            and quiet_hours_start <> quiet_hours_end)
        or
        (not quiet_hours_enabled and quiet_hours_start is null and quiet_hours_end is null)
    ),
    constraint chk_notification_setting_version check (version > 0)
);

create table ap_notification_preference (
    tenant_id varchar(128) not null,
    user_id varchar(256) not null,
    event_type varchar(64) not null,
    channel varchar(32) not null,
    enabled boolean not null,
    settings_version bigint not null,
    primary key (tenant_id, user_id, event_type, channel),
    constraint fk_notification_preference_setting
        foreign key (tenant_id, user_id)
        references ap_notification_user_setting (tenant_id, user_id)
        on delete cascade,
    constraint chk_notification_preference_event check (event_type in (
        'TASK_ASSIGNED',
        'AUTOMATIC_DELEGATION',
        'EMPLOYEE_HANDOVER',
        'TASK_COLLABORATION_ASSIGNED',
        'TASK_COLLABORATION_RESULT',
        'APPROVAL_COMPLETED',
        'APPROVAL_REJECTED',
        'COMMENT_MENTION'
    )),
    constraint chk_notification_preference_channel check (channel in (
        'IN_APP', 'CONNECTOR', 'EMAIL'
    )),
    constraint chk_notification_preference_version check (settings_version > 0)
);

create table ap_notification_intent (
    intent_id uuid not null,
    tenant_id varchar(128) not null,
    event_type varchar(64) not null,
    channel varchar(32) not null,
    recipient_id varchar(256) not null,
    sender_id varchar(256) not null,
    instance_id uuid,
    task_id uuid,
    aggregate_type varchar(128) not null,
    aggregate_id varchar(256) not null,
    template_key varchar(128) not null,
    template_version integer not null,
    title varchar(256) not null,
    body varchar(2000) not null,
    metadata_json jsonb not null,
    business_event_key varchar(512) not null,
    urgent boolean not null,
    status varchar(32) not null,
    attempt_count integer not null,
    max_attempts integer not null,
    next_attempt_at timestamptz not null,
    delivered_at timestamptz,
    read_at timestamptz,
    last_error_code varchar(128),
    last_error_message varchar(2000),
    locked_by varchar(256),
    locked_until timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null,
    primary key (tenant_id, intent_id),
    constraint uk_notification_business_recipient_channel
        unique (tenant_id, business_event_key, recipient_id, channel),
    constraint chk_notification_intent_event check (event_type in (
        'TASK_ASSIGNED',
        'AUTOMATIC_DELEGATION',
        'EMPLOYEE_HANDOVER',
        'TASK_COLLABORATION_ASSIGNED',
        'TASK_COLLABORATION_RESULT',
        'APPROVAL_COMPLETED',
        'APPROVAL_REJECTED',
        'COMMENT_MENTION'
    )),
    constraint chk_notification_intent_channel check (channel in (
        'IN_APP', 'CONNECTOR', 'EMAIL'
    )),
    constraint chk_notification_intent_status check (status in (
        'PENDING', 'PROCESSING', 'RETRY', 'DELIVERED', 'DEAD_LETTER'
    )),
    constraint chk_notification_template_version check (template_version > 0),
    constraint chk_notification_attempts check (
        attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts
    ),
    constraint chk_notification_intent_version check (version > 0),
    constraint chk_notification_delivery_state check (
        (status = 'DELIVERED' and delivered_at is not null
            and locked_by is null and locked_until is null)
        or
        (status <> 'DELIVERED' and delivered_at is null)
    ),
    constraint chk_notification_processing_lock check (
        (status = 'PROCESSING' and locked_by is not null and locked_until is not null)
        or
        (status <> 'PROCESSING' and locked_by is null and locked_until is null)
    )
);

create index idx_notification_due
    on ap_notification_intent (status, next_attempt_at, created_at)
    where status in ('PENDING', 'RETRY', 'PROCESSING');

create index idx_notification_recipient_history
    on ap_notification_intent (tenant_id, recipient_id, created_at desc, intent_id desc);

create index idx_notification_recipient_unread
    on ap_notification_intent (tenant_id, recipient_id, read_at)
    where read_at is null;

create index idx_notification_instance
    on ap_notification_intent (tenant_id, instance_id, created_at desc)
    where instance_id is not null;

create table ap_notification_delivery_attempt (
    attempt_id uuid not null,
    tenant_id varchar(128) not null,
    intent_id uuid not null,
    attempt_number integer not null,
    started_at timestamptz not null,
    completed_at timestamptz not null,
    successful boolean not null,
    retryable boolean not null,
    provider_message_id varchar(512),
    error_code varchar(128),
    error_message varchar(2000),
    primary key (tenant_id, attempt_id),
    constraint fk_notification_attempt_intent
        foreign key (tenant_id, intent_id)
        references ap_notification_intent (tenant_id, intent_id)
        on delete cascade,
    constraint uk_notification_attempt_number
        unique (tenant_id, intent_id, attempt_number),
    constraint chk_notification_attempt_number check (attempt_number > 0),
    constraint chk_notification_attempt_result check (
        (successful and error_code is null and error_message is null)
        or
        (not successful and error_code is not null and error_message is not null)
    )
);

create index idx_notification_attempt_intent
    on ap_notification_delivery_attempt (tenant_id, intent_id, attempt_number);

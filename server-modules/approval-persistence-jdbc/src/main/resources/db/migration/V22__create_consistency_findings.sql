create table ap_consistency_check (
    check_id uuid not null,
    tenant_id varchar(128) not null,
    requested_by varchar(256) not null,
    request_id varchar(512) not null,
    trace_id varchar(512),
    scope varchar(32) not null,
    status varchar(32) not null,
    started_at timestamptz not null,
    completed_at timestamptz,
    finding_count integer not null,
    error_code varchar(128),
    error_message varchar(2000),
    version bigint not null,
    primary key (tenant_id, check_id),
    constraint uk_consistency_check_request unique (tenant_id, request_id),
    constraint chk_consistency_check_scope check (scope in ('TENANT')),
    constraint chk_consistency_check_status check (
        status in ('RUNNING', 'COMPLETED', 'FAILED')
    ),
    constraint chk_consistency_check_count check (finding_count >= 0),
    constraint chk_consistency_check_version check (version > 0),
    constraint chk_consistency_check_terminal check (
        (
            status = 'RUNNING'
            and completed_at is null
            and error_code is null
            and error_message is null
        )
        or (
            status = 'COMPLETED'
            and completed_at is not null
            and error_code is null
            and error_message is null
        )
        or (
            status = 'FAILED'
            and completed_at is not null
            and error_code is not null
            and error_message is not null
        )
    )
);

create table ap_consistency_finding (
    finding_id uuid not null,
    tenant_id varchar(128) not null,
    check_id uuid not null,
    check_type varchar(64) not null,
    severity varchar(32) not null,
    aggregate_type varchar(128) not null,
    aggregate_id varchar(512) not null,
    detected_at timestamptz not null,
    details_json jsonb not null,
    suggested_action varchar(2000) not null,
    primary key (tenant_id, finding_id),
    constraint fk_consistency_finding_check
        foreign key (tenant_id, check_id)
        references ap_consistency_check (tenant_id, check_id)
        on delete cascade,
    constraint chk_consistency_finding_type check (check_type in (
        'INSTANCE_TASK_STATE',
        'DELEGATION_EVIDENCE',
        'HANDOVER_EVIDENCE',
        'COLLABORATION_POLICY',
        'NOTIFICATION_DELIVERY',
        'COMMENT_REVISION',
        'ATTACHMENT_REFERENCE',
        'AUDIT_BUSINESS_EVIDENCE'
    )),
    constraint chk_consistency_finding_severity check (
        severity in ('WARNING', 'ERROR', 'CRITICAL')
    )
);

create index idx_consistency_check_tenant_time
    on ap_consistency_check (tenant_id, started_at desc, check_id desc);

create index idx_consistency_check_status
    on ap_consistency_check (tenant_id, status, started_at desc);

create index idx_consistency_finding_check
    on ap_consistency_finding (
        tenant_id,
        check_id,
        severity,
        check_type,
        detected_at,
        finding_id
    );

create index idx_consistency_finding_aggregate
    on ap_consistency_finding (
        tenant_id,
        aggregate_type,
        aggregate_id,
        detected_at desc
    );

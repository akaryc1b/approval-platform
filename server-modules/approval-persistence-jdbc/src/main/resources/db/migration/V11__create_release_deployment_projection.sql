alter table ap_approval_release_package
    add constraint ap_approval_release_exact_package_unique unique (
        tenant_id, definition_key, release_version, package_hash
    );

create table ap_approval_release_deployment (
    deployment_record_id uuid not null,
    tenant_id varchar(128) not null,
    definition_key varchar(64) not null,
    release_version integer not null check (release_version > 0),
    release_package_hash char(64) not null check (
        release_package_hash ~ '^[0-9a-f]{64}$'
    ),
    status varchar(16) not null check (status in ('PENDING', 'DEPLOYED', 'FAILED')),
    attempt_count integer not null check (attempt_count > 0),
    engine_deployment_id varchar(128),
    engine_definition_id varchar(256),
    engine_version integer check (engine_version is null or engine_version > 0),
    last_error_code varchar(128),
    last_error_message varchar(1000),
    requested_by varchar(256) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deployed_at timestamptz,
    primary key (tenant_id, definition_key, release_version),
    unique (tenant_id, deployment_record_id),
    constraint ap_approval_release_deployment_package_fk foreign key (
        tenant_id, definition_key, release_version, release_package_hash
    ) references ap_approval_release_package (
        tenant_id, definition_key, release_version, package_hash
    ),
    constraint ap_approval_release_deployment_state_check check (
        (status = 'PENDING'
            and engine_deployment_id is null
            and engine_definition_id is null
            and engine_version is null
            and last_error_code is null
            and last_error_message is null
            and deployed_at is null)
        or (status = 'DEPLOYED'
            and engine_deployment_id is not null
            and engine_definition_id is not null
            and engine_version is not null
            and last_error_code is null
            and last_error_message is null
            and deployed_at is not null)
        or (status = 'FAILED'
            and engine_deployment_id is null
            and engine_definition_id is null
            and engine_version is null
            and last_error_code is not null
            and last_error_message is not null
            and deployed_at is null)
    )
);

create index ap_approval_release_deployment_status_idx
    on ap_approval_release_deployment (tenant_id, status, updated_at desc);

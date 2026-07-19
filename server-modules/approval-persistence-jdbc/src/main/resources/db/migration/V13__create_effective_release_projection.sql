create table ap_approval_effective_release (
    tenant_id varchar(128) not null,
    definition_key varchar(64) not null,
    effective_release_version integer not null check (effective_release_version > 0),
    previous_release_version integer check (previous_release_version is null or previous_release_version > 0),
    release_package_hash char(64) not null check (release_package_hash ~ '^[0-9a-f]{64}$'),
    definition_version integer not null check (definition_version > 0),
    definition_hash char(64) not null check (definition_hash ~ '^[0-9a-f]{64}$'),
    form_package_version integer not null check (form_package_version > 0),
    form_package_hash char(64) not null check (form_package_hash ~ '^[0-9a-f]{64}$'),
    form_schema_version integer not null check (form_schema_version > 0),
    form_schema_hash char(64) not null check (form_schema_hash ~ '^[0-9a-f]{64}$'),
    ui_schema_version integer not null check (ui_schema_version > 0),
    ui_schema_hash char(64) not null check (ui_schema_hash ~ '^[0-9a-f]{64}$'),
    compiler_version varchar(64) not null,
    compiled_artifact_hash char(64) not null check (compiled_artifact_hash ~ '^[0-9a-f]{64}$'),
    bpmn_hash char(64) not null check (bpmn_hash ~ '^[0-9a-f]{64}$'),
    deployment_metadata_hash char(64) not null check (deployment_metadata_hash ~ '^[0-9a-f]{64}$'),
    engine_deployment_id varchar(128) not null,
    engine_definition_id varchar(256) not null,
    engine_version integer not null check (engine_version > 0),
    status varchar(16) not null check (status = 'ACTIVE'),
    revision bigint not null check (revision > 0),
    activated_by varchar(256) not null,
    activated_at timestamptz not null,
    change_reason varchar(1000) not null,
    request_id varchar(256) not null,
    trace_id varchar(256),
    primary key (tenant_id, definition_key),
    constraint ap_approval_effective_release_package_fk foreign key (
        tenant_id, definition_key, effective_release_version, release_package_hash
    ) references ap_approval_release_package (
        tenant_id, definition_key, release_version, package_hash
    ),
    constraint ap_approval_effective_release_previous_check check (
        previous_release_version is null
        or previous_release_version <> effective_release_version
    )
);

create table ap_approval_release_activation_history (
    activation_id uuid primary key,
    tenant_id varchar(128) not null,
    definition_key varchar(64) not null,
    release_version integer not null check (release_version > 0),
    previous_release_version integer check (previous_release_version is null or previous_release_version > 0),
    release_package_hash char(64) not null check (release_package_hash ~ '^[0-9a-f]{64}$'),
    definition_version integer not null check (definition_version > 0),
    form_package_version integer not null check (form_package_version > 0),
    compiler_version varchar(64) not null,
    engine_deployment_id varchar(128) not null,
    engine_definition_id varchar(256) not null,
    engine_version integer not null check (engine_version > 0),
    action varchar(16) not null check (action in ('ACTIVATE', 'ROLLBACK')),
    revision bigint not null check (revision > 0),
    activated_by varchar(256) not null,
    activated_at timestamptz not null,
    change_reason varchar(1000) not null,
    request_id varchar(256) not null,
    trace_id varchar(256),
    unique (tenant_id, definition_key, revision),
    constraint ap_approval_release_activation_package_fk foreign key (
        tenant_id, definition_key, release_version, release_package_hash
    ) references ap_approval_release_package (
        tenant_id, definition_key, release_version, package_hash
    ),
    constraint ap_approval_release_activation_previous_check check (
        previous_release_version is null or previous_release_version <> release_version
    )
);

create index ap_approval_release_activation_history_idx
    on ap_approval_release_activation_history (
        tenant_id, definition_key, activated_at desc, revision desc
    );

alter table ap_approval_instance
    add column release_version integer,
    add column release_package_hash char(64),
    add column form_package_version integer,
    add column form_package_hash char(64),
    add column ui_schema_version integer,
    add column ui_schema_hash char(64),
    add column engine_definition_id varchar(256);

alter table ap_approval_instance
    add constraint ap_approval_instance_release_snapshot_check check (
        (release_version is null
            and release_package_hash is null
            and form_package_version is null
            and form_package_hash is null
            and ui_schema_version is null
            and ui_schema_hash is null
            and engine_definition_id is null)
        or (release_version > 0
            and release_package_hash ~ '^[0-9a-f]{64}$'
            and form_package_version > 0
            and form_package_hash ~ '^[0-9a-f]{64}$'
            and ui_schema_version > 0
            and ui_schema_hash ~ '^[0-9a-f]{64}$'
            and engine_definition_id is not null)
    );

alter table ap_approval_instance
    add constraint ap_approval_instance_release_package_fk foreign key (
        tenant_id, definition_key, release_version, release_package_hash
    ) references ap_approval_release_package (
        tenant_id, definition_key, release_version, package_hash
    );

create index ap_approval_instance_release_version_idx
    on ap_approval_instance (tenant_id, definition_key, release_version, created_at desc);

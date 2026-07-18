alter table ap_form_definition
    add constraint ap_form_definition_exact_hash_unique unique (
        tenant_id, form_key, form_version, content_hash
    );

alter table ap_form_ui_schema
    add constraint ap_form_ui_schema_exact_hash_unique unique (
        tenant_id, form_key, form_version, ui_schema_version, content_hash
    );

alter table ap_form_package
    add constraint ap_form_package_exact_hash_unique unique (
        tenant_id, form_key, package_version, package_hash
    );

create table ap_approval_design_draft (
    tenant_id varchar(128) not null,
    draft_id uuid not null,
    definition_key varchar(64) not null,
    name varchar(256) not null,
    definition_version integer not null check (definition_version > 0),
    approval_dsl_json jsonb not null,
    form_package_version integer not null check (form_package_version > 0),
    form_package_hash char(64) not null check (form_package_hash ~ '^[0-9a-f]{64}$'),
    source_definition_version integer,
    revision bigint not null check (revision > 0),
    status varchar(16) not null check (
        status in ('DRAFT', 'VALIDATED', 'PUBLISHED', 'ARCHIVED')
    ),
    published_definition_version integer,
    published_release_version integer,
    created_by varchar(256) not null,
    updated_by varchar(256) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (tenant_id, draft_id),
    constraint ap_approval_design_draft_source_version_check check (
        source_definition_version is null or source_definition_version > 0
    ),
    constraint ap_approval_design_draft_published_pair_check check (
        (status = 'PUBLISHED'
            and published_definition_version is not null
            and published_release_version is not null)
        or (status <> 'PUBLISHED'
            and published_definition_version is null
            and published_release_version is null)
    ),
    constraint ap_approval_design_draft_form_package_fk foreign key (
        tenant_id, definition_key, form_package_version, form_package_hash
    ) references ap_form_package (
        tenant_id, form_key, package_version, package_hash
    )
);

create index ap_approval_design_draft_list_idx
    on ap_approval_design_draft (tenant_id, status, updated_at desc, draft_id);

create index ap_approval_design_draft_key_idx
    on ap_approval_design_draft (tenant_id, definition_key, updated_at desc);

create table ap_approval_definition (
    tenant_id varchar(128) not null,
    definition_key varchar(64) not null,
    definition_version integer not null check (definition_version > 0),
    definition_hash char(64) not null check (definition_hash ~ '^[0-9a-f]{64}$'),
    form_package_version integer not null check (form_package_version > 0),
    form_package_hash char(64) not null check (form_package_hash ~ '^[0-9a-f]{64}$'),
    approval_dsl_json jsonb not null,
    source_draft_id uuid not null,
    published_by varchar(256) not null,
    published_at timestamptz not null,
    primary key (tenant_id, definition_key, definition_version),
    unique (tenant_id, source_draft_id),
    constraint ap_approval_definition_exact_hash_unique unique (
        tenant_id, definition_key, definition_version, definition_hash
    ),
    constraint ap_approval_definition_form_package_fk foreign key (
        tenant_id, definition_key, form_package_version, form_package_hash
    ) references ap_form_package (
        tenant_id, form_key, package_version, package_hash
    ),
    constraint ap_approval_definition_draft_fk foreign key (
        tenant_id, source_draft_id
    ) references ap_approval_design_draft (
        tenant_id, draft_id
    )
);

create index ap_approval_definition_latest_idx
    on ap_approval_definition (tenant_id, definition_key, definition_version desc);

create table ap_approval_compiled_artifact (
    tenant_id varchar(128) not null,
    definition_key varchar(64) not null,
    definition_version integer not null check (definition_version > 0),
    definition_hash char(64) not null check (definition_hash ~ '^[0-9a-f]{64}$'),
    form_version integer not null check (form_version > 0),
    form_hash char(64) not null check (form_hash ~ '^[0-9a-f]{64}$'),
    compiler_version varchar(64) not null,
    resource_name varchar(256) not null,
    bpmn_xml text not null,
    compiled_artifact_hash char(64) not null check (
        compiled_artifact_hash ~ '^[0-9a-f]{64}$'
    ),
    bpmn_hash char(64) not null check (bpmn_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null,
    primary key (tenant_id, definition_key, definition_version, compiler_version),
    constraint ap_approval_compiled_artifact_exact_hash_unique unique (
        tenant_id, definition_key, definition_version, compiler_version,
        compiled_artifact_hash, bpmn_hash
    ),
    constraint ap_approval_compiled_artifact_definition_fk foreign key (
        tenant_id, definition_key, definition_version, definition_hash
    ) references ap_approval_definition (
        tenant_id, definition_key, definition_version, definition_hash
    ),
    constraint ap_approval_compiled_artifact_form_fk foreign key (
        tenant_id, definition_key, form_version, form_hash
    ) references ap_form_definition (
        tenant_id, form_key, form_version, content_hash
    )
);

create table ap_approval_release_package (
    tenant_id varchar(128) not null,
    definition_key varchar(64) not null,
    release_version integer not null check (release_version > 0),
    definition_version integer not null check (definition_version > 0),
    definition_hash char(64) not null check (definition_hash ~ '^[0-9a-f]{64}$'),
    form_package_version integer not null check (form_package_version > 0),
    form_package_hash char(64) not null check (form_package_hash ~ '^[0-9a-f]{64}$'),
    form_version integer not null check (form_version > 0),
    form_hash char(64) not null check (form_hash ~ '^[0-9a-f]{64}$'),
    ui_schema_version integer not null check (ui_schema_version > 0),
    ui_schema_hash char(64) not null check (ui_schema_hash ~ '^[0-9a-f]{64}$'),
    compiler_version varchar(64) not null,
    bpmn_resource_name varchar(256) not null,
    bpmn_artifact text not null,
    compiled_artifact_hash char(64) not null check (
        compiled_artifact_hash ~ '^[0-9a-f]{64}$'
    ),
    bpmn_hash char(64) not null check (bpmn_hash ~ '^[0-9a-f]{64}$'),
    dmn_artifact text,
    dmn_hash char(64),
    deployment_metadata_hash char(64) not null check (
        deployment_metadata_hash ~ '^[0-9a-f]{64}$'
    ),
    package_hash char(64) not null check (package_hash ~ '^[0-9a-f]{64}$'),
    source_draft_id uuid not null,
    published_by varchar(256) not null,
    published_at timestamptz not null,
    primary key (tenant_id, definition_key, release_version),
    unique (tenant_id, source_draft_id),
    constraint ap_approval_release_dmn_pair_check check (
        (dmn_artifact is null and dmn_hash is null)
        or (dmn_artifact is not null and dmn_hash is not null)
    ),
    constraint ap_approval_release_definition_fk foreign key (
        tenant_id, definition_key, definition_version, definition_hash
    ) references ap_approval_definition (
        tenant_id, definition_key, definition_version, definition_hash
    ),
    constraint ap_approval_release_form_package_fk foreign key (
        tenant_id, definition_key, form_package_version, form_package_hash
    ) references ap_form_package (
        tenant_id, form_key, package_version, package_hash
    ),
    constraint ap_approval_release_form_fk foreign key (
        tenant_id, definition_key, form_version, form_hash
    ) references ap_form_definition (
        tenant_id, form_key, form_version, content_hash
    ),
    constraint ap_approval_release_ui_fk foreign key (
        tenant_id, definition_key, form_version, ui_schema_version, ui_schema_hash
    ) references ap_form_ui_schema (
        tenant_id, form_key, form_version, ui_schema_version, content_hash
    ),
    constraint ap_approval_release_artifact_fk foreign key (
        tenant_id, definition_key, definition_version, compiler_version,
        compiled_artifact_hash, bpmn_hash
    ) references ap_approval_compiled_artifact (
        tenant_id, definition_key, definition_version, compiler_version,
        compiled_artifact_hash, bpmn_hash
    ),
    constraint ap_approval_release_draft_fk foreign key (
        tenant_id, source_draft_id
    ) references ap_approval_design_draft (
        tenant_id, draft_id
    )
);

create index ap_approval_release_latest_idx
    on ap_approval_release_package (tenant_id, definition_key, release_version desc);

alter table ap_approval_design_draft
    add constraint ap_approval_design_draft_source_fk foreign key (
        tenant_id, definition_key, source_definition_version
    ) references ap_approval_definition (
        tenant_id, definition_key, definition_version
    ),
    add constraint ap_approval_design_draft_published_definition_fk foreign key (
        tenant_id, definition_key, published_definition_version
    ) references ap_approval_definition (
        tenant_id, definition_key, definition_version
    ),
    add constraint ap_approval_design_draft_published_release_fk foreign key (
        tenant_id, definition_key, published_release_version
    ) references ap_approval_release_package (
        tenant_id, definition_key, release_version
    );

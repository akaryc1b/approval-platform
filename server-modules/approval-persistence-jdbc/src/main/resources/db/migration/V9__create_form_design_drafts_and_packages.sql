create table ap_form_design_draft (
    tenant_id varchar(128) not null,
    draft_id uuid not null,
    form_key varchar(64) not null,
    name varchar(256) not null,
    form_version integer not null check (form_version > 0),
    ui_schema_version integer not null check (ui_schema_version > 0),
    form_schema_json jsonb not null,
    ui_schema_json jsonb not null,
    source_form_version integer,
    source_ui_schema_version integer,
    revision bigint not null check (revision > 0),
    status varchar(16) not null check (status in ('DRAFT', 'VALIDATED', 'PUBLISHED', 'ARCHIVED')),
    published_package_version integer,
    created_by varchar(256) not null,
    updated_by varchar(256) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (tenant_id, draft_id),
    constraint ap_form_design_draft_source_pair_check check (
        source_ui_schema_version is null or source_form_version is not null
    ),
    constraint ap_form_design_draft_published_pair_check check (
        (status = 'PUBLISHED' and published_package_version is not null)
        or (status <> 'PUBLISHED' and published_package_version is null)
    ),
    constraint ap_form_design_draft_source_form_fk foreign key (
        tenant_id, form_key, source_form_version
    ) references ap_form_definition (
        tenant_id, form_key, form_version
    ),
    constraint ap_form_design_draft_source_ui_fk foreign key (
        tenant_id, form_key, source_form_version, source_ui_schema_version
    ) references ap_form_ui_schema (
        tenant_id, form_key, form_version, ui_schema_version
    )
);

create index ap_form_design_draft_list_idx
    on ap_form_design_draft (tenant_id, status, updated_at desc, draft_id);

create index ap_form_design_draft_form_idx
    on ap_form_design_draft (tenant_id, form_key, updated_at desc);

create table ap_form_package (
    tenant_id varchar(128) not null,
    form_key varchar(64) not null,
    package_version integer not null check (package_version > 0),
    form_version integer not null check (form_version > 0),
    form_hash char(64) not null check (form_hash ~ '^[0-9a-f]{64}$'),
    ui_schema_version integer not null check (ui_schema_version > 0),
    ui_schema_hash char(64) not null check (ui_schema_hash ~ '^[0-9a-f]{64}$'),
    package_hash char(64) not null check (package_hash ~ '^[0-9a-f]{64}$'),
    source_draft_id uuid not null,
    published_by varchar(256) not null,
    published_at timestamptz not null,
    primary key (tenant_id, form_key, package_version),
    unique (tenant_id, source_draft_id),
    constraint ap_form_package_form_fk foreign key (
        tenant_id, form_key, form_version
    ) references ap_form_definition (
        tenant_id, form_key, form_version
    ),
    constraint ap_form_package_ui_fk foreign key (
        tenant_id, form_key, form_version, ui_schema_version
    ) references ap_form_ui_schema (
        tenant_id, form_key, form_version, ui_schema_version
    ),
    constraint ap_form_package_draft_fk foreign key (
        tenant_id, source_draft_id
    ) references ap_form_design_draft (
        tenant_id, draft_id
    )
);

create index ap_form_package_latest_idx
    on ap_form_package (tenant_id, form_key, package_version desc);

alter table ap_form_design_draft
    add constraint ap_form_design_draft_package_fk foreign key (
        tenant_id, form_key, published_package_version
    ) references ap_form_package (
        tenant_id, form_key, package_version
    );

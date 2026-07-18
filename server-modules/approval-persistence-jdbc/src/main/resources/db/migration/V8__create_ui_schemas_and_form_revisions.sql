create table ap_form_ui_schema (
    tenant_id varchar(128) not null,
    form_key varchar(64) not null,
    form_version integer not null check (form_version > 0),
    ui_schema_version integer not null check (ui_schema_version > 0),
    schema_version varchar(32) not null,
    name varchar(200) not null,
    section_count integer not null check (section_count > 0),
    schema_json jsonb not null,
    content_hash char(64) not null,
    published_by varchar(256) not null,
    published_at timestamptz not null,
    primary key (tenant_id, form_key, form_version, ui_schema_version),
    foreign key (tenant_id, form_key, form_version)
        references ap_form_definition (tenant_id, form_key, form_version)
);

create index ap_form_ui_schema_latest_idx
    on ap_form_ui_schema (tenant_id, form_key, form_version, ui_schema_version desc);

alter table ap_form_submission
    add column ui_schema_version integer,
    add column ui_schema_hash char(64),
    add constraint ap_form_submission_ui_schema_pair_check check (
        (ui_schema_version is null and ui_schema_hash is null)
        or (ui_schema_version is not null and ui_schema_hash is not null)
    ),
    add constraint ap_form_submission_ui_schema_fk foreign key (
        tenant_id, form_key, form_version, ui_schema_version
    ) references ap_form_ui_schema (
        tenant_id, form_key, form_version, ui_schema_version
    );

create table ap_form_submission_revision (
    revision_id uuid primary key,
    tenant_id varchar(128) not null,
    instance_id uuid not null references ap_approval_instance(instance_id),
    revision_number integer not null check (revision_number > 0),
    values_json jsonb not null,
    modified_by varchar(256) not null,
    modified_at timestamptz not null,
    request_hash char(64) not null,
    unique (tenant_id, instance_id, revision_number)
);

create index ap_form_submission_revision_latest_idx
    on ap_form_submission_revision (tenant_id, instance_id, revision_number desc);

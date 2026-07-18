create table if not exists ap_form_definition (
    tenant_id varchar(128) not null,
    form_key varchar(64) not null,
    form_version integer not null,
    schema_version varchar(32) not null,
    name varchar(256) not null,
    field_count integer not null,
    schema_json jsonb not null,
    content_hash varchar(64) not null,
    published_by varchar(256) not null,
    published_at timestamptz not null,
    primary key (tenant_id, form_key, form_version),
    constraint ap_form_definition_version_check check (form_version > 0),
    constraint ap_form_definition_field_count_check check (field_count > 0)
);

create index if not exists ap_form_definition_tenant_published_idx
    on ap_form_definition (tenant_id, published_at desc, form_key, form_version desc);

create index if not exists ap_form_definition_tenant_name_idx
    on ap_form_definition (tenant_id, lower(name), form_key);

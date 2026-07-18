create table if not exists ap_form_submission (
    submission_id uuid primary key,
    tenant_id varchar(128) not null,
    form_key varchar(128) not null,
    form_version integer not null check (form_version > 0),
    schema_hash char(64) not null,
    business_key varchar(256) not null,
    values_json jsonb not null,
    start_parameters_json jsonb not null,
    instance_id uuid not null references ap_approval_instance(instance_id),
    submitted_by varchar(256) not null,
    submitted_at timestamptz not null,
    request_hash char(64) not null,
    unique (tenant_id, business_key),
    unique (tenant_id, instance_id)
);

create index if not exists ap_form_submission_submitter_idx
    on ap_form_submission (tenant_id, submitted_by, submitted_at desc);
create index if not exists ap_form_submission_form_idx
    on ap_form_submission (tenant_id, form_key, form_version, submitted_at desc);

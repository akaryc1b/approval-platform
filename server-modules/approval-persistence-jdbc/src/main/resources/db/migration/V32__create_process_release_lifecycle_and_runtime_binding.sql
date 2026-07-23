create table ap_process_release_lifecycle (
    tenant_id varchar(128) not null,
    definition_key varchar(64) not null,
    release_version integer not null check (release_version > 0),
    release_package_hash char(64) not null check (
        release_package_hash ~ '^[0-9a-f]{64}$'
    ),
    lifecycle_state varchar(16) not null check (
        lifecycle_state in ('PUBLISHED', 'ACTIVE', 'DEPRECATED', 'RETIRED')
    ),
    revision bigint not null check (revision > 0),
    published_by varchar(256) not null,
    published_at timestamptz not null,
    activated_at timestamptz,
    deprecated_at timestamptz,
    retired_at timestamptz,
    last_transition_by varchar(256) not null,
    last_transition_at timestamptz not null,
    last_transition_reason varchar(1000) not null,
    last_idempotency_key varchar(200) not null,
    last_request_id varchar(256) not null,
    last_trace_id varchar(256),
    last_audit_chain_reference varchar(256) not null,
    primary key (tenant_id, definition_key, release_version),
    constraint fk_process_release_lifecycle_package foreign key (
        tenant_id, definition_key, release_version, release_package_hash
    ) references ap_approval_release_package (
        tenant_id, definition_key, release_version, package_hash
    ),
    constraint chk_process_release_lifecycle_text check (
        published_by <> ''
        and last_transition_by <> ''
        and last_transition_reason <> ''
        and last_idempotency_key <> ''
        and last_request_id <> ''
        and last_audit_chain_reference <> ''
    ),
    constraint chk_process_release_lifecycle_time check (
        last_transition_at >= published_at
        and (activated_at is null or activated_at >= published_at)
        and (deprecated_at is null or deprecated_at >= activated_at)
        and (retired_at is null or retired_at >= published_at)
    ),
    constraint chk_process_release_lifecycle_state_evidence check (
        (lifecycle_state = 'PUBLISHED'
            and activated_at is null and deprecated_at is null and retired_at is null)
        or (lifecycle_state = 'ACTIVE'
            and activated_at is not null and retired_at is null)
        or (lifecycle_state = 'DEPRECATED'
            and activated_at is not null and deprecated_at is not null
            and retired_at is null)
        or (lifecycle_state = 'RETIRED' and retired_at is not null)
    )
);

create unique index uk_process_release_single_active
    on ap_process_release_lifecycle (tenant_id, definition_key)
    where lifecycle_state = 'ACTIVE';

create index idx_process_release_lifecycle_list
    on ap_process_release_lifecycle (
        tenant_id,
        definition_key,
        release_version desc
    );

create index idx_process_release_lifecycle_state
    on ap_process_release_lifecycle (
        tenant_id,
        lifecycle_state,
        last_transition_at desc,
        definition_key,
        release_version desc
    );

create temporary table ap_v32_release_event (
    tenant_id varchar(128) not null,
    definition_key varchar(64) not null,
    release_version integer not null,
    release_package_hash char(64) not null,
    event_type varchar(16) not null,
    source_identity varchar(256) not null,
    happened_at timestamptz not null,
    event_sequence bigint not null,
    event_order integer not null,
    from_state varchar(16) not null,
    to_state varchar(16) not null,
    operator_id varchar(256) not null,
    reason varchar(1000) not null,
    idempotency_key varchar(200) not null,
    request_id varchar(256) not null,
    trace_id varchar(256),
    audit_chain_reference varchar(256) not null
) on commit drop;

insert into ap_v32_release_event (
    tenant_id,
    definition_key,
    release_version,
    release_package_hash,
    event_type,
    source_identity,
    happened_at,
    event_sequence,
    event_order,
    from_state,
    to_state,
    operator_id,
    reason,
    idempotency_key,
    request_id,
    trace_id,
    audit_chain_reference
)
select
    package.tenant_id,
    package.definition_key,
    package.release_version,
    package.package_hash,
    'PUBLISH',
    package.source_draft_id::text,
    package.published_at,
    0,
    0,
    'DRAFT',
    'PUBLISHED',
    package.published_by,
    'Release package was published before M4-E lifecycle adoption',
    'm4e-v32:publish:' || package.source_draft_id,
    'migration-v32-publish-' || package.source_draft_id,
    null,
    'migration:V32:release-package:' || package.package_hash
from ap_approval_release_package package;

insert into ap_v32_release_event (
    tenant_id,
    definition_key,
    release_version,
    release_package_hash,
    event_type,
    source_identity,
    happened_at,
    event_sequence,
    event_order,
    from_state,
    to_state,
    operator_id,
    reason,
    idempotency_key,
    request_id,
    trace_id,
    audit_chain_reference
)
select
    activation.tenant_id,
    activation.definition_key,
    activation.previous_release_version,
    previous.package_hash,
    'DEPRECATE',
    activation.activation_id::text,
    activation.activated_at,
    activation.revision,
    1,
    'ACTIVE',
    'DEPRECATED',
    activation.activated_by,
    left(
        'Superseded by activation of release '
            || activation.release_version || ': ' || activation.change_reason,
        1000
    ),
    'm4e-v32:deprecate:' || activation.activation_id,
    activation.request_id,
    activation.trace_id,
    'migration:V32:activation:' || activation.activation_id
from ap_approval_release_activation_history activation
join ap_approval_release_package previous
  on previous.tenant_id = activation.tenant_id
 and previous.definition_key = activation.definition_key
 and previous.release_version = activation.previous_release_version
where activation.previous_release_version is not null;

insert into ap_v32_release_event (
    tenant_id,
    definition_key,
    release_version,
    release_package_hash,
    event_type,
    source_identity,
    happened_at,
    event_sequence,
    event_order,
    from_state,
    to_state,
    operator_id,
    reason,
    idempotency_key,
    request_id,
    trace_id,
    audit_chain_reference
)
select
    activation.tenant_id,
    activation.definition_key,
    activation.release_version,
    activation.release_package_hash,
    'ACTIVATE',
    activation.activation_id::text,
    activation.activated_at,
    activation.revision,
    2,
    case
        when row_number() over (
            partition by activation.tenant_id, activation.definition_key,
                activation.release_version
            order by activation.revision, activation.activated_at,
                activation.activation_id
        ) = 1 then 'PUBLISHED'
        else 'DEPRECATED'
    end,
    'ACTIVE',
    activation.activated_by,
    activation.change_reason,
    'm4e-v32:activate:' || activation.activation_id,
    activation.request_id,
    activation.trace_id,
    'migration:V32:activation:' || activation.activation_id
from ap_approval_release_activation_history activation;

with ranked_release_events as (
    select
        event.*,
        row_number() over (
            partition by event.tenant_id, event.definition_key, event.release_version
            order by event.event_sequence, event.event_order, event.happened_at,
                event.source_identity
        ) as lifecycle_revision,
        min(case when event.to_state = 'ACTIVE' then event.happened_at end) over (
            partition by event.tenant_id, event.definition_key, event.release_version
        ) as activated_at,
        max(case when event.to_state = 'DEPRECATED' then event.happened_at end) over (
            partition by event.tenant_id, event.definition_key, event.release_version
        ) as deprecated_at
    from ap_v32_release_event event
),
current_release_event as (
    select distinct on (tenant_id, definition_key, release_version)
        tenant_id,
        definition_key,
        release_version,
        release_package_hash,
        to_state,
        lifecycle_revision,
        activated_at,
        deprecated_at,
        operator_id,
        happened_at,
        reason,
        idempotency_key,
        request_id,
        trace_id,
        audit_chain_reference
    from ranked_release_events
    order by tenant_id, definition_key, release_version,
        lifecycle_revision desc, source_identity desc
)
insert into ap_process_release_lifecycle (
    tenant_id,
    definition_key,
    release_version,
    release_package_hash,
    lifecycle_state,
    revision,
    published_by,
    published_at,
    activated_at,
    deprecated_at,
    retired_at,
    last_transition_by,
    last_transition_at,
    last_transition_reason,
    last_idempotency_key,
    last_request_id,
    last_trace_id,
    last_audit_chain_reference
)
select
    package.tenant_id,
    package.definition_key,
    package.release_version,
    package.package_hash,
    current_event.to_state,
    current_event.lifecycle_revision,
    package.published_by,
    package.published_at,
    current_event.activated_at,
    current_event.deprecated_at,
    null,
    current_event.operator_id,
    current_event.happened_at,
    current_event.reason,
    current_event.idempotency_key,
    current_event.request_id,
    current_event.trace_id,
    current_event.audit_chain_reference
from ap_approval_release_package package
join current_release_event current_event
  on current_event.tenant_id = package.tenant_id
 and current_event.definition_key = package.definition_key
 and current_event.release_version = package.release_version;

create table ap_process_release_lifecycle_history (
    transition_id uuid not null,
    tenant_id varchar(128) not null,
    definition_key varchar(64) not null,
    release_version integer not null check (release_version > 0),
    release_package_hash char(64) not null check (
        release_package_hash ~ '^[0-9a-f]{64}$'
    ),
    from_state varchar(16) not null,
    to_state varchar(16) not null,
    revision bigint not null check (revision > 0),
    reason varchar(1000) not null,
    idempotency_key varchar(200) not null,
    operator_id varchar(256) not null,
    request_id varchar(256) not null,
    trace_id varchar(256),
    audit_chain_reference varchar(256) not null,
    happened_at timestamptz not null,
    primary key (tenant_id, transition_id),
    constraint fk_process_release_history_lifecycle foreign key (
        tenant_id, definition_key, release_version
    ) references ap_process_release_lifecycle (
        tenant_id, definition_key, release_version
    ),
    constraint fk_process_release_history_package foreign key (
        tenant_id, definition_key, release_version, release_package_hash
    ) references ap_approval_release_package (
        tenant_id, definition_key, release_version, package_hash
    ),
    constraint uk_process_release_history_revision unique (
        tenant_id, definition_key, release_version, revision
    ),
    constraint uk_process_release_history_idempotency unique (
        tenant_id, idempotency_key
    ),
    constraint chk_process_release_history_transition check (
        (from_state = 'DRAFT' and to_state = 'PUBLISHED')
        or (from_state = 'PUBLISHED' and to_state in ('ACTIVE', 'RETIRED'))
        or (from_state = 'ACTIVE' and to_state = 'DEPRECATED')
        or (from_state = 'DEPRECATED' and to_state in ('ACTIVE', 'RETIRED'))
    ),
    constraint chk_process_release_history_text check (
        reason <> '' and idempotency_key <> '' and operator_id <> ''
        and request_id <> '' and audit_chain_reference <> ''
    )
);

create index idx_process_release_history_timeline
    on ap_process_release_lifecycle_history (
        tenant_id,
        definition_key,
        release_version,
        happened_at desc,
        revision desc
    );

create index idx_process_release_history_request
    on ap_process_release_lifecycle_history (
        tenant_id,
        request_id,
        happened_at desc,
        transition_id
    );

with ranked_release_events as (
    select
        event.*,
        row_number() over (
            partition by event.tenant_id, event.definition_key, event.release_version
            order by event.event_sequence, event.event_order, event.happened_at,
                event.source_identity
        ) as lifecycle_revision
    from ap_v32_release_event event
)
insert into ap_process_release_lifecycle_history (
    transition_id,
    tenant_id,
    definition_key,
    release_version,
    release_package_hash,
    from_state,
    to_state,
    revision,
    reason,
    idempotency_key,
    operator_id,
    request_id,
    trace_id,
    audit_chain_reference,
    happened_at
)
select
    md5(
        event.event_type || ':' || event.tenant_id || ':' || event.definition_key
        || ':' || event.release_version || ':' || event.source_identity
    )::uuid,
    event.tenant_id,
    event.definition_key,
    event.release_version,
    event.release_package_hash,
    event.from_state,
    event.to_state,
    event.lifecycle_revision,
    event.reason,
    event.idempotency_key,
    event.operator_id,
    event.request_id,
    event.trace_id,
    event.audit_chain_reference,
    event.happened_at
from ranked_release_events event;

do $$
begin
    if exists (
        select 1
        from ap_approval_effective_release effective
        left join ap_process_release_lifecycle lifecycle
          on lifecycle.tenant_id = effective.tenant_id
         and lifecycle.definition_key = effective.definition_key
         and lifecycle.release_version = effective.effective_release_version
         and lifecycle.release_package_hash = effective.release_package_hash
         and lifecycle.lifecycle_state = 'ACTIVE'
        where lifecycle.release_version is null
    ) or exists (
        select 1
        from ap_process_release_lifecycle lifecycle
        left join ap_approval_effective_release effective
          on effective.tenant_id = lifecycle.tenant_id
         and effective.definition_key = lifecycle.definition_key
         and effective.effective_release_version = lifecycle.release_version
         and effective.release_package_hash = lifecycle.release_package_hash
        where lifecycle.lifecycle_state = 'ACTIVE'
          and effective.effective_release_version is null
    ) then
        raise exception using
            errcode = '23514',
            message = 'V32 lifecycle backfill does not match the effective release projection';
    end if;
end;
$$;

create table ap_process_runtime_binding (
    tenant_id varchar(128) not null,
    approval_instance_id uuid not null,
    business_key varchar(256) not null,
    engine_instance_id varchar(256) not null,
    definition_key varchar(64) not null,
    release_version integer not null check (release_version > 0),
    release_package_hash char(64) not null check (
        release_package_hash ~ '^[0-9a-f]{64}$'
    ),
    definition_version integer not null check (definition_version > 0),
    definition_hash char(64) not null check (definition_hash ~ '^[0-9a-f]{64}$'),
    form_package_version integer not null check (form_package_version > 0),
    form_package_hash char(64) not null check (form_package_hash ~ '^[0-9a-f]{64}$'),
    form_version integer not null check (form_version > 0),
    form_hash char(64) not null check (form_hash ~ '^[0-9a-f]{64}$'),
    ui_schema_version integer not null check (ui_schema_version > 0),
    ui_schema_hash char(64) not null check (ui_schema_hash ~ '^[0-9a-f]{64}$'),
    compiler_version varchar(64) not null,
    compiled_artifact_hash char(64) not null check (
        compiled_artifact_hash ~ '^[0-9a-f]{64}$'
    ),
    bpmn_hash char(64) not null check (bpmn_hash ~ '^[0-9a-f]{64}$'),
    deployment_metadata_hash char(64) not null check (
        deployment_metadata_hash ~ '^[0-9a-f]{64}$'
    ),
    engine_deployment_id varchar(128) not null,
    engine_definition_id varchar(256) not null,
    engine_version integer not null check (engine_version > 0),
    binding_evidence_hash char(64) not null check (
        binding_evidence_hash ~ '^[0-9a-f]{64}$'
    ),
    bound_by varchar(256) not null,
    bound_at timestamptz not null,
    request_id varchar(256) not null,
    trace_id varchar(256),
    audit_chain_reference varchar(256) not null,
    primary key (tenant_id, approval_instance_id),
    constraint uk_process_runtime_binding_engine_instance unique (
        tenant_id, engine_instance_id
    ),
    constraint fk_process_runtime_binding_instance foreign key (
        tenant_id, approval_instance_id
    ) references ap_approval_instance (tenant_id, instance_id),
    constraint fk_process_runtime_binding_release foreign key (
        tenant_id, definition_key, release_version, release_package_hash
    ) references ap_approval_release_package (
        tenant_id, definition_key, release_version, package_hash
    ),
    constraint fk_process_runtime_binding_lifecycle foreign key (
        tenant_id, definition_key, release_version
    ) references ap_process_release_lifecycle (
        tenant_id, definition_key, release_version
    ),
    constraint chk_process_runtime_binding_text check (
        business_key <> '' and engine_instance_id <> '' and compiler_version <> ''
        and engine_deployment_id <> '' and engine_definition_id <> ''
        and bound_by <> '' and request_id <> '' and audit_chain_reference <> ''
    )
);

create index idx_process_runtime_binding_release_usage
    on ap_process_runtime_binding (
        tenant_id,
        definition_key,
        release_version,
        bound_at desc,
        approval_instance_id
    );

create index idx_process_runtime_binding_business_key
    on ap_process_runtime_binding (tenant_id, business_key, approval_instance_id);

insert into ap_process_runtime_binding (
    tenant_id,
    approval_instance_id,
    business_key,
    engine_instance_id,
    definition_key,
    release_version,
    release_package_hash,
    definition_version,
    definition_hash,
    form_package_version,
    form_package_hash,
    form_version,
    form_hash,
    ui_schema_version,
    ui_schema_hash,
    compiler_version,
    compiled_artifact_hash,
    bpmn_hash,
    deployment_metadata_hash,
    engine_deployment_id,
    engine_definition_id,
    engine_version,
    binding_evidence_hash,
    bound_by,
    bound_at,
    request_id,
    trace_id,
    audit_chain_reference
)
select
    instance.tenant_id,
    instance.instance_id,
    instance.business_key,
    instance.engine_instance_id,
    instance.definition_key,
    instance.release_version,
    instance.release_package_hash,
    package.definition_version,
    package.definition_hash,
    package.form_package_version,
    package.form_package_hash,
    package.form_version,
    package.form_hash,
    package.ui_schema_version,
    package.ui_schema_hash,
    package.compiler_version,
    package.compiled_artifact_hash,
    package.bpmn_hash,
    package.deployment_metadata_hash,
    deployment.engine_deployment_id,
    deployment.engine_definition_id,
    deployment.engine_version,
    encode(sha256(convert_to(concat_ws(
        chr(31),
        instance.tenant_id,
        instance.instance_id::text,
        instance.business_key,
        instance.engine_instance_id,
        instance.definition_key,
        instance.release_version::text,
        instance.release_package_hash,
        package.definition_version::text,
        package.definition_hash,
        package.form_package_version::text,
        package.form_package_hash,
        package.form_version::text,
        package.form_hash,
        package.ui_schema_version::text,
        package.ui_schema_hash,
        package.compiler_version,
        package.compiled_artifact_hash,
        package.bpmn_hash,
        package.deployment_metadata_hash,
        deployment.engine_deployment_id,
        deployment.engine_definition_id,
        deployment.engine_version::text
    ), 'UTF8')), 'hex'),
    instance.initiator_id,
    instance.created_at,
    'migration-v32-binding-' || instance.instance_id,
    null,
    'migration:V32:runtime-binding:' || instance.instance_id
from ap_approval_instance instance
join ap_approval_release_package package
  on package.tenant_id = instance.tenant_id
 and package.definition_key = instance.definition_key
 and package.release_version = instance.release_version
 and package.package_hash = instance.release_package_hash
 and package.definition_version = instance.definition_version
 and package.definition_hash = instance.content_hash
 and package.form_package_version = instance.form_package_version
 and package.form_package_hash = instance.form_package_hash
 and package.form_version = instance.form_version
 and package.ui_schema_version = instance.ui_schema_version
 and package.ui_schema_hash = instance.ui_schema_hash
 and package.compiler_version = instance.compiler_version
 and instance.form_key = package.definition_key
join ap_approval_release_deployment deployment
  on deployment.tenant_id = package.tenant_id
 and deployment.definition_key = package.definition_key
 and deployment.release_version = package.release_version
 and deployment.release_package_hash = package.package_hash
 and deployment.engine_definition_id = instance.engine_definition_id
 and deployment.status = 'DEPLOYED'
where instance.release_version is not null;

do $$
begin
    if exists (
        select 1
        from ap_approval_instance instance
        left join ap_process_runtime_binding binding
          on binding.tenant_id = instance.tenant_id
         and binding.approval_instance_id = instance.instance_id
        where instance.release_version is not null
          and binding.approval_instance_id is null
    ) then
        raise exception using
            errcode = '23514',
            message = 'V32 could not derive an exact runtime binding for a release-bound instance';
    end if;
end;
$$;

create function ap_guard_process_release_lifecycle_mutation()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'INSERT' then
        if new.lifecycle_state <> 'PUBLISHED'
            or new.revision <> 1
            or new.activated_at is not null
            or new.deprecated_at is not null
            or new.retired_at is not null then
            raise exception using
                errcode = '23514',
                message = 'new process releases must enter lifecycle as PUBLISHED';
        end if;
        return new;
    end if;
    if tg_op = 'DELETE' then
        raise exception using
            errcode = '55000',
            message = 'process release lifecycle records cannot be deleted';
    end if;
    if new.tenant_id is distinct from old.tenant_id
        or new.definition_key is distinct from old.definition_key
        or new.release_version is distinct from old.release_version
        or new.release_package_hash is distinct from old.release_package_hash
        or new.published_by is distinct from old.published_by
        or new.published_at is distinct from old.published_at then
        raise exception using
            errcode = '55000',
            message = 'published process release identity and evidence are immutable';
    end if;
    if new.last_transition_at < old.last_transition_at
        or new.last_idempotency_key is not distinct from old.last_idempotency_key then
        raise exception using
            errcode = '23514',
            message = 'process release transition evidence must advance';
    end if;
    if new.revision <> old.revision + 1 then
        raise exception using
            errcode = '40001',
            message = 'process release lifecycle revision must advance exactly once';
    end if;
    if not (
        (old.lifecycle_state = 'PUBLISHED'
            and new.lifecycle_state in ('ACTIVE', 'RETIRED'))
        or (old.lifecycle_state = 'ACTIVE'
            and new.lifecycle_state = 'DEPRECATED')
        or (old.lifecycle_state = 'DEPRECATED'
            and new.lifecycle_state in ('ACTIVE', 'RETIRED'))
    ) then
        raise exception using
            errcode = '23514',
            message = 'process release lifecycle transition is not permitted';
    end if;
    return new;
end;
$$;

create function ap_reject_process_version_evidence_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception using
        errcode = '55000',
        message = 'process release and runtime binding evidence is immutable';
end;
$$;

create trigger trg_process_release_lifecycle_guard
before insert or update or delete on ap_process_release_lifecycle
for each row execute function ap_guard_process_release_lifecycle_mutation();

create trigger trg_process_release_history_append_only
before update or delete on ap_process_release_lifecycle_history
for each row execute function ap_reject_process_version_evidence_mutation();

create trigger trg_process_runtime_binding_immutable
before update or delete on ap_process_runtime_binding
for each row execute function ap_reject_process_version_evidence_mutation();

create trigger trg_approval_release_package_immutable
before update or delete on ap_approval_release_package
for each row execute function ap_reject_process_version_evidence_mutation();

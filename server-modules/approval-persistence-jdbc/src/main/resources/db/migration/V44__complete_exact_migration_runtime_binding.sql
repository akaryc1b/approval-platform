-- M5-D5: exact-target runtime-binding CAS, per-instance completion and conflict evidence.
-- This migration creates no Flowable call, retry, scheduler, public endpoint or rollback semantics.

alter table ap_process_runtime_binding
 add column binding_revision bigint not null default 1,
 add column last_migration_attempt_id uuid,
 add column last_verification_id uuid,
 add column last_verification_evidence_hash char(64);

alter table ap_process_runtime_binding
 add constraint ck_process_runtime_binding_revision_v44 check (binding_revision>0),
 add constraint ck_process_runtime_binding_migration_lineage_v44 check (
  (binding_revision=1 and last_migration_attempt_id is null
   and last_verification_id is null and last_verification_evidence_hash is null)
  or
  (binding_revision>1 and last_migration_attempt_id is not null
   and last_verification_id is not null
   and last_verification_evidence_hash ~ '^[0-9a-f]{64}$')
 ),
 add constraint fk_process_runtime_binding_attempt_v44 foreign key (
  tenant_id,last_migration_attempt_id
 ) references ap_process_migration_attempt (tenant_id,attempt_id),
 add constraint fk_process_runtime_binding_verification_v44 foreign key (
  tenant_id,last_verification_id
 ) references ap_process_migration_exact_verification (tenant_id,verification_id);

create table ap_process_runtime_binding_evidence (
 tenant_id varchar(128) not null,
 binding_evidence_id uuid not null,
 approval_instance_id uuid not null,
 binding_revision bigint not null,
 attempt_id uuid,
 verification_id uuid,
 previous_binding_evidence_hash char(64),
 binding_evidence_hash char(64) not null,
 definition_key varchar(64) not null,
 release_version integer not null,
 release_package_hash char(64) not null,
 engine_deployment_id varchar(128) not null,
 engine_definition_id varchar(256) not null,
 engine_version integer not null,
 evidence_hash char(64) not null,
 recorded_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,binding_evidence_id),
 unique (tenant_id,approval_instance_id,binding_revision),
 unique (tenant_id,approval_instance_id,binding_evidence_hash),
 foreign key (tenant_id,approval_instance_id)
  references ap_process_runtime_binding (tenant_id,approval_instance_id),
 foreign key (tenant_id,attempt_id)
  references ap_process_migration_attempt (tenant_id,attempt_id),
 foreign key (tenant_id,verification_id)
  references ap_process_migration_exact_verification (tenant_id,verification_id),
 foreign key (tenant_id,definition_key,release_version,release_package_hash)
  references ap_approval_release_package (
   tenant_id,definition_key,release_version,package_hash
  ) deferrable initially deferred,
 check (binding_revision>0 and release_version>0 and engine_version>0),
 check (binding_evidence_hash ~ '^[0-9a-f]{64}$'
  and evidence_hash ~ '^[0-9a-f]{64}$'),
 check (previous_binding_evidence_hash is null
  or previous_binding_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (jsonb_typeof(payload_json)='object'),
 check (btrim(definition_key)<>'' and btrim(engine_deployment_id)<>''
  and btrim(engine_definition_id)<>'' and btrim(request_id)<>''),
 check ((binding_revision=1 and attempt_id is null and verification_id is null
   and previous_binding_evidence_hash is null)
  or (binding_revision>1 and attempt_id is not null and verification_id is not null
   and previous_binding_evidence_hash is not null))
);

create index idx_process_runtime_binding_evidence_instance_v44
 on ap_process_runtime_binding_evidence (
  tenant_id,approval_instance_id,binding_revision,binding_evidence_id
 );
create index idx_process_runtime_binding_evidence_attempt_v44
 on ap_process_runtime_binding_evidence (tenant_id,attempt_id,recorded_at);

create table ap_process_migration_instance_completion (
 tenant_id varchar(128) not null,
 completion_id uuid not null,
 intent_id uuid not null,
 attempt_id uuid not null,
 approval_instance_id uuid not null,
 verification_id uuid not null,
 binding_evidence_id uuid not null,
 binding_revision bigint not null,
 expected_attempt_revision bigint not null,
 expected_fence_revision bigint not null,
 worker_id varchar(200) not null,
 request_hash char(64) not null,
 source_binding_evidence_hash char(64) not null,
 target_binding_evidence_hash char(64) not null,
 source_release_version integer not null,
 source_package_hash char(64) not null,
 source_engine_definition_id varchar(256) not null,
 target_release_version integer not null,
 target_package_hash char(64) not null,
 target_engine_deployment_id varchar(128) not null,
 target_engine_definition_id varchar(256) not null,
 verification_evidence_hash char(64) not null,
 completion_evidence_hash char(64) not null,
 completed_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,completion_id),
 unique (tenant_id,attempt_id),
 unique (tenant_id,verification_id),
 unique (tenant_id,request_hash),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,attempt_id)
  references ap_process_migration_attempt (tenant_id,attempt_id),
 foreign key (tenant_id,verification_id)
  references ap_process_migration_exact_verification (tenant_id,verification_id),
 foreign key (tenant_id,binding_evidence_id)
  references ap_process_runtime_binding_evidence (tenant_id,binding_evidence_id),
 check (binding_revision>1 and expected_attempt_revision>0 and expected_fence_revision>0),
 check (source_release_version>0 and target_release_version>0
  and source_release_version<>target_release_version),
 check (source_binding_evidence_hash ~ '^[0-9a-f]{64}$'
  and target_binding_evidence_hash ~ '^[0-9a-f]{64}$'
  and source_binding_evidence_hash<>target_binding_evidence_hash),
 check (source_package_hash ~ '^[0-9a-f]{64}$'
  and target_package_hash ~ '^[0-9a-f]{64}$'
  and source_package_hash<>target_package_hash),
 check (verification_evidence_hash ~ '^[0-9a-f]{64}$'
  and request_hash ~ '^[0-9a-f]{64}$'
  and completion_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (source_engine_definition_id<>target_engine_definition_id),
 check (btrim(worker_id)<>'' and btrim(target_engine_deployment_id)<>''
  and btrim(source_engine_definition_id)<>'' and btrim(target_engine_definition_id)<>''
  and btrim(request_id)<>'' and jsonb_typeof(payload_json)='object')
);

create index idx_process_migration_instance_completion_intent_v44
 on ap_process_migration_instance_completion (tenant_id,intent_id,completed_at,attempt_id);

create table ap_process_migration_binding_cas_conflict (
 tenant_id varchar(128) not null,
 conflict_id uuid not null,
 intent_id uuid not null,
 attempt_id uuid not null,
 approval_instance_id uuid not null,
 verification_id uuid not null,
 worker_id varchar(200) not null,
 expected_attempt_revision bigint not null,
 expected_fence_revision bigint not null,
 expected_binding_revision bigint not null,
 expected_binding_evidence_hash char(64) not null,
 expected_source_release_version integer not null,
 expected_source_package_hash char(64) not null,
 expected_source_engine_definition_id varchar(256) not null,
 observed_binding_revision bigint,
 observed_binding_evidence_hash char(64),
 observed_release_version integer,
 observed_package_hash char(64),
 observed_engine_definition_id varchar(256),
 verification_evidence_hash char(64) not null,
 request_hash char(64) not null,
 conflict_evidence_hash char(64) not null,
 recorded_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,conflict_id),
 unique (tenant_id,attempt_id),
 unique (tenant_id,request_hash),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,attempt_id)
  references ap_process_migration_attempt (tenant_id,attempt_id),
 foreign key (tenant_id,verification_id)
  references ap_process_migration_exact_verification (tenant_id,verification_id),
 check (expected_attempt_revision>0 and expected_fence_revision>0
  and expected_binding_revision>0 and expected_source_release_version>0),
 check (expected_binding_evidence_hash ~ '^[0-9a-f]{64}$'
  and expected_source_package_hash ~ '^[0-9a-f]{64}$'
  and verification_evidence_hash ~ '^[0-9a-f]{64}$'
  and request_hash ~ '^[0-9a-f]{64}$'
  and conflict_evidence_hash ~ '^[0-9a-f]{64}$'),
 check ((observed_binding_revision is null and observed_binding_evidence_hash is null
   and observed_release_version is null and observed_package_hash is null
   and observed_engine_definition_id is null)
  or (observed_binding_revision>0
   and observed_binding_evidence_hash ~ '^[0-9a-f]{64}$'
   and observed_release_version>0 and observed_package_hash ~ '^[0-9a-f]{64}$'
   and btrim(observed_engine_definition_id)<>'')),
 check (btrim(worker_id)<>'' and btrim(expected_source_engine_definition_id)<>''
  and btrim(request_id)<>'' and jsonb_typeof(payload_json)='object')
);

create index idx_process_migration_binding_cas_conflict_intent_v44
 on ap_process_migration_binding_cas_conflict (tenant_id,intent_id,recorded_at,attempt_id);

create function ap_runtime_binding_hash_v44(value ap_process_runtime_binding)
returns char(64) language sql immutable as $$
 select encode(sha256(convert_to(concat_ws(
  chr(31),
  'm5-runtime-binding-v44',
  value.tenant_id,
  value.approval_instance_id::text,
  value.business_key,
  value.engine_instance_id,
  value.definition_key,
  value.release_version::text,
  value.release_package_hash,
  value.definition_version::text,
  value.definition_hash,
  value.form_package_version::text,
  value.form_package_hash,
  value.form_version::text,
  value.form_hash,
  value.ui_schema_version::text,
  value.ui_schema_hash,
  value.compiler_version,
  value.compiled_artifact_hash,
  value.bpmn_hash,
  value.deployment_metadata_hash,
  value.engine_deployment_id,
  value.engine_definition_id,
  value.engine_version::text,
  value.binding_revision::text,
  value.last_migration_attempt_id::text,
  value.last_verification_id::text,
  value.last_verification_evidence_hash,
  value.bound_by,
  value.bound_at::text,
  value.request_id,
  coalesce(value.trace_id,''),
  value.audit_chain_reference
 ),'UTF8')),'hex')::char(64)
$$;

insert into ap_process_runtime_binding_evidence (
 tenant_id,binding_evidence_id,approval_instance_id,binding_revision,
 attempt_id,verification_id,previous_binding_evidence_hash,binding_evidence_hash,
 definition_key,release_version,release_package_hash,engine_deployment_id,
 engine_definition_id,engine_version,evidence_hash,recorded_at,request_id,trace_id,payload_json
)
select binding.tenant_id,gen_random_uuid(),binding.approval_instance_id,1,
 null,null,null,binding.binding_evidence_hash,binding.definition_key,
 binding.release_version,binding.release_package_hash,binding.engine_deployment_id,
 binding.engine_definition_id,binding.engine_version,
 encode(sha256(convert_to(concat_ws(chr(31),
  'm5-runtime-binding-history-v44',binding.tenant_id,binding.approval_instance_id::text,
  '1',binding.binding_evidence_hash,binding.definition_key
 ),'UTF8')),'hex'),
 binding.bound_at,binding.request_id,binding.trace_id,
 jsonb_build_object(
  'tenantId',binding.tenant_id,
  'approvalInstanceId',binding.approval_instance_id,
  'bindingRevision',1,
  'attemptId',null,
  'verificationId',null,
  'previousBindingEvidenceHash',null,
  'bindingEvidenceHash',binding.binding_evidence_hash,
  'definitionKey',binding.definition_key,
  'releaseVersion',binding.release_version,
  'releasePackageHash',binding.release_package_hash,
  'engineDeploymentId',binding.engine_deployment_id,
  'engineDefinitionId',binding.engine_definition_id,
  'engineVersion',binding.engine_version,
  'evidenceHash',encode(sha256(convert_to(concat_ws(chr(31),
   'm5-runtime-binding-history-v44',binding.tenant_id,binding.approval_instance_id::text,
   '1',binding.binding_evidence_hash,binding.definition_key
  ),'UTF8')),'hex'),
  'recordedAt',binding.bound_at,
  'requestId',binding.request_id,
  'traceId',binding.trace_id
 )
from ap_process_runtime_binding binding;

create function ap_append_runtime_binding_evidence_v44()
returns trigger language plpgsql as $$
declare previous_hash char(64);
 evidence_value char(64);
begin
 previous_hash:=case when tg_op='UPDATE' then old.binding_evidence_hash else null end;
 evidence_value:=encode(sha256(convert_to(concat_ws(chr(31),
  'm5-runtime-binding-history-v44',new.tenant_id,new.approval_instance_id::text,
  new.binding_revision::text,new.binding_evidence_hash,new.definition_key,
  coalesce(previous_hash,''),coalesce(new.last_migration_attempt_id::text,''),
  coalesce(new.last_verification_id::text,'')
 ),'UTF8')),'hex');
 insert into ap_process_runtime_binding_evidence (
  tenant_id,binding_evidence_id,approval_instance_id,binding_revision,
  attempt_id,verification_id,previous_binding_evidence_hash,binding_evidence_hash,
  definition_key,release_version,release_package_hash,engine_deployment_id,
  engine_definition_id,engine_version,evidence_hash,recorded_at,request_id,trace_id,payload_json
 ) values (
  new.tenant_id,gen_random_uuid(),new.approval_instance_id,new.binding_revision,
  new.last_migration_attempt_id,new.last_verification_id,previous_hash,new.binding_evidence_hash,
  new.definition_key,new.release_version,new.release_package_hash,new.engine_deployment_id,
  new.engine_definition_id,new.engine_version,evidence_value,new.bound_at,new.request_id,new.trace_id,
  jsonb_build_object(
   'tenantId',new.tenant_id,
   'approvalInstanceId',new.approval_instance_id,
   'bindingRevision',new.binding_revision,
   'attemptId',new.last_migration_attempt_id,
   'verificationId',new.last_verification_id,
   'previousBindingEvidenceHash',previous_hash,
   'bindingEvidenceHash',new.binding_evidence_hash,
   'definitionKey',new.definition_key,
   'releaseVersion',new.release_version,
   'releasePackageHash',new.release_package_hash,
   'engineDeploymentId',new.engine_deployment_id,
   'engineDefinitionId',new.engine_definition_id,
   'engineVersion',new.engine_version,
   'evidenceHash',evidence_value,
   'recordedAt',new.bound_at,
   'requestId',new.request_id,
   'traceId',new.trace_id
  )
 );
 return null;
end $$;

drop trigger trg_process_runtime_binding_immutable on ap_process_runtime_binding;

create function ap_guard_process_runtime_binding_v44()
returns trigger language plpgsql as $$
declare attempt_row ap_process_migration_attempt%rowtype;
 verification_row ap_process_migration_exact_verification%rowtype;
 plan_row ap_process_migration_plan%rowtype;
 package_row ap_approval_release_package%rowtype;
 deployment_row ap_approval_release_deployment%rowtype;
 fence_row ap_approval_instance_command_fence%rowtype;
begin
 if tg_op='DELETE' then
  raise exception using errcode='55000',message='process runtime binding cannot be deleted';
 end if;
 if tg_op='INSERT' then
  if new.binding_revision<>1 or new.last_migration_attempt_id is not null
   or new.last_verification_id is not null or new.last_verification_evidence_hash is not null then
   raise exception using errcode='23514',message='new runtime binding must begin revision 1';
  end if;
  return new;
 end if;
 if new.tenant_id is distinct from old.tenant_id
  or new.approval_instance_id is distinct from old.approval_instance_id
  or new.business_key is distinct from old.business_key
  or new.engine_instance_id is distinct from old.engine_instance_id
  or new.definition_key is distinct from old.definition_key then
  raise exception using errcode='55000',message='runtime binding instance identity is immutable';
 end if;
 if new.binding_revision<>old.binding_revision+1 then
  raise exception using errcode='40001',message='runtime binding revision is stale';
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.last_migration_attempt_id for update;
 if not found then
  raise exception using errcode='23514',message='runtime binding update attempt does not exist';
 end if;
 select * into verification_row from ap_process_migration_exact_verification
  where tenant_id=new.tenant_id and verification_id=new.last_verification_id;
 if not found
  or attempt_row.approval_instance_id<>new.approval_instance_id
  or attempt_row.status<>'VERIFYING'
  or attempt_row.revision<>verification_row.expected_attempt_revision
  or attempt_row.expected_binding_evidence_hash<>old.binding_evidence_hash
  or verification_row.attempt_id<>attempt_row.attempt_id
  or verification_row.classification<>'EXACT_TARGET_RUNTIME'
  or not verification_row.read_succeeded or verification_row.truncated
  or verification_row.verification_evidence_hash<>new.last_verification_evidence_hash
  or verification_row.target_engine_definition_id<>new.engine_definition_id then
  raise exception using errcode='23514',message='runtime binding update requires exact target verification';
 end if;
 select p.* into plan_row from ap_process_migration_intent i
  join ap_process_migration_plan p
   on p.tenant_id=i.tenant_id and p.plan_id=i.plan_id and p.plan_hash=i.plan_hash
  join ap_process_migration_plan_consumption c
   on c.tenant_id=i.tenant_id and c.intent_id=i.intent_id and c.plan_id=i.plan_id
  where i.tenant_id=new.tenant_id and i.intent_id=attempt_row.intent_id
   and i.status='RUNNING' and p.status='CONSUMED' for update of i,p;
 if not found or old.release_version<>plan_row.source_release_version
  or old.release_package_hash<>plan_row.source_package_hash
  or old.engine_definition_id<>attempt_row.payload_json->>'sourceEngineDefinitionId'
  or new.release_version<>plan_row.target_release_version
  or new.release_package_hash<>plan_row.target_package_hash
  or new.engine_deployment_id<>plan_row.target_engine_deployment_id
  or new.engine_definition_id<>plan_row.target_engine_definition_id then
  raise exception using errcode='23514',message='runtime binding source or target plan identity is stale';
 end if;
 select * into package_row from ap_approval_release_package
  where tenant_id=new.tenant_id and definition_key=new.definition_key
   and release_version=new.release_version and package_hash=new.release_package_hash;
 select * into deployment_row from ap_approval_release_deployment
  where tenant_id=new.tenant_id and definition_key=new.definition_key
   and release_version=new.release_version and release_package_hash=new.release_package_hash
   and engine_deployment_id=new.engine_deployment_id
   and engine_definition_id=new.engine_definition_id and status='DEPLOYED';
 if package_row.release_version is null or deployment_row.release_version is null
  or new.definition_version<>package_row.definition_version
  or new.definition_hash<>package_row.definition_hash
  or new.form_package_version<>package_row.form_package_version
  or new.form_package_hash<>package_row.form_package_hash
  or new.form_version<>package_row.form_version
  or new.form_hash<>package_row.form_hash
  or new.ui_schema_version<>package_row.ui_schema_version
  or new.ui_schema_hash<>package_row.ui_schema_hash
  or new.compiler_version<>package_row.compiler_version
  or new.compiled_artifact_hash<>package_row.compiled_artifact_hash
  or new.bpmn_hash<>package_row.bpmn_hash
  or new.deployment_metadata_hash<>package_row.deployment_metadata_hash
  or new.engine_version<>deployment_row.engine_version then
  raise exception using errcode='23514',message='runtime binding target release evidence mismatch';
 end if;
 select * into fence_row from ap_approval_instance_command_fence
  where tenant_id=new.tenant_id and attempt_id=attempt_row.attempt_id for update;
 if not found or fence_row.status<>'ACTIVE'
  or fence_row.revision<>verification_row.expected_fence_revision
  or fence_row.lease_owner<>verification_row.worker_id
  or fence_row.lease_until<=new.bound_at
  or new.bound_by<>verification_row.worker_id
  or new.bound_at<verification_row.recorded_at
  or new.audit_chain_reference<>'migration-verification:'||verification_row.verification_id::text
  or new.binding_evidence_hash<>ap_runtime_binding_hash_v44(new) then
  raise exception using errcode='23514',message='runtime binding CAS authority or hash mismatch';
 end if;
 return new;
end $$;

create trigger trg_process_runtime_binding_guard_v44
 before insert or update or delete on ap_process_runtime_binding
 for each row execute function ap_guard_process_runtime_binding_v44();

create trigger trg_process_runtime_binding_evidence_append_v44
 after insert or update on ap_process_runtime_binding
 for each row execute function ap_append_runtime_binding_evidence_v44();

create function ap_require_runtime_binding_projection_v44()
returns trigger language plpgsql as $$
begin
 if not exists (
  select 1 from ap_approval_instance instance
  where instance.tenant_id=new.tenant_id
   and instance.instance_id=new.approval_instance_id
   and instance.business_key=new.business_key
   and instance.engine_instance_id=new.engine_instance_id
   and instance.definition_key=new.definition_key
   and instance.release_version=new.release_version
   and instance.release_package_hash=new.release_package_hash
   and instance.definition_version=new.definition_version
   and instance.content_hash=new.definition_hash
   and instance.form_package_version=new.form_package_version
   and instance.form_package_hash=new.form_package_hash
   and instance.form_version=new.form_version
   and instance.ui_schema_version=new.ui_schema_version
   and instance.ui_schema_hash=new.ui_schema_hash
   and instance.compiler_version=new.compiler_version
   and instance.engine_definition_id=new.engine_definition_id
 ) then
  raise exception using errcode='23514',message='runtime binding requires matching approval instance release projection';
 end if;
 return null;
end $$;

create constraint trigger trg_process_runtime_binding_projection_required_v44
 after insert or update on ap_process_runtime_binding
 deferrable initially deferred
 for each row execute function ap_require_runtime_binding_projection_v44();

create function ap_guard_runtime_binding_evidence_v44()
returns trigger language plpgsql as $$
declare binding_row ap_process_runtime_binding%rowtype;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='runtime binding revision evidence is append-only';
 end if;
 select * into binding_row from ap_process_runtime_binding
  where tenant_id=new.tenant_id and approval_instance_id=new.approval_instance_id;
 if not found or binding_row.binding_revision<>new.binding_revision
  or binding_row.binding_evidence_hash<>new.binding_evidence_hash
  or binding_row.definition_key<>new.definition_key
  or binding_row.release_version<>new.release_version
  or binding_row.release_package_hash<>new.release_package_hash
  or binding_row.engine_deployment_id<>new.engine_deployment_id
  or binding_row.engine_definition_id<>new.engine_definition_id
  or binding_row.engine_version<>new.engine_version
  or binding_row.last_migration_attempt_id is distinct from new.attempt_id
  or binding_row.last_verification_id is distinct from new.verification_id then
  raise exception using errcode='23514',message='runtime binding evidence does not match current revision';
 end if;
 return new;
end $$;

create trigger trg_process_runtime_binding_evidence_guard_v44
 before insert or update or delete on ap_process_runtime_binding_evidence
 for each row execute function ap_guard_runtime_binding_evidence_v44();

create function ap_guard_migration_instance_completion_v44()
returns trigger language plpgsql as $$
declare attempt_row ap_process_migration_attempt%rowtype;
 verification_row ap_process_migration_exact_verification%rowtype;
 binding_row ap_process_runtime_binding%rowtype;
 evidence_row ap_process_runtime_binding_evidence%rowtype;
 fence_row ap_approval_instance_command_fence%rowtype;
 expected_hash char(64);
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='migration instance completion evidence is append-only';
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 select * into verification_row from ap_process_migration_exact_verification
  where tenant_id=new.tenant_id and verification_id=new.verification_id;
 select * into binding_row from ap_process_runtime_binding
  where tenant_id=new.tenant_id and approval_instance_id=new.approval_instance_id for update;
 select * into evidence_row from ap_process_runtime_binding_evidence
  where tenant_id=new.tenant_id and binding_evidence_id=new.binding_evidence_id;
 select * into fence_row from ap_approval_instance_command_fence
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 if attempt_row.attempt_id is null or verification_row.verification_id is null
  or binding_row.approval_instance_id is null or evidence_row.binding_evidence_id is null
  or fence_row.fence_id is null
  or attempt_row.intent_id<>new.intent_id
  or attempt_row.approval_instance_id<>new.approval_instance_id
  or attempt_row.status<>'VERIFYING' or attempt_row.revision<>new.expected_attempt_revision
  or verification_row.attempt_id<>new.attempt_id
  or verification_row.classification<>'EXACT_TARGET_RUNTIME'
  or verification_row.verification_evidence_hash<>new.verification_evidence_hash
  or binding_row.binding_revision<>new.binding_revision
  or binding_row.binding_evidence_hash<>new.target_binding_evidence_hash
  or binding_row.last_migration_attempt_id<>new.attempt_id
  or binding_row.last_verification_id<>new.verification_id
  or evidence_row.binding_revision<>new.binding_revision
  or evidence_row.binding_evidence_hash<>new.target_binding_evidence_hash
  or evidence_row.previous_binding_evidence_hash<>new.source_binding_evidence_hash
  or fence_row.status<>'ACTIVE' or fence_row.revision<>new.expected_fence_revision
  or fence_row.lease_owner<>new.worker_id or fence_row.lease_until<=new.completed_at then
  raise exception using errcode='23514',message='migration completion lineage or CAS evidence mismatch';
 end if;
 expected_hash:=encode(sha256(convert_to(concat_ws(chr(31),
  'm5-instance-completion-v44',new.completion_id::text,new.tenant_id,new.intent_id::text,
  new.attempt_id::text,new.approval_instance_id::text,new.verification_id::text,
  new.binding_evidence_id::text,new.binding_revision::text,new.request_hash,
  new.source_binding_evidence_hash,new.target_binding_evidence_hash,
  new.source_release_version::text,new.source_package_hash,new.source_engine_definition_id,
  new.target_release_version::text,new.target_package_hash,new.target_engine_deployment_id,
  new.target_engine_definition_id,new.verification_evidence_hash
 ),'UTF8')),'hex');
 if new.completion_evidence_hash<>expected_hash then
  raise exception using errcode='23514',message='migration completion evidence hash mismatch';
 end if;
 return new;
end $$;

create trigger trg_process_migration_instance_completion_guard_v44
 before insert or update or delete on ap_process_migration_instance_completion
 for each row execute function ap_guard_migration_instance_completion_v44();

create function ap_require_migration_instance_completion_final_v44()
returns trigger language plpgsql as $$
begin
 if not exists (
  select 1 from ap_process_migration_attempt attempt
  join ap_approval_instance_command_fence fence
   on fence.tenant_id=attempt.tenant_id and fence.attempt_id=attempt.attempt_id
  join ap_process_runtime_binding binding
   on binding.tenant_id=attempt.tenant_id
   and binding.approval_instance_id=attempt.approval_instance_id
  where attempt.tenant_id=new.tenant_id and attempt.attempt_id=new.attempt_id
   and attempt.status='SUCCEEDED' and attempt.engine_outcome='CONFIRMED'
   and attempt.revision=new.expected_attempt_revision+1
   and fence.status='RELEASED' and fence.revision=new.expected_fence_revision+1
   and binding.binding_revision=new.binding_revision
   and binding.binding_evidence_hash=new.target_binding_evidence_hash
 ) then
  raise exception using errcode='23514',message='migration completion requires succeeded attempt and released fence';
 end if;
 return null;
end $$;

create constraint trigger trg_process_migration_instance_completion_final_v44
 after insert on ap_process_migration_instance_completion
 deferrable initially deferred
 for each row execute function ap_require_migration_instance_completion_final_v44();

create function ap_guard_migration_binding_cas_conflict_v44()
returns trigger language plpgsql as $$
declare attempt_row ap_process_migration_attempt%rowtype;
 verification_row ap_process_migration_exact_verification%rowtype;
 binding_row ap_process_runtime_binding%rowtype;
 fence_row ap_approval_instance_command_fence%rowtype;
 expected_hash char(64);
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='migration binding CAS conflict evidence is append-only';
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 select * into verification_row from ap_process_migration_exact_verification
  where tenant_id=new.tenant_id and verification_id=new.verification_id;
 select * into binding_row from ap_process_runtime_binding
  where tenant_id=new.tenant_id and approval_instance_id=new.approval_instance_id for update;
 select * into fence_row from ap_approval_instance_command_fence
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 if attempt_row.attempt_id is null or verification_row.verification_id is null
  or fence_row.fence_id is null or attempt_row.intent_id<>new.intent_id
  or attempt_row.approval_instance_id<>new.approval_instance_id
  or attempt_row.status<>'VERIFYING' or attempt_row.revision<>new.expected_attempt_revision
  or verification_row.attempt_id<>new.attempt_id
  or verification_row.classification<>'EXACT_TARGET_RUNTIME'
  or verification_row.worker_id<>new.worker_id
  or verification_row.verification_evidence_hash<>new.verification_evidence_hash
  or fence_row.status<>'ACTIVE' or fence_row.revision<>new.expected_fence_revision
  or fence_row.lease_owner<>new.worker_id or fence_row.lease_until<=new.recorded_at then
  raise exception using errcode='23514',message='migration binding CAS conflict authority mismatch';
 end if;
 if binding_row.approval_instance_id is null then
  if new.observed_binding_revision is not null then
   raise exception using errcode='23514',message='missing binding conflict cannot claim observed binding';
  end if;
 elsif new.observed_binding_revision is distinct from binding_row.binding_revision
  or new.observed_binding_evidence_hash is distinct from binding_row.binding_evidence_hash
  or new.observed_release_version is distinct from binding_row.release_version
  or new.observed_package_hash is distinct from binding_row.release_package_hash
  or new.observed_engine_definition_id is distinct from binding_row.engine_definition_id then
  raise exception using errcode='23514',message='CAS conflict observed binding evidence mismatch';
 end if;
 expected_hash:=encode(sha256(convert_to(concat_ws(chr(31),
  'm5-binding-cas-conflict-v44',new.conflict_id::text,new.tenant_id,new.intent_id::text,
  new.attempt_id::text,new.approval_instance_id::text,new.verification_id::text,
  new.worker_id,new.expected_attempt_revision::text,new.expected_fence_revision::text,
  new.expected_binding_revision::text,new.expected_binding_evidence_hash,
  new.expected_source_release_version::text,new.expected_source_package_hash,
  new.expected_source_engine_definition_id,coalesce(new.observed_binding_revision::text,''),
  coalesce(new.observed_binding_evidence_hash,''),coalesce(new.observed_release_version::text,''),
  coalesce(new.observed_package_hash,''),coalesce(new.observed_engine_definition_id,''),
  new.verification_evidence_hash,new.request_hash
 ),'UTF8')),'hex');
 if new.conflict_evidence_hash<>expected_hash then
  raise exception using errcode='23514',message='binding CAS conflict evidence hash mismatch';
 end if;
 return new;
end $$;

create trigger trg_process_migration_binding_cas_conflict_guard_v44
 before insert or update or delete on ap_process_migration_binding_cas_conflict
 for each row execute function ap_guard_migration_binding_cas_conflict_v44();

create function ap_require_migration_binding_cas_conflict_final_v44()
returns trigger language plpgsql as $$
begin
 if not exists (
  select 1 from ap_process_migration_attempt attempt
  where attempt.tenant_id=new.tenant_id and attempt.attempt_id=new.attempt_id
   and attempt.status='RECONCILING'
   and attempt.engine_outcome='VERIFICATION_MISMATCH'
   and attempt.revision=new.expected_attempt_revision+1
 ) then
  raise exception using errcode='23514',message='binding CAS conflict requires reconciliation state';
 end if;
 return null;
end $$;

create constraint trigger trg_process_migration_binding_cas_conflict_final_v44
 after insert on ap_process_migration_binding_cas_conflict
 deferrable initially deferred
 for each row execute function ap_require_migration_binding_cas_conflict_final_v44();

create or replace function ap_guard_approval_instance_command_fence_v40()
returns trigger language plpgsql as $$
declare attempt_row ap_process_migration_attempt%rowtype;
begin
 if tg_op='DELETE' then
  raise exception using errcode='55000',message='approval instance command fence cannot be deleted';
 end if;
 if (new.payload_json->>'fenceId')::uuid is distinct from new.fence_id
  or (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'approvalInstanceId')::uuid is distinct from new.approval_instance_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'operation') is distinct from new.operation
  or (new.payload_json->>'status') is distinct from new.status
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'leaseOwner') is distinct from new.lease_owner
  or ap_migration_json_instant_v37(new.payload_json,'leaseUntil') is distinct from new.lease_until
  or (new.payload_json->>'idempotencyKey') is distinct from new.idempotency_key
  or (new.payload_json->>'requestHash') is distinct from new.request_hash
  or ap_migration_json_instant_v37(new.payload_json,'acquiredAt') is distinct from new.acquired_at
  or ap_migration_json_instant_v37(new.payload_json,'updatedAt') is distinct from new.updated_at
  or ap_migration_json_instant_v37(new.payload_json,'releasedAt') is distinct from new.released_at
  or (new.payload_json->>'requestId') is distinct from new.request_id
  or (new.payload_json->>'traceId') is distinct from new.trace_id then
  raise exception using errcode='23514',message='approval instance command fence payload does not match durable columns';
 end if;
 if tg_op='INSERT' then
  if new.status<>'ACTIVE' or new.revision<>1 then
   raise exception using errcode='23514',message='command fence must begin ACTIVE revision 1';
  end if;
 else
  if new.tenant_id is distinct from old.tenant_id or new.fence_id is distinct from old.fence_id
   or new.approval_instance_id is distinct from old.approval_instance_id
   or new.attempt_id is distinct from old.attempt_id or new.operation is distinct from old.operation
   or new.idempotency_key is distinct from old.idempotency_key
   or new.request_hash is distinct from old.request_hash or new.acquired_at is distinct from old.acquired_at
   or new.request_id is distinct from old.request_id or new.trace_id is distinct from old.trace_id then
   raise exception using errcode='55000',message='approval instance command fence identity is immutable';
  end if;
  if new.revision<>old.revision+1 or old.status<>'ACTIVE' then
   raise exception using errcode='40001',message='command fence revision or source status is stale';
  end if;
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if not found or attempt_row.approval_instance_id<>new.approval_instance_id then
  raise exception using errcode='23514',message='command fence attempt lineage mismatch';
 end if;
 if tg_op='INSERT' or new.status='ACTIVE' then
  if attempt_row.status<>'CLAIMED' or attempt_row.lease_owner<>new.lease_owner
   or attempt_row.lease_until<>new.lease_until then
   raise exception using errcode='23514',message='active command fence must match claimed attempt lease';
  end if;
  if tg_op='UPDATE' then
   if new.lease_owner=old.lease_owner then
    if new.updated_at>=old.lease_until or new.lease_until<=old.lease_until then
     raise exception using errcode='23514',message='same-owner fence renewal requires current ownership and extension';
    end if;
   elsif new.updated_at<old.lease_until then
    raise exception using errcode='23514',message='command fence takeover requires expiry';
   end if;
  end if;
 elsif new.status='RELEASED' then
  if attempt_row.status<>'SUCCEEDED'
   or new.lease_owner<>old.lease_owner or new.lease_until<>old.lease_until
   or new.updated_at>=old.lease_until or new.released_at<>new.updated_at
   or not exists (
    select 1 from ap_process_migration_instance_completion completion
    where completion.tenant_id=new.tenant_id and completion.attempt_id=new.attempt_id
   ) then
   raise exception using errcode='23514',message='command fence release requires exact completed migration';
  end if;
 else
  raise exception using errcode='23514',message='command fence transition is not permitted';
 end if;
 return new;
end $$;

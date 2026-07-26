-- M5-D3: immutable one-instance engine request and outcome evidence.
-- This migration creates no scheduler, endpoint, retry authority or runtime-binding mutation.

create table ap_process_migration_engine_request (
 tenant_id varchar(128) not null,
 engine_request_id uuid not null,
 intent_id uuid not null,
 attempt_id uuid not null,
 approval_instance_id uuid not null,
 worker_id varchar(200) not null,
 attempt_revision bigint not null,
 fence_id uuid not null,
 fence_revision bigint not null,
 engine_instance_id varchar(256) not null,
 source_binding_evidence_hash char(64) not null,
 source_engine_definition_id varchar(256) not null,
 target_release_version integer not null,
 target_package_hash char(64) not null,
 target_engine_deployment_id varchar(256) not null,
 target_engine_definition_id varchar(256) not null,
 activity_mapping_json jsonb not null,
 request_hash char(64) not null,
 evidence_hash char(64) not null,
 requested_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,engine_request_id),
 unique (tenant_id,attempt_id),
 unique (tenant_id,request_hash),
 foreign key (tenant_id,intent_id) references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,attempt_id) references ap_process_migration_attempt (tenant_id,attempt_id),
 foreign key (tenant_id,fence_id) references ap_approval_instance_command_fence (tenant_id,fence_id),
 check (attempt_revision>0 and fence_revision>0 and target_release_version>0),
 check (source_binding_evidence_hash ~ '^[0-9a-f]{64}$'
  and target_package_hash ~ '^[0-9a-f]{64}$'
  and request_hash ~ '^[0-9a-f]{64}$' and evidence_hash ~ '^[0-9a-f]{64}$'),
 check (jsonb_typeof(activity_mapping_json)='array'
  and jsonb_array_length(activity_mapping_json) between 0 and 64),
 check (jsonb_typeof(payload_json)='object'),
 check (btrim(worker_id)<>'' and btrim(engine_instance_id)<>''
  and btrim(source_engine_definition_id)<>'' and btrim(target_engine_deployment_id)<>''
  and btrim(target_engine_definition_id)<>'' and btrim(request_id)<>'')
);

create table ap_process_migration_engine_outcome (
 tenant_id varchar(128) not null,
 engine_outcome_id uuid not null,
 engine_request_id uuid not null,
 intent_id uuid not null,
 attempt_id uuid not null,
 worker_id varchar(200) not null,
 expected_attempt_revision bigint not null,
 expected_fence_revision bigint not null,
 disposition varchar(48) not null,
 engine_call_attempted boolean not null,
 engine_call_returned boolean not null,
 engine_call_may_have_occurred boolean not null,
 stable_code varchar(96) not null,
 bounded_summary varchar(1000),
 pre_dispatch_snapshot_hash char(64) not null,
 outcome_hash char(64) not null,
 recorded_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,engine_outcome_id),
 unique (tenant_id,engine_request_id),
 unique (tenant_id,attempt_id),
 foreign key (tenant_id,engine_request_id)
  references ap_process_migration_engine_request (tenant_id,engine_request_id),
 foreign key (tenant_id,intent_id) references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,attempt_id) references ap_process_migration_attempt (tenant_id,attempt_id),
 check (expected_attempt_revision>0 and expected_fence_revision>0),
 check (disposition in ('PRE_DISPATCH_REJECTED','ENGINE_REJECTED',
  'CALL_RETURNED_AWAITING_VERIFICATION','AMBIGUOUS_UNKNOWN')),
 check (pre_dispatch_snapshot_hash ~ '^[0-9a-f]{64}$' and outcome_hash ~ '^[0-9a-f]{64}$'),
 check (jsonb_typeof(payload_json)='object'),
 check (btrim(worker_id)<>'' and btrim(stable_code)<>'' and btrim(request_id)<>''),
 check (not engine_call_returned or engine_call_attempted),
 check (disposition<>'PRE_DISPATCH_REJECTED' or not engine_call_attempted),
 check (disposition<>'CALL_RETURNED_AWAITING_VERIFICATION'
  or (engine_call_attempted and engine_call_returned and not engine_call_may_have_occurred)),
 check (disposition<>'AMBIGUOUS_UNKNOWN'
  or (not engine_call_returned and engine_call_may_have_occurred))
);

create index idx_process_migration_engine_request_attempt_v41
 on ap_process_migration_engine_request (tenant_id,attempt_id,requested_at);
create index idx_process_migration_engine_outcome_attempt_v41
 on ap_process_migration_engine_outcome (tenant_id,attempt_id,recorded_at);

create function ap_guard_process_migration_engine_request_v41()
returns trigger language plpgsql as $$
declare attempt_row ap_process_migration_attempt%rowtype;
 fence_row ap_approval_instance_command_fence%rowtype;
 intent_row ap_process_migration_intent%rowtype;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='migration engine request evidence is append-only';
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 if not found or attempt_row.intent_id<>new.intent_id
  or attempt_row.approval_instance_id<>new.approval_instance_id
  or attempt_row.status<>'CLAIMED' or attempt_row.revision<>new.attempt_revision
  or attempt_row.lease_owner<>new.worker_id or attempt_row.lease_until<=new.requested_at
  or attempt_row.expected_binding_evidence_hash<>new.source_binding_evidence_hash
  or attempt_row.source_engine_definition_id<>new.source_engine_definition_id
  or attempt_row.target_engine_definition_id<>new.target_engine_definition_id
  or attempt_row.engine_instance_id<>new.engine_instance_id then
  raise exception using errcode='23514',message='migration engine request requires current exact claimed attempt';
 end if;
 select * into fence_row from ap_approval_instance_command_fence
  where tenant_id=new.tenant_id and fence_id=new.fence_id for update;
 if not found or fence_row.attempt_id<>new.attempt_id
  or fence_row.approval_instance_id<>new.approval_instance_id
  or fence_row.status<>'ACTIVE' or fence_row.revision<>new.fence_revision
  or fence_row.lease_owner<>new.worker_id or fence_row.lease_until<=new.requested_at then
  raise exception using errcode='23514',message='migration engine request requires current exact command fence';
 end if;
 select * into intent_row from ap_process_migration_intent
  where tenant_id=new.tenant_id and intent_id=new.intent_id;
 if not found or intent_row.status<>'RUNNING'
  or intent_row.target_release_version<>new.target_release_version
  or intent_row.target_package_hash<>new.target_package_hash then
  raise exception using errcode='23514',message='migration engine request requires current exact intent';
 end if;
 return new;
end $$;

create function ap_guard_process_migration_engine_outcome_v41()
returns trigger language plpgsql as $$
declare request_row ap_process_migration_engine_request%rowtype;
 attempt_row ap_process_migration_attempt%rowtype;
 fence_row ap_approval_instance_command_fence%rowtype;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='migration engine outcome evidence is append-only';
 end if;
 select * into request_row from ap_process_migration_engine_request
  where tenant_id=new.tenant_id and engine_request_id=new.engine_request_id;
 if not found or request_row.intent_id<>new.intent_id or request_row.attempt_id<>new.attempt_id
  or request_row.worker_id<>new.worker_id then
  raise exception using errcode='23514',message='migration engine outcome request lineage mismatch';
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 if not found or attempt_row.status<>'ENGINE_REQUESTED'
  or attempt_row.revision<>new.expected_attempt_revision
  or attempt_row.engine_request_reference<>new.engine_request_id::text then
  raise exception using errcode='23514',message='migration engine outcome requires current requested attempt';
 end if;
 select * into fence_row from ap_approval_instance_command_fence
  where tenant_id=new.tenant_id and fence_id=request_row.fence_id for update;
 if not found or fence_row.status<>'ACTIVE' or fence_row.attempt_id<>new.attempt_id
  or fence_row.revision<>new.expected_fence_revision or fence_row.lease_owner<>new.worker_id
  or fence_row.lease_until<=new.recorded_at then
  raise exception using errcode='23514',message='migration engine outcome requires current exact command fence';
 end if;
 return new;
end $$;

create trigger trg_process_migration_engine_request_guard_v41
 before insert or update or delete on ap_process_migration_engine_request
 for each row execute function ap_guard_process_migration_engine_request_v41();
create trigger trg_process_migration_engine_outcome_guard_v41
 before insert or update or delete on ap_process_migration_engine_outcome
 for each row execute function ap_guard_process_migration_engine_outcome_v41();

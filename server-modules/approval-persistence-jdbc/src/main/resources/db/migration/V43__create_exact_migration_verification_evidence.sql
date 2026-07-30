-- M5-D4: immutable, bounded, server-generated exact verification evidence.
-- This migration does not mutate runtime binding and creates no scheduler or public endpoint.

create table ap_process_migration_exact_verification (
 tenant_id varchar(128) not null,
 verification_id uuid not null,
 intent_id uuid not null,
 attempt_id uuid not null,
 engine_request_id uuid not null,
 engine_outcome_id uuid not null,
 worker_id varchar(200) not null,
 expected_attempt_revision bigint not null,
 expected_fence_revision bigint not null,
 source_engine_definition_id varchar(256) not null,
 target_engine_definition_id varchar(256) not null,
 classification varchar(64) not null,
 read_succeeded boolean not null,
 runtime_present boolean not null,
 history_present boolean not null,
 truncated boolean not null,
 observed_runtime_definition_id varchar(256),
 observed_history_definition_id varchar(256),
 snapshot_hash char(64) not null,
 request_hash char(64) not null,
 verification_evidence_hash char(64) not null,
 recorded_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,verification_id),
 unique (tenant_id,attempt_id),
 unique (tenant_id,request_hash),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,attempt_id)
  references ap_process_migration_attempt (tenant_id,attempt_id),
 foreign key (tenant_id,engine_request_id)
  references ap_process_migration_engine_request (tenant_id,engine_request_id),
 foreign key (tenant_id,engine_outcome_id)
  references ap_process_migration_engine_outcome (tenant_id,engine_outcome_id),
 check (expected_attempt_revision>0 and expected_fence_revision>0),
 check (source_engine_definition_id<>target_engine_definition_id),
 check (classification in (
  'EXACT_TARGET_RUNTIME','EXACT_SOURCE_RUNTIME','SOURCE_HISTORY_TERMINAL',
  'TARGET_HISTORY_TERMINAL','MIXED_SOURCE_TARGET_EVIDENCE','MISSING_NO_EVIDENCE',
  'STALE_OR_CONTRADICTORY_EVIDENCE','TRUNCATED_MANUAL_REVIEW_REQUIRED',
  'READ_FAILURE_RECONCILIATION_REQUIRED','INCOMPLETE_RECONCILIATION_REQUIRED'
 )),
 check (snapshot_hash ~ '^[0-9a-f]{64}$' and request_hash ~ '^[0-9a-f]{64}$'
  and verification_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (jsonb_typeof(payload_json)='object'),
 check (btrim(worker_id)<>'' and btrim(source_engine_definition_id)<>''
  and btrim(target_engine_definition_id)<>'' and btrim(request_id)<>''),
 check (classification<>'READ_FAILURE_RECONCILIATION_REQUIRED' or not read_succeeded),
 check (classification<>'TRUNCATED_MANUAL_REVIEW_REQUIRED' or truncated),
 check (classification<>'EXACT_TARGET_RUNTIME' or (
  read_succeeded and runtime_present and history_present and not truncated
  and observed_runtime_definition_id=target_engine_definition_id
  and observed_history_definition_id=target_engine_definition_id
 )),
 check (classification<>'EXACT_SOURCE_RUNTIME' or (
  read_succeeded and runtime_present and history_present and not truncated
  and observed_runtime_definition_id=source_engine_definition_id
  and observed_history_definition_id=source_engine_definition_id
 ))
);

create index idx_process_migration_exact_verification_attempt_v43
 on ap_process_migration_exact_verification (tenant_id,attempt_id,recorded_at);
create index idx_process_migration_exact_verification_class_v43
 on ap_process_migration_exact_verification (tenant_id,classification,recorded_at);

create function ap_guard_process_migration_exact_verification_v43()
returns trigger language plpgsql as $$
declare attempt_row ap_process_migration_attempt%rowtype;
 request_row ap_process_migration_engine_request%rowtype;
 outcome_row ap_process_migration_engine_outcome%rowtype;
 fence_row ap_approval_instance_command_fence%rowtype;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='exact migration verification evidence is append-only';
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 if not found or attempt_row.intent_id<>new.intent_id
  or attempt_row.status<>'VERIFYING' or attempt_row.revision<>new.expected_attempt_revision
  or attempt_row.engine_request_reference<>new.engine_request_id::text
  or attempt_row.payload_json->>'sourceEngineDefinitionId'<>new.source_engine_definition_id
  or attempt_row.payload_json->>'targetEngineDefinitionId'<>new.target_engine_definition_id then
  raise exception using errcode='23514',message='exact verification requires current exact VERIFYING attempt';
 end if;
 select * into request_row from ap_process_migration_engine_request
  where tenant_id=new.tenant_id and engine_request_id=new.engine_request_id;
 if not found or request_row.intent_id<>new.intent_id or request_row.attempt_id<>new.attempt_id
  or request_row.worker_id<>new.worker_id
  or request_row.source_engine_definition_id<>new.source_engine_definition_id
  or request_row.target_engine_definition_id<>new.target_engine_definition_id then
  raise exception using errcode='23514',message='exact verification request lineage mismatch';
 end if;
 select * into outcome_row from ap_process_migration_engine_outcome
  where tenant_id=new.tenant_id and engine_outcome_id=new.engine_outcome_id;
 if not found or outcome_row.engine_request_id<>new.engine_request_id
  or outcome_row.attempt_id<>new.attempt_id or outcome_row.worker_id<>new.worker_id
  or outcome_row.disposition<>'CALL_RETURNED_AWAITING_VERIFICATION'
  or not outcome_row.engine_call_attempted or not outcome_row.engine_call_returned then
  raise exception using errcode='23514',message='exact verification requires returned engine outcome';
 end if;
 select * into fence_row from ap_approval_instance_command_fence
  where tenant_id=new.tenant_id and fence_id=request_row.fence_id for update;
 if not found or fence_row.status<>'ACTIVE' or fence_row.attempt_id<>new.attempt_id
  or fence_row.revision<>new.expected_fence_revision or fence_row.lease_owner<>new.worker_id
  or fence_row.lease_until<=new.recorded_at then
  raise exception using errcode='23514',message='exact verification requires current exact command fence';
 end if;
 return new;
end $$;

create trigger trg_process_migration_exact_verification_guard_v43
 before insert or update or delete on ap_process_migration_exact_verification
 for each row execute function ap_guard_process_migration_exact_verification_v43();

-- M5-D7: deterministic canonical canary, bounded one-shot orchestration and dispatch kill switch.
-- This migration creates no Flowable request, scheduler, retry, rollback or public execution surface.

create table ap_process_migration_canary_selection (
 tenant_id varchar(128) not null,
 selection_id uuid not null,
 plan_id uuid not null,
 intent_id uuid not null,
 algorithm_version varchar(64) not null,
 sequence_no integer not null,
 approval_instance_id uuid not null,
 plan_hash char(64) not null,
 instance_evidence_hash char(64) not null,
 selection_evidence_hash char(64) not null,
 recorded_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,selection_id),
 unique (tenant_id,plan_id),
 unique (tenant_id,intent_id),
 foreign key (tenant_id,plan_id)
  references ap_process_migration_plan (tenant_id,plan_id),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,plan_id,approval_instance_id)
  references ap_process_migration_plan_instance (tenant_id,plan_id,approval_instance_id),
 check (algorithm_version='CANONICAL_FIRST_V1' and sequence_no=1),
 check (plan_hash ~ '^[0-9a-f]{64}$'
  and instance_evidence_hash ~ '^[0-9a-f]{64}$'
  and selection_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(request_id)<>'' and jsonb_typeof(payload_json)='object')
);

create table ap_process_migration_orchestration_run (
 tenant_id varchar(128) not null,
 run_id uuid not null,
 plan_id uuid not null,
 intent_id uuid not null,
 run_revision bigint not null,
 phase varchar(16) not null,
 requested_limit integer not null,
 canary_selection_id uuid not null,
 expected_kill_switch_revision bigint not null,
 predecessor_hash char(64) not null,
 request_hash char(64) not null,
 run_evidence_hash char(64) not null,
 started_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,run_id),
 unique (tenant_id,intent_id,run_revision),
 unique (tenant_id,request_hash),
 foreign key (tenant_id,plan_id)
  references ap_process_migration_plan (tenant_id,plan_id),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,canary_selection_id)
  references ap_process_migration_canary_selection (tenant_id,selection_id),
 check (run_revision>0 and expected_kill_switch_revision>0),
 check (phase in ('CANARY','BOUNDED')),
 check (requested_limit between 1 and 100),
 check (predecessor_hash ~ '^[0-9a-f]{64}$'
  and request_hash ~ '^[0-9a-f]{64}$'
  and run_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(request_id)<>'' and jsonb_typeof(payload_json)='object')
);

create table ap_process_migration_orchestration_event (
 tenant_id varchar(128) not null,
 event_id uuid not null,
 run_id uuid not null,
 sequence bigint not null,
 event_type varchar(32) not null,
 pause_reason varchar(64) not null,
 attempt_id uuid,
 predecessor_hash char(64) not null,
 event_evidence_hash char(64) not null,
 happened_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,event_id),
 unique (tenant_id,run_id,sequence),
 foreign key (tenant_id,run_id)
  references ap_process_migration_orchestration_run (tenant_id,run_id),
 foreign key (tenant_id,attempt_id)
  references ap_process_migration_attempt (tenant_id,attempt_id),
 check (sequence>0),
 check (event_type in (
  'PREPARED','DISPATCH_ALLOWED','KILL_SWITCH_BLOCKED','CANARY_COMPLETED',
  'BATCH_RECORDED','PAUSED','COMPLETED'
 )),
 check (pause_reason in (
  'NONE','CANARY_IN_FLIGHT','CANARY_UNKNOWN','CANARY_RECONCILIATION',
  'CANARY_MANUAL_REVIEW','CANARY_BINDING_CONFLICT','CANARY_NOT_EXACT_TARGET',
  'KILL_SWITCH_ACTIVE','STALE_KILL_SWITCH_REVISION','STALE_ORCHESTRATION_REVISION',
  'STALE_WORKER','STALE_LEASE','TERMINAL_FAILURE',
  'MISSING_OR_INCOMPLETE_EVIDENCE','EMPTY_BATCH'
 )),
 check (((event_type='PAUSED' or event_type='KILL_SWITCH_BLOCKED') and pause_reason<>'NONE')
  or (event_type not in ('PAUSED','KILL_SWITCH_BLOCKED') and pause_reason='NONE')),
 check (predecessor_hash ~ '^[0-9a-f]{64}$'
  and event_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(request_id)<>'' and jsonb_typeof(payload_json)='object')
);

create table ap_process_migration_orchestration_batch (
 tenant_id varchar(128) not null,
 batch_evidence_id uuid not null,
 run_id uuid not null,
 claim_batch_id uuid not null,
 requested_limit integer not null,
 attempt_ids jsonb not null,
 dispositions jsonb not null,
 predecessor_hash char(64) not null,
 batch_evidence_hash char(64) not null,
 recorded_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,batch_evidence_id),
 unique (tenant_id,run_id),
 unique (tenant_id,claim_batch_id),
 foreign key (tenant_id,run_id)
  references ap_process_migration_orchestration_run (tenant_id,run_id),
 foreign key (tenant_id,claim_batch_id)
  references ap_process_migration_claim_batch (tenant_id,claim_batch_id),
 check (requested_limit between 1 and 100),
 check (jsonb_typeof(attempt_ids)='array' and jsonb_typeof(dispositions)='array'
  and jsonb_array_length(attempt_ids)=jsonb_array_length(dispositions)
  and jsonb_array_length(attempt_ids)<=requested_limit),
 check (predecessor_hash ~ '^[0-9a-f]{64}$'
  and batch_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(request_id)<>'' and jsonb_typeof(payload_json)='object')
);

create table ap_process_migration_kill_switch_observation (
 tenant_id varchar(128) not null,
 observation_id uuid not null,
 run_id uuid not null,
 attempt_id uuid not null,
 expected_revision bigint not null,
 observed_revision bigint not null,
 switch_enabled boolean not null,
 dispatch_allowed boolean not null,
 reason_code varchar(64) not null,
 request_hash char(64) not null,
 observation_evidence_hash char(64) not null,
 observed_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,observation_id),
 unique (tenant_id,run_id,attempt_id),
 unique (tenant_id,request_hash),
 foreign key (tenant_id,run_id)
  references ap_process_migration_orchestration_run (tenant_id,run_id),
 foreign key (tenant_id,attempt_id)
  references ap_process_migration_attempt (tenant_id,attempt_id),
 check (expected_revision>0 and observed_revision>0),
 check (reason_code in ('DISPATCH_ALLOWED','KILL_SWITCH_ACTIVE','STALE_KILL_SWITCH_REVISION')),
 check (dispatch_allowed=(not switch_enabled and expected_revision=observed_revision)),
 check ((dispatch_allowed and reason_code='DISPATCH_ALLOWED')
  or (not dispatch_allowed and switch_enabled and reason_code='KILL_SWITCH_ACTIVE')
  or (not dispatch_allowed and not switch_enabled
   and expected_revision<>observed_revision and reason_code='STALE_KILL_SWITCH_REVISION')),
 check (request_hash ~ '^[0-9a-f]{64}$'
  and observation_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(request_id)<>'' and jsonb_typeof(payload_json)='object')
);

create index idx_process_migration_orchestration_run_plan_v47
 on ap_process_migration_orchestration_run (tenant_id,plan_id,run_revision);
create index idx_process_migration_orchestration_event_run_v47
 on ap_process_migration_orchestration_event (tenant_id,run_id,sequence);
create index idx_process_migration_kill_switch_observation_time_v47
 on ap_process_migration_kill_switch_observation (tenant_id,observed_at,run_id);

create function ap_guard_process_migration_d7_append_only()
returns trigger language plpgsql as $$
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='M5-D7 evidence is append-only';
 end if;
 return new;
end $$;

create trigger trg_process_migration_canary_selection_append_only_v47
 before insert or update or delete on ap_process_migration_canary_selection
 for each row execute function ap_guard_process_migration_d7_append_only();
create trigger trg_process_migration_orchestration_run_append_only_v47
 before insert or update or delete on ap_process_migration_orchestration_run
 for each row execute function ap_guard_process_migration_d7_append_only();
create trigger trg_process_migration_orchestration_event_append_only_v47
 before insert or update or delete on ap_process_migration_orchestration_event
 for each row execute function ap_guard_process_migration_d7_append_only();
create trigger trg_process_migration_orchestration_batch_append_only_v47
 before insert or update or delete on ap_process_migration_orchestration_batch
 for each row execute function ap_guard_process_migration_d7_append_only();
create trigger trg_process_migration_kill_switch_observation_append_only_v47
 before insert or update or delete on ap_process_migration_kill_switch_observation
 for each row execute function ap_guard_process_migration_d7_append_only();

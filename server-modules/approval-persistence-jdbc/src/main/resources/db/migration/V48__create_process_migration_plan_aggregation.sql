-- M5-D8: immutable deterministic plan aggregation from D1-D7 server evidence only.
-- This migration creates no Flowable call, dispatch, retry, scheduler or runtime-binding mutation.

create table ap_process_migration_plan_aggregate (
 tenant_id varchar(128) not null,
 aggregate_id uuid not null,
 plan_id uuid not null,
 intent_id uuid not null,
 plan_hash char(64) not null,
 aggregate_revision bigint not null,
 status varchar(64) not null,
 terminal_outcome varchar(64) not null,
 selected_count integer not null,
 provisioned_attempt_count integer not null,
 pending_count integer not null,
 claimed_count integer not null,
 engine_requested_count integer not null,
 verifying_count integer not null,
 reconciling_count integer not null,
 unknown_count integer not null,
 manual_review_count integer not null,
 binding_conflict_count integer not null,
 blocked_stale_count integer not null,
 terminal_failed_count integer not null,
 exact_success_count integer not null,
 unresolved_count integer not null,
 canary_status varchar(32) not null,
 orchestration_status varchar(32) not null,
 paused boolean not null,
 pause_reason varchar(64) not null,
 kill_switch_observed boolean not null,
 input_evidence_hash char(64) not null,
 predecessor_hash char(64) not null,
 operator_id varchar(256) not null,
 idempotency_key varchar(200) not null,
 request_hash char(64) not null,
 aggregate_hash char(64) not null,
 aggregated_at timestamptz not null,
 reason varchar(1000) not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 audit_reference varchar(256) not null,
 payload_json jsonb not null,
 primary key (tenant_id,aggregate_id),
 unique (tenant_id,plan_id,aggregate_revision),
 unique (tenant_id,idempotency_key),
 unique (tenant_id,request_hash),
 unique (tenant_id,plan_id,aggregate_hash),
 unique (tenant_id,plan_id,input_evidence_hash),
 foreign key (tenant_id,plan_id,plan_hash)
  references ap_process_migration_plan (tenant_id,plan_id,plan_hash),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 check (aggregate_revision>0),
 check (status in (
  'NOT_STARTED','CANARY_PENDING','CANARY_IN_PROGRESS',
  'BOUNDED_EXECUTION_IN_PROGRESS','PAUSED','UNRESOLVED',
  'TERMINAL_FAILURE_PRESENT','PARTIALLY_COMPLETED','COMPLETED_SUCCEEDED',
  'COMPLETED_WITH_TERMINAL_FAILURE','INVALID_OR_INCOMPLETE_EVIDENCE'
 )),
 check (terminal_outcome in (
  'NONE','SUCCEEDED','COMPLETED_WITH_TERMINAL_FAILURE',
  'UNRESOLVED','INVALID_EVIDENCE'
 )),
 check (selected_count between 1 and 5000),
 check (provisioned_attempt_count between 0 and selected_count),
 check (pending_count between 0 and selected_count
  and claimed_count between 0 and selected_count
  and engine_requested_count between 0 and selected_count
  and verifying_count between 0 and selected_count
  and reconciling_count between 0 and selected_count
  and unknown_count between 0 and selected_count
  and manual_review_count between 0 and selected_count
  and binding_conflict_count between 0 and selected_count
  and blocked_stale_count between 0 and selected_count
  and terminal_failed_count between 0 and selected_count
  and exact_success_count between 0 and selected_count
  and unresolved_count between 0 and selected_count),
 check (pending_count+claimed_count+engine_requested_count+verifying_count
  +reconciling_count+unknown_count+manual_review_count+binding_conflict_count
  +blocked_stale_count+terminal_failed_count+exact_success_count=selected_count),
 check (unresolved_count=selected_count-exact_success_count-terminal_failed_count),
 check (canary_status in (
  'NOT_SELECTED','PENDING','IN_PROGRESS','COMPLETED','PAUSED','INVALID'
 )),
 check (orchestration_status in (
  'NOT_STARTED','CANARY_IN_PROGRESS','BOUNDED_IN_PROGRESS',
  'PAUSED','COMPLETED','INVALID'
 )),
 check (pause_reason in (
  'NONE','KILL_SWITCH','UNKNOWN','RECONCILIATION','MANUAL_REVIEW',
  'BINDING_CONFLICT','STALE_AUTHORITY','TERMINAL_FAILURE',
  'CANARY_IN_FLIGHT','EMPTY_BATCH','INCOMPLETE_EVIDENCE'
 )),
 check (paused=(pause_reason<>'NONE')),
 check (pause_reason<>'KILL_SWITCH' or kill_switch_observed),
 check (status<>'COMPLETED_SUCCEEDED' or (
  terminal_outcome='SUCCEEDED' and selected_count>0
  and exact_success_count=selected_count and terminal_failed_count=0
  and unresolved_count=0 and not paused
 )),
 check (status<>'COMPLETED_WITH_TERMINAL_FAILURE' or (
  terminal_outcome='COMPLETED_WITH_TERMINAL_FAILURE'
  and terminal_failed_count>0 and unresolved_count=0
  and exact_success_count+terminal_failed_count=selected_count
 )),
 check (plan_hash ~ '^[0-9a-f]{64}$'
  and input_evidence_hash ~ '^[0-9a-f]{64}$'
  and predecessor_hash ~ '^[0-9a-f]{64}$'
  and request_hash ~ '^[0-9a-f]{64}$'
  and aggregate_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(operator_id)<>'' and btrim(idempotency_key)<>''
  and btrim(reason)<>'' and btrim(request_id)<>'' and btrim(audit_reference)<>''
  and jsonb_typeof(payload_json)='object')
);

create table ap_process_migration_plan_aggregate_event (
 tenant_id varchar(128) not null,
 event_id uuid not null,
 aggregate_id uuid not null,
 plan_id uuid not null,
 intent_id uuid not null,
 aggregate_revision bigint not null,
 status varchar(64) not null,
 terminal_outcome varchar(64) not null,
 pause_reason varchar(64) not null,
 predecessor_hash char(64) not null,
 aggregate_hash char(64) not null,
 event_hash char(64) not null,
 happened_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 audit_reference varchar(256) not null,
 payload_json jsonb not null,
 primary key (tenant_id,event_id),
 unique (tenant_id,aggregate_id),
 unique (tenant_id,plan_id,aggregate_revision),
 foreign key (tenant_id,aggregate_id)
  references ap_process_migration_plan_aggregate (tenant_id,aggregate_id),
 foreign key (tenant_id,plan_id)
  references ap_process_migration_plan (tenant_id,plan_id),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 check (aggregate_revision>0),
 check (status in (
  'NOT_STARTED','CANARY_PENDING','CANARY_IN_PROGRESS',
  'BOUNDED_EXECUTION_IN_PROGRESS','PAUSED','UNRESOLVED',
  'TERMINAL_FAILURE_PRESENT','PARTIALLY_COMPLETED','COMPLETED_SUCCEEDED',
  'COMPLETED_WITH_TERMINAL_FAILURE','INVALID_OR_INCOMPLETE_EVIDENCE'
 )),
 check (terminal_outcome in (
  'NONE','SUCCEEDED','COMPLETED_WITH_TERMINAL_FAILURE',
  'UNRESOLVED','INVALID_EVIDENCE'
 )),
 check (pause_reason in (
  'NONE','KILL_SWITCH','UNKNOWN','RECONCILIATION','MANUAL_REVIEW',
  'BINDING_CONFLICT','STALE_AUTHORITY','TERMINAL_FAILURE',
  'CANARY_IN_FLIGHT','EMPTY_BATCH','INCOMPLETE_EVIDENCE'
 )),
 check (predecessor_hash ~ '^[0-9a-f]{64}$'
  and aggregate_hash ~ '^[0-9a-f]{64}$'
  and event_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(request_id)<>'' and btrim(audit_reference)<>''
  and jsonb_typeof(payload_json)='object')
);

create table ap_process_migration_plan_completion (
 tenant_id varchar(128) not null,
 completion_id uuid not null,
 plan_id uuid not null,
 intent_id uuid not null,
 aggregate_id uuid not null,
 aggregate_revision bigint not null,
 completion_status varchar(64) not null,
 terminal_outcome varchar(64) not null,
 selected_count integer not null,
 terminal_failed_count integer not null,
 exact_success_count integer not null,
 input_evidence_hash char(64) not null,
 aggregate_hash char(64) not null,
 completion_evidence_hash char(64) not null,
 completed_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 audit_reference varchar(256) not null,
 payload_json jsonb not null,
 primary key (tenant_id,completion_id),
 unique (tenant_id,plan_id),
 unique (tenant_id,intent_id),
 unique (tenant_id,aggregate_id),
 foreign key (tenant_id,aggregate_id)
  references ap_process_migration_plan_aggregate (tenant_id,aggregate_id),
 foreign key (tenant_id,plan_id)
  references ap_process_migration_plan (tenant_id,plan_id),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 check (aggregate_revision>0),
 check (completion_status in (
  'COMPLETED_SUCCEEDED','COMPLETED_WITH_TERMINAL_FAILURE'
 )),
 check (terminal_outcome in (
  'SUCCEEDED','COMPLETED_WITH_TERMINAL_FAILURE'
 )),
 check (selected_count between 1 and 5000
  and terminal_failed_count between 0 and selected_count
  and exact_success_count between 0 and selected_count),
 check ((completion_status='COMPLETED_SUCCEEDED'
   and terminal_outcome='SUCCEEDED'
   and exact_success_count=selected_count and terminal_failed_count=0)
  or (completion_status='COMPLETED_WITH_TERMINAL_FAILURE'
   and terminal_outcome='COMPLETED_WITH_TERMINAL_FAILURE'
   and terminal_failed_count>0
   and exact_success_count+terminal_failed_count=selected_count)),
 check (input_evidence_hash ~ '^[0-9a-f]{64}$'
  and aggregate_hash ~ '^[0-9a-f]{64}$'
  and completion_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(request_id)<>'' and btrim(audit_reference)<>''
  and jsonb_typeof(payload_json)='object')
);

create index idx_process_migration_plan_aggregate_plan_v48
 on ap_process_migration_plan_aggregate (
  tenant_id,plan_id,aggregate_revision,aggregate_id
 );
create index idx_process_migration_plan_aggregate_status_v48
 on ap_process_migration_plan_aggregate (
  tenant_id,status,aggregated_at,aggregate_id
 );
create index idx_process_migration_plan_aggregate_unresolved_v48
 on ap_process_migration_plan_aggregate (
  tenant_id,unresolved_count,pause_reason,aggregated_at,aggregate_id
 );
create index idx_process_migration_plan_aggregate_event_plan_v48
 on ap_process_migration_plan_aggregate_event (
  tenant_id,plan_id,aggregate_revision,event_id
 );
create index idx_process_migration_plan_aggregate_event_intent_v48
 on ap_process_migration_plan_aggregate_event (
  tenant_id,intent_id,aggregate_revision,event_id
 );
create index idx_process_migration_plan_completion_time_v48
 on ap_process_migration_plan_completion (
  tenant_id,completed_at,plan_id
 );

create function ap_guard_process_migration_d8_evidence_v48()
returns trigger language plpgsql as $$
declare
 plan_row ap_process_migration_plan%rowtype;
 intent_row ap_process_migration_intent%rowtype;
 aggregate_row ap_process_migration_plan_aggregate%rowtype;
 previous_hash char(64);
 actual_count integer;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='M5-D8 evidence is append-only';
 end if;

 if tg_table_name='ap_process_migration_plan_aggregate' then
  if (new.payload_json->>'aggregateId')::uuid is distinct from new.aggregate_id
   or (new.payload_json->>'tenantId') is distinct from new.tenant_id
   or (new.payload_json->>'operatorId') is distinct from new.operator_id
   or (new.payload_json->>'planId')::uuid is distinct from new.plan_id
   or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
   or (new.payload_json->>'planHash') is distinct from new.plan_hash
   or (new.payload_json->>'aggregateRevision')::bigint is distinct from new.aggregate_revision
   or (new.payload_json->>'status') is distinct from new.status
   or (new.payload_json->>'terminalOutcome') is distinct from new.terminal_outcome
   or (new.payload_json->'counts'->>'selectedCount')::integer is distinct from new.selected_count
   or (new.payload_json->'counts'->>'provisionedAttemptCount')::integer
      is distinct from new.provisioned_attempt_count
   or (new.payload_json->'counts'->>'pendingCount')::integer is distinct from new.pending_count
   or (new.payload_json->'counts'->>'claimedCount')::integer is distinct from new.claimed_count
   or (new.payload_json->'counts'->>'engineRequestedCount')::integer
      is distinct from new.engine_requested_count
   or (new.payload_json->'counts'->>'verifyingCount')::integer is distinct from new.verifying_count
   or (new.payload_json->'counts'->>'reconcilingCount')::integer
      is distinct from new.reconciling_count
   or (new.payload_json->'counts'->>'unknownCount')::integer is distinct from new.unknown_count
   or (new.payload_json->'counts'->>'manualReviewCount')::integer
      is distinct from new.manual_review_count
   or (new.payload_json->'counts'->>'bindingConflictCount')::integer
      is distinct from new.binding_conflict_count
   or (new.payload_json->'counts'->>'blockedStaleCount')::integer
      is distinct from new.blocked_stale_count
   or (new.payload_json->'counts'->>'terminalFailedCount')::integer
      is distinct from new.terminal_failed_count
   or (new.payload_json->'counts'->>'exactSuccessCount')::integer
      is distinct from new.exact_success_count
   or (new.payload_json->'counts'->>'unresolvedCount')::integer
      is distinct from new.unresolved_count
   or (new.payload_json->>'canaryStatus') is distinct from new.canary_status
   or (new.payload_json->>'orchestrationStatus') is distinct from new.orchestration_status
   or (new.payload_json->>'paused')::boolean is distinct from new.paused
   or (new.payload_json->>'pauseReason') is distinct from new.pause_reason
   or (new.payload_json->>'killSwitchObserved')::boolean is distinct from new.kill_switch_observed
   or (new.payload_json->>'inputEvidenceHash') is distinct from new.input_evidence_hash
   or (new.payload_json->>'predecessorHash') is distinct from new.predecessor_hash
   or (new.payload_json->>'idempotencyKey') is distinct from new.idempotency_key
   or (new.payload_json->>'requestHash') is distinct from new.request_hash
   or (new.payload_json->>'aggregateHash') is distinct from new.aggregate_hash
   or ap_migration_json_instant_v37(new.payload_json,'aggregatedAt')
      is distinct from new.aggregated_at
   or (new.payload_json->>'reason') is distinct from new.reason
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id
   or (new.payload_json->>'auditReference') is distinct from new.audit_reference then
   raise exception using errcode='23514',message='D8 aggregate payload mismatch';
  end if;

  select * into plan_row from ap_process_migration_plan
   where tenant_id=new.tenant_id and plan_id=new.plan_id and plan_hash=new.plan_hash
   for update;
  select * into intent_row from ap_process_migration_intent
   where tenant_id=new.tenant_id and intent_id=new.intent_id for update;
  select count(*) into actual_count from ap_process_migration_plan_instance
   where tenant_id=new.tenant_id and plan_id=new.plan_id;
  if plan_row.plan_id is null or intent_row.intent_id is null
   or plan_row.status<>'CONSUMED'
   or intent_row.plan_id<>new.plan_id or intent_row.plan_hash<>new.plan_hash
   or plan_row.selected_instance_count<>new.selected_count
   or actual_count<>new.selected_count then
   raise exception using errcode='23514',message='D8 aggregate sealed-plan lineage mismatch';
  end if;

  if new.aggregate_revision=1 then
   if new.predecessor_hash<>repeat('0',64)
    or exists (select 1 from ap_process_migration_plan_aggregate value
      where value.tenant_id=new.tenant_id and value.plan_id=new.plan_id) then
    raise exception using errcode='23514',message='D8 initial aggregate predecessor mismatch';
   end if;
  else
   select value.aggregate_hash into previous_hash
    from ap_process_migration_plan_aggregate value
    where value.tenant_id=new.tenant_id and value.plan_id=new.plan_id
      and value.aggregate_revision=new.aggregate_revision-1;
   if previous_hash is null or previous_hash<>new.predecessor_hash then
    raise exception using errcode='23514',message='D8 aggregate predecessor mismatch';
   end if;
  end if;
  return new;
 end if;

 if tg_table_name='ap_process_migration_plan_aggregate_event' then
  if (new.payload_json->>'eventId')::uuid is distinct from new.event_id
   or (new.payload_json->>'tenantId') is distinct from new.tenant_id
   or (new.payload_json->>'aggregateId')::uuid is distinct from new.aggregate_id
   or (new.payload_json->>'planId')::uuid is distinct from new.plan_id
   or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
   or (new.payload_json->>'aggregateRevision')::bigint is distinct from new.aggregate_revision
   or (new.payload_json->>'status') is distinct from new.status
   or (new.payload_json->>'terminalOutcome') is distinct from new.terminal_outcome
   or (new.payload_json->>'pauseReason') is distinct from new.pause_reason
   or (new.payload_json->>'predecessorHash') is distinct from new.predecessor_hash
   or (new.payload_json->>'aggregateHash') is distinct from new.aggregate_hash
   or (new.payload_json->>'eventHash') is distinct from new.event_hash
   or ap_migration_json_instant_v37(new.payload_json,'happenedAt')
      is distinct from new.happened_at
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id
   or (new.payload_json->>'auditReference') is distinct from new.audit_reference then
   raise exception using errcode='23514',message='D8 aggregate event payload mismatch';
  end if;
  select * into aggregate_row from ap_process_migration_plan_aggregate
   where tenant_id=new.tenant_id and aggregate_id=new.aggregate_id;
  if aggregate_row.aggregate_id is null
   or aggregate_row.plan_id<>new.plan_id
   or aggregate_row.intent_id<>new.intent_id
   or aggregate_row.aggregate_revision<>new.aggregate_revision
   or aggregate_row.status<>new.status
   or aggregate_row.terminal_outcome<>new.terminal_outcome
   or aggregate_row.pause_reason<>new.pause_reason
   or aggregate_row.predecessor_hash<>new.predecessor_hash
   or aggregate_row.aggregate_hash<>new.aggregate_hash
   or aggregate_row.request_id<>new.request_id
   or aggregate_row.audit_reference<>new.audit_reference then
   raise exception using errcode='23514',message='D8 aggregate event lineage mismatch';
  end if;
  return new;
 end if;

 if tg_table_name='ap_process_migration_plan_completion' then
  if (new.payload_json->>'completionId')::uuid is distinct from new.completion_id
   or (new.payload_json->>'tenantId') is distinct from new.tenant_id
   or (new.payload_json->>'planId')::uuid is distinct from new.plan_id
   or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
   or (new.payload_json->>'aggregateId')::uuid is distinct from new.aggregate_id
   or (new.payload_json->>'aggregateRevision')::bigint is distinct from new.aggregate_revision
   or (new.payload_json->>'completionStatus') is distinct from new.completion_status
   or (new.payload_json->>'terminalOutcome') is distinct from new.terminal_outcome
   or (new.payload_json->'counts'->>'selectedCount')::integer is distinct from new.selected_count
   or (new.payload_json->'counts'->>'terminalFailedCount')::integer
      is distinct from new.terminal_failed_count
   or (new.payload_json->'counts'->>'exactSuccessCount')::integer
      is distinct from new.exact_success_count
   or (new.payload_json->'counts'->>'unresolvedCount')::integer<>0
   or (new.payload_json->>'inputEvidenceHash') is distinct from new.input_evidence_hash
   or (new.payload_json->>'aggregateHash') is distinct from new.aggregate_hash
   or (new.payload_json->>'completionEvidenceHash') is distinct from new.completion_evidence_hash
   or ap_migration_json_instant_v37(new.payload_json,'completedAt')
      is distinct from new.completed_at
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id
   or (new.payload_json->>'auditReference') is distinct from new.audit_reference then
   raise exception using errcode='23514',message='D8 completion payload mismatch';
  end if;
  select * into aggregate_row from ap_process_migration_plan_aggregate
   where tenant_id=new.tenant_id and aggregate_id=new.aggregate_id;
  if aggregate_row.aggregate_id is null
   or aggregate_row.plan_id<>new.plan_id
   or aggregate_row.intent_id<>new.intent_id
   or aggregate_row.aggregate_revision<>new.aggregate_revision
   or aggregate_row.status<>new.completion_status
   or aggregate_row.terminal_outcome<>new.terminal_outcome
   or aggregate_row.selected_count<>new.selected_count
   or aggregate_row.terminal_failed_count<>new.terminal_failed_count
   or aggregate_row.exact_success_count<>new.exact_success_count
   or aggregate_row.unresolved_count<>0
   or aggregate_row.input_evidence_hash<>new.input_evidence_hash
   or aggregate_row.aggregate_hash<>new.aggregate_hash
   or aggregate_row.request_id<>new.request_id
   or aggregate_row.audit_reference<>new.audit_reference then
   raise exception using errcode='23514',message='D8 completion aggregate lineage mismatch';
  end if;
  return new;
 end if;

 raise exception using errcode='55000',message='unexpected D8 evidence table';
end $$;

create trigger trg_process_migration_plan_aggregate_guard_v48
 before insert or update or delete on ap_process_migration_plan_aggregate
 for each row execute function ap_guard_process_migration_d8_evidence_v48();
create trigger trg_process_migration_plan_aggregate_event_guard_v48
 before insert or update or delete on ap_process_migration_plan_aggregate_event
 for each row execute function ap_guard_process_migration_d8_evidence_v48();
create trigger trg_process_migration_plan_completion_guard_v48
 before insert or update or delete on ap_process_migration_plan_completion
 for each row execute function ap_guard_process_migration_d8_evidence_v48();
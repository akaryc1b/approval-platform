-- M5-D8: deterministic plan-level aggregation from immutable D1-D7 evidence only.
-- This migration creates no Flowable call, dispatch, runtime-binding mutation or execution authority.

create table ap_process_migration_plan_aggregate (
 tenant_id varchar(128) not null,
 aggregate_id uuid not null,
 plan_id uuid not null,
 intent_id uuid not null,
 aggregate_revision bigint not null,
 status varchar(64) not null,
 selected_count integer not null,
 terminal_count integer not null,
 succeeded_count integer not null,
 unresolved_count integer not null,
 input_evidence_hash char(64) not null,
 predecessor_hash char(64) not null,
 request_hash char(64) not null,
 aggregate_hash char(64) not null,
 aggregated_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,aggregate_id),
 unique (tenant_id,intent_id,aggregate_revision),
 unique (tenant_id,request_id),
 unique (tenant_id,request_hash),
 unique (tenant_id,intent_id,aggregate_hash),
 foreign key (tenant_id,plan_id)
  references ap_process_migration_plan (tenant_id,plan_id),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 check (aggregate_revision>0),
 check (status in (
  'NOT_STARTED','CANARY_PENDING','CANARY_RUNNING','BOUNDED_EXECUTION_RUNNING',
  'PAUSED','KILL_SWITCH_BLOCKED','UNKNOWN_PRESENT','RECONCILIATION_PRESENT',
  'MANUAL_REVIEW_PRESENT','TERMINAL_FAILURE_PRESENT','PARTIALLY_COMPLETED',
  'ALL_INSTANCES_EXACTLY_COMPLETED','COMPLETED_WITH_MANUAL_DISPOSITION',
  'COMPLETION_CONFLICT','INVALID_INCOMPLETE_EVIDENCE'
 )),
 check (selected_count>0 and terminal_count between 0 and selected_count
  and succeeded_count between 0 and terminal_count
  and unresolved_count=selected_count-terminal_count),
 check (status<>'ALL_INSTANCES_EXACTLY_COMPLETED'
  or (terminal_count=selected_count and succeeded_count=selected_count
   and unresolved_count=0)),
 check (status<>'COMPLETED_WITH_MANUAL_DISPOSITION'
  or (terminal_count=selected_count and succeeded_count<selected_count
   and unresolved_count=0)),
 check (input_evidence_hash ~ '^[0-9a-f]{64}$'
  and predecessor_hash ~ '^[0-9a-f]{64}$'
  and request_hash ~ '^[0-9a-f]{64}$'
  and aggregate_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(request_id)<>'' and jsonb_typeof(payload_json)='object')
);

create table ap_process_migration_plan_aggregate_event (
 tenant_id varchar(128) not null,
 event_id uuid not null,
 aggregate_id uuid not null,
 plan_id uuid not null,
 intent_id uuid not null,
 aggregate_revision bigint not null,
 status varchar(64) not null,
 predecessor_hash char(64) not null,
 event_hash char(64) not null,
 happened_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,event_id),
 unique (tenant_id,aggregate_id),
 unique (tenant_id,intent_id,aggregate_revision),
 foreign key (tenant_id,aggregate_id)
  references ap_process_migration_plan_aggregate (tenant_id,aggregate_id),
 foreign key (tenant_id,plan_id)
  references ap_process_migration_plan (tenant_id,plan_id),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 check (aggregate_revision>0),
 check (status in (
  'NOT_STARTED','CANARY_PENDING','CANARY_RUNNING','BOUNDED_EXECUTION_RUNNING',
  'PAUSED','KILL_SWITCH_BLOCKED','UNKNOWN_PRESENT','RECONCILIATION_PRESENT',
  'MANUAL_REVIEW_PRESENT','TERMINAL_FAILURE_PRESENT','PARTIALLY_COMPLETED',
  'ALL_INSTANCES_EXACTLY_COMPLETED','COMPLETED_WITH_MANUAL_DISPOSITION',
  'COMPLETION_CONFLICT','INVALID_INCOMPLETE_EVIDENCE'
 )),
 check (predecessor_hash ~ '^[0-9a-f]{64}$'
  and event_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(request_id)<>'' and jsonb_typeof(payload_json)='object')
);

create table ap_process_migration_plan_completion (
 tenant_id varchar(128) not null,
 completion_id uuid not null,
 plan_id uuid not null,
 intent_id uuid not null,
 aggregate_id uuid not null,
 aggregate_revision bigint not null,
 completion_status varchar(64) not null,
 selected_count integer not null,
 terminal_count integer not null,
 succeeded_count integer not null,
 input_evidence_hash char(64) not null,
 aggregate_hash char(64) not null,
 completion_evidence_hash char(64) not null,
 completed_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
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
  'ALL_INSTANCES_EXACTLY_COMPLETED','COMPLETED_WITH_MANUAL_DISPOSITION'
 )),
 check (selected_count>0 and terminal_count=selected_count
  and succeeded_count between 0 and selected_count),
 check ((completion_status='ALL_INSTANCES_EXACTLY_COMPLETED'
   and succeeded_count=selected_count)
  or (completion_status='COMPLETED_WITH_MANUAL_DISPOSITION'
   and succeeded_count<selected_count)),
 check (input_evidence_hash ~ '^[0-9a-f]{64}$'
  and aggregate_hash ~ '^[0-9a-f]{64}$'
  and completion_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(request_id)<>'' and jsonb_typeof(payload_json)='object')
);

create index idx_process_migration_plan_aggregate_plan_v48
 on ap_process_migration_plan_aggregate (
  tenant_id,plan_id,aggregate_revision,aggregate_id
 );
create index idx_process_migration_plan_aggregate_status_v48
 on ap_process_migration_plan_aggregate (
  tenant_id,status,aggregated_at,aggregate_id
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
declare plan_row ap_process_migration_plan%rowtype;
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
   or (new.payload_json->>'planId')::uuid is distinct from new.plan_id
   or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
   or (new.payload_json->>'aggregateRevision')::bigint is distinct from new.aggregate_revision
   or (new.payload_json->>'status') is distinct from new.status
   or (new.payload_json->>'selectedCount')::integer is distinct from new.selected_count
   or (new.payload_json->>'terminalCount')::integer is distinct from new.terminal_count
   or (new.payload_json->>'succeededCount')::integer is distinct from new.succeeded_count
   or (new.payload_json->>'unresolvedCount')::integer is distinct from new.unresolved_count
   or (new.payload_json->>'inputEvidenceHash') is distinct from new.input_evidence_hash
   or (new.payload_json->>'predecessorHash') is distinct from new.predecessor_hash
   or (new.payload_json->>'requestHash') is distinct from new.request_hash
   or (new.payload_json->>'aggregateHash') is distinct from new.aggregate_hash
   or ap_migration_json_instant_v37(new.payload_json,'aggregatedAt')
      is distinct from new.aggregated_at
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id then
   raise exception using errcode='23514',message='D8 aggregate payload mismatch';
  end if;
  select * into plan_row from ap_process_migration_plan
   where tenant_id=new.tenant_id and plan_id=new.plan_id for update;
  select * into intent_row from ap_process_migration_intent
   where tenant_id=new.tenant_id and intent_id=new.intent_id for update;
  select count(*) into actual_count from ap_process_migration_plan_instance
   where tenant_id=new.tenant_id and plan_id=new.plan_id;
  if not found or plan_row.status<>'CONSUMED'
   or intent_row.plan_id<>new.plan_id or intent_row.plan_hash<>plan_row.plan_hash
   or plan_row.selected_instance_count<>new.selected_count
   or actual_count<>new.selected_count then
   raise exception using errcode='23514',message='D8 aggregate sealed-plan lineage mismatch';
  end if;
  if new.aggregate_revision=1 then
   if new.predecessor_hash<>repeat('0',64)
    or exists (select 1 from ap_process_migration_plan_aggregate value
      where value.tenant_id=new.tenant_id and value.intent_id=new.intent_id) then
    raise exception using errcode='23514',message='D8 initial aggregate predecessor mismatch';
   end if;
  else
   select value.aggregate_hash into previous_hash
    from ap_process_migration_plan_aggregate value
    where value.tenant_id=new.tenant_id and value.intent_id=new.intent_id
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
   or (new.payload_json->>'predecessorHash') is distinct from new.predecessor_hash
   or (new.payload_json->>'eventHash') is distinct from new.event_hash
   or ap_migration_json_instant_v37(new.payload_json,'happenedAt')
      is distinct from new.happened_at
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id then
   raise exception using errcode='23514',message='D8 aggregate event payload mismatch';
  end if;
  select * into aggregate_row from ap_process_migration_plan_aggregate
   where tenant_id=new.tenant_id and aggregate_id=new.aggregate_id;
  if not found or aggregate_row.plan_id<>new.plan_id
   or aggregate_row.intent_id<>new.intent_id
   or aggregate_row.aggregate_revision<>new.aggregate_revision
   or aggregate_row.status<>new.status
   or aggregate_row.aggregate_hash<>new.predecessor_hash
   or aggregate_row.request_id<>new.request_id then
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
   or (new.payload_json->>'selectedCount')::integer is distinct from new.selected_count
   or (new.payload_json->>'terminalCount')::integer is distinct from new.terminal_count
   or (new.payload_json->>'succeededCount')::integer is distinct from new.succeeded_count
   or (new.payload_json->>'inputEvidenceHash') is distinct from new.input_evidence_hash
   or (new.payload_json->>'aggregateHash') is distinct from new.aggregate_hash
   or (new.payload_json->>'completionEvidenceHash')
      is distinct from new.completion_evidence_hash
   or ap_migration_json_instant_v37(new.payload_json,'completedAt')
      is distinct from new.completed_at
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id then
   raise exception using errcode='23514',message='D8 plan completion payload mismatch';
  end if;
  select * into aggregate_row from ap_process_migration_plan_aggregate
   where tenant_id=new.tenant_id and aggregate_id=new.aggregate_id;
  if not found or aggregate_row.plan_id<>new.plan_id
   or aggregate_row.intent_id<>new.intent_id
   or aggregate_row.aggregate_revision<>new.aggregate_revision
   or aggregate_row.status<>new.completion_status
   or aggregate_row.selected_count<>new.selected_count
   or aggregate_row.terminal_count<>new.terminal_count
   or aggregate_row.succeeded_count<>new.succeeded_count
   or aggregate_row.unresolved_count<>0
   or aggregate_row.input_evidence_hash<>new.input_evidence_hash
   or aggregate_row.aggregate_hash<>new.aggregate_hash
   or aggregate_row.request_id<>new.request_id then
   raise exception using errcode='23514',message='D8 plan completion aggregate mismatch';
  end if;
  return new;
 end if;

 raise exception using errcode='23514',message='unsupported M5-D8 evidence table';
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

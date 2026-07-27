-- M5-D6: independent reconciliation lease and immutable UNKNOWN observation evidence.
-- This migration creates no migration redispatch, scheduler, public endpoint or rollback semantics.

create table ap_process_migration_reconciliation_lease (
 tenant_id varchar(128) not null,
 lease_id uuid not null,
 intent_id uuid not null,
 attempt_id uuid not null,
 status varchar(32) not null,
 revision bigint not null,
 worker_id varchar(200) not null,
 lease_until timestamptz not null,
 acquired_at timestamptz not null,
 updated_at timestamptz not null,
 released_at timestamptz,
 request_hash char(64) not null,
 evidence_hash char(64) not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,lease_id),
 unique (tenant_id,attempt_id),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,attempt_id)
  references ap_process_migration_attempt (tenant_id,attempt_id),
 check (status in ('ACTIVE','RELEASED') and revision>0),
 check (request_hash ~ '^[0-9a-f]{64}$' and evidence_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(worker_id)<>'' and btrim(request_id)<>''),
 check (jsonb_typeof(payload_json)='object'),
 check ((status='ACTIVE' and released_at is null and lease_until>updated_at)
  or (status='RELEASED' and released_at=updated_at and released_at>=acquired_at))
);

create table ap_process_migration_reconciliation_lease_event (
 tenant_id varchar(128) not null,
 event_id uuid not null,
 lease_id uuid not null,
 attempt_id uuid not null,
 revision bigint not null,
 from_status varchar(32),
 to_status varchar(32) not null,
 worker_id varchar(200) not null,
 lease_until timestamptz not null,
 happened_at timestamptz not null,
 request_hash char(64) not null,
 evidence_hash char(64) not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,event_id),
 unique (tenant_id,attempt_id,revision),
 foreign key (tenant_id,lease_id)
  references ap_process_migration_reconciliation_lease (tenant_id,lease_id),
 foreign key (tenant_id,attempt_id)
  references ap_process_migration_attempt (tenant_id,attempt_id),
 check (revision>0),
 check (from_status is null or from_status in ('ACTIVE','RELEASED')),
 check (to_status in ('ACTIVE','RELEASED')),
 check (request_hash ~ '^[0-9a-f]{64}$' and evidence_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(worker_id)<>'' and btrim(request_id)<>''),
 check (jsonb_typeof(payload_json)='object')
);

create table ap_process_migration_reconciliation_observation (
 tenant_id varchar(128) not null,
 observation_id uuid not null,
 intent_id uuid not null,
 attempt_id uuid not null,
 reconciliation_id uuid not null,
 lease_id uuid not null,
 worker_id varchar(200) not null,
 expected_attempt_revision bigint not null,
 expected_lease_revision bigint not null,
 source_engine_definition_id varchar(256) not null,
 target_engine_definition_id varchar(256) not null,
 classification varchar(64) not null,
 disposition varchar(64) not null,
 read_succeeded boolean not null,
 snapshot_hash char(64) not null,
 request_hash char(64) not null,
 evidence_hash char(64) not null,
 recorded_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 primary key (tenant_id,observation_id),
 unique (tenant_id,attempt_id),
 unique (tenant_id,request_hash),
 foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,attempt_id)
  references ap_process_migration_attempt (tenant_id,attempt_id),
 foreign key (tenant_id,reconciliation_id)
  references ap_process_migration_reconciliation (tenant_id,reconciliation_id),
 foreign key (tenant_id,lease_id)
  references ap_process_migration_reconciliation_lease (tenant_id,lease_id),
 check (expected_attempt_revision>0 and expected_lease_revision>0),
 check (source_engine_definition_id<>target_engine_definition_id),
 check (classification in (
  'EXACT_TARGET_RUNTIME','EXACT_SOURCE_RUNTIME','SOURCE_HISTORY_TERMINAL',
  'TARGET_HISTORY_TERMINAL','MIXED_SOURCE_TARGET_EVIDENCE','MISSING_NO_EVIDENCE',
  'STALE_OR_CONTRADICTORY_EVIDENCE','TRUNCATED_MANUAL_REVIEW_REQUIRED',
  'READ_FAILURE_RECONCILIATION_REQUIRED','INCOMPLETE_RECONCILIATION_REQUIRED'
 )),
 check (disposition in (
  'SOURCE_CONFIRMED_NO_RETRY','SOURCE_TERMINAL_CONFIRMED_NO_RETRY',
  'TARGET_CONFIRMED_BINDING_CAS_REQUIRED','TARGET_TERMINAL_BINDING_CAS_REQUIRED',
  'MANUAL_REVIEW_REQUIRED'
 )),
 check ((classification='EXACT_SOURCE_RUNTIME' and disposition='SOURCE_CONFIRMED_NO_RETRY')
  or (classification='SOURCE_HISTORY_TERMINAL'
   and disposition='SOURCE_TERMINAL_CONFIRMED_NO_RETRY')
  or (classification='EXACT_TARGET_RUNTIME'
   and disposition='TARGET_CONFIRMED_BINDING_CAS_REQUIRED')
  or (classification='TARGET_HISTORY_TERMINAL'
   and disposition='TARGET_TERMINAL_BINDING_CAS_REQUIRED')
  or (classification not in (
    'EXACT_SOURCE_RUNTIME','SOURCE_HISTORY_TERMINAL',
    'EXACT_TARGET_RUNTIME','TARGET_HISTORY_TERMINAL'
   ) and disposition='MANUAL_REVIEW_REQUIRED')),
 check (snapshot_hash ~ '^[0-9a-f]{64}$' and request_hash ~ '^[0-9a-f]{64}$'
  and evidence_hash ~ '^[0-9a-f]{64}$'),
 check (btrim(worker_id)<>'' and btrim(source_engine_definition_id)<>''
  and btrim(target_engine_definition_id)<>'' and btrim(request_id)<>''),
 check (jsonb_typeof(payload_json)='object')
);

create index idx_process_migration_reconciliation_lease_active_v45
 on ap_process_migration_reconciliation_lease (tenant_id,status,lease_until,attempt_id);
create index idx_process_migration_reconciliation_observation_attempt_v45
 on ap_process_migration_reconciliation_observation (tenant_id,attempt_id,recorded_at);

create function ap_guard_migration_reconciliation_lease_v45()
returns trigger language plpgsql as $$
declare attempt_row ap_process_migration_attempt%rowtype;
 latest_status varchar(32);
begin
 if (new.payload_json->>'leaseId')::uuid is distinct from new.lease_id
  or (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'status') is distinct from new.status
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'workerId') is distinct from new.worker_id
  or ap_migration_json_instant_v37(new.payload_json,'leaseUntil') is distinct from new.lease_until
  or ap_migration_json_instant_v37(new.payload_json,'acquiredAt') is distinct from new.acquired_at
  or ap_migration_json_instant_v37(new.payload_json,'updatedAt') is distinct from new.updated_at
  or ap_migration_json_instant_v37(new.payload_json,'releasedAt') is distinct from new.released_at
  or (new.payload_json->>'requestHash') is distinct from new.request_hash
  or (new.payload_json->>'evidenceHash') is distinct from new.evidence_hash
  or (new.payload_json->>'requestId') is distinct from new.request_id
  or (new.payload_json->>'traceId') is distinct from new.trace_id then
  raise exception using errcode='23514',message='reconciliation lease payload mismatch';
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 select status into latest_status from ap_process_migration_reconciliation
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id
  order by sequence desc limit 1;
 if not found or attempt_row.intent_id<>new.intent_id
  or attempt_row.status<>'RECONCILING' or attempt_row.engine_outcome<>'UNKNOWN'
  or latest_status<>'OPEN' then
  raise exception using errcode='23514',message='reconciliation lease requires current open UNKNOWN lineage';
 end if;
 if tg_op='INSERT' then
  if new.status<>'ACTIVE' or new.revision<>1 or new.released_at is not null
   or new.acquired_at<>new.updated_at or new.lease_until<=new.updated_at then
   raise exception using errcode='23514',message='reconciliation lease must begin ACTIVE revision 1';
  end if;
  return new;
 end if;
 if new.tenant_id is distinct from old.tenant_id or new.lease_id is distinct from old.lease_id
  or new.intent_id is distinct from old.intent_id or new.attempt_id is distinct from old.attempt_id
  or new.acquired_at is distinct from old.acquired_at then
  raise exception using errcode='55000',message='reconciliation lease identity is immutable';
 end if;
 if old.status<>'ACTIVE' or new.revision<>old.revision+1 then
  raise exception using errcode='40001',message='reconciliation lease revision or source status is stale';
 end if;
 if new.status='ACTIVE' then
  if new.worker_id=old.worker_id then
   if new.updated_at>=old.lease_until or new.lease_until<=old.lease_until then
    raise exception using errcode='23514',message='reconciliation lease renewal requires owner and extension';
   end if;
  elsif new.updated_at<old.lease_until or new.lease_until<=new.updated_at then
   raise exception using errcode='23514',message='reconciliation lease takeover requires expiry';
  end if;
 elsif new.status='RELEASED' then
  if new.worker_id<>old.worker_id or new.updated_at>=old.lease_until
   or new.lease_until<>old.lease_until or new.request_hash<>old.request_hash
   or new.request_id<>old.request_id or new.trace_id is distinct from old.trace_id
   or new.released_at<>new.updated_at then
   raise exception using errcode='23514',message='reconciliation lease release requires current owner';
  end if;
 end if;
 return new;
end $$;

create trigger trg_process_migration_reconciliation_lease_guard_v45
 before insert or update or delete on ap_process_migration_reconciliation_lease
 for each row execute function ap_guard_migration_reconciliation_lease_v45();

create function ap_guard_migration_reconciliation_lease_event_v45()
returns trigger language plpgsql as $$
declare lease_row ap_process_migration_reconciliation_lease%rowtype;
 previous_status varchar(32);
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='reconciliation lease event is append-only';
 end if;
 if (new.payload_json->>'eventId')::uuid is distinct from new.event_id
  or (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'leaseId')::uuid is distinct from new.lease_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'fromStatus') is distinct from new.from_status
  or (new.payload_json->>'toStatus') is distinct from new.to_status
  or (new.payload_json->>'workerId') is distinct from new.worker_id
  or ap_migration_json_instant_v37(new.payload_json,'leaseUntil') is distinct from new.lease_until
  or ap_migration_json_instant_v37(new.payload_json,'happenedAt') is distinct from new.happened_at
  or (new.payload_json->>'requestHash') is distinct from new.request_hash
  or (new.payload_json->>'evidenceHash') is distinct from new.evidence_hash
  or (new.payload_json->>'requestId') is distinct from new.request_id
  or (new.payload_json->>'traceId') is distinct from new.trace_id then
  raise exception using errcode='23514',message='reconciliation lease event payload mismatch';
 end if;
 select * into lease_row from ap_process_migration_reconciliation_lease
  where tenant_id=new.tenant_id and lease_id=new.lease_id;
 select to_status into previous_status from ap_process_migration_reconciliation_lease_event
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id
  order by revision desc limit 1;
 if lease_row.lease_id is null or lease_row.attempt_id<>new.attempt_id
  or lease_row.revision<>new.revision or lease_row.status<>new.to_status
  or lease_row.worker_id<>new.worker_id or lease_row.lease_until<>new.lease_until
  or lease_row.request_hash<>new.request_hash then
  raise exception using errcode='23514',message='reconciliation lease event does not match current lease';
 end if;
 if new.revision=1 then
  if previous_status is not null or new.from_status is not null or new.to_status<>'ACTIVE' then
   raise exception using errcode='23514',message='initial reconciliation lease event is invalid';
  end if;
 elsif previous_status<>'ACTIVE' or new.from_status<>'ACTIVE' then
  raise exception using errcode='23514',message='reconciliation lease event progression is invalid';
 end if;
 return new;
end $$;

create trigger trg_process_migration_reconciliation_lease_event_guard_v45
 before insert or update or delete on ap_process_migration_reconciliation_lease_event
 for each row execute function ap_guard_migration_reconciliation_lease_event_v45();

create function ap_guard_migration_reconciliation_observation_v45()
returns trigger language plpgsql as $$
declare attempt_row ap_process_migration_attempt%rowtype;
 lease_row ap_process_migration_reconciliation_lease%rowtype;
 reconciliation_row ap_process_migration_reconciliation%rowtype;
 outcome_row ap_process_migration_engine_outcome%rowtype;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='migration reconciliation observation is append-only';
 end if;
 if (new.payload_json->>'observationId')::uuid is distinct from new.observation_id
  or (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'reconciliationId')::uuid is distinct from new.reconciliation_id
  or (new.payload_json->>'leaseId')::uuid is distinct from new.lease_id
  or (new.payload_json->>'workerId') is distinct from new.worker_id
  or (new.payload_json->>'expectedAttemptRevision')::bigint
   is distinct from new.expected_attempt_revision
  or (new.payload_json->>'expectedLeaseRevision')::bigint
   is distinct from new.expected_lease_revision
  or (new.payload_json->>'sourceEngineDefinitionId') is distinct from new.source_engine_definition_id
  or (new.payload_json->>'targetEngineDefinitionId') is distinct from new.target_engine_definition_id
  or (new.payload_json->>'classification') is distinct from new.classification
  or (new.payload_json->>'disposition') is distinct from new.disposition
  or (new.payload_json->'snapshot'->>'readSucceeded')::boolean is distinct from new.read_succeeded
  or (new.payload_json->'snapshot'->>'snapshotHash') is distinct from new.snapshot_hash
  or (new.payload_json->>'requestHash') is distinct from new.request_hash
  or (new.payload_json->>'evidenceHash') is distinct from new.evidence_hash
  or ap_migration_json_instant_v37(new.payload_json,'recordedAt') is distinct from new.recorded_at
  or (new.payload_json->>'requestId') is distinct from new.request_id
  or (new.payload_json->>'traceId') is distinct from new.trace_id then
  raise exception using errcode='23514',message='reconciliation observation payload mismatch';
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 select * into lease_row from ap_process_migration_reconciliation_lease
  where tenant_id=new.tenant_id and lease_id=new.lease_id for update;
 select * into reconciliation_row from ap_process_migration_reconciliation
  where tenant_id=new.tenant_id and reconciliation_id=new.reconciliation_id;
 select outcome.* into outcome_row from ap_process_migration_engine_outcome outcome
  join ap_process_migration_engine_request request
   on request.tenant_id=outcome.tenant_id
   and request.engine_request_id=outcome.engine_request_id
  where outcome.tenant_id=new.tenant_id and outcome.attempt_id=new.attempt_id
   and request.engine_request_id::text=attempt_row.engine_request_reference;
 if attempt_row.attempt_id is null or lease_row.lease_id is null
  or reconciliation_row.reconciliation_id is null or outcome_row.engine_outcome_id is null
  or attempt_row.intent_id<>new.intent_id or attempt_row.status<>'RECONCILING'
  or attempt_row.engine_outcome<>'UNKNOWN' or attempt_row.revision<>new.expected_attempt_revision
  or reconciliation_row.attempt_id<>new.attempt_id or reconciliation_row.status<>'OPEN'
  or lease_row.attempt_id<>new.attempt_id or lease_row.status<>'ACTIVE'
  or lease_row.revision<>new.expected_lease_revision or lease_row.worker_id<>new.worker_id
  or lease_row.lease_until<=new.recorded_at or lease_row.request_hash<>new.request_hash
  or outcome_row.disposition<>'AMBIGUOUS_UNKNOWN'
  or not outcome_row.engine_call_may_have_occurred then
  raise exception using errcode='23514',message='reconciliation observation authority mismatch';
 end if;
 return new;
end $$;

create trigger trg_process_migration_reconciliation_observation_guard_v45
 before insert or update or delete on ap_process_migration_reconciliation_observation
 for each row execute function ap_guard_migration_reconciliation_observation_v45();

create function ap_require_migration_reconciliation_observation_final_v45()
returns trigger language plpgsql as $$
declare attempt_status varchar(32);
 latest_status varchar(32);
 lease_status varchar(32);
 lease_revision bigint;
begin
 select status into attempt_status from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 select status into latest_status from ap_process_migration_reconciliation
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id order by sequence desc limit 1;
 select status,revision into lease_status,lease_revision
  from ap_process_migration_reconciliation_lease
  where tenant_id=new.tenant_id and lease_id=new.lease_id;
 if lease_status<>'RELEASED' or lease_revision<>new.expected_lease_revision+1 then
  raise exception using errcode='23514',message='reconciliation observation requires released lease';
 end if;
 if new.disposition='SOURCE_CONFIRMED_NO_RETRY' then
  if latest_status<>'RESOLVED_SOURCE' or attempt_status<>'BLOCKED_STALE' then
   raise exception using errcode='23514',message='source observation requires blocked no-retry closure';
  end if;
 elsif new.disposition='SOURCE_TERMINAL_CONFIRMED_NO_RETRY' then
  if latest_status<>'RESOLVED_TERMINAL' or attempt_status<>'FAILED_TERMINAL' then
   raise exception using errcode='23514',message='source terminal observation requires terminal closure';
  end if;
 elsif latest_status<>'MANUAL_REVIEW_REQUIRED' or attempt_status<>'RECONCILING' then
  raise exception using errcode='23514',message='target or incomplete observation requires manual review';
 end if;
 return null;
end $$;

create constraint trigger trg_process_migration_reconciliation_observation_final_v45
 after insert on ap_process_migration_reconciliation_observation
 deferrable initially deferred
 for each row execute function ap_require_migration_reconciliation_observation_final_v45();

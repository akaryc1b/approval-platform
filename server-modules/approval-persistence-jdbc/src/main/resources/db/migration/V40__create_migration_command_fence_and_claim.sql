-- M5-D2: shared approval-instance command fence and bounded migration-attempt claim.
-- No Flowable call, scheduler, runtime-binding mutation or public execution surface is created.

alter table ap_process_migration_attempt
 add constraint uq_process_migration_attempt_fence_v40
 unique (tenant_id,attempt_id,approval_instance_id);

create index idx_process_migration_attempt_claim_v40
 on ap_process_migration_attempt (
  tenant_id,intent_id,status,lease_until,created_at,attempt_id
 );

create table ap_process_migration_claim_batch (
 tenant_id varchar(128) not null,
 claim_batch_id uuid not null,
 intent_id uuid not null,
 worker_id varchar(200) not null,
 requested_limit integer not null,
 claimed_count integer not null,
 claimed_attempt_ids jsonb not null,
 fence_ids jsonb not null,
 request_hash char(64) not null,
 claimed_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 constraint pk_process_migration_claim_batch_v40
  primary key (tenant_id,claim_batch_id),
 constraint uq_process_migration_claim_batch_request_v40
  unique (tenant_id,request_id),
 constraint fk_process_migration_claim_batch_intent_v40
  foreign key (tenant_id,intent_id)
  references ap_process_migration_intent (tenant_id,intent_id),
 constraint ck_process_migration_claim_batch_limit_v40 check (
  requested_limit between 1 and 100
  and claimed_count between 0 and requested_limit
 ),
 constraint ck_process_migration_claim_batch_arrays_v40 check (
  jsonb_typeof(claimed_attempt_ids)='array'
  and jsonb_typeof(fence_ids)='array'
  and jsonb_array_length(claimed_attempt_ids)=claimed_count
  and jsonb_array_length(fence_ids)=claimed_count
 ),
 constraint ck_process_migration_claim_batch_hash_v40 check (
  request_hash ~ '^[0-9a-f]{64}$'
 ),
 constraint ck_process_migration_claim_batch_payload_v40 check (
  jsonb_typeof(payload_json)='object'
 )
);

create index idx_process_migration_claim_batch_intent_v40
 on ap_process_migration_claim_batch (tenant_id,intent_id,claimed_at,claim_batch_id);

create table ap_approval_instance_command_fence (
 tenant_id varchar(128) not null,
 fence_id uuid not null,
 approval_instance_id uuid not null,
 attempt_id uuid not null,
 operation varchar(32) not null,
 status varchar(16) not null,
 revision bigint not null,
 lease_owner varchar(200) not null,
 lease_until timestamptz not null,
 idempotency_key varchar(200) not null,
 request_hash char(64) not null,
 acquired_at timestamptz not null,
 updated_at timestamptz not null,
 released_at timestamptz,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 constraint pk_approval_instance_command_fence_v40
  primary key (tenant_id,fence_id),
 constraint uq_approval_instance_command_fence_attempt_v40
  unique (tenant_id,attempt_id),
 constraint uq_approval_instance_command_fence_key_v40
  unique (tenant_id,idempotency_key),
 constraint fk_approval_instance_command_fence_attempt_v40
  foreign key (tenant_id,attempt_id,approval_instance_id)
  references ap_process_migration_attempt (
   tenant_id,attempt_id,approval_instance_id
  ),
 constraint ck_approval_instance_command_fence_operation_v40 check (
  operation='MIGRATION'
 ),
 constraint ck_approval_instance_command_fence_status_v40 check (
  status in ('ACTIVE','RELEASED')
 ),
 constraint ck_approval_instance_command_fence_revision_v40 check (
  revision>0
 ),
 constraint ck_approval_instance_command_fence_hash_v40 check (
  request_hash ~ '^[0-9a-f]{64}$'
 ),
 constraint ck_approval_instance_command_fence_time_v40 check (
  updated_at>=acquired_at and lease_until>updated_at
  and ((status='ACTIVE' and released_at is null)
   or (status='RELEASED' and released_at=updated_at))
 ),
 constraint ck_approval_instance_command_fence_payload_v40 check (
  jsonb_typeof(payload_json)='object'
 )
);

create unique index uk_approval_instance_command_fence_active_v40
 on ap_approval_instance_command_fence (tenant_id,approval_instance_id)
 where status='ACTIVE';
create index idx_approval_instance_command_fence_active_lease_v40
 on ap_approval_instance_command_fence (
  tenant_id,status,lease_until,approval_instance_id,fence_id
 );

create table ap_approval_instance_command_fence_event (
 tenant_id varchar(128) not null,
 event_id uuid not null,
 fence_id uuid not null,
 approval_instance_id uuid not null,
 attempt_id uuid not null,
 revision bigint not null,
 from_status varchar(16),
 to_status varchar(16) not null,
 lease_actor varchar(200) not null,
 lease_owner varchar(200) not null,
 lease_until timestamptz not null,
 happened_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 payload_json jsonb not null,
 constraint pk_approval_instance_command_fence_event_v40
  primary key (tenant_id,event_id),
 constraint uq_approval_instance_command_fence_event_revision_v40
  unique (tenant_id,fence_id,revision),
 constraint fk_approval_instance_command_fence_event_v40
  foreign key (tenant_id,fence_id)
  references ap_approval_instance_command_fence (tenant_id,fence_id),
 constraint ck_approval_instance_command_fence_event_status_v40 check (
  to_status in ('ACTIVE','RELEASED')
  and (from_status is null or from_status in ('ACTIVE','RELEASED'))
 ),
 constraint ck_approval_instance_command_fence_event_time_v40 check (
  revision>0 and lease_until>happened_at
 ),
 constraint ck_approval_instance_command_fence_event_payload_v40 check (
  jsonb_typeof(payload_json)='object'
 )
);

create index idx_approval_instance_command_fence_event_v40
 on ap_approval_instance_command_fence_event (tenant_id,fence_id,revision);

create function ap_guard_process_migration_claim_batch_v40()
returns trigger language plpgsql as $$
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',
   message='migration claim batch evidence is append-only';
 end if;
 if (new.payload_json->>'claimBatchId')::uuid is distinct from new.claim_batch_id
  or (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
  or (new.payload_json->>'workerId') is distinct from new.worker_id
  or (new.payload_json->>'requestedLimit')::integer is distinct from new.requested_limit
  or jsonb_array_length(new.payload_json->'claimedAttemptIds') is distinct from new.claimed_count
  or (new.payload_json->'claimedAttemptIds') is distinct from new.claimed_attempt_ids
  or (new.payload_json->'fenceIds') is distinct from new.fence_ids
  or (new.payload_json->>'requestHash') is distinct from new.request_hash
  or ap_migration_json_instant_v37(new.payload_json,'claimedAt') is distinct from new.claimed_at
  or (new.payload_json->>'requestId') is distinct from new.request_id
  or (new.payload_json->>'traceId') is distinct from new.trace_id then
  raise exception using errcode='23514',
   message='migration claim batch payload does not match durable columns';
 end if;
 if exists (
  select 1 from jsonb_array_elements(new.claimed_attempt_ids) item
  where jsonb_typeof(item)<>'string'
 ) or exists (
  select 1 from jsonb_array_elements(new.fence_ids) item
  where jsonb_typeof(item)<>'string'
 ) then
  raise exception using errcode='23514',
   message='migration claim batch identities must be strings';
 end if;
 return new;
end $$;

create trigger trg_process_migration_claim_batch_guard_v40
 before insert or update or delete on ap_process_migration_claim_batch
 for each row execute function ap_guard_process_migration_claim_batch_v40();

create function ap_guard_approval_instance_command_fence_v40()
returns trigger language plpgsql as $$
declare attempt_row ap_process_migration_attempt%rowtype;
begin
 if tg_op='DELETE' then
  raise exception using errcode='55000',
   message='approval instance command fence cannot be deleted';
 end if;
 if (new.payload_json->>'fenceId')::uuid is distinct from new.fence_id
  or (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'approvalInstanceId')::uuid
    is distinct from new.approval_instance_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'operation') is distinct from new.operation
  or (new.payload_json->>'status') is distinct from new.status
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'leaseOwner') is distinct from new.lease_owner
  or ap_migration_json_instant_v37(new.payload_json,'leaseUntil')
    is distinct from new.lease_until
  or (new.payload_json->>'idempotencyKey') is distinct from new.idempotency_key
  or (new.payload_json->>'requestHash') is distinct from new.request_hash
  or ap_migration_json_instant_v37(new.payload_json,'acquiredAt')
    is distinct from new.acquired_at
  or ap_migration_json_instant_v37(new.payload_json,'updatedAt')
    is distinct from new.updated_at
  or ap_migration_json_instant_v37(new.payload_json,'releasedAt')
    is distinct from new.released_at
  or (new.payload_json->>'requestId') is distinct from new.request_id
  or (new.payload_json->>'traceId') is distinct from new.trace_id then
  raise exception using errcode='23514',
   message='approval instance command fence payload does not match durable columns';
 end if;
 if tg_op='INSERT' then
  if new.status<>'ACTIVE' or new.revision<>1 then
   raise exception using errcode='23514',
    message='command fence must begin ACTIVE revision 1';
  end if;
 else
  if new.tenant_id is distinct from old.tenant_id
   or new.fence_id is distinct from old.fence_id
   or new.approval_instance_id is distinct from old.approval_instance_id
   or new.attempt_id is distinct from old.attempt_id
   or new.operation is distinct from old.operation
   or new.idempotency_key is distinct from old.idempotency_key
   or new.request_hash is distinct from old.request_hash
   or new.acquired_at is distinct from old.acquired_at
   or new.request_id is distinct from old.request_id
   or new.trace_id is distinct from old.trace_id then
   raise exception using errcode='55000',
    message='approval instance command fence identity is immutable';
  end if;
  if new.revision<>old.revision+1 or old.status<>'ACTIVE' then
   raise exception using errcode='40001',
    message='command fence revision or source status is stale';
  end if;
  if new.status='ACTIVE' then
   if new.lease_owner=old.lease_owner then
    if new.updated_at>=old.lease_until or new.lease_until<=old.lease_until then
     raise exception using errcode='23514',
      message='same-owner fence renewal requires current ownership and extension';
    end if;
   elsif new.updated_at<old.lease_until then
    raise exception using errcode='23514',
     message='command fence takeover requires expiry';
   end if;
  elsif new.status<>'RELEASED' then
   raise exception using errcode='23514',
    message='command fence transition is not permitted';
  end if;
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if not found or attempt_row.approval_instance_id<>new.approval_instance_id
  or attempt_row.status<>'CLAIMED'
  or attempt_row.lease_owner<>new.lease_owner
  or attempt_row.lease_until<>new.lease_until then
  raise exception using errcode='23514',
   message='command fence must match the current claimed migration attempt lease';
 end if;
 return new;
end $$;

create trigger trg_approval_instance_command_fence_guard_v40
 before insert or update or delete on ap_approval_instance_command_fence
 for each row execute function ap_guard_approval_instance_command_fence_v40();

create function ap_guard_approval_instance_command_fence_event_v40()
returns trigger language plpgsql as $$
declare current_row ap_approval_instance_command_fence%rowtype;
 previous_status varchar(16);
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',
   message='approval instance command fence events are append-only';
 end if;
 if (new.payload_json->>'eventId')::uuid is distinct from new.event_id
  or (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'fenceId')::uuid is distinct from new.fence_id
  or (new.payload_json->>'approvalInstanceId')::uuid
    is distinct from new.approval_instance_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'fromStatus') is distinct from new.from_status
  or (new.payload_json->>'toStatus') is distinct from new.to_status
  or (new.payload_json->>'leaseActor') is distinct from new.lease_actor
  or (new.payload_json->>'leaseOwner') is distinct from new.lease_owner
  or ap_migration_json_instant_v37(new.payload_json,'leaseUntil')
    is distinct from new.lease_until
  or ap_migration_json_instant_v37(new.payload_json,'happenedAt')
    is distinct from new.happened_at
  or (new.payload_json->>'requestId') is distinct from new.request_id
  or (new.payload_json->>'traceId') is distinct from new.trace_id then
  raise exception using errcode='23514',
   message='approval instance command fence event payload mismatch';
 end if;
 select * into current_row from ap_approval_instance_command_fence
  where tenant_id=new.tenant_id and fence_id=new.fence_id;
 if not found or current_row.approval_instance_id<>new.approval_instance_id
  or current_row.attempt_id<>new.attempt_id
  or current_row.revision<>new.revision
  or current_row.status<>new.to_status
  or current_row.lease_owner<>new.lease_owner
  or current_row.lease_until<>new.lease_until
  or current_row.updated_at<>new.happened_at then
  raise exception using errcode='23514',
   message='command fence event does not match current durable state';
 end if;
 if new.revision=1 then
  if new.from_status is not null or new.to_status<>'ACTIVE'
   or new.lease_actor<>new.lease_owner then
   raise exception using errcode='23514',
    message='initial command fence event must create actor-owned ACTIVE revision 1';
  end if;
 else
  select to_status into previous_status
   from ap_approval_instance_command_fence_event
   where tenant_id=new.tenant_id and fence_id=new.fence_id
    and revision=new.revision-1;
  if previous_status is null or previous_status<>new.from_status
   or new.from_status<>'ACTIVE' then
   raise exception using errcode='23514',
    message='command fence event chain is not contiguous';
  end if;
 end if;
 return new;
end $$;

create trigger trg_approval_instance_command_fence_event_guard_v40
 before insert or update or delete on ap_approval_instance_command_fence_event
 for each row execute function ap_guard_approval_instance_command_fence_event_v40();

create function ap_require_approval_instance_command_fence_event_v40()
returns trigger language plpgsql as $$
begin
 if not exists (
  select 1 from ap_approval_instance_command_fence_event event
   where event.tenant_id=new.tenant_id and event.fence_id=new.fence_id
    and event.revision=new.revision and event.to_status=new.status
    and event.lease_owner=new.lease_owner
    and event.lease_until=new.lease_until
    and event.happened_at=new.updated_at
 ) then
  raise exception using errcode='23514',
   message='command fence current row requires matching durable event';
 end if;
 return null;
end $$;

create constraint trigger trg_approval_instance_command_fence_event_required_v40
 after insert or update on ap_approval_instance_command_fence
 deferrable initially deferred
 for each row execute function ap_require_approval_instance_command_fence_event_v40();

create function ap_require_process_migration_claim_batch_links_v40()
returns trigger language plpgsql as $$
declare attempt_value text;
 fence_value text;
 position integer:=0;
begin
 for attempt_value in
  select value from jsonb_array_elements_text(new.claimed_attempt_ids)
 loop
  fence_value:=new.fence_ids->>position;
  if not exists (
   select 1 from ap_process_migration_attempt attempt
   join ap_approval_instance_command_fence fence
    on fence.tenant_id=attempt.tenant_id and fence.attempt_id=attempt.attempt_id
   where attempt.tenant_id=new.tenant_id
    and attempt.intent_id=new.intent_id
    and attempt.attempt_id=attempt_value::uuid
    and attempt.status='CLAIMED'
    and attempt.lease_owner=new.worker_id
    and fence.fence_id=fence_value::uuid
    and fence.status='ACTIVE'
    and fence.lease_owner=new.worker_id
    and attempt.updated_at=new.claimed_at
    and fence.updated_at=new.claimed_at
  ) then
   raise exception using errcode='23514',
    message='migration claim batch does not match claimed attempts and fences';
  end if;
  position:=position+1;
 end loop;
 return null;
end $$;

create constraint trigger trg_process_migration_claim_batch_links_v40
 after insert on ap_process_migration_claim_batch
 deferrable initially deferred
 for each row execute function ap_require_process_migration_claim_batch_links_v40();

create function ap_migration_json_instant_v37(payload jsonb, field_name text)
returns timestamptz language plpgsql immutable as $$
declare value jsonb; raw_value text;
begin
 value:=payload->field_name;
 if value is null or value='null'::jsonb then return null; end if;
 raw_value:=value#>>'{}';
 if jsonb_typeof(value)='number' then return to_timestamp(raw_value::double precision); end if;
 if jsonb_typeof(value)='string' then return raw_value::timestamptz; end if;
 raise exception using errcode='23514',message='migration timestamp payload must be string number or null';
end $$;

alter table ap_process_migration_attempt
 add column lease_actor varchar(200),
 add column engine_request_reference varchar(256),
 add column failure_class varchar(32),
 add column error_summary varchar(1000);

alter table ap_process_migration_attempt disable trigger user;
update ap_process_migration_attempt
 set lease_actor=case when status='CLAIMED' then lease_owner else null end,
     engine_request_reference=nullif(payload_json->>'engineRequestReference',''),
     failure_class=coalesce(nullif(payload_json->>'failureClass',''),'NONE'),
     error_summary=nullif(payload_json->>'errorSummary','');
alter table ap_process_migration_attempt enable trigger user;

alter table ap_process_migration_attempt alter column failure_class set not null;
alter table ap_process_migration_attempt
 add constraint ck_process_migration_attempt_failure_class_v37 check (
  failure_class in ('NONE','STALE_ASSESSMENT','STALE_BINDING','STALE_INSTANCE_STATE',
   'ENGINE_REJECTED','ENGINE_OUTCOME_UNKNOWN','VERIFICATION_MISMATCH',
   'PLATFORM_EVIDENCE_CONFLICT','RECONCILIATION_REQUIRED','INTERNAL')
 ),
 add constraint ck_process_migration_attempt_lease_v37 check (
  (status='CLAIMED' and lease_owner is not null and lease_until is not null
   and lease_until>updated_at)
  or (status<>'CLAIMED' and lease_owner is null and lease_until is null)
 ),
 add constraint ck_process_migration_attempt_request_v37 check (
  (status in ('ENGINE_REQUESTED','VERIFYING','UNKNOWN','RECONCILING','SUCCEEDED'))
   = (engine_request_reference is not null)
 ),
 add constraint ck_process_migration_attempt_failure_v37 check (
  (status in ('BLOCKED_STALE','FAILED_RETRYABLE','FAILED_TERMINAL','UNKNOWN','RECONCILING')
   and failure_class<>'NONE' and error_summary is not null)
  or (status not in ('BLOCKED_STALE','FAILED_RETRYABLE','FAILED_TERMINAL','UNKNOWN','RECONCILING')
   and failure_class='NONE' and error_summary is null)
 ),
 add constraint ck_process_migration_attempt_unknown_v37 check (
  status<>'UNKNOWN' or (engine_outcome='UNKNOWN'
   and engine_request_reference is not null
   and failure_class='ENGINE_OUTCOME_UNKNOWN'
   and error_summary is not null)
 );

alter table ap_process_migration_attempt_event
 add column engine_outcome varchar(32),
 add column lease_actor varchar(200),
 add column lease_owner varchar(200),
 add column lease_until timestamptz,
 add column engine_request_reference varchar(256),
 add column failure_class varchar(32),
 add column error_summary varchar(1000);

alter table ap_process_migration_attempt_event disable trigger user;
update ap_process_migration_attempt_event
 set engine_outcome=coalesce(nullif(payload_json->>'engineOutcome',''),'NOT_REQUESTED'),
     lease_actor=nullif(payload_json->>'leaseActor',''),
     lease_owner=nullif(payload_json->>'leaseOwner',''),
     lease_until=ap_migration_json_instant_v37(payload_json,'leaseUntil'),
     engine_request_reference=nullif(payload_json->>'engineRequestReference',''),
     failure_class=coalesce(nullif(payload_json->>'failureClass',''),'NONE'),
     error_summary=nullif(payload_json->>'errorSummary','');
alter table ap_process_migration_attempt_event enable trigger user;

alter table ap_process_migration_attempt_event alter column engine_outcome set not null;
alter table ap_process_migration_attempt_event alter column failure_class set not null;
alter table ap_process_migration_attempt_event
 add constraint ck_process_migration_attempt_event_outcome_v37 check (
  engine_outcome in ('NOT_REQUESTED','ACCEPTED','REJECTED','CONFIRMED','UNKNOWN','VERIFICATION_MISMATCH')
 ),
 add constraint ck_process_migration_attempt_event_failure_v37 check (
  failure_class in ('NONE','STALE_ASSESSMENT','STALE_BINDING','STALE_INSTANCE_STATE',
   'ENGINE_REJECTED','ENGINE_OUTCOME_UNKNOWN','VERIFICATION_MISMATCH',
   'PLATFORM_EVIDENCE_CONFLICT','RECONCILIATION_REQUIRED','INTERNAL')
 ),
 add constraint ck_process_migration_attempt_event_lease_pair_v37 check (
  (lease_owner is null and lease_until is null) or
  (lease_owner is not null and lease_until is not null)
 );

create or replace function ap_validate_process_migration_attempt_payload_v36()
returns trigger language plpgsql as $$
declare parent_row ap_process_migration_attempt%rowtype;
begin
 if (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
  or (new.payload_json->>'approvalInstanceId')::uuid is distinct from new.approval_instance_id
  or (new.payload_json->>'attemptNumber')::integer is distinct from new.attempt_number
  or (new.payload_json->>'parentAttemptId')::uuid is distinct from new.parent_attempt_id
  or (new.payload_json->>'status') is distinct from new.status
  or (new.payload_json->>'engineOutcome') is distinct from new.engine_outcome
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'leaseOwner') is distinct from new.lease_owner
  or ap_migration_json_instant_v37(new.payload_json,'leaseUntil') is distinct from new.lease_until
  or (new.payload_json->>'engineRequestReference') is distinct from new.engine_request_reference
  or (new.payload_json->>'failureClass') is distinct from new.failure_class
  or (new.payload_json->>'errorSummary') is distinct from new.error_summary
  or ap_migration_json_instant_v37(new.payload_json,'createdAt') is distinct from new.created_at
  or ap_migration_json_instant_v37(new.payload_json,'updatedAt') is distinct from new.updated_at
  or (new.payload_json->>'expectedBindingEvidenceHash') is distinct from new.expected_binding_evidence_hash
 then raise exception using errcode='23514',message='migration attempt payload does not match durable columns'; end if;
 if tg_op='INSERT' then
  if new.status<>'PENDING' or new.revision<>1 or new.engine_outcome<>'NOT_REQUESTED'
   or new.lease_actor is not null or new.lease_owner is not null or new.lease_until is not null
   or new.engine_request_reference is not null or new.failure_class<>'NONE'
   or new.error_summary is not null then
   raise exception using errcode='23514',message='migration attempt must begin unclaimed PENDING revision 1';
  end if;
  if new.attempt_number=1 then
   if new.parent_attempt_id is not null then
    raise exception using errcode='23514',message='first migration attempt cannot have a parent';
   end if;
  else
   select * into parent_row from ap_process_migration_attempt
    where tenant_id=new.tenant_id and attempt_id=new.parent_attempt_id;
   if not found then raise exception using errcode='23514',message='migration retry parent does not exist in tenant'; end if;
   if parent_row.intent_id<>new.intent_id
    or parent_row.approval_instance_id<>new.approval_instance_id
    or parent_row.attempt_number+1<>new.attempt_number
    or parent_row.status<>'FAILED_RETRYABLE' then
    raise exception using errcode='23514',message='migration retry must follow the immediate retryable parent';
   end if;
  end if;
 end if;
 if tg_op='UPDATE' and
  (new.payload_json - array['status','engineOutcome','revision','leaseOwner','leaseUntil',
   'engineRequestReference','failureClass','errorSummary','updatedAt']) is distinct from
  (old.payload_json - array['status','engineOutcome','revision','leaseOwner','leaseUntil',
   'engineRequestReference','failureClass','errorSummary','updatedAt'])
 then raise exception using errcode='55000',message='migration attempt stable payload is immutable'; end if;
 return new;
end $$;

create or replace function ap_guard_process_migration_attempt() returns trigger language plpgsql as $$
declare latest_reconciliation_status varchar(32);
begin
 if tg_op='DELETE' then raise exception using errcode='55000',message='migration attempt cannot be deleted'; end if;
 if new.tenant_id is distinct from old.tenant_id or new.attempt_id is distinct from old.attempt_id
  or new.intent_id is distinct from old.intent_id or new.approval_instance_id is distinct from old.approval_instance_id
  or new.attempt_number is distinct from old.attempt_number or new.parent_attempt_id is distinct from old.parent_attempt_id
  or new.expected_binding_evidence_hash is distinct from old.expected_binding_evidence_hash
  or new.created_at is distinct from old.created_at
 then raise exception using errcode='55000',message='migration attempt identity evidence is immutable'; end if;
 if new.revision<>old.revision+1 then raise exception using errcode='40001',message='migration attempt revision must advance exactly once'; end if;
 if not ((old.status='PENDING' and new.status in ('CLAIMED','BLOCKED_STALE','CANCELLED'))
  or (old.status='CLAIMED' and new.status in ('CLAIMED','ENGINE_REQUESTED','BLOCKED_STALE','FAILED_RETRYABLE','FAILED_TERMINAL','CANCELLED'))
  or (old.status='ENGINE_REQUESTED' and new.status in ('VERIFYING','UNKNOWN','FAILED_RETRYABLE','FAILED_TERMINAL'))
  or (old.status='VERIFYING' and new.status in ('SUCCEEDED','RECONCILING','FAILED_TERMINAL'))
  or (old.status='UNKNOWN' and new.status='RECONCILING')
  or (old.status='RECONCILING' and new.status in ('SUCCEEDED','BLOCKED_STALE','FAILED_TERMINAL')))
 then raise exception using errcode='23514',message='migration attempt transition is not permitted'; end if;

 if old.status='PENDING' and new.status='CLAIMED' then
  if new.lease_actor is null or new.lease_actor<>new.lease_owner then
   raise exception using errcode='23514',message='initial migration claim actor must own the lease';
  end if;
 elsif old.status='CLAIMED' then
  if new.status='CLAIMED' then
   if new.lease_actor is null then
    raise exception using errcode='23514',message='migration lease transition requires an actor';
   end if;
   if new.lease_actor=old.lease_owner then
    if new.lease_owner<>old.lease_owner or new.updated_at>=old.lease_until
     or new.lease_until<=old.lease_until then
     raise exception using errcode='23514',message='migration lease renewal requires current owner before expiry and an extension';
    end if;
   elsif new.updated_at<old.lease_until or new.lease_owner<>new.lease_actor then
    raise exception using errcode='23514',message='migration lease takeover requires expiry and new-owner evidence';
   end if;
  elsif new.lease_actor is distinct from old.lease_owner or new.updated_at>=old.lease_until then
   raise exception using errcode='23514',message='migration attempt advance requires current unexpired lease owner';
  end if;
 elsif new.lease_actor is not null then
  raise exception using errcode='23514',message='non-lease migration transition cannot name a lease actor';
 end if;

 if new.status='UNKNOWN' and (old.status<>'ENGINE_REQUESTED'
  or new.engine_outcome<>'UNKNOWN'
  or new.engine_request_reference is distinct from old.engine_request_reference
  or new.failure_class<>'ENGINE_OUTCOME_UNKNOWN' or new.error_summary is null) then
  raise exception using errcode='23514',message='UNKNOWN requires preserved durable engine request evidence';
 end if;
 if old.status='UNKNOWN' then
  select status into latest_reconciliation_status from ap_process_migration_reconciliation
   where tenant_id=old.tenant_id and attempt_id=old.attempt_id order by sequence desc limit 1;
  if latest_reconciliation_status is null
   or latest_reconciliation_status not in ('OPEN','MANUAL_REVIEW_REQUIRED')
   or new.engine_outcome<>'UNKNOWN'
   or new.engine_request_reference is distinct from old.engine_request_reference
   or new.failure_class<>'RECONCILIATION_REQUIRED' or new.error_summary is null then
   raise exception using errcode='23514',message='UNKNOWN requires open reconciliation before progression';
  end if;
 end if;
 if old.status='RECONCILING'
  and new.status in ('SUCCEEDED','BLOCKED_STALE','FAILED_TERMINAL')
  and exists (select 1 from ap_process_migration_attempt_event
   where tenant_id=old.tenant_id and attempt_id=old.attempt_id and to_status='UNKNOWN') then
  select status into latest_reconciliation_status from ap_process_migration_reconciliation
   where tenant_id=old.tenant_id and attempt_id=old.attempt_id order by sequence desc limit 1;
  if latest_reconciliation_status is null
   or latest_reconciliation_status not in ('RESOLVED_SOURCE','RESOLVED_TARGET','RESOLVED_TERMINAL','UNRESOLVED') then
   raise exception using errcode='23514',message='UNKNOWN-derived reconciliation requires terminal evidence before attempt closure';
  end if;
 end if;
 return new;
end $$;

create or replace function ap_guard_process_migration_attempt_event() returns trigger language plpgsql as $$
declare current_row ap_process_migration_attempt%rowtype; previous_status varchar(32);
begin
 select * into current_row from ap_process_migration_attempt where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if current_row.revision<>new.revision or current_row.status<>new.to_status
  or current_row.engine_outcome<>new.engine_outcome
  or current_row.lease_actor is distinct from new.lease_actor
  or current_row.lease_owner is distinct from new.lease_owner
  or current_row.lease_until is distinct from new.lease_until
  or current_row.engine_request_reference is distinct from new.engine_request_reference
  or current_row.failure_class<>new.failure_class
  or current_row.error_summary is distinct from new.error_summary
  or current_row.updated_at is distinct from new.happened_at then
  raise exception using errcode='23514',message='migration attempt event does not match current durable state';
 end if;
 if (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'eventId')::uuid is distinct from new.event_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'fromStatus') is distinct from new.from_status
  or (new.payload_json->>'toStatus') is distinct from new.to_status
  or (new.payload_json->>'engineOutcome') is distinct from new.engine_outcome
  or (new.payload_json->>'leaseActor') is distinct from new.lease_actor
  or (new.payload_json->>'leaseOwner') is distinct from new.lease_owner
  or ap_migration_json_instant_v37(new.payload_json,'leaseUntil') is distinct from new.lease_until
  or (new.payload_json->>'engineRequestReference') is distinct from new.engine_request_reference
  or (new.payload_json->>'failureClass') is distinct from new.failure_class
  or (new.payload_json->>'errorSummary') is distinct from new.error_summary
  or ap_migration_json_instant_v37(new.payload_json,'happenedAt') is distinct from new.happened_at
 then raise exception using errcode='23514',message='migration attempt event payload mismatch'; end if;
 if new.revision=1 then
  if new.from_status is not null or new.to_status<>'PENDING'
   or new.lease_actor is not null or new.engine_request_reference is not null then
   raise exception using errcode='23514',message='initial migration attempt event must create PENDING revision 1';
  end if;
 else
  select to_status into previous_status from ap_process_migration_attempt_event
   where tenant_id=new.tenant_id and attempt_id=new.attempt_id and revision=new.revision-1;
  if previous_status is null or previous_status<>new.from_status then
   raise exception using errcode='23514',message='migration attempt event chain is not contiguous';
  end if;
 end if;
 return new;
end $$;

create or replace function ap_require_process_migration_attempt_event_v36() returns trigger language plpgsql as $$
begin
 if not exists (select 1 from ap_process_migration_attempt_event e
  where e.tenant_id=new.tenant_id and e.attempt_id=new.attempt_id
   and e.revision=new.revision and e.to_status=new.status
   and e.engine_outcome=new.engine_outcome
   and e.lease_actor is not distinct from new.lease_actor
   and e.lease_owner is not distinct from new.lease_owner
   and e.lease_until is not distinct from new.lease_until
   and e.engine_request_reference is not distinct from new.engine_request_reference
   and e.failure_class=new.failure_class
   and e.error_summary is not distinct from new.error_summary
   and e.happened_at=new.updated_at) then
  raise exception using errcode='23514',message='migration attempt current row requires matching durable event';
 end if;
 return null;
end $$;

create function ap_validate_process_migration_intent_payload_v36() returns trigger language plpgsql as $$
begin
 if (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
  or (new.payload_json->>'idempotencyKey') is distinct from new.idempotency_key
  or (new.payload_json->>'planId')::uuid is distinct from new.plan_id
  or (new.payload_json->>'planHash') is distinct from new.plan_hash
  or (new.payload_json->>'definitionKey') is distinct from new.definition_key
  or (new.payload_json->>'sourceReleaseVersion')::integer is distinct from new.source_release_version
  or (new.payload_json->>'sourcePackageHash') is distinct from new.source_package_hash
  or (new.payload_json->>'targetReleaseVersion')::integer is distinct from new.target_release_version
  or (new.payload_json->>'targetPackageHash') is distinct from new.target_package_hash
  or (new.payload_json->>'status') is distinct from new.status
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'intentEvidenceHash') is distinct from new.intent_evidence_hash
 then raise exception using errcode='23514',message='migration intent payload does not match durable columns'; end if;
 if tg_op='INSERT' and (new.status<>'PENDING' or new.revision<>1) then
  raise exception using errcode='23514',message='migration intent must begin PENDING revision 1';
 end if;
 if tg_op='UPDATE' and
  (new.payload_json - array['status','revision','updatedAt']) is distinct from
  (old.payload_json - array['status','revision','updatedAt'])
 then raise exception using errcode='55000',message='migration intent stable payload is immutable'; end if;
 return new;
end $$;

create function ap_validate_process_migration_attempt_payload_v36() returns trigger language plpgsql as $$
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
  or (new.payload_json->>'expectedBindingEvidenceHash') is distinct from new.expected_binding_evidence_hash
 then raise exception using errcode='23514',message='migration attempt payload does not match durable columns'; end if;
 if tg_op='INSERT' then
  if new.status<>'PENDING' or new.revision<>1 or new.engine_outcome<>'NOT_REQUESTED'
   or new.lease_owner is not null or new.lease_until is not null then
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
  (new.payload_json - array['status','engineOutcome','revision','leaseOwner','leaseUntil','engineRequestReference','failureClass','errorSummary','updatedAt']) is distinct from
  (old.payload_json - array['status','engineOutcome','revision','leaseOwner','leaseUntil','engineRequestReference','failureClass','errorSummary','updatedAt'])
 then raise exception using errcode='55000',message='migration attempt stable payload is immutable'; end if;
 return new;
end $$;

create trigger trg_process_migration_intent_payload_v36 before insert or update on ap_process_migration_intent
 for each row execute function ap_validate_process_migration_intent_payload_v36();
create trigger trg_process_migration_attempt_payload_v36 before insert or update on ap_process_migration_attempt
 for each row execute function ap_validate_process_migration_attempt_payload_v36();

create or replace function ap_guard_process_migration_intent_event() returns trigger language plpgsql as $$
declare current_row ap_process_migration_intent%rowtype; previous_status varchar(24);
begin
 select * into current_row from ap_process_migration_intent where tenant_id=new.tenant_id and intent_id=new.intent_id;
 if current_row.revision<>new.revision or current_row.status<>new.to_status then
  raise exception using errcode='23514',message='migration intent event does not match current state';
 end if;
 if (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'eventId')::uuid is distinct from new.event_id
  or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'fromStatus') is distinct from new.from_status
  or (new.payload_json->>'toStatus') is distinct from new.to_status
 then raise exception using errcode='23514',message='migration intent event payload mismatch'; end if;
 if new.revision=1 then
  if new.from_status is not null or new.to_status<>'PENDING' then
   raise exception using errcode='23514',message='initial migration intent event must create PENDING revision 1';
  end if;
 else
  select to_status into previous_status from ap_process_migration_intent_event
   where tenant_id=new.tenant_id and intent_id=new.intent_id and revision=new.revision-1;
  if previous_status is null or previous_status<>new.from_status then
   raise exception using errcode='23514',message='migration intent event chain is not contiguous';
  end if;
 end if;
 return new;
end $$;

create or replace function ap_guard_process_migration_attempt_event() returns trigger language plpgsql as $$
declare current_row ap_process_migration_attempt%rowtype; previous_status varchar(32);
begin
 select * into current_row from ap_process_migration_attempt where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if current_row.revision<>new.revision or current_row.status<>new.to_status then
  raise exception using errcode='23514',message='migration attempt event does not match current state';
 end if;
 if (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'eventId')::uuid is distinct from new.event_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'fromStatus') is distinct from new.from_status
  or (new.payload_json->>'toStatus') is distinct from new.to_status
 then raise exception using errcode='23514',message='migration attempt event payload mismatch'; end if;
 if new.revision=1 then
  if new.from_status is not null or new.to_status<>'PENDING' then
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

create or replace function ap_guard_process_migration_verification_insert() returns trigger language plpgsql as $$
declare owner_intent uuid; expected_sequence integer;
begin
 select intent_id into owner_intent from ap_process_migration_attempt where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if owner_intent is null or owner_intent<>new.intent_id then raise exception using errcode='23514',message='migration verification lineage mismatch'; end if;
 if (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'verificationId')::uuid is distinct from new.verification_id
  or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'sequence')::integer is distinct from new.sequence
  or (new.payload_json->>'outcome') is distinct from new.outcome
  or (new.payload_json->>'evidenceHash') is distinct from new.evidence_hash
 then raise exception using errcode='23514',message='migration verification payload mismatch'; end if;
 select coalesce(max(sequence),0)+1 into expected_sequence from ap_process_migration_verification where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if new.sequence<>expected_sequence then raise exception using errcode='23514',message='migration verification sequence must advance exactly once'; end if;
 return new;
end $$;

create or replace function ap_guard_process_migration_reconciliation_insert() returns trigger language plpgsql as $$
declare owner_intent uuid; expected_sequence integer; previous_status varchar(32);
begin
 select intent_id into owner_intent from ap_process_migration_attempt where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if owner_intent is null or owner_intent<>new.intent_id then raise exception using errcode='23514',message='migration reconciliation lineage mismatch'; end if;
 if (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'reconciliationId')::uuid is distinct from new.reconciliation_id
  or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
  or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
  or (new.payload_json->>'sequence')::integer is distinct from new.sequence
  or (new.payload_json->>'status') is distinct from new.status
  or (new.payload_json->>'evidenceHash') is distinct from new.evidence_hash
  or (new.payload_json->>'resolutionEvidenceHash') is distinct from new.resolution_evidence_hash
 then raise exception using errcode='23514',message='migration reconciliation payload mismatch'; end if;
 select coalesce(max(sequence),0)+1 into expected_sequence from ap_process_migration_reconciliation where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if new.sequence<>expected_sequence then raise exception using errcode='23514',message='migration reconciliation sequence must advance exactly once'; end if;
 select status into previous_status from ap_process_migration_reconciliation where tenant_id=new.tenant_id and attempt_id=new.attempt_id order by sequence desc limit 1;
 if previous_status is null and new.status<>'OPEN' then raise exception using errcode='23514',message='migration reconciliation must begin OPEN'; end if;
 if previous_status='OPEN' and new.status not in ('MANUAL_REVIEW_REQUIRED','RESOLVED_SOURCE','RESOLVED_TARGET','RESOLVED_TERMINAL','UNRESOLVED') then raise exception using errcode='23514',message='migration reconciliation transition is not permitted'; end if;
 if previous_status='MANUAL_REVIEW_REQUIRED' and new.status not in ('RESOLVED_SOURCE','RESOLVED_TARGET','RESOLVED_TERMINAL','UNRESOLVED') then raise exception using errcode='23514',message='migration reconciliation transition is not permitted'; end if;
 if previous_status in ('RESOLVED_SOURCE','RESOLVED_TARGET','RESOLVED_TERMINAL','UNRESOLVED') then raise exception using errcode='55000',message='terminal migration reconciliation cannot advance'; end if;
 return new;
end $$;

create function ap_require_process_migration_intent_event_v36() returns trigger language plpgsql as $$
begin
 if not exists (select 1 from ap_process_migration_intent_event
  where tenant_id=new.tenant_id and intent_id=new.intent_id and revision=new.revision and to_status=new.status) then
  raise exception using errcode='23514',message='migration intent current row requires matching event';
 end if;
 return null;
end $$;
create function ap_require_process_migration_attempt_event_v36() returns trigger language plpgsql as $$
begin
 if not exists (select 1 from ap_process_migration_attempt_event
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id and revision=new.revision and to_status=new.status) then
  raise exception using errcode='23514',message='migration attempt current row requires matching event';
 end if;
 return null;
end $$;
create constraint trigger trg_process_migration_intent_event_required_v36 after insert or update on ap_process_migration_intent
 deferrable initially deferred for each row execute function ap_require_process_migration_intent_event_v36();
create constraint trigger trg_process_migration_attempt_event_required_v36 after insert or update on ap_process_migration_attempt
 deferrable initially deferred for each row execute function ap_require_process_migration_attempt_event_v36();

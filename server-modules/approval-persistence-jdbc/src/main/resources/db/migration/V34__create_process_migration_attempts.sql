create table ap_process_migration_attempt (
 tenant_id varchar(128) not null, attempt_id uuid not null, intent_id uuid not null,
 approval_instance_id uuid not null, attempt_number integer not null, parent_attempt_id uuid,
 status varchar(32) not null, revision bigint not null, engine_outcome varchar(32) not null,
 lease_owner varchar(200), lease_until timestamptz, expected_binding_evidence_hash char(64) not null,
 payload_json jsonb not null, created_at timestamptz not null, updated_at timestamptz not null,
 primary key (tenant_id,attempt_id), unique (tenant_id,intent_id,approval_instance_id,attempt_number),
 foreign key (tenant_id,intent_id) references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,parent_attempt_id) references ap_process_migration_attempt (tenant_id,attempt_id),
 check (attempt_number>0 and revision>0 and updated_at>=created_at),
 check ((attempt_number=1 and parent_attempt_id is null) or (attempt_number>1 and parent_attempt_id is not null)),
 check (expected_binding_evidence_hash ~ '^[0-9a-f]{64}$' and jsonb_typeof(payload_json)='object'),
 check (status in ('PENDING','CLAIMED','ENGINE_REQUESTED','VERIFYING','UNKNOWN','RECONCILING','SUCCEEDED','BLOCKED_STALE','FAILED_RETRYABLE','FAILED_TERMINAL','CANCELLED')),
 check (engine_outcome in ('NOT_REQUESTED','ACCEPTED','REJECTED','CONFIRMED','UNKNOWN','VERIFICATION_MISMATCH')),
 check ((status='CLAIMED' and lease_owner is not null and lease_until is not null)
  or (status<>'CLAIMED' and lease_owner is null and lease_until is null))
);
create unique index uk_process_migration_attempt_active_instance on ap_process_migration_attempt(tenant_id,approval_instance_id)
 where status in ('PENDING','CLAIMED','ENGINE_REQUESTED','VERIFYING','UNKNOWN','RECONCILING');
create index idx_process_migration_attempt_intent on ap_process_migration_attempt(tenant_id,intent_id,attempt_number);
create table ap_process_migration_attempt_event (
 tenant_id varchar(128) not null, event_id uuid not null, attempt_id uuid not null,
 revision bigint not null, from_status varchar(32), to_status varchar(32) not null,
 payload_json jsonb not null, happened_at timestamptz not null,
 primary key (tenant_id,event_id), unique (tenant_id,attempt_id,revision),
 foreign key (tenant_id,attempt_id) references ap_process_migration_attempt (tenant_id,attempt_id),
 check (revision>0 and jsonb_typeof(payload_json)='object')
);
create function ap_guard_process_migration_attempt() returns trigger language plpgsql as $$
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
  or (old.status='CLAIMED' and new.status in ('ENGINE_REQUESTED','BLOCKED_STALE','FAILED_RETRYABLE','FAILED_TERMINAL','CANCELLED'))
  or (old.status='ENGINE_REQUESTED' and new.status in ('VERIFYING','UNKNOWN','FAILED_RETRYABLE','FAILED_TERMINAL'))
  or (old.status='VERIFYING' and new.status in ('SUCCEEDED','RECONCILING','FAILED_TERMINAL'))
  or (old.status='UNKNOWN' and new.status='RECONCILING')
  or (old.status='RECONCILING' and new.status in ('SUCCEEDED','BLOCKED_STALE','FAILED_TERMINAL')))
 then raise exception using errcode='23514',message='migration attempt transition is not permitted'; end if;
 return new;
end $$;
create function ap_guard_process_migration_attempt_event() returns trigger language plpgsql as $$
declare current_row ap_process_migration_attempt%rowtype;
begin
 select * into current_row from ap_process_migration_attempt where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if current_row.revision<>new.revision or current_row.status<>new.to_status then
  raise exception using errcode='23514',message='migration attempt event does not match current state';
 end if;
 if new.revision=1 and (new.from_status is not null or new.to_status<>'PENDING') then
  raise exception using errcode='23514',message='initial migration attempt event must create PENDING revision 1';
 end if;
 return new;
end $$;
create trigger trg_process_migration_attempt_guard before update or delete on ap_process_migration_attempt for each row execute function ap_guard_process_migration_attempt();
create trigger trg_process_migration_attempt_event_guard before insert on ap_process_migration_attempt_event for each row execute function ap_guard_process_migration_attempt_event();
create trigger trg_process_migration_attempt_event_append_only before update or delete on ap_process_migration_attempt_event for each row execute function ap_reject_process_migration_evidence_mutation();

create table ap_process_migration_verification (
 tenant_id varchar(128) not null, verification_id uuid not null, intent_id uuid not null,
 attempt_id uuid not null, sequence integer not null, outcome varchar(48) not null,
 evidence_hash char(64) not null, payload_json jsonb not null, recorded_at timestamptz not null,
 primary key (tenant_id,verification_id), unique (tenant_id,attempt_id,sequence),
 foreign key (tenant_id,intent_id) references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,attempt_id) references ap_process_migration_attempt (tenant_id,attempt_id),
 check (sequence>0 and evidence_hash ~ '^[0-9a-f]{64}$' and jsonb_typeof(payload_json)='object'),
 check (outcome in ('SOURCE_CONFIRMED','TARGET_CONFIRMED','TARGET_TERMINAL_CONFIRMED','MISSING_NO_EVIDENCE','MIXED_RECONCILIATION_REQUIRED','INCOMPLETE_RECONCILIATION_REQUIRED'))
);
create table ap_process_migration_reconciliation (
 tenant_id varchar(128) not null, reconciliation_id uuid not null, intent_id uuid not null,
 attempt_id uuid not null, sequence integer not null, status varchar(32) not null,
 evidence_hash char(64) not null, resolution_evidence_hash char(64), payload_json jsonb not null,
 recorded_at timestamptz not null, resolved_at timestamptz,
 primary key (tenant_id,reconciliation_id), unique (tenant_id,attempt_id,sequence),
 foreign key (tenant_id,intent_id) references ap_process_migration_intent (tenant_id,intent_id),
 foreign key (tenant_id,attempt_id) references ap_process_migration_attempt (tenant_id,attempt_id),
 check (sequence>0 and evidence_hash ~ '^[0-9a-f]{64}$' and jsonb_typeof(payload_json)='object'),
 check (resolution_evidence_hash is null or resolution_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (status in ('OPEN','MANUAL_REVIEW_REQUIRED','RESOLVED_SOURCE','RESOLVED_TARGET','RESOLVED_TERMINAL','UNRESOLVED')),
 check ((status in ('OPEN','MANUAL_REVIEW_REQUIRED') and resolution_evidence_hash is null and resolved_at is null)
  or (status in ('RESOLVED_SOURCE','RESOLVED_TARGET','RESOLVED_TERMINAL','UNRESOLVED')
      and resolution_evidence_hash is not null and resolved_at is not null))
);
create index idx_process_migration_verification_attempt on ap_process_migration_verification(tenant_id,attempt_id,sequence);
create index idx_process_migration_reconciliation_attempt on ap_process_migration_reconciliation(tenant_id,attempt_id,sequence);
create function ap_guard_process_migration_verification_insert() returns trigger language plpgsql as $$
declare owner_intent uuid; expected_sequence integer;
begin
 select intent_id into owner_intent from ap_process_migration_attempt where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if owner_intent is null or owner_intent<>new.intent_id then raise exception using errcode='23514',message='migration verification lineage mismatch'; end if;
 select coalesce(max(sequence),0)+1 into expected_sequence from ap_process_migration_verification where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if new.sequence<>expected_sequence then raise exception using errcode='23514',message='migration verification sequence must advance exactly once'; end if;
 return new;
end $$;
create function ap_guard_process_migration_reconciliation_insert() returns trigger language plpgsql as $$
declare owner_intent uuid; expected_sequence integer; previous_status varchar(32);
begin
 select intent_id into owner_intent from ap_process_migration_attempt where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if owner_intent is null or owner_intent<>new.intent_id then raise exception using errcode='23514',message='migration reconciliation lineage mismatch'; end if;
 select coalesce(max(sequence),0)+1 into expected_sequence from ap_process_migration_reconciliation where tenant_id=new.tenant_id and attempt_id=new.attempt_id;
 if new.sequence<>expected_sequence then raise exception using errcode='23514',message='migration reconciliation sequence must advance exactly once'; end if;
 select status into previous_status from ap_process_migration_reconciliation where tenant_id=new.tenant_id and attempt_id=new.attempt_id order by sequence desc limit 1;
 if previous_status is null and new.status<>'OPEN' then raise exception using errcode='23514',message='migration reconciliation must begin OPEN'; end if;
 if previous_status='OPEN' and new.status not in ('MANUAL_REVIEW_REQUIRED','RESOLVED_SOURCE','RESOLVED_TARGET','RESOLVED_TERMINAL','UNRESOLVED') then raise exception using errcode='23514',message='migration reconciliation transition is not permitted'; end if;
 if previous_status='MANUAL_REVIEW_REQUIRED' and new.status not in ('RESOLVED_SOURCE','RESOLVED_TARGET','RESOLVED_TERMINAL','UNRESOLVED') then raise exception using errcode='23514',message='migration reconciliation transition is not permitted'; end if;
 if previous_status in ('RESOLVED_SOURCE','RESOLVED_TARGET','RESOLVED_TERMINAL','UNRESOLVED') then raise exception using errcode='55000',message='terminal migration reconciliation cannot advance'; end if;
 return new;
end $$;
create trigger trg_process_migration_verification_guard before insert on ap_process_migration_verification for each row execute function ap_guard_process_migration_verification_insert();
create trigger trg_process_migration_reconciliation_guard before insert on ap_process_migration_reconciliation for each row execute function ap_guard_process_migration_reconciliation_insert();
create trigger trg_process_migration_verification_append_only before update or delete on ap_process_migration_verification for each row execute function ap_reject_process_migration_evidence_mutation();
create trigger trg_process_migration_reconciliation_append_only before update or delete on ap_process_migration_reconciliation for each row execute function ap_reject_process_migration_evidence_mutation();

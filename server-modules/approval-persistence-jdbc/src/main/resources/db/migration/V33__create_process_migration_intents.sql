create table ap_process_migration_intent (
 tenant_id varchar(128) not null, intent_id uuid not null, idempotency_key varchar(200) not null,
 plan_id uuid not null, plan_hash char(64) not null, definition_key varchar(64) not null,
 source_release_version integer not null, source_package_hash char(64) not null,
 target_release_version integer not null, target_package_hash char(64) not null,
 status varchar(24) not null, revision bigint not null, intent_evidence_hash char(64) not null,
 payload_json jsonb not null, created_at timestamptz not null, updated_at timestamptz not null,
 primary key (tenant_id,intent_id), unique (tenant_id,idempotency_key), unique (tenant_id,plan_id),
 unique (tenant_id,plan_hash),
 foreign key (tenant_id,definition_key,source_release_version,source_package_hash)
  references ap_approval_release_package (tenant_id,definition_key,release_version,package_hash),
 foreign key (tenant_id,definition_key,target_release_version,target_package_hash)
  references ap_approval_release_package (tenant_id,definition_key,release_version,package_hash),
 check (plan_hash ~ '^[0-9a-f]{64}$' and intent_evidence_hash ~ '^[0-9a-f]{64}$'),
 check (source_package_hash ~ '^[0-9a-f]{64}$' and target_package_hash ~ '^[0-9a-f]{64}$'),
 check (source_release_version > 0 and target_release_version > 0 and source_release_version <> target_release_version),
 check (source_package_hash <> target_package_hash),
 check (status in ('PENDING','RUNNING','RECONCILING','COMPLETED','PARTIAL','FAILED','CANCELLED')),
 check (revision > 0 and updated_at >= created_at and jsonb_typeof(payload_json)='object')
);
create table ap_process_migration_intent_event (
 tenant_id varchar(128) not null, event_id uuid not null, intent_id uuid not null,
 revision bigint not null, from_status varchar(24), to_status varchar(24) not null,
 payload_json jsonb not null, happened_at timestamptz not null,
 primary key (tenant_id,event_id), unique (tenant_id,intent_id,revision),
 foreign key (tenant_id,intent_id) references ap_process_migration_intent (tenant_id,intent_id),
 check (revision > 0 and jsonb_typeof(payload_json)='object')
);
create index idx_process_migration_intent_status on ap_process_migration_intent(tenant_id,status,updated_at,intent_id);
create index idx_process_migration_intent_event on ap_process_migration_intent_event(tenant_id,intent_id,revision);
create function ap_guard_process_migration_intent() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' then raise exception using errcode='55000',message='migration intent cannot be deleted'; end if;
 if new.tenant_id is distinct from old.tenant_id or new.intent_id is distinct from old.intent_id
  or new.idempotency_key is distinct from old.idempotency_key or new.plan_id is distinct from old.plan_id
  or new.plan_hash is distinct from old.plan_hash or new.definition_key is distinct from old.definition_key
  or new.source_release_version is distinct from old.source_release_version
  or new.source_package_hash is distinct from old.source_package_hash
  or new.target_release_version is distinct from old.target_release_version
  or new.target_package_hash is distinct from old.target_package_hash
  or new.intent_evidence_hash is distinct from old.intent_evidence_hash or new.created_at is distinct from old.created_at
 then raise exception using errcode='55000',message='migration intent identity evidence is immutable'; end if;
 if new.revision<>old.revision+1 then raise exception using errcode='40001',message='migration intent revision must advance exactly once'; end if;
 if not ((old.status='PENDING' and new.status in ('RUNNING','FAILED','CANCELLED'))
  or (old.status='RUNNING' and new.status in ('RECONCILING','COMPLETED','PARTIAL','FAILED','CANCELLED'))
  or (old.status='RECONCILING' and new.status in ('COMPLETED','PARTIAL','FAILED')))
 then raise exception using errcode='23514',message='migration intent transition is not permitted'; end if;
 return new;
end $$;
create function ap_guard_process_migration_intent_event() returns trigger language plpgsql as $$
declare current_row ap_process_migration_intent%rowtype;
begin
 select * into current_row from ap_process_migration_intent where tenant_id=new.tenant_id and intent_id=new.intent_id;
 if current_row.revision<>new.revision or current_row.status<>new.to_status then
  raise exception using errcode='23514',message='migration intent event does not match current state';
 end if;
 if new.revision=1 and (new.from_status is not null or new.to_status<>'PENDING') then
  raise exception using errcode='23514',message='initial migration intent event must create PENDING revision 1';
 end if;
 return new;
end $$;
create function ap_reject_process_migration_evidence_mutation() returns trigger language plpgsql as $$
begin raise exception using errcode='55000',message='migration protocol evidence is append-only'; end $$;
create trigger trg_process_migration_intent_guard before update or delete on ap_process_migration_intent for each row execute function ap_guard_process_migration_intent();
create trigger trg_process_migration_intent_event_guard before insert on ap_process_migration_intent_event for each row execute function ap_guard_process_migration_intent_event();
create trigger trg_process_migration_intent_event_append_only before update or delete on ap_process_migration_intent_event for each row execute function ap_reject_process_migration_evidence_mutation();

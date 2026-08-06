-- M6-F P4: durable hash-only controlled-automation lineage.
-- This migration introduces no command implementation, Provider call, connector invocation,
-- Flowable access, raw Proposal parameter value, credential, Secret, Queue, Worker or Scheduler.

create table ap_ai_controlled_automation_lineage (
 tenant_evidence_hash varchar(64) not null,
 proposal_id uuid not null,
 confirmation_id uuid not null,
 operator_evidence_hash varchar(64) not null,
 proposal_lineage_hash varchar(64) not null,
 confirmation_evidence_hash varchar(64) not null,
 canonical_action_type varchar(96) not null,
 resource_evidence_hash varchar(64) not null,
 whitelist_version varchar(160) not null,
 policy_version varchar(160) not null,
 registration_idempotency_key_hash varchar(64) not null,
 registration_idempotency_payload_hash varchar(64) not null,
 registration_evidence_hash varchar(64) not null,
 revision bigint not null,
 status varchar(16) not null,
 outcome varchar(16) not null,
 command_attempts smallint not null,
 automatic_retry_allowed boolean not null,
 confirmed_at timestamptz not null,
 expires_at timestamptz not null,
 updated_at timestamptz not null,
 current_evidence_hash varchar(64) not null,
 current_event_hash varchar(64) not null,
 primary key (tenant_evidence_hash,proposal_id),
 unique (tenant_evidence_hash,confirmation_id),
 unique (tenant_evidence_hash,registration_idempotency_key_hash),
 check (tenant_evidence_hash ~ '^[0-9a-f]{64}$'
  and operator_evidence_hash ~ '^[0-9a-f]{64}$'
  and proposal_lineage_hash ~ '^[0-9a-f]{64}$'
  and confirmation_evidence_hash ~ '^[0-9a-f]{64}$'
  and resource_evidence_hash ~ '^[0-9a-f]{64}$'
  and registration_idempotency_key_hash ~ '^[0-9a-f]{64}$'
  and registration_idempotency_payload_hash ~ '^[0-9a-f]{64}$'
  and registration_evidence_hash ~ '^[0-9a-f]{64}$'
  and current_evidence_hash ~ '^[0-9a-f]{64}$'
  and current_event_hash ~ '^[0-9a-f]{64}$'),
 check (canonical_action_type ~ '^[A-Z][A-Z0-9_]{2,95}$'),
 check (btrim(whitelist_version)<>'' and btrim(policy_version)<>''),
 check (revision in (1,2)),
 check (status in ('CONFIRMED','CANCELLED','SUCCEEDED','FAILED','PARTIAL','UNKNOWN')),
 check (outcome in ('NONE','SUCCESS','FAILURE','PARTIAL','UNKNOWN')),
 check (command_attempts between 0 and 1),
 check (not automatic_retry_allowed),
 check (expires_at>confirmed_at and updated_at>=confirmed_at),
 check ((revision=1 and status='CONFIRMED' and outcome='NONE'
   and command_attempts=0 and updated_at=confirmed_at
   and current_evidence_hash=registration_evidence_hash)
  or (revision=2 and status='CANCELLED' and outcome='NONE' and command_attempts=0)
  or (revision=2 and status='SUCCEEDED' and outcome='SUCCESS' and command_attempts=1)
  or (revision=2 and status='FAILED' and outcome='FAILURE' and command_attempts=1)
  or (revision=2 and status='PARTIAL' and outcome='PARTIAL' and command_attempts=1)
  or (revision=2 and status='UNKNOWN' and outcome='UNKNOWN' and command_attempts=1))
);

create table ap_ai_controlled_automation_lineage_event (
 tenant_evidence_hash varchar(64) not null,
 event_id uuid not null,
 proposal_id uuid not null,
 revision bigint not null,
 event_type varchar(16) not null,
 operator_evidence_hash varchar(64) not null,
 from_status varchar(16),
 to_status varchar(16) not null,
 outcome varchar(16) not null,
 operation_hash varchar(64) not null,
 result_evidence_hash varchar(64) not null,
 idempotency_key_hash varchar(64) not null,
 idempotency_payload_hash varchar(64) not null,
 command_attempts smallint not null,
 automatic_retry_allowed boolean not null,
 predecessor_hash varchar(64) not null,
 event_hash varchar(64) not null,
 happened_at timestamptz not null,
 primary key (tenant_evidence_hash,event_id),
 unique (tenant_evidence_hash,proposal_id,revision),
 unique (tenant_evidence_hash,idempotency_key_hash),
 unique (tenant_evidence_hash,proposal_id,event_hash),
 foreign key (tenant_evidence_hash,proposal_id)
  references ap_ai_controlled_automation_lineage (tenant_evidence_hash,proposal_id),
 check (tenant_evidence_hash ~ '^[0-9a-f]{64}$'
  and operator_evidence_hash ~ '^[0-9a-f]{64}$'
  and operation_hash ~ '^[0-9a-f]{64}$'
  and result_evidence_hash ~ '^[0-9a-f]{64}$'
  and idempotency_key_hash ~ '^[0-9a-f]{64}$'
  and idempotency_payload_hash ~ '^[0-9a-f]{64}$'
  and predecessor_hash ~ '^[0-9a-f]{64}$'
  and event_hash ~ '^[0-9a-f]{64}$'),
 check (revision in (1,2)),
 check (event_type in ('REGISTERED','TERMINATED')),
 check (from_status is null or from_status in (
  'CONFIRMED','CANCELLED','SUCCEEDED','FAILED','PARTIAL','UNKNOWN'
 )),
 check (to_status in ('CONFIRMED','CANCELLED','SUCCEEDED','FAILED','PARTIAL','UNKNOWN')),
 check (outcome in ('NONE','SUCCESS','FAILURE','PARTIAL','UNKNOWN')),
 check (command_attempts between 0 and 1),
 check (not automatic_retry_allowed),
 check ((event_type='REGISTERED' and revision=1 and from_status is null
   and to_status='CONFIRMED' and outcome='NONE' and command_attempts=0
   and predecessor_hash=repeat('0',64) and operation_hash=result_evidence_hash)
  or (event_type='TERMINATED' and revision=2 and from_status='CONFIRMED'
   and predecessor_hash<>repeat('0',64)
   and ((to_status='CANCELLED' and outcome='NONE' and command_attempts=0)
    or (to_status='SUCCEEDED' and outcome='SUCCESS' and command_attempts=1)
    or (to_status='FAILED' and outcome='FAILURE' and command_attempts=1)
    or (to_status='PARTIAL' and outcome='PARTIAL' and command_attempts=1)
    or (to_status='UNKNOWN' and outcome='UNKNOWN' and command_attempts=1))))
);

create index idx_ai_controlled_automation_status_v50
 on ap_ai_controlled_automation_lineage (
  tenant_evidence_hash,status,updated_at,proposal_id
 );
create index idx_ai_controlled_automation_resource_v50
 on ap_ai_controlled_automation_lineage (
  tenant_evidence_hash,resource_evidence_hash,updated_at,proposal_id
 );
create index idx_ai_controlled_automation_event_v50
 on ap_ai_controlled_automation_lineage_event (
  tenant_evidence_hash,proposal_id,revision,event_id
 );

create function ap_guard_ai_controlled_automation_lineage_v50()
returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' then
  raise exception using errcode='55000',
   message='M6-F P4 controlled-automation lineage cannot be deleted';
 end if;
 if tg_op='INSERT' then
  if new.revision<>1 or new.status<>'CONFIRMED' or new.outcome<>'NONE'
   or new.command_attempts<>0 or new.automatic_retry_allowed
   or new.updated_at<>new.confirmed_at then
   raise exception using errcode='23514',
    message='M6-F P4 lineage must begin as non-executing confirmation revision one';
  end if;
  return new;
 end if;
 if old.tenant_evidence_hash<>new.tenant_evidence_hash
  or old.proposal_id<>new.proposal_id
  or old.confirmation_id<>new.confirmation_id
  or old.operator_evidence_hash<>new.operator_evidence_hash
  or old.proposal_lineage_hash<>new.proposal_lineage_hash
  or old.confirmation_evidence_hash<>new.confirmation_evidence_hash
  or old.canonical_action_type<>new.canonical_action_type
  or old.resource_evidence_hash<>new.resource_evidence_hash
  or old.whitelist_version<>new.whitelist_version
  or old.policy_version<>new.policy_version
  or old.registration_idempotency_key_hash<>new.registration_idempotency_key_hash
  or old.registration_idempotency_payload_hash<>new.registration_idempotency_payload_hash
  or old.registration_evidence_hash<>new.registration_evidence_hash
  or old.confirmed_at<>new.confirmed_at
  or old.expires_at<>new.expires_at then
  raise exception using errcode='55000',
   message='M6-F P4 immutable lineage identity cannot change';
 end if;
 if old.revision<>1 or old.status<>'CONFIRMED'
  or new.revision<>2 or new.status='CONFIRMED'
  or new.updated_at<old.updated_at
  or new.automatic_retry_allowed then
  raise exception using errcode='23514',
   message='M6-F P4 permits one ordered terminal CAS transition only';
 end if;
 return new;
end;
$$;

create function ap_guard_ai_controlled_automation_event_v50()
returns trigger language plpgsql as $$
declare
 current_revision bigint;
 current_status varchar(16);
 lineage_event_hash varchar(64);
 current_operator_hash varchar(64);
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',
   message='M6-F P4 controlled-automation events are append-only';
 end if;
 select stored.revision,stored.status,stored.current_event_hash,
       stored.operator_evidence_hash
 into current_revision,current_status,lineage_event_hash,current_operator_hash
 from ap_ai_controlled_automation_lineage stored
 where stored.tenant_evidence_hash=new.tenant_evidence_hash
  and stored.proposal_id=new.proposal_id;
 if current_revision is null then
  raise exception using errcode='23503',message='M6-F P4 lineage does not exist';
 end if;
 if current_operator_hash<>new.operator_evidence_hash then
  raise exception using errcode='23514',message='M6-F P4 operator evidence mismatch';
 end if;
 if new.event_type='REGISTERED' then
  if current_revision<>1 or current_status<>'CONFIRMED'
   or lineage_event_hash<>new.event_hash then
   raise exception using errcode='23514',
    message='M6-F P4 registration event does not match lineage revision one';
  end if;
  return new;
 end if;
 if current_revision<>1 or current_status<>'CONFIRMED'
  or new.revision<>2 or new.from_status<>'CONFIRMED'
  or new.predecessor_hash<>lineage_event_hash then
  raise exception using errcode='23514',
   message='M6-F P4 terminal event predecessor or CAS state mismatch';
 end if;
 return new;
end;
$$;

create function ap_verify_ai_controlled_automation_state_event_v50()
returns trigger language plpgsql as $$
declare
 matching_event integer;
begin
 select count(*) into matching_event
 from ap_ai_controlled_automation_lineage_event event
 where event.tenant_evidence_hash=new.tenant_evidence_hash
  and event.proposal_id=new.proposal_id
  and event.revision=new.revision
  and event.to_status=new.status
  and event.outcome=new.outcome
  and event.command_attempts=new.command_attempts
  and event.automatic_retry_allowed=new.automatic_retry_allowed
  and event.event_hash=new.current_event_hash
  and event.operation_hash=new.current_evidence_hash
  and event.happened_at=new.updated_at;
 if matching_event<>1 then
  raise exception using errcode='23514',
   message='M6-F P4 lineage state lacks an exact append-only event';
 end if;
 return null;
end;
$$;

create function ap_verify_ai_controlled_automation_event_state_v50()
returns trigger language plpgsql as $$
declare
 matching_state integer;
begin
 select count(*) into matching_state
 from ap_ai_controlled_automation_lineage state
 where state.tenant_evidence_hash=new.tenant_evidence_hash
  and state.proposal_id=new.proposal_id
  and state.revision=new.revision
  and state.status=new.to_status
  and state.outcome=new.outcome
  and state.command_attempts=new.command_attempts
  and state.automatic_retry_allowed=new.automatic_retry_allowed
  and state.current_event_hash=new.event_hash
  and state.current_evidence_hash=new.operation_hash
  and state.updated_at=new.happened_at;
 if matching_state<>1 then
  raise exception using errcode='23514',
   message='M6-F P4 append-only event lacks an exact lineage state';
 end if;
 return null;
end;
$$;

create trigger trg_ai_controlled_automation_lineage_guard_v50
 before insert or update or delete on ap_ai_controlled_automation_lineage
 for each row execute function ap_guard_ai_controlled_automation_lineage_v50();

create trigger trg_ai_controlled_automation_event_guard_v50
 before insert or update or delete on ap_ai_controlled_automation_lineage_event
 for each row execute function ap_guard_ai_controlled_automation_event_v50();

create constraint trigger trg_ai_controlled_automation_state_event_v50
 after insert or update on ap_ai_controlled_automation_lineage
 deferrable initially deferred
 for each row execute function ap_verify_ai_controlled_automation_state_event_v50();

create constraint trigger trg_ai_controlled_automation_event_state_v50
 after insert on ap_ai_controlled_automation_lineage_event
 deferrable initially deferred
 for each row execute function ap_verify_ai_controlled_automation_event_state_v50();

-- M5-D1: exact authorized-plan consumption and atomic intent admission.
-- This migration creates no plan, intent, attempt or engine request and changes no runtime binding.

-- V38 already provides exact composite unique keys for plan and authorization evidence.
-- V39 adds only the composite intent identity needed by the admission foreign key.
alter table ap_process_migration_intent
 add constraint uq_process_migration_intent_admission_v39
 unique (tenant_id,intent_id,plan_id,plan_hash,intent_evidence_hash);

create table ap_process_migration_plan_consumption (
 tenant_id varchar(128) not null,
 consumption_id uuid not null,
 plan_id uuid not null,
 plan_hash char(64) not null,
 authorization_id uuid not null,
 authorization_evidence_hash char(64) not null,
 intent_id uuid not null,
 intent_evidence_hash char(64) not null,
 idempotency_key varchar(200) not null,
 request_hash char(64) not null,
 consumed_by varchar(256) not null,
 reason varchar(1000) not null,
 consumed_at timestamptz not null,
 request_id varchar(256) not null,
 trace_id varchar(256),
 audit_chain_reference varchar(256) not null,
 payload_json jsonb not null,
 constraint pk_process_migration_plan_consumption_v39
  primary key (tenant_id,consumption_id),
 constraint uq_process_migration_plan_consumption_plan_v39
  unique (tenant_id,plan_id),
 constraint uq_process_migration_plan_consumption_intent_v39
  unique (tenant_id,intent_id),
 constraint uq_process_migration_plan_consumption_key_v39
  unique (tenant_id,idempotency_key),
 constraint fk_process_migration_plan_consumption_plan_v39
  foreign key (tenant_id,plan_id,plan_hash)
  references ap_process_migration_plan (tenant_id,plan_id,plan_hash),
 constraint fk_process_migration_plan_consumption_authorization_v39
  foreign key (tenant_id,authorization_id,authorization_evidence_hash)
  references ap_process_migration_plan_authorization (
   tenant_id,authorization_id,authorization_evidence_hash
  ),
 constraint fk_process_migration_plan_consumption_intent_v39
  foreign key (tenant_id,intent_id,plan_id,plan_hash,intent_evidence_hash)
  references ap_process_migration_intent (
   tenant_id,intent_id,plan_id,plan_hash,intent_evidence_hash
  ),
 constraint ck_process_migration_plan_consumption_hashes_v39 check (
  plan_hash ~ '^[0-9a-f]{64}$'
  and authorization_evidence_hash ~ '^[0-9a-f]{64}$'
  and intent_evidence_hash ~ '^[0-9a-f]{64}$'
  and request_hash ~ '^[0-9a-f]{64}$'
 ),
 constraint ck_process_migration_plan_consumption_text_v39 check (
  btrim(idempotency_key)<>'' and btrim(consumed_by)<>''
  and btrim(reason)<>'' and btrim(request_id)<>''
  and btrim(audit_chain_reference)<>''
 ),
 constraint ck_process_migration_plan_consumption_payload_v39 check (
  jsonb_typeof(payload_json)='object'
 )
);

create index idx_process_migration_plan_consumption_time_v39
 on ap_process_migration_plan_consumption (tenant_id,consumed_at,plan_id);

create function ap_guard_process_migration_plan_consumption_v39()
returns trigger language plpgsql as $$
declare plan_row ap_process_migration_plan%rowtype;
 intent_row ap_process_migration_intent%rowtype;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',
   message='migration plan consumption evidence is append-only';
 end if;
 if (new.payload_json->>'consumptionId')::uuid is distinct from new.consumption_id
  or (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'planId')::uuid is distinct from new.plan_id
  or (new.payload_json->>'planHash') is distinct from new.plan_hash
  or (new.payload_json->>'authorizationId')::uuid is distinct from new.authorization_id
  or (new.payload_json->>'authorizationEvidenceHash')
    is distinct from new.authorization_evidence_hash
  or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
  or (new.payload_json->>'intentEvidenceHash') is distinct from new.intent_evidence_hash
  or (new.payload_json->>'idempotencyKey') is distinct from new.idempotency_key
  or (new.payload_json->>'requestHash') is distinct from new.request_hash
  or (new.payload_json->>'consumedBy') is distinct from new.consumed_by
  or (new.payload_json->>'reason') is distinct from new.reason
  or ap_migration_json_instant_v37(new.payload_json,'consumedAt')
    is distinct from new.consumed_at
  or (new.payload_json->>'requestId') is distinct from new.request_id
  or (new.payload_json->>'traceId') is distinct from new.trace_id
  or (new.payload_json->>'auditChainReference') is distinct from new.audit_chain_reference then
  raise exception using errcode='23514',
   message='migration plan consumption payload does not match durable columns';
 end if;
 select * into plan_row from ap_process_migration_plan
  where tenant_id=new.tenant_id and plan_id=new.plan_id and plan_hash=new.plan_hash;
 if not found or plan_row.status<>'AUTHORIZED'
  or plan_row.authorization_id<>new.authorization_id
  or plan_row.authorization_evidence_hash<>new.authorization_evidence_hash
  or new.consumed_at<plan_row.authorized_at
  or new.consumed_at>=plan_row.expires_at
  or new.consumed_at>=plan_row.authorization_expires_at then
  raise exception using errcode='23514',
   message='migration plan consumption requires current exact authorization';
 end if;
 select * into intent_row from ap_process_migration_intent
  where tenant_id=new.tenant_id and intent_id=new.intent_id;
 if not found or intent_row.plan_id<>new.plan_id or intent_row.plan_hash<>new.plan_hash
  or intent_row.intent_evidence_hash<>new.intent_evidence_hash
  or intent_row.idempotency_key<>new.idempotency_key
  or intent_row.status<>'PENDING' or intent_row.revision<>1
  or intent_row.created_at<>new.consumed_at or intent_row.updated_at<>new.consumed_at then
  raise exception using errcode='23514',
   message='migration plan consumption requires exact initial intent evidence';
 end if;
 return new;
end $$;

create trigger trg_process_migration_plan_consumption_guard_v39
 before insert or update or delete on ap_process_migration_plan_consumption
 for each row execute function ap_guard_process_migration_plan_consumption_v39();

create or replace function ap_guard_process_migration_plan_v38()
returns trigger language plpgsql as $$
declare authorization_row ap_process_migration_plan_authorization%rowtype;
 consumption_row ap_process_migration_plan_consumption%rowtype;
begin
 if tg_op='DELETE' then
  raise exception using errcode='55000',message='migration plan cannot be deleted';
 end if;
 if new.tenant_id is distinct from old.tenant_id
  or new.plan_id is distinct from old.plan_id
  or new.idempotency_key is distinct from old.idempotency_key
  or new.plan_hash is distinct from old.plan_hash
  or new.assessment_id is distinct from old.assessment_id
  or new.assessment_report_hash is distinct from old.assessment_report_hash
  or new.definition_key is distinct from old.definition_key
  or new.source_release_version is distinct from old.source_release_version
  or new.source_package_hash is distinct from old.source_package_hash
  or new.target_release_version is distinct from old.target_release_version
  or new.target_package_hash is distinct from old.target_package_hash
  or new.target_deployment_record_id is distinct from old.target_deployment_record_id
  or new.target_engine_deployment_id is distinct from old.target_engine_deployment_id
  or new.target_engine_definition_id is distinct from old.target_engine_definition_id
  or new.target_engine_version is distinct from old.target_engine_version
  or new.selected_instance_count is distinct from old.selected_instance_count
  or new.requested_by is distinct from old.requested_by
  or new.operation_reason is distinct from old.operation_reason
  or new.assessed_at is distinct from old.assessed_at
  or new.created_at is distinct from old.created_at
  or new.expires_at is distinct from old.expires_at
  or new.request_id is distinct from old.request_id
  or new.trace_id is distinct from old.trace_id
  or new.audit_chain_reference is distinct from old.audit_chain_reference then
  raise exception using errcode='55000',message='migration plan identity evidence is immutable';
 end if;
 if new.revision<>old.revision+1 then
  raise exception using errcode='40001',
   message='migration plan revision must advance exactly once';
 end if;
 if old.status='PROPOSED' and new.status='AUTHORIZED' then
  if new.updated_at is distinct from new.authorized_at
   or new.authorization_id is null or new.authorization_evidence_hash is null
   or new.authorized_by is null or new.authorization_expires_at is null then
   raise exception using errcode='23514',
    message='authorized migration plan requires complete current authorization evidence';
  end if;
  select * into authorization_row from ap_process_migration_plan_authorization
   where tenant_id=new.tenant_id and authorization_id=new.authorization_id;
  if not found or authorization_row.plan_id<>new.plan_id
   or authorization_row.plan_hash<>new.plan_hash
   or authorization_row.authorization_evidence_hash<>new.authorization_evidence_hash
   or authorization_row.authorized_by<>new.authorized_by
   or authorization_row.decided_at<>new.authorized_at
   or authorization_row.expires_at<>new.authorization_expires_at then
   raise exception using errcode='23514',
    message='migration plan current authorization does not match append-only decision evidence';
  end if;
 elsif old.status='AUTHORIZED' and new.status='CONSUMED' then
  if new.authorization_id is distinct from old.authorization_id
   or new.authorization_evidence_hash is distinct from old.authorization_evidence_hash
   or new.authorized_by is distinct from old.authorized_by
   or new.authorized_at is distinct from old.authorized_at
   or new.authorization_expires_at is distinct from old.authorization_expires_at
   or new.updated_at<old.updated_at or new.updated_at>=old.expires_at
   or new.updated_at>=old.authorization_expires_at then
   raise exception using errcode='23514',
    message='consumed migration plan must preserve current authorization evidence';
  end if;
  select * into consumption_row from ap_process_migration_plan_consumption
   where tenant_id=new.tenant_id and plan_id=new.plan_id;
  if not found or consumption_row.plan_hash<>new.plan_hash
   or consumption_row.authorization_id<>new.authorization_id
   or consumption_row.authorization_evidence_hash<>new.authorization_evidence_hash
   or consumption_row.consumed_at<>new.updated_at then
   raise exception using errcode='23514',
    message='migration plan consumption requires exact append-only admission evidence';
  end if;
 else
  raise exception using errcode='23514',
   message='M5-D1 migration plan transition is not permitted';
 end if;
 return new;
end $$;

create or replace function ap_guard_process_migration_plan_event_v38()
returns trigger language plpgsql as $$
declare current_row ap_process_migration_plan%rowtype;
 previous_status varchar(16);
 consumption_row ap_process_migration_plan_consumption%rowtype;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='migration plan events are append-only';
 end if;
 if (new.payload_json->>'eventId')::uuid is distinct from new.event_id
  or (new.payload_json->>'tenantId') is distinct from new.tenant_id
  or (new.payload_json->>'planId')::uuid is distinct from new.plan_id
  or (new.payload_json->>'planHash') is distinct from new.plan_hash
  or (new.payload_json->>'revision')::bigint is distinct from new.revision
  or (new.payload_json->>'fromStatus') is distinct from new.from_status
  or (new.payload_json->>'toStatus') is distinct from new.to_status
  or (new.payload_json->>'actorId') is distinct from new.actor_id
  or (new.payload_json->>'reason') is distinct from new.reason
  or (new.payload_json->>'authorizationId')::uuid is distinct from new.authorization_id
  or (new.payload_json->>'authorizationEvidenceHash')
    is distinct from new.authorization_evidence_hash
  or ap_migration_json_instant_v37(new.payload_json,'happenedAt')
    is distinct from new.happened_at
  or (new.payload_json->>'requestId') is distinct from new.request_id
  or (new.payload_json->>'traceId') is distinct from new.trace_id
  or (new.payload_json->>'auditChainReference') is distinct from new.audit_chain_reference then
  raise exception using errcode='23514',
   message='migration plan event payload does not match durable columns';
 end if;
 select * into current_row from ap_process_migration_plan
  where tenant_id=new.tenant_id and plan_id=new.plan_id;
 if not found or current_row.plan_hash<>new.plan_hash
  or current_row.revision<>new.revision or current_row.status<>new.to_status
  or current_row.updated_at<>new.happened_at then
  raise exception using errcode='23514',
   message='migration plan event does not match current durable state';
 end if;
 if new.revision=1 then
  if new.from_status is not null or new.to_status<>'PROPOSED'
   or new.actor_id<>current_row.requested_by or new.authorization_id is not null then
   raise exception using errcode='23514',
    message='initial migration plan event must create PROPOSED revision 1';
  end if;
  return new;
 end if;
 select to_status into previous_status from ap_process_migration_plan_event
  where tenant_id=new.tenant_id and plan_id=new.plan_id and revision=new.revision-1;
 if previous_status is null or previous_status<>new.from_status
  or new.authorization_id is distinct from current_row.authorization_id
  or new.authorization_evidence_hash is distinct from current_row.authorization_evidence_hash then
  raise exception using errcode='23514',
   message='migration plan event chain or authorization evidence is invalid';
 end if;
 if new.from_status='PROPOSED' and new.to_status='AUTHORIZED' then
  if new.actor_id<>current_row.authorized_by then
   raise exception using errcode='23514',
    message='migration plan authorization event actor is invalid';
  end if;
 elsif new.from_status='AUTHORIZED' and new.to_status='CONSUMED' then
  select * into consumption_row from ap_process_migration_plan_consumption
   where tenant_id=new.tenant_id and plan_id=new.plan_id;
  if not found or consumption_row.plan_hash<>new.plan_hash
   or consumption_row.consumed_by<>new.actor_id
   or consumption_row.reason<>new.reason
   or consumption_row.consumed_at<>new.happened_at
   or consumption_row.request_id<>new.request_id
   or consumption_row.trace_id is distinct from new.trace_id
   or consumption_row.audit_chain_reference<>new.audit_chain_reference then
   raise exception using errcode='23514',
    message='migration plan consumption event does not match admission evidence';
  end if;
 else
  raise exception using errcode='23514',
   message='migration plan event transition is not permitted';
 end if;
 return new;
end $$;

create function ap_require_process_migration_plan_consumption_v39()
returns trigger language plpgsql as $$
begin
 if new.status<>'CONSUMED' and exists (
  select 1 from ap_process_migration_intent intent
   where intent.tenant_id=new.tenant_id and intent.plan_id=new.plan_id
 ) then
  raise exception using errcode='23514',
   message='unconsumed governed migration plan cannot have an execution intent';
 end if;
 if new.status='CONSUMED' and not exists (
  select 1 from ap_process_migration_plan_consumption consumption
   join ap_process_migration_intent intent
    on intent.tenant_id=consumption.tenant_id
    and intent.intent_id=consumption.intent_id
   where consumption.tenant_id=new.tenant_id
    and consumption.plan_id=new.plan_id
    and consumption.plan_hash=new.plan_hash
    and consumption.authorization_id=new.authorization_id
    and consumption.authorization_evidence_hash=new.authorization_evidence_hash
    and intent.plan_id=new.plan_id
    and intent.plan_hash=new.plan_hash
    and intent.intent_evidence_hash=consumption.intent_evidence_hash
 ) then
  raise exception using errcode='23514',
   message='consumed migration plan requires exact admitted intent evidence';
 end if;
 return null;
end $$;

create constraint trigger trg_process_migration_plan_consumption_required_v39
 after insert or update on ap_process_migration_plan
 deferrable initially deferred
 for each row execute function ap_require_process_migration_plan_consumption_v39();

create function ap_require_process_migration_intent_admission_v39()
returns trigger language plpgsql as $$
begin
 if exists (
  select 1 from ap_process_migration_plan plan
   where plan.tenant_id=new.tenant_id and plan.plan_id=new.plan_id
 ) and not exists (
  select 1 from ap_process_migration_plan_consumption consumption
   join ap_process_migration_plan plan
    on plan.tenant_id=consumption.tenant_id and plan.plan_id=consumption.plan_id
   where consumption.tenant_id=new.tenant_id
    and consumption.intent_id=new.intent_id
    and consumption.plan_id=new.plan_id
    and consumption.plan_hash=new.plan_hash
    and consumption.intent_evidence_hash=new.intent_evidence_hash
    and plan.plan_hash=new.plan_hash
    and plan.status='CONSUMED'
 ) then
  raise exception using errcode='23514',
   message='intent for governed migration plan requires exact consumption evidence';
 end if;
 return null;
end $$;

create constraint trigger trg_process_migration_intent_admission_required_v39
 after insert or update on ap_process_migration_intent
 deferrable initially deferred
 for each row execute function ap_require_process_migration_intent_admission_v39();

create function ap_require_process_migration_consumption_link_v39()
returns trigger language plpgsql as $$
begin
 if not exists (
  select 1 from ap_process_migration_plan plan
   join ap_process_migration_intent intent
    on intent.tenant_id=plan.tenant_id
    and intent.plan_id=plan.plan_id and intent.plan_hash=plan.plan_hash
   where plan.tenant_id=new.tenant_id and plan.plan_id=new.plan_id
    and plan.plan_hash=new.plan_hash and plan.status='CONSUMED'
    and plan.authorization_id=new.authorization_id
    and plan.authorization_evidence_hash=new.authorization_evidence_hash
    and intent.intent_id=new.intent_id
    and intent.intent_evidence_hash=new.intent_evidence_hash
 ) then
  raise exception using errcode='23514',
   message='migration consumption must link one consumed plan and one exact intent';
 end if;
 return null;
end $$;

create constraint trigger trg_process_migration_consumption_link_required_v39
 after insert on ap_process_migration_plan_consumption
 deferrable initially deferred
 for each row execute function ap_require_process_migration_consumption_link_v39();

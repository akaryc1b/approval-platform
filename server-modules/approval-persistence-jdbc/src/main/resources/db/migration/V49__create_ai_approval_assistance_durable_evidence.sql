-- M6-E P4: hash-only durable approval-assistance evidence and retention tombstones.
-- No raw Provider input/output, Prompt body, advisory text, JSON payload, Secret, network,
-- approval command, Flowable call, retry worker, scheduler or autonomous deletion is introduced.

create table ap_ai_approval_assistance_evidence (
 tenant_id varchar(128) not null,
 evidence_id uuid not null,
 request_evidence_hash char(64) not null,
 subject_evidence_hash char(64) not null,
 resource_evidence_hash char(64) not null,
 projection_evidence_hash char(64) not null,
 execution_evidence_hash char(64) not null,
 route_evidence_hash char(64),
 version_evidence_hash char(64) not null,
 outcome_evidence_hash char(64) not null,
 use_case varchar(32) not null,
 classification varchar(64) not null,
 provider_id varchar(120) not null,
 provider_version varchar(120) not null,
 model_provider_id varchar(120) not null,
 model_id varchar(160) not null,
 model_version varchar(160) not null,
 prompt_template_id varchar(160) not null,
 prompt_template_version varchar(120) not null,
 prompt_template_hash varchar(160) not null,
 knowledge_source_id varchar(160) not null,
 knowledge_source_version varchar(120) not null,
 knowledge_source_hash varchar(160) not null,
 knowledge_contains_customer_data boolean not null,
 policy_id varchar(160) not null,
 policy_version varchar(120) not null,
 policy_hash varchar(160) not null,
 output_schema_id varchar(160) not null,
 output_schema_version integer not null,
 provider_attempts smallint not null,
 provider_invocation_started boolean not null,
 retry_attempted boolean not null,
 post_invocation_fallback_attempted boolean not null,
 kill_switch_generation bigint not null,
 advisory_result_present boolean not null,
 observation_count smallint not null,
 risk_signal_count smallint not null,
 missing_material_count smallint not null,
 recommendation_count smallint not null,
 evidence_reference_count smallint not null,
 limitation_count smallint not null,
 confidence_score double precision,
 confidence_band varchar(16),
 requested_at timestamptz not null,
 recorded_at timestamptz not null,
 retention_until timestamptz not null,
 evidence_hash char(64) not null,
 primary key (tenant_id,evidence_id),
 unique (tenant_id,request_evidence_hash),
 unique (tenant_id,evidence_hash),
 check (request_evidence_hash ~ '^[0-9a-f]{64}$'
  and subject_evidence_hash ~ '^[0-9a-f]{64}$'
  and resource_evidence_hash ~ '^[0-9a-f]{64}$'
  and projection_evidence_hash ~ '^[0-9a-f]{64}$'
  and execution_evidence_hash ~ '^[0-9a-f]{64}$'
  and (route_evidence_hash is null or route_evidence_hash ~ '^[0-9a-f]{64}$')
  and version_evidence_hash ~ '^[0-9a-f]{64}$'
  and outcome_evidence_hash ~ '^[0-9a-f]{64}$'
  and evidence_hash ~ '^[0-9a-f]{64}$'),
 check (use_case in ('SUMMARY','MATERIAL_COMPLETENESS','RISK_REVIEW')),
 check (classification in (
  'SUCCESS','DISABLED','UNSUPPORTED','REJECTED','TIMEOUT',
  'PROVIDER_UNAVAILABLE','INVALID_OUTPUT','POLICY_BLOCKED',
  'LOW_CONFIDENCE','UNKNOWN'
 )),
 check (btrim(provider_id)<>'' and btrim(provider_version)<>''
  and btrim(model_provider_id)<>'' and btrim(model_id)<>''
  and btrim(model_version)<>'' and btrim(prompt_template_id)<>''
  and btrim(prompt_template_version)<>'' and btrim(prompt_template_hash)<>''
  and btrim(knowledge_source_id)<>'' and btrim(knowledge_source_version)<>''
  and btrim(knowledge_source_hash)<>'' and btrim(policy_id)<>''
  and btrim(policy_version)<>'' and btrim(policy_hash)<>''
  and btrim(output_schema_id)<>''),
 check (provider_id=model_provider_id),
 check ((use_case='SUMMARY' and prompt_template_id='approval-summary')
  or (use_case='MATERIAL_COMPLETENESS'
   and prompt_template_id='approval-material-completeness')
  or (use_case='RISK_REVIEW' and prompt_template_id='approval-risk-review')),
 check (knowledge_source_id='none'
  and knowledge_source_version='none'
  and knowledge_source_hash='none'
  and not knowledge_contains_customer_data),
 check (output_schema_id='approval-assistance' and output_schema_version>0),
 check (provider_attempts between 0 and 1),
 check (provider_invocation_started=(provider_attempts=1)),
 check (not retry_attempted and not post_invocation_fallback_attempted),
 check (kill_switch_generation>0),
 check (not provider_invocation_started or route_evidence_hash is not null),
 check (observation_count between 0 and 25
  and risk_signal_count between 0 and 25
  and missing_material_count between 0 and 25
  and recommendation_count between 0 and 25
  and evidence_reference_count between 0 and 64
  and limitation_count between 0 and 12),
 check ((advisory_result_present
   and classification in ('SUCCESS','LOW_CONFIDENCE')
   and confidence_score between 0.0 and 1.0
   and confidence_band in ('LOW','MEDIUM','HIGH')
   and evidence_reference_count>0)
  or (not advisory_result_present
   and classification not in ('SUCCESS','LOW_CONFIDENCE')
   and observation_count=0 and risk_signal_count=0
   and missing_material_count=0 and recommendation_count=0
   and evidence_reference_count=0 and limitation_count=0
   and confidence_score is null and confidence_band is null)),
 check (recorded_at>=requested_at),
 check (retention_until>recorded_at
  and retention_until<=recorded_at+interval '3650 days')
);

create table ap_ai_approval_assistance_evidence_state (
 tenant_id varchar(128) not null,
 evidence_id uuid not null,
 revision bigint not null,
 state varchar(16) not null,
 delete_reason varchar(32),
 tombstoned_at timestamptz,
 deletion_request_hash char(64),
 tombstone_hash char(64),
 current_event_hash char(64) not null,
 updated_at timestamptz not null,
 primary key (tenant_id,evidence_id),
 foreign key (tenant_id,evidence_id)
  references ap_ai_approval_assistance_evidence (tenant_id,evidence_id),
 check (revision in (1,2)),
 check (state in ('ACTIVE','TOMBSTONED')),
 check (delete_reason is null or delete_reason in (
  'RETENTION_EXPIRED','DATA_SUBJECT_REQUEST','TENANT_POLICY',
  'SECURITY_INCIDENT','LEGAL_REQUIREMENT'
 )),
 check (current_event_hash ~ '^[0-9a-f]{64}$'),
 check ((state='ACTIVE' and revision=1 and delete_reason is null
   and tombstoned_at is null and deletion_request_hash is null
   and tombstone_hash is null)
  or (state='TOMBSTONED' and revision=2 and delete_reason is not null
   and tombstoned_at is not null
   and deletion_request_hash ~ '^[0-9a-f]{64}$'
   and tombstone_hash ~ '^[0-9a-f]{64}$'))
);

create table ap_ai_approval_assistance_evidence_event (
 tenant_id varchar(128) not null,
 event_id uuid not null,
 evidence_id uuid not null,
 revision bigint not null,
 event_type varchar(16) not null,
 predecessor_hash char(64) not null,
 event_hash char(64) not null,
 happened_at timestamptz not null,
 delete_reason varchar(32),
 deletion_request_hash char(64),
 primary key (tenant_id,event_id),
 unique (tenant_id,evidence_id,revision),
 unique (tenant_id,evidence_id,event_hash),
 foreign key (tenant_id,evidence_id)
  references ap_ai_approval_assistance_evidence (tenant_id,evidence_id),
 check (revision in (1,2)),
 check (event_type in ('STORED','TOMBSTONED')),
 check (predecessor_hash ~ '^[0-9a-f]{64}$'
  and event_hash ~ '^[0-9a-f]{64}$'),
 check (delete_reason is null or delete_reason in (
  'RETENTION_EXPIRED','DATA_SUBJECT_REQUEST','TENANT_POLICY',
  'SECURITY_INCIDENT','LEGAL_REQUIREMENT'
 )),
 check ((event_type='STORED' and revision=1
   and predecessor_hash=repeat('0',64)
   and delete_reason is null and deletion_request_hash is null)
  or (event_type='TOMBSTONED' and revision=2
   and predecessor_hash<>repeat('0',64)
   and delete_reason is not null
   and deletion_request_hash ~ '^[0-9a-f]{64}$'))
);

create index idx_ai_assistance_evidence_retention_v49
 on ap_ai_approval_assistance_evidence (
  tenant_id,retention_until,evidence_id
 );
create index idx_ai_assistance_evidence_resource_v49
 on ap_ai_approval_assistance_evidence (
  tenant_id,resource_evidence_hash,recorded_at,evidence_id
 );
create index idx_ai_assistance_evidence_class_v49
 on ap_ai_approval_assistance_evidence (
  tenant_id,classification,recorded_at,evidence_id
 );
create index idx_ai_assistance_evidence_state_v49
 on ap_ai_approval_assistance_evidence_state (
  tenant_id,state,updated_at,evidence_id
 );
create index idx_ai_assistance_evidence_event_v49
 on ap_ai_approval_assistance_evidence_event (
  tenant_id,evidence_id,revision,event_id
 );

create function ap_guard_ai_assistance_evidence_v49()
returns trigger language plpgsql as $$
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',
   message='M6-E P4 durable evidence is immutable';
 end if;
 return new;
end;
$$;

create function ap_guard_ai_assistance_state_v49()
returns trigger language plpgsql as $$
declare
 evidence_recorded_at timestamptz;
 retained_until timestamptz;
begin
 if tg_op='DELETE' then
  raise exception using errcode='55000',
   message='M6-E P4 evidence state cannot be deleted';
 end if;
 select recorded_at,retention_until
 into evidence_recorded_at,retained_until
 from ap_ai_approval_assistance_evidence
 where tenant_id=new.tenant_id and evidence_id=new.evidence_id;
 if evidence_recorded_at is null then
  raise exception using errcode='23503',message='P4 evidence does not exist';
 end if;
 if tg_op='INSERT' then
  if new.state<>'ACTIVE' or new.revision<>1
   or new.updated_at<>evidence_recorded_at then
   raise exception using errcode='23514',
    message='P4 evidence state must begin active at recorded revision one';
  end if;
  return new;
 end if;
 if old.tenant_id<>new.tenant_id or old.evidence_id<>new.evidence_id
  or old.state<>'ACTIVE' or old.revision<>1
  or new.state<>'TOMBSTONED' or new.revision<>2
  or new.updated_at<>new.tombstoned_at
  or new.tombstoned_at<evidence_recorded_at then
  raise exception using errcode='23514',
   message='P4 evidence state permits one ordered CAS tombstone transition only';
 end if;
 if new.delete_reason='RETENTION_EXPIRED'
  and new.tombstoned_at<retained_until then
  raise exception using errcode='23514',
   message='P4 retention-expired tombstone is premature';
 end if;
 return new;
end;
$$;

create function ap_guard_ai_assistance_event_v49()
returns trigger language plpgsql as $$
declare
 stored_event_hash char(64);
 evidence_recorded_at timestamptz;
 retained_until timestamptz;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',
   message='M6-E P4 evidence events are append-only';
 end if;
 select recorded_at,retention_until
 into evidence_recorded_at,retained_until
 from ap_ai_approval_assistance_evidence
 where tenant_id=new.tenant_id and evidence_id=new.evidence_id;
 if evidence_recorded_at is null then
  raise exception using errcode='23503',message='P4 evidence does not exist';
 end if;
 if new.happened_at<evidence_recorded_at then
  raise exception using errcode='23514',
   message='P4 evidence event precedes durable evidence';
 end if;
 if new.event_type='STORED' then
  if new.happened_at<>evidence_recorded_at then
   raise exception using errcode='23514',
    message='P4 stored event must match evidence recorded time';
  end if;
  return new;
 end if;
 select event_hash into stored_event_hash
 from ap_ai_approval_assistance_evidence_event
 where tenant_id=new.tenant_id and evidence_id=new.evidence_id
  and revision=1 and event_type='STORED';
 if stored_event_hash is null or new.predecessor_hash<>stored_event_hash then
  raise exception using errcode='23514',
   message='P4 tombstone event predecessor mismatch';
 end if;
 if new.delete_reason='RETENTION_EXPIRED' and new.happened_at<retained_until then
  raise exception using errcode='23514',
   message='P4 retention-expired event is premature';
 end if;
 return new;
end;
$$;

create function ap_verify_ai_assistance_state_event_v49()
returns trigger language plpgsql as $$
declare
 matching_event integer;
begin
 select count(*) into matching_event
 from ap_ai_approval_assistance_evidence_event event
 where event.tenant_id=new.tenant_id
  and event.evidence_id=new.evidence_id
  and event.revision=new.revision
  and event.event_hash=new.current_event_hash
  and event.happened_at=new.updated_at
  and event.event_type=case when new.state='ACTIVE' then 'STORED' else 'TOMBSTONED' end
  and (new.state='ACTIVE'
   or (event.delete_reason=new.delete_reason
    and event.deletion_request_hash=new.deletion_request_hash
    and event.happened_at=new.tombstoned_at));
 if matching_event<>1 then
  raise exception using errcode='23514',
   message='P4 evidence state lacks matching append-only event';
 end if;
 return null;
end;
$$;

create function ap_verify_ai_assistance_event_state_v49()
returns trigger language plpgsql as $$
declare
 matching_state integer;
begin
 select count(*) into matching_state
 from ap_ai_approval_assistance_evidence_state state
 where state.tenant_id=new.tenant_id
  and state.evidence_id=new.evidence_id
  and state.revision=new.revision
  and state.current_event_hash=new.event_hash
  and state.updated_at=new.happened_at
  and state.state=case when new.event_type='STORED' then 'ACTIVE' else 'TOMBSTONED' end
  and (new.event_type='STORED'
   or (state.delete_reason=new.delete_reason
    and state.deletion_request_hash=new.deletion_request_hash
    and state.tombstoned_at=new.happened_at));
 if matching_state<>1 then
  raise exception using errcode='23514',
   message='P4 append-only event lacks matching evidence state';
 end if;
 return null;
end;
$$;

create trigger trg_ai_assistance_evidence_immutable_v49
 before insert or update or delete on ap_ai_approval_assistance_evidence
 for each row execute function ap_guard_ai_assistance_evidence_v49();

create trigger trg_ai_assistance_state_guard_v49
 before insert or update or delete on ap_ai_approval_assistance_evidence_state
 for each row execute function ap_guard_ai_assistance_state_v49();

create trigger trg_ai_assistance_event_immutable_v49
 before insert or update or delete on ap_ai_approval_assistance_evidence_event
 for each row execute function ap_guard_ai_assistance_event_v49();

create constraint trigger trg_ai_assistance_state_event_v49
 after insert or update on ap_ai_approval_assistance_evidence_state
 deferrable initially deferred
 for each row execute function ap_verify_ai_assistance_state_event_v49();

create constraint trigger trg_ai_assistance_event_state_v49
 after insert on ap_ai_approval_assistance_evidence_event
 deferrable initially deferred
 for each row execute function ap_verify_ai_assistance_event_state_v49();

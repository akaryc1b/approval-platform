-- M5-D3 follow-up: bind V41 request guards to the immutable attempt payload identity.
-- ApprovalMigrationAttempt deliberately persists engine identity inside its guarded payload_json.

create or replace function ap_guard_process_migration_engine_request_v41()
returns trigger language plpgsql as $$
declare attempt_row ap_process_migration_attempt%rowtype;
 fence_row ap_approval_instance_command_fence%rowtype;
 intent_row ap_process_migration_intent%rowtype;
 plan_target_release integer;
 plan_target_hash char(64);
 plan_target_deployment varchar(256);
 plan_target_definition varchar(256);
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='migration engine request evidence is append-only';
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 if not found or attempt_row.intent_id<>new.intent_id
  or attempt_row.approval_instance_id<>new.approval_instance_id
  or attempt_row.status<>'CLAIMED' or attempt_row.revision<>new.attempt_revision
  or attempt_row.lease_owner<>new.worker_id or attempt_row.lease_until<=new.requested_at
  or attempt_row.expected_binding_evidence_hash<>new.source_binding_evidence_hash
  or attempt_row.payload_json->>'engineInstanceId'<>new.engine_instance_id
  or attempt_row.payload_json->>'sourceEngineDefinitionId'<>new.source_engine_definition_id
  or attempt_row.payload_json->>'targetEngineDefinitionId'<>new.target_engine_definition_id then
  raise exception using errcode='23514',message='migration engine request requires current exact claimed attempt';
 end if;
 select * into fence_row from ap_approval_instance_command_fence
  where tenant_id=new.tenant_id and fence_id=new.fence_id for update;
 if not found or fence_row.attempt_id<>new.attempt_id
  or fence_row.approval_instance_id<>new.approval_instance_id
  or fence_row.status<>'ACTIVE' or fence_row.revision<>new.fence_revision
  or fence_row.lease_owner<>new.worker_id or fence_row.lease_until<=new.requested_at then
  raise exception using errcode='23514',message='migration engine request requires current exact command fence';
 end if;
 select * into intent_row from ap_process_migration_intent
  where tenant_id=new.tenant_id and intent_id=new.intent_id;
 if not found or intent_row.status<>'RUNNING'
  or intent_row.target_release_version<>new.target_release_version
  or intent_row.target_package_hash<>new.target_package_hash then
  raise exception using errcode='23514',message='migration engine request requires current exact intent';
 end if;
 select target_release_version,target_package_hash,target_engine_deployment_id,target_engine_definition_id
 into plan_target_release,plan_target_hash,plan_target_deployment,plan_target_definition
 from ap_process_migration_plan
 where tenant_id=new.tenant_id and plan_id=intent_row.plan_id
  and plan_hash=intent_row.plan_hash and status='CONSUMED';
 if not found or plan_target_release<>new.target_release_version
  or plan_target_hash<>new.target_package_hash
  or plan_target_deployment<>new.target_engine_deployment_id
  or plan_target_definition<>new.target_engine_definition_id then
  raise exception using errcode='23514',message='migration engine request requires exact consumed target plan';
 end if;
 return new;
end $$;

create or replace function ap_guard_process_migration_engine_outcome_v41()
returns trigger language plpgsql as $$
declare request_row ap_process_migration_engine_request%rowtype;
 attempt_row ap_process_migration_attempt%rowtype;
 fence_row ap_approval_instance_command_fence%rowtype;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='migration engine outcome evidence is append-only';
 end if;
 select * into request_row from ap_process_migration_engine_request
  where tenant_id=new.tenant_id and engine_request_id=new.engine_request_id;
 if not found or request_row.intent_id<>new.intent_id or request_row.attempt_id<>new.attempt_id
  or request_row.worker_id<>new.worker_id then
  raise exception using errcode='23514',message='migration engine outcome request lineage mismatch';
 end if;
 select * into attempt_row from ap_process_migration_attempt
  where tenant_id=new.tenant_id and attempt_id=new.attempt_id for update;
 if not found or attempt_row.status<>'ENGINE_REQUESTED'
  or attempt_row.revision<>new.expected_attempt_revision
  or attempt_row.engine_request_reference<>new.engine_request_id::text
  or attempt_row.payload_json->>'engineInstanceId'<>request_row.engine_instance_id
  or attempt_row.payload_json->>'sourceEngineDefinitionId'<>request_row.source_engine_definition_id
  or attempt_row.payload_json->>'targetEngineDefinitionId'<>request_row.target_engine_definition_id then
  raise exception using errcode='23514',message='migration engine outcome requires current requested attempt';
 end if;
 select * into fence_row from ap_approval_instance_command_fence
  where tenant_id=new.tenant_id and fence_id=request_row.fence_id for update;
 if not found or fence_row.status<>'ACTIVE' or fence_row.attempt_id<>new.attempt_id
  or fence_row.revision<>new.expected_fence_revision or fence_row.lease_owner<>new.worker_id
  or fence_row.lease_until<=new.recorded_at then
  raise exception using errcode='23514',message='migration engine outcome requires current exact command fence';
 end if;
 return new;
end $$;

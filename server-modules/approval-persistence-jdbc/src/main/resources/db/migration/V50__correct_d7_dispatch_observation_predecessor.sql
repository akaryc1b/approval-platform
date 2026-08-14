-- M5-D7 correction: dispatch events must chain to the immutable kill-switch observation.
-- V47 remains immutable; this replaces only its trigger function body.

create or replace function ap_guard_process_migration_d7_evidence()
returns trigger language plpgsql as $$
declare
 plan_hash_value char(64);
 plan_status_value varchar(16);
 intent_plan_id uuid;
 intent_status_value varchar(32);
 selected_sequence integer;
 selected_instance_hash char(64);
 canary_plan_id uuid;
 canary_intent_id uuid;
 run_intent_id uuid;
 run_expected_switch_revision bigint;
 previous_hash char(64);
 batch_hash_value char(64);
 observation_hash_value char(64);
 claim_intent_id uuid;
 claim_attempt_ids jsonb;
 attempt_intent_id uuid;
begin
 if tg_op<>'INSERT' then
  raise exception using errcode='55000',message='M5-D7 evidence is append-only';
 end if;

 if tg_table_name='ap_process_migration_canary_selection' then
  if (new.payload_json->>'selectionId')::uuid is distinct from new.selection_id
   or (new.payload_json->>'tenantId') is distinct from new.tenant_id
   or (new.payload_json->>'planId')::uuid is distinct from new.plan_id
   or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
   or (new.payload_json->>'algorithmVersion') is distinct from new.algorithm_version
   or (new.payload_json->>'sequenceNo')::integer is distinct from new.sequence_no
   or (new.payload_json->>'approvalInstanceId')::uuid is distinct from new.approval_instance_id
   or (new.payload_json->>'planHash') is distinct from new.plan_hash
   or (new.payload_json->>'instanceEvidenceHash') is distinct from new.instance_evidence_hash
   or (new.payload_json->>'selectionEvidenceHash') is distinct from new.selection_evidence_hash
   or ap_migration_json_instant_v37(new.payload_json,'recordedAt') is distinct from new.recorded_at
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id then
   raise exception using errcode='23514',message='D7 canary payload mismatch';
  end if;
  select plan.plan_hash,plan.status,intent.plan_id,intent.status,
         selection.sequence_no,selection.instance_evidence_hash
   into plan_hash_value,plan_status_value,intent_plan_id,intent_status_value,
        selected_sequence,selected_instance_hash
   from ap_process_migration_plan plan
   join ap_process_migration_intent intent
     on intent.tenant_id=plan.tenant_id and intent.plan_id=plan.plan_id
   join ap_process_migration_plan_instance selection
     on selection.tenant_id=plan.tenant_id and selection.plan_id=plan.plan_id
    and selection.approval_instance_id=new.approval_instance_id
   where plan.tenant_id=new.tenant_id and plan.plan_id=new.plan_id
     and intent.intent_id=new.intent_id;
  if not found or plan_status_value<>'CONSUMED'
   or intent_status_value not in ('PENDING','RUNNING')
   or intent_plan_id<>new.plan_id or plan_hash_value<>new.plan_hash
   or selected_sequence<>1 or selected_instance_hash<>new.instance_evidence_hash then
   raise exception using errcode='23514',message='D7 canary canonical lineage mismatch';
  end if;
  return new;
 end if;

 if tg_table_name='ap_process_migration_orchestration_run' then
  if (new.payload_json->>'runId')::uuid is distinct from new.run_id
   or (new.payload_json->>'tenantId') is distinct from new.tenant_id
   or (new.payload_json->>'planId')::uuid is distinct from new.plan_id
   or (new.payload_json->>'intentId')::uuid is distinct from new.intent_id
   or (new.payload_json->>'runRevision')::bigint is distinct from new.run_revision
   or (new.payload_json->>'phase') is distinct from new.phase
   or (new.payload_json->>'requestedLimit')::integer is distinct from new.requested_limit
   or (new.payload_json->>'canarySelectionId')::uuid is distinct from new.canary_selection_id
   or (new.payload_json->>'expectedKillSwitchRevision')::bigint
      is distinct from new.expected_kill_switch_revision
   or (new.payload_json->>'predecessorHash') is distinct from new.predecessor_hash
   or (new.payload_json->>'requestHash') is distinct from new.request_hash
   or (new.payload_json->>'runEvidenceHash') is distinct from new.run_evidence_hash
   or ap_migration_json_instant_v37(new.payload_json,'startedAt') is distinct from new.started_at
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id then
   raise exception using errcode='23514',message='D7 orchestration run payload mismatch';
  end if;
  select canary.plan_id,canary.intent_id into canary_plan_id,canary_intent_id
   from ap_process_migration_canary_selection canary
   where canary.tenant_id=new.tenant_id and canary.selection_id=new.canary_selection_id;
  if not found or canary_plan_id<>new.plan_id or canary_intent_id<>new.intent_id then
   raise exception using errcode='23514',message='D7 run canary lineage mismatch';
  end if;
  if new.run_revision=1 then
   if new.predecessor_hash<>repeat('0',64) then
    raise exception using errcode='23514',message='D7 initial run predecessor mismatch';
   end if;
  else
   select run_evidence_hash into previous_hash
    from ap_process_migration_orchestration_run
    where tenant_id=new.tenant_id and intent_id=new.intent_id
      and run_revision=new.run_revision-1;
   if previous_hash is null or previous_hash<>new.predecessor_hash then
    raise exception using errcode='23514',message='D7 run predecessor mismatch';
   end if;
  end if;
  return new;
 end if;

 if tg_table_name='ap_process_migration_orchestration_event' then
  if (new.payload_json->>'eventId')::uuid is distinct from new.event_id
   or (new.payload_json->>'tenantId') is distinct from new.tenant_id
   or (new.payload_json->>'runId')::uuid is distinct from new.run_id
   or (new.payload_json->>'sequence')::bigint is distinct from new.sequence
   or (new.payload_json->>'eventType') is distinct from new.event_type
   or (new.payload_json->>'pauseReason') is distinct from new.pause_reason
   or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
   or (new.payload_json->>'predecessorHash') is distinct from new.predecessor_hash
   or (new.payload_json->>'eventEvidenceHash') is distinct from new.event_evidence_hash
   or ap_migration_json_instant_v37(new.payload_json,'happenedAt') is distinct from new.happened_at
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id then
   raise exception using errcode='23514',message='D7 orchestration event payload mismatch';
  end if;
  select run.intent_id into run_intent_id
   from ap_process_migration_orchestration_run run
   where run.tenant_id=new.tenant_id and run.run_id=new.run_id;
  if not found then
   raise exception using errcode='23514',message='D7 event run lineage missing';
  end if;
  if new.attempt_id is not null then
   select attempt.intent_id into attempt_intent_id
    from ap_process_migration_attempt attempt
    where attempt.tenant_id=new.tenant_id and attempt.attempt_id=new.attempt_id;
   if attempt_intent_id is null or attempt_intent_id<>run_intent_id then
    raise exception using errcode='23514',message='D7 event attempt lineage mismatch';
   end if;
  end if;
  if new.sequence=1 then
   select run_evidence_hash into previous_hash
    from ap_process_migration_orchestration_run
    where tenant_id=new.tenant_id and run_id=new.run_id;
  else
   select event_evidence_hash into previous_hash
    from ap_process_migration_orchestration_event
    where tenant_id=new.tenant_id and run_id=new.run_id
      and sequence=new.sequence-1;
   select batch_evidence_hash into batch_hash_value
    from ap_process_migration_orchestration_batch
    where tenant_id=new.tenant_id and run_id=new.run_id;
   select observation.observation_evidence_hash into observation_hash_value
    from ap_process_migration_kill_switch_observation observation
    where observation.tenant_id=new.tenant_id
      and observation.run_id=new.run_id
      and observation.attempt_id=new.attempt_id
      and observation.observation_evidence_hash=new.predecessor_hash
      and ((new.event_type='DISPATCH_ALLOWED' and observation.dispatch_allowed)
       or (new.event_type='KILL_SWITCH_BLOCKED' and not observation.dispatch_allowed));
  end if;
  if previous_hash is null
   or (new.predecessor_hash<>previous_hash
    and new.predecessor_hash is distinct from batch_hash_value
    and new.predecessor_hash is distinct from observation_hash_value) then
   raise exception using errcode='23514',message='D7 event predecessor mismatch';
  end if;
  return new;
 end if;

 if tg_table_name='ap_process_migration_orchestration_batch' then
  if (new.payload_json->>'batchEvidenceId')::uuid is distinct from new.batch_evidence_id
   or (new.payload_json->>'tenantId') is distinct from new.tenant_id
   or (new.payload_json->>'runId')::uuid is distinct from new.run_id
   or (new.payload_json->>'claimBatchId')::uuid is distinct from new.claim_batch_id
   or (new.payload_json->>'requestedLimit')::integer is distinct from new.requested_limit
   or (new.payload_json->'attemptIds') is distinct from new.attempt_ids
   or (new.payload_json->'dispositions') is distinct from new.dispositions
   or (new.payload_json->>'predecessorHash') is distinct from new.predecessor_hash
   or (new.payload_json->>'batchEvidenceHash') is distinct from new.batch_evidence_hash
   or ap_migration_json_instant_v37(new.payload_json,'recordedAt') is distinct from new.recorded_at
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id then
   raise exception using errcode='23514',message='D7 batch payload mismatch';
  end if;
  select run.intent_id into run_intent_id
   from ap_process_migration_orchestration_run run
   where run.tenant_id=new.tenant_id and run.run_id=new.run_id;
  select claim.intent_id,claim.claimed_attempt_ids into claim_intent_id,claim_attempt_ids
   from ap_process_migration_claim_batch claim
   where claim.tenant_id=new.tenant_id and claim.claim_batch_id=new.claim_batch_id;
  if run_intent_id is null or claim_intent_id<>run_intent_id
   or claim_attempt_ids<>new.attempt_ids then
   raise exception using errcode='23514',message='D7 batch D2 claim lineage mismatch';
  end if;
  return new;
 end if;

 if tg_table_name='ap_process_migration_kill_switch_observation' then
  if (new.payload_json->>'observationId')::uuid is distinct from new.observation_id
   or (new.payload_json->>'tenantId') is distinct from new.tenant_id
   or (new.payload_json->>'runId')::uuid is distinct from new.run_id
   or (new.payload_json->>'attemptId')::uuid is distinct from new.attempt_id
   or (new.payload_json->>'expectedRevision')::bigint is distinct from new.expected_revision
   or (new.payload_json->>'observedRevision')::bigint is distinct from new.observed_revision
   or (new.payload_json->>'enabled')::boolean is distinct from new.switch_enabled
   or (new.payload_json->>'dispatchAllowed')::boolean is distinct from new.dispatch_allowed
   or (new.payload_json->>'reasonCode') is distinct from new.reason_code
   or (new.payload_json->>'requestHash') is distinct from new.request_hash
   or (new.payload_json->>'observationEvidenceHash')
      is distinct from new.observation_evidence_hash
   or ap_migration_json_instant_v37(new.payload_json,'observedAt') is distinct from new.observed_at
   or (new.payload_json->>'requestId') is distinct from new.request_id
   or (new.payload_json->>'traceId') is distinct from new.trace_id then
   raise exception using errcode='23514',message='D7 kill-switch payload mismatch';
  end if;
  select run.intent_id,run.expected_kill_switch_revision
   into run_intent_id,run_expected_switch_revision
   from ap_process_migration_orchestration_run run
   where run.tenant_id=new.tenant_id and run.run_id=new.run_id;
  select attempt.intent_id into attempt_intent_id
   from ap_process_migration_attempt attempt
   where attempt.tenant_id=new.tenant_id and attempt.attempt_id=new.attempt_id;
  if run_intent_id is null or attempt_intent_id<>run_intent_id
   or new.expected_revision<>run_expected_switch_revision then
   raise exception using errcode='23514',message='D7 kill-switch lineage mismatch';
  end if;
  return new;
 end if;

 raise exception using errcode='23514',message='unsupported M5-D7 evidence table';
end $$;

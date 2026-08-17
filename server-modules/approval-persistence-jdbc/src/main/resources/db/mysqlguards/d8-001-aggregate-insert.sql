create trigger trg_process_migration_plan_aggregate_insert_guard_v48
 before insert on ap_process_migration_plan_aggregate
 for each row
begin
 if not (
   json_unquote(json_extract(new.payload_json,'$.aggregateId')) <=> new.aggregate_id
   and json_unquote(json_extract(new.payload_json,'$.tenantId')) <=> new.tenant_id
   and json_unquote(json_extract(new.payload_json,'$.operatorId')) <=> new.operator_id
   and json_unquote(json_extract(new.payload_json,'$.planId')) <=> new.plan_id
   and json_unquote(json_extract(new.payload_json,'$.intentId')) <=> new.intent_id
   and json_unquote(json_extract(new.payload_json,'$.planHash')) <=> new.plan_hash
   and cast(json_unquote(json_extract(
     new.payload_json,'$.aggregateRevision'
   )) as signed) <=> new.aggregate_revision
   and json_unquote(json_extract(new.payload_json,'$.status')) <=> new.status
   and json_unquote(json_extract(
     new.payload_json,'$.terminalOutcome'
   )) <=> new.terminal_outcome
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.selectedCount'
   )) as signed) <=> new.selected_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.provisionedAttemptCount'
   )) as signed) <=> new.provisioned_attempt_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.pendingCount'
   )) as signed) <=> new.pending_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.claimedCount'
   )) as signed) <=> new.claimed_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.engineRequestedCount'
   )) as signed) <=> new.engine_requested_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.verifyingCount'
   )) as signed) <=> new.verifying_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.reconcilingCount'
   )) as signed) <=> new.reconciling_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.unknownCount'
   )) as signed) <=> new.unknown_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.manualReviewCount'
   )) as signed) <=> new.manual_review_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.bindingConflictCount'
   )) as signed) <=> new.binding_conflict_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.blockedStaleCount'
   )) as signed) <=> new.blocked_stale_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.terminalFailedCount'
   )) as signed) <=> new.terminal_failed_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.exactSuccessCount'
   )) as signed) <=> new.exact_success_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.unresolvedCount'
   )) as signed) <=> new.unresolved_count
   and json_unquote(json_extract(
     new.payload_json,'$.canaryStatus'
   )) <=> new.canary_status
   and json_unquote(json_extract(
     new.payload_json,'$.orchestrationStatus'
   )) <=> new.orchestration_status
   and json_unquote(json_extract(
     new.payload_json,'$.paused'
   )) <=> if(new.paused,'true','false')
   and json_unquote(json_extract(
     new.payload_json,'$.pauseReason'
   )) <=> new.pause_reason
   and json_unquote(json_extract(
     new.payload_json,'$.killSwitchObserved'
   )) <=> if(new.kill_switch_observed,'true','false')
   and json_unquote(json_extract(
     new.payload_json,'$.inputEvidenceHash'
   )) <=> new.input_evidence_hash
   and json_unquote(json_extract(
     new.payload_json,'$.predecessorHash'
   )) <=> new.predecessor_hash
   and json_unquote(json_extract(
     new.payload_json,'$.idempotencyKey'
   )) <=> new.idempotency_key
   and json_unquote(json_extract(
     new.payload_json,'$.requestHash'
   )) <=> new.request_hash
   and json_unquote(json_extract(
     new.payload_json,'$.aggregateHash'
   )) <=> new.aggregate_hash
   and (
     case json_type(json_extract(new.payload_json,'$.aggregatedAt'))
       when 'STRING' then cast(replace(replace(replace(
         json_unquote(json_extract(new.payload_json,'$.aggregatedAt')),
         'T',' '
       ),'Z',''),'+00:00','') as datetime(6))
       when 'INTEGER' then timestampadd(
         microsecond,
         cast(cast(json_unquote(json_extract(
           new.payload_json,'$.aggregatedAt'
         )) as decimal(30,9))*1000000 as signed),
         cast('1970-01-01 00:00:00' as datetime(6))
       )
       when 'DOUBLE' then timestampadd(
         microsecond,
         cast(cast(json_unquote(json_extract(
           new.payload_json,'$.aggregatedAt'
         )) as decimal(30,9))*1000000 as signed),
         cast('1970-01-01 00:00:00' as datetime(6))
       )
       when 'DECIMAL' then timestampadd(
         microsecond,
         cast(cast(json_unquote(json_extract(
           new.payload_json,'$.aggregatedAt'
         )) as decimal(30,9))*1000000 as signed),
         cast('1970-01-01 00:00:00' as datetime(6))
       )
       else null
     end
   ) <=> new.aggregated_at
   and json_unquote(json_extract(new.payload_json,'$.reason')) <=> new.reason
   and json_unquote(json_extract(
     new.payload_json,'$.requestId'
   )) <=> new.request_id
   and nullif(json_unquote(json_extract(
     new.payload_json,'$.traceId'
   )),'null') <=> new.trace_id
   and json_unquote(json_extract(
     new.payload_json,'$.auditReference'
   )) <=> new.audit_reference
 ) then
  signal sqlstate '45000'
   set message_text='D8 aggregate payload mismatch';
 end if;

 if not exists (
   select 1
   from ap_process_migration_plan plan
   join ap_process_migration_plan_consumption consumption
     on consumption.tenant_id=plan.tenant_id
    and consumption.plan_id=plan.plan_id
    and consumption.plan_hash=plan.plan_hash
   join ap_process_migration_intent intent
     on intent.tenant_id=consumption.tenant_id
    and intent.intent_id=consumption.intent_id
    and intent.plan_id=plan.plan_id
    and intent.plan_hash=plan.plan_hash
    and intent.intent_evidence_hash=consumption.intent_evidence_hash
   where plan.tenant_id=new.tenant_id
     and plan.plan_id=new.plan_id
     and plan.plan_hash=new.plan_hash
     and plan.status='CONSUMED'
     and plan.selected_instance_count=new.selected_count
     and intent.intent_id=new.intent_id
 ) or (
   select count(*)
   from ap_process_migration_plan_instance selection
   where selection.tenant_id=new.tenant_id
     and selection.plan_id=new.plan_id
 ) <> new.selected_count then
  signal sqlstate '45000'
   set message_text='D8 aggregate sealed-plan lineage mismatch';
 end if;

 if new.aggregate_revision=1 then
  if new.predecessor_hash<>repeat('0',64)
    or exists (
      select 1
      from ap_process_migration_plan_aggregate value
      where value.tenant_id=new.tenant_id
        and value.plan_id=new.plan_id
    ) then
   signal sqlstate '45000'
    set message_text='D8 aggregate predecessor mismatch';
  end if;
 else
  if not exists (
    select 1
    from ap_process_migration_plan_aggregate value
    where value.tenant_id=new.tenant_id
      and value.plan_id=new.plan_id
      and value.aggregate_revision=new.aggregate_revision-1
      and value.aggregate_hash=new.predecessor_hash
  ) then
   signal sqlstate '45000'
    set message_text='D8 aggregate predecessor mismatch';
  end if;
 end if;
end

create trigger trg_process_migration_plan_completion_insert_guard_v48
 before insert on ap_process_migration_plan_completion
 for each row
begin
 if not (
   json_unquote(json_extract(
     new.payload_json,'$.completionId'
   )) <=> new.completion_id
   and json_unquote(json_extract(new.payload_json,'$.tenantId')) <=> new.tenant_id
   and json_unquote(json_extract(new.payload_json,'$.planId')) <=> new.plan_id
   and json_unquote(json_extract(new.payload_json,'$.intentId')) <=> new.intent_id
   and json_unquote(json_extract(
     new.payload_json,'$.aggregateId'
   )) <=> new.aggregate_id
   and cast(json_unquote(json_extract(
     new.payload_json,'$.aggregateRevision'
   )) as signed) <=> new.aggregate_revision
   and json_unquote(json_extract(
     new.payload_json,'$.completionStatus'
   )) <=> new.completion_status
   and json_unquote(json_extract(
     new.payload_json,'$.terminalOutcome'
   )) <=> new.terminal_outcome
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.selectedCount'
   )) as signed) <=> new.selected_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.terminalFailedCount'
   )) as signed) <=> new.terminal_failed_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.exactSuccessCount'
   )) as signed) <=> new.exact_success_count
   and cast(json_unquote(json_extract(
     new.payload_json,'$.counts.unresolvedCount'
   )) as signed)=0
   and json_unquote(json_extract(
     new.payload_json,'$.inputEvidenceHash'
   )) <=> new.input_evidence_hash
   and json_unquote(json_extract(
     new.payload_json,'$.aggregateHash'
   )) <=> new.aggregate_hash
   and json_unquote(json_extract(
     new.payload_json,'$.completionEvidenceHash'
   )) <=> new.completion_evidence_hash
   and (
     case json_type(json_extract(new.payload_json,'$.completedAt'))
       when 'STRING' then cast(replace(replace(replace(
         json_unquote(json_extract(new.payload_json,'$.completedAt')),
         'T',' '
       ),'Z',''),'+00:00','') as datetime(6))
       when 'INTEGER' then timestampadd(
         microsecond,
         cast(cast(json_unquote(json_extract(
           new.payload_json,'$.completedAt'
         )) as decimal(30,9))*1000000 as signed),
         cast('1970-01-01 00:00:00' as datetime(6))
       )
       when 'DOUBLE' then timestampadd(
         microsecond,
         cast(cast(json_unquote(json_extract(
           new.payload_json,'$.completedAt'
         )) as decimal(30,9))*1000000 as signed),
         cast('1970-01-01 00:00:00' as datetime(6))
       )
       when 'DECIMAL' then timestampadd(
         microsecond,
         cast(cast(json_unquote(json_extract(
           new.payload_json,'$.completedAt'
         )) as decimal(30,9))*1000000 as signed),
         cast('1970-01-01 00:00:00' as datetime(6))
       )
       else null
     end
   ) <=> new.completed_at
   and json_unquote(json_extract(
     new.payload_json,'$.requestId'
   )) <=> new.request_id
   and (
     case json_type(json_extract(new.payload_json,'$.traceId'))
       when 'NULL' then null
       else json_unquote(json_extract(new.payload_json,'$.traceId'))
     end
   ) <=> new.trace_id
   and json_unquote(json_extract(
     new.payload_json,'$.auditReference'
   )) <=> new.audit_reference
 ) then
  signal sqlstate '45000'
   set message_text='D8 completion payload mismatch';
 end if;

 if not exists (
   select 1
   from ap_process_migration_plan_aggregate aggregate_value
   where aggregate_value.tenant_id=new.tenant_id
     and aggregate_value.aggregate_id=new.aggregate_id
     and aggregate_value.plan_id=new.plan_id
     and aggregate_value.intent_id=new.intent_id
     and aggregate_value.aggregate_revision=new.aggregate_revision
     and aggregate_value.status=new.completion_status
     and aggregate_value.terminal_outcome=new.terminal_outcome
     and aggregate_value.selected_count=new.selected_count
     and aggregate_value.terminal_failed_count=new.terminal_failed_count
     and aggregate_value.exact_success_count=new.exact_success_count
     and aggregate_value.unresolved_count=0
     and aggregate_value.input_evidence_hash=new.input_evidence_hash
     and aggregate_value.aggregate_hash=new.aggregate_hash
     and aggregate_value.request_id=new.request_id
     and aggregate_value.audit_reference=new.audit_reference
 ) then
  signal sqlstate '45000'
   set message_text='D8 completion aggregate lineage mismatch';
 end if;
end

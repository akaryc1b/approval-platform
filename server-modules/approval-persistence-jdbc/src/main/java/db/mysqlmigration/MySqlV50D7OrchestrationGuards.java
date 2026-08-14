package db.mysqlmigration;

import java.util.List;

/** MySQL 8.4 equivalents of the PostgreSQL V47 D7 evidence guard. */
final class MySqlV50D7OrchestrationGuards {

    private static final String APPEND_ONLY_MESSAGE =
        "M5-D7 evidence is append-only";

    private MySqlV50D7OrchestrationGuards() {
    }

    static List<String> statements() {
        return List.of(
            canaryInsert(),
            immutable("trg_migration_d7_canary_update_v47", "update",
                "ap_process_migration_canary_selection"),
            immutable("trg_migration_d7_canary_delete_v47", "delete",
                "ap_process_migration_canary_selection"),
            runInsert(),
            immutable("trg_migration_d7_run_update_v47", "update",
                "ap_process_migration_orchestration_run"),
            immutable("trg_migration_d7_run_delete_v47", "delete",
                "ap_process_migration_orchestration_run"),
            eventInsert(),
            immutable("trg_migration_d7_event_update_v47", "update",
                "ap_process_migration_orchestration_event"),
            immutable("trg_migration_d7_event_delete_v47", "delete",
                "ap_process_migration_orchestration_event"),
            batchInsert(),
            immutable("trg_migration_d7_batch_update_v47", "update",
                "ap_process_migration_orchestration_batch"),
            immutable("trg_migration_d7_batch_delete_v47", "delete",
                "ap_process_migration_orchestration_batch"),
            killSwitchInsert(),
            immutable("trg_migration_d7_kill_switch_update_v47", "update",
                "ap_process_migration_kill_switch_observation"),
            immutable("trg_migration_d7_kill_switch_delete_v47", "delete",
                "ap_process_migration_kill_switch_observation")
        );
    }

    static int checksum() {
        return statements().hashCode();
    }

    private static String immutable(String name, String operation, String table) {
        return "create trigger " + name + " before " + operation + " on " + table
            + " for each row signal sqlstate '45000' set message_text='"
            + APPEND_ONLY_MESSAGE + "'";
    }

    private static String canaryInsert() {
        return """
            create trigger trg_migration_d7_canary_insert_v47
            before insert on ap_process_migration_canary_selection
            for each row
            begin
              if json_unquote(json_extract(new.payload_json,'$.selectionId'))<>new.selection_id
                or json_unquote(json_extract(new.payload_json,'$.tenantId'))<>new.tenant_id
                or json_unquote(json_extract(new.payload_json,'$.planId'))<>new.plan_id
                or json_unquote(json_extract(new.payload_json,'$.intentId'))<>new.intent_id
                or json_unquote(json_extract(new.payload_json,'$.algorithmVersion'))
                   <>new.algorithm_version
                or cast(json_unquote(json_extract(new.payload_json,'$.sequenceNo')) as signed)
                   <>new.sequence_no
                or json_unquote(json_extract(new.payload_json,'$.approvalInstanceId'))
                   <>new.approval_instance_id
                or json_unquote(json_extract(new.payload_json,'$.planHash'))<>new.plan_hash
                or json_unquote(json_extract(new.payload_json,'$.instanceEvidenceHash'))
                   <>new.instance_evidence_hash
                or json_unquote(json_extract(new.payload_json,'$.selectionEvidenceHash'))
                   <>new.selection_evidence_hash
                or json_unquote(json_extract(new.payload_json,'$.requestId'))<>new.request_id
                or not (json_unquote(json_extract(new.payload_json,'$.traceId'))<=>new.trace_id)
              then
                signal sqlstate '45000' set message_text='D7 canary payload mismatch';
              end if;
              if not exists (
                select 1
                from ap_process_migration_plan plan
                join ap_process_migration_intent intent
                  on intent.tenant_id=plan.tenant_id and intent.plan_id=plan.plan_id
                join ap_process_migration_plan_instance selection
                  on selection.tenant_id=plan.tenant_id
                 and selection.plan_id=plan.plan_id
                 and selection.approval_instance_id=new.approval_instance_id
                where plan.tenant_id=new.tenant_id and plan.plan_id=new.plan_id
                  and intent.intent_id=new.intent_id and plan.status='CONSUMED'
                  and intent.status in ('PENDING','RUNNING')
                  and plan.plan_hash=new.plan_hash and selection.sequence_no=1
                  and selection.instance_evidence_hash=new.instance_evidence_hash
              ) then
                signal sqlstate '45000'
                  set message_text='D7 canary canonical lineage mismatch';
              end if;
            end
            """.strip();
    }

    private static String runInsert() {
        return """
            create trigger trg_migration_d7_run_insert_v47
            before insert on ap_process_migration_orchestration_run
            for each row
            begin
              if json_unquote(json_extract(new.payload_json,'$.runId'))<>new.run_id
                or json_unquote(json_extract(new.payload_json,'$.tenantId'))<>new.tenant_id
                or json_unquote(json_extract(new.payload_json,'$.planId'))<>new.plan_id
                or json_unquote(json_extract(new.payload_json,'$.intentId'))<>new.intent_id
                or cast(json_unquote(json_extract(new.payload_json,'$.runRevision')) as signed)
                   <>new.run_revision
                or json_unquote(json_extract(new.payload_json,'$.phase'))<>new.phase
                or cast(json_unquote(json_extract(new.payload_json,'$.requestedLimit')) as signed)
                   <>new.requested_limit
                or json_unquote(json_extract(new.payload_json,'$.canarySelectionId'))
                   <>new.canary_selection_id
                or cast(json_unquote(json_extract(
                     new.payload_json,'$.expectedKillSwitchRevision')) as signed)
                   <>new.expected_kill_switch_revision
                or json_unquote(json_extract(new.payload_json,'$.predecessorHash'))
                   <>new.predecessor_hash
                or json_unquote(json_extract(new.payload_json,'$.requestHash'))<>new.request_hash
                or json_unquote(json_extract(new.payload_json,'$.runEvidenceHash'))
                   <>new.run_evidence_hash
                or json_unquote(json_extract(new.payload_json,'$.requestId'))<>new.request_id
                or not (json_unquote(json_extract(new.payload_json,'$.traceId'))<=>new.trace_id)
              then
                signal sqlstate '45000'
                  set message_text='D7 orchestration run payload mismatch';
              end if;
              if not exists (
                select 1 from ap_process_migration_canary_selection canary
                where canary.tenant_id=new.tenant_id
                  and canary.selection_id=new.canary_selection_id
                  and canary.plan_id=new.plan_id and canary.intent_id=new.intent_id
              ) then
                signal sqlstate '45000' set message_text='D7 run canary lineage mismatch';
              end if;
              if new.run_revision=1 then
                if new.predecessor_hash<>repeat('0',64) then
                  signal sqlstate '45000'
                    set message_text='D7 initial run predecessor mismatch';
                end if;
              elseif not exists (
                select 1 from ap_process_migration_orchestration_run prior_run
                where prior_run.tenant_id=new.tenant_id
                  and prior_run.intent_id=new.intent_id
                  and prior_run.run_revision=new.run_revision-1
                  and prior_run.run_evidence_hash=new.predecessor_hash
              ) then
                signal sqlstate '45000' set message_text='D7 run predecessor mismatch';
              end if;
            end
            """.strip();
    }

    private static String eventInsert() {
        return """
            create trigger trg_migration_d7_event_insert_v47
            before insert on ap_process_migration_orchestration_event
            for each row
            begin
              if json_unquote(json_extract(new.payload_json,'$.eventId'))<>new.event_id
                or json_unquote(json_extract(new.payload_json,'$.tenantId'))<>new.tenant_id
                or json_unquote(json_extract(new.payload_json,'$.runId'))<>new.run_id
                or cast(json_unquote(json_extract(new.payload_json,'$.sequence')) as signed)
                   <>new.sequence
                or json_unquote(json_extract(new.payload_json,'$.eventType'))<>new.event_type
                or json_unquote(json_extract(new.payload_json,'$.pauseReason'))<>new.pause_reason
                or not (json_unquote(json_extract(new.payload_json,'$.attemptId'))
                   <=>new.attempt_id)
                or json_unquote(json_extract(new.payload_json,'$.predecessorHash'))
                   <>new.predecessor_hash
                or json_unquote(json_extract(new.payload_json,'$.eventEvidenceHash'))
                   <>new.event_evidence_hash
                or json_unquote(json_extract(new.payload_json,'$.requestId'))<>new.request_id
                or not (json_unquote(json_extract(new.payload_json,'$.traceId'))<=>new.trace_id)
              then
                signal sqlstate '45000'
                  set message_text='D7 orchestration event payload mismatch';
              end if;
              if not exists (
                select 1 from ap_process_migration_orchestration_run run
                where run.tenant_id=new.tenant_id and run.run_id=new.run_id
              ) then
                signal sqlstate '45000' set message_text='D7 event run lineage missing';
              end if;
              if new.attempt_id is not null and not exists (
                select 1
                from ap_process_migration_attempt attempt
                join ap_process_migration_orchestration_run run
                  on run.tenant_id=attempt.tenant_id and run.intent_id=attempt.intent_id
                where run.tenant_id=new.tenant_id and run.run_id=new.run_id
                  and attempt.attempt_id=new.attempt_id
              ) then
                signal sqlstate '45000'
                  set message_text='D7 event attempt lineage mismatch';
              end if;
              if new.sequence=1 then
                if not exists (
                  select 1 from ap_process_migration_orchestration_run run
                  where run.tenant_id=new.tenant_id and run.run_id=new.run_id
                    and run.run_evidence_hash=new.predecessor_hash
                ) then
                  signal sqlstate '45000'
                    set message_text='D7 event predecessor mismatch';
                end if;
              elseif not exists (
                select 1 from ap_process_migration_orchestration_event prior_event
                where prior_event.tenant_id=new.tenant_id
                  and prior_event.run_id=new.run_id
                  and prior_event.sequence=new.sequence-1
                  and prior_event.event_evidence_hash=new.predecessor_hash
              ) and not exists (
                select 1 from ap_process_migration_orchestration_batch batch
                where batch.tenant_id=new.tenant_id and batch.run_id=new.run_id
                  and batch.batch_evidence_hash=new.predecessor_hash
              ) then
                signal sqlstate '45000' set message_text='D7 event predecessor mismatch';
              end if;
            end
            """.strip();
    }

    private static String batchInsert() {
        return """
            create trigger trg_migration_d7_batch_insert_v47
            before insert on ap_process_migration_orchestration_batch
            for each row
            begin
              if json_unquote(json_extract(new.payload_json,'$.batchEvidenceId'))
                   <>new.batch_evidence_id
                or json_unquote(json_extract(new.payload_json,'$.tenantId'))<>new.tenant_id
                or json_unquote(json_extract(new.payload_json,'$.runId'))<>new.run_id
                or json_unquote(json_extract(new.payload_json,'$.claimBatchId'))
                   <>new.claim_batch_id
                or cast(json_unquote(json_extract(new.payload_json,'$.requestedLimit')) as signed)
                   <>new.requested_limit
                or not (json_extract(new.payload_json,'$.attemptIds')<=>new.attempt_ids)
                or not (json_extract(new.payload_json,'$.dispositions')<=>new.dispositions)
                or json_unquote(json_extract(new.payload_json,'$.predecessorHash'))
                   <>new.predecessor_hash
                or json_unquote(json_extract(new.payload_json,'$.batchEvidenceHash'))
                   <>new.batch_evidence_hash
                or json_unquote(json_extract(new.payload_json,'$.requestId'))<>new.request_id
                or not (json_unquote(json_extract(new.payload_json,'$.traceId'))<=>new.trace_id)
              then
                signal sqlstate '45000' set message_text='D7 batch payload mismatch';
              end if;
              if not exists (
                select 1
                from ap_process_migration_orchestration_run run
                join ap_process_migration_claim_batch claim
                  on claim.tenant_id=run.tenant_id and claim.intent_id=run.intent_id
                where run.tenant_id=new.tenant_id and run.run_id=new.run_id
                  and claim.claim_batch_id=new.claim_batch_id
                  and claim.claimed_attempt_ids=new.attempt_ids
              ) then
                signal sqlstate '45000'
                  set message_text='D7 batch D2 claim lineage mismatch';
              end if;
            end
            """.strip();
    }

    private static String killSwitchInsert() {
        return """
            create trigger trg_migration_d7_kill_switch_insert_v47
            before insert on ap_process_migration_kill_switch_observation
            for each row
            begin
              if json_unquote(json_extract(new.payload_json,'$.observationId'))
                   <>new.observation_id
                or json_unquote(json_extract(new.payload_json,'$.tenantId'))<>new.tenant_id
                or json_unquote(json_extract(new.payload_json,'$.runId'))<>new.run_id
                or json_unquote(json_extract(new.payload_json,'$.attemptId'))<>new.attempt_id
                or cast(json_unquote(json_extract(new.payload_json,'$.expectedRevision')) as signed)
                   <>new.expected_revision
                or cast(json_unquote(json_extract(new.payload_json,'$.observedRevision')) as signed)
                   <>new.observed_revision
                or json_unquote(json_extract(new.payload_json,'$.enabled'))
                   <>if(new.switch_enabled,'true','false')
                or json_unquote(json_extract(new.payload_json,'$.dispatchAllowed'))
                   <>if(new.dispatch_allowed,'true','false')
                or json_unquote(json_extract(new.payload_json,'$.reasonCode'))<>new.reason_code
                or json_unquote(json_extract(new.payload_json,'$.requestHash'))<>new.request_hash
                or json_unquote(json_extract(
                     new.payload_json,'$.observationEvidenceHash'))
                   <>new.observation_evidence_hash
                or json_unquote(json_extract(new.payload_json,'$.requestId'))<>new.request_id
                or not (json_unquote(json_extract(new.payload_json,'$.traceId'))<=>new.trace_id)
              then
                signal sqlstate '45000' set message_text='D7 kill-switch payload mismatch';
              end if;
              if not exists (
                select 1
                from ap_process_migration_orchestration_run run
                join ap_process_migration_attempt attempt
                  on attempt.tenant_id=run.tenant_id and attempt.intent_id=run.intent_id
                where run.tenant_id=new.tenant_id and run.run_id=new.run_id
                  and attempt.attempt_id=new.attempt_id
                  and run.expected_kill_switch_revision=new.expected_revision
              ) then
                signal sqlstate '45000' set message_text='D7 kill-switch lineage mismatch';
              end if;
            end
            """.strip();
    }
}

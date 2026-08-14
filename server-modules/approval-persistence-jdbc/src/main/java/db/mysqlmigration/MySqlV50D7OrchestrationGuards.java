package db.mysqlmigration;

import java.util.List;

/** MySQL 8.4 equivalents of the PostgreSQL V47 D7 evidence guard. */
final class MySqlV50D7OrchestrationGuards {

    private static final String APPEND_ONLY_MESSAGE =
        "M5-D7 evidence is append-only";
    private static final String EPOCH = "1970-01-01 00:00:00.000000";

    private MySqlV50D7OrchestrationGuards() {
    }

    static List<String> statements() {
        return List.of(
            canaryInsert(),
            immutable(
                "trg_migration_d7_canary_update_v47",
                "update",
                "ap_process_migration_canary_selection"
            ),
            immutable(
                "trg_migration_d7_canary_delete_v47",
                "delete",
                "ap_process_migration_canary_selection"
            ),
            runInsert(),
            immutable(
                "trg_migration_d7_run_update_v47",
                "update",
                "ap_process_migration_orchestration_run"
            ),
            immutable(
                "trg_migration_d7_run_delete_v47",
                "delete",
                "ap_process_migration_orchestration_run"
            ),
            eventInsert(),
            immutable(
                "trg_migration_d7_event_update_v47",
                "update",
                "ap_process_migration_orchestration_event"
            ),
            immutable(
                "trg_migration_d7_event_delete_v47",
                "delete",
                "ap_process_migration_orchestration_event"
            ),
            batchInsert(),
            immutable(
                "trg_migration_d7_batch_update_v47",
                "update",
                "ap_process_migration_orchestration_batch"
            ),
            immutable(
                "trg_migration_d7_batch_delete_v47",
                "delete",
                "ap_process_migration_orchestration_batch"
            ),
            killSwitchInsert(),
            immutable(
                "trg_migration_d7_kill_switch_update_v47",
                "update",
                "ap_process_migration_kill_switch_observation"
            ),
            immutable(
                "trg_migration_d7_kill_switch_delete_v47",
                "delete",
                "ap_process_migration_kill_switch_observation"
            )
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
              if %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
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
            """.formatted(
                textMismatch("selectionId", "selection_id"),
                textMismatch("tenantId", "tenant_id"),
                textMismatch("planId", "plan_id"),
                textMismatch("intentId", "intent_id"),
                textMismatch("algorithmVersion", "algorithm_version"),
                longMismatch("sequenceNo", "sequence_no"),
                textMismatch("approvalInstanceId", "approval_instance_id"),
                textMismatch("planHash", "plan_hash"),
                textMismatch("instanceEvidenceHash", "instance_evidence_hash"),
                textMismatch("selectionEvidenceHash", "selection_evidence_hash"),
                instantMismatch("recordedAt", "recorded_at"),
                textMismatch("requestId", "request_id"),
                nullableTextMismatch("traceId", "trace_id"),
                jsonObjectMismatch()
            ).strip();
    }

    private static String runInsert() {
        return """
            create trigger trg_migration_d7_run_insert_v47
            before insert on ap_process_migration_orchestration_run
            for each row
            begin
              if %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
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
            """.formatted(
                textMismatch("runId", "run_id"),
                textMismatch("tenantId", "tenant_id"),
                textMismatch("planId", "plan_id"),
                textMismatch("intentId", "intent_id"),
                longMismatch("runRevision", "run_revision"),
                textMismatch("phase", "phase"),
                longMismatch("requestedLimit", "requested_limit"),
                textMismatch("canarySelectionId", "canary_selection_id"),
                longMismatch(
                    "expectedKillSwitchRevision",
                    "expected_kill_switch_revision"
                ),
                textMismatch("predecessorHash", "predecessor_hash"),
                textMismatch("requestHash", "request_hash"),
                textMismatch("runEvidenceHash", "run_evidence_hash"),
                instantMismatch("startedAt", "started_at"),
                textMismatch("requestId", "request_id"),
                nullableTextMismatch("traceId", "trace_id"),
                jsonObjectMismatch()
            ).strip();
    }

    private static String eventInsert() {
        return """
            create trigger trg_migration_d7_event_insert_v47
            before insert on ap_process_migration_orchestration_event
            for each row
            begin
              if %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
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
              ) and not exists (
                select 1
                from ap_process_migration_kill_switch_observation observation
                where observation.tenant_id=new.tenant_id
                  and observation.run_id=new.run_id
                  and observation.attempt_id=new.attempt_id
                  and observation.observation_evidence_hash=new.predecessor_hash
                  and ((new.event_type='DISPATCH_ALLOWED'
                    and observation.dispatch_allowed=true)
                   or (new.event_type='KILL_SWITCH_BLOCKED'
                    and observation.dispatch_allowed=false))
              ) then
                signal sqlstate '45000' set message_text='D7 event predecessor mismatch';
              end if;
            end
            """.formatted(
                textMismatch("eventId", "event_id"),
                textMismatch("tenantId", "tenant_id"),
                textMismatch("runId", "run_id"),
                longMismatch("sequence", "sequence"),
                textMismatch("eventType", "event_type"),
                textMismatch("pauseReason", "pause_reason"),
                nullableTextMismatch("attemptId", "attempt_id"),
                textMismatch("predecessorHash", "predecessor_hash"),
                textMismatch("eventEvidenceHash", "event_evidence_hash"),
                instantMismatch("happenedAt", "happened_at"),
                textMismatch("requestId", "request_id"),
                nullableTextMismatch("traceId", "trace_id"),
                jsonObjectMismatch()
            ).strip();
    }

    private static String batchInsert() {
        return """
            create trigger trg_migration_d7_batch_insert_v47
            before insert on ap_process_migration_orchestration_batch
            for each row
            begin
              if %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
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
            """.formatted(
                textMismatch("batchEvidenceId", "batch_evidence_id"),
                textMismatch("tenantId", "tenant_id"),
                textMismatch("runId", "run_id"),
                textMismatch("claimBatchId", "claim_batch_id"),
                longMismatch("requestedLimit", "requested_limit"),
                jsonMismatch("attemptIds", "attempt_ids"),
                jsonMismatch("dispositions", "dispositions"),
                textMismatch("predecessorHash", "predecessor_hash"),
                textMismatch("batchEvidenceHash", "batch_evidence_hash"),
                instantMismatch("recordedAt", "recorded_at"),
                textMismatch("requestId", "request_id"),
                nullableTextMismatch("traceId", "trace_id"),
                jsonObjectMismatch()
            ).strip();
    }

    private static String killSwitchInsert() {
        return """
            create trigger trg_migration_d7_kill_switch_insert_v47
            before insert on ap_process_migration_kill_switch_observation
            for each row
            begin
              if %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
                or %s
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
            """.formatted(
                textMismatch("observationId", "observation_id"),
                textMismatch("tenantId", "tenant_id"),
                textMismatch("runId", "run_id"),
                textMismatch("attemptId", "attempt_id"),
                longMismatch("expectedRevision", "expected_revision"),
                longMismatch("observedRevision", "observed_revision"),
                booleanMismatch("enabled", "switch_enabled"),
                booleanMismatch("dispatchAllowed", "dispatch_allowed"),
                textMismatch("reasonCode", "reason_code"),
                textMismatch("requestHash", "request_hash"),
                textMismatch(
                    "observationEvidenceHash",
                    "observation_evidence_hash"
                ),
                instantMismatch("observedAt", "observed_at"),
                textMismatch("requestId", "request_id"),
                nullableTextMismatch("traceId", "trace_id"),
                jsonObjectMismatch()
            ).strip();
    }

    private static String textMismatch(String jsonField, String column) {
        return "not (json_unquote(json_extract(new.payload_json,'$."
            + jsonField + "'))<=>new." + column + ')';
    }

    private static String nullableTextMismatch(String jsonField, String column) {
        String value = "json_extract(new.payload_json,'$." + jsonField + "')";
        return "not ((case when json_type(" + value + ")='NULL' then null "
            + "else json_unquote(" + value + ") end)<=>new." + column + ')';
    }

    private static String longMismatch(String jsonField, String column) {
        return "not (cast(json_unquote(json_extract(new.payload_json,'$."
            + jsonField + "')) as signed)<=>new." + column + ')';
    }

    private static String booleanMismatch(String jsonField, String column) {
        return "not (json_unquote(json_extract(new.payload_json,'$."
            + jsonField + "'))<=>if(new." + column + ",'true','false'))";
    }

    private static String jsonMismatch(String jsonField, String column) {
        return "not (json_extract(new.payload_json,'$." + jsonField
            + "')<=>new." + column + ')';
    }

    private static String instantMismatch(String jsonField, String column) {
        return "not (round(cast(json_unquote(json_extract(new.payload_json,'$."
            + jsonField + "')) as decimal(30,9))*1000000,0)"
            + "<=>timestampdiff(microsecond,'" + EPOCH + "',new." + column + "))";
    }

    private static String jsonObjectMismatch() {
        return "not (json_type(new.payload_json)<=>'OBJECT')";
    }
}

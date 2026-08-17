package db.mysqlmigration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** MySQL 8.4 physical P4 evidence/event/state authority installed after the V50 snapshot. */
final class MySqlV50AiEvidenceGuards {

    private static final int REVIEWED_CHECKSUM = -2060857899;
    private static final String REVIEWED_SQL_SHA256 =
        "ee3071c3dda10fb86865b3b6771dc7b623fc5d407c9ed635ecaabdc1fd6fd4e0";
    private static final List<String> STATEMENTS = List.of(
        """
        alter table ap_ai_approval_assistance_evidence_event
          add column tombstone_hash char(64),
          add constraint chk_ai_assistance_event_tombstone_hash_v49 check (
            (event_type='STORED' and tombstone_hash is null)
            or (
              event_type='TOMBSTONED'
              and regexp_like(tombstone_hash,'^[0-9a-f]{64}$','c')
            )
          )
        """.strip(),
        """
        create trigger trg_ai_assistance_evidence_update_guard_v49
          before update on ap_ai_approval_assistance_evidence
          for each row
          signal sqlstate '45000'
            set message_text='P4 durable evidence is immutable'
        """.strip(),
        """
        create trigger trg_ai_assistance_evidence_delete_guard_v49
          before delete on ap_ai_approval_assistance_evidence
          for each row
          signal sqlstate '45000'
            set message_text='P4 durable evidence cannot be deleted'
        """.strip(),
        """
        create trigger trg_ai_assistance_event_before_insert_v49
          before insert on ap_ai_approval_assistance_evidence_event
          for each row
        begin
          declare evidence_recorded_at datetime(6) default null;
          declare retained_until datetime(6) default null;
          declare current_revision bigint default null;
          declare current_state varchar(16) default null;
          declare current_event_hash char(64) default null;

          select recorded_at,retention_until
            into evidence_recorded_at,retained_until
            from ap_ai_approval_assistance_evidence
           where tenant_id=new.tenant_id and evidence_id=new.evidence_id;
          if evidence_recorded_at is null then
            signal sqlstate '45000'
              set message_text='P4 evidence event lacks durable evidence';
          end if;
          if new.happened_at<evidence_recorded_at then
            signal sqlstate '45000'
              set message_text='P4 evidence event precedes durable evidence';
          end if;

          if new.event_type='STORED' then
            if new.revision<>1
              or new.predecessor_hash<>repeat('0',64)
              or new.happened_at<>evidence_recorded_at
              or new.delete_reason is not null
              or new.deletion_request_hash is not null
              or new.tombstone_hash is not null then
              signal sqlstate '45000'
                set message_text='P4 stored event evidence is invalid';
            end if;
            if exists (
              select 1 from ap_ai_approval_assistance_evidence_state
               where tenant_id=new.tenant_id and evidence_id=new.evidence_id
            ) then
              signal sqlstate '45000'
                set message_text='P4 stored event already has evidence state';
            end if;
          elseif new.event_type='TOMBSTONED' then
            select revision,state,current_event_hash
              into current_revision,current_state,current_event_hash
              from ap_ai_approval_assistance_evidence_state
             where tenant_id=new.tenant_id and evidence_id=new.evidence_id;
            if current_revision is null
              or current_state is null
              or current_event_hash is null
              or current_revision<>1
              or current_state<>'ACTIVE'
              or current_event_hash<>new.predecessor_hash
              or new.revision<>2
              or new.delete_reason is null
              or new.deletion_request_hash is null
              or new.tombstone_hash is null
              or not regexp_like(new.deletion_request_hash,'^[0-9a-f]{64}$','c')
              or not regexp_like(new.tombstone_hash,'^[0-9a-f]{64}$','c') then
              signal sqlstate '45000'
                set message_text='P4 tombstone event authority is invalid';
            end if;
            if new.delete_reason='RETENTION_EXPIRED'
              and new.happened_at<retained_until then
              signal sqlstate '45000'
                set message_text='P4 retention-expired event is premature';
            end if;
          else
            signal sqlstate '45000'
              set message_text='P4 evidence event type is unsupported';
          end if;
        end
        """.strip(),
        """
        create trigger trg_ai_assistance_event_after_insert_v49
          after insert on ap_ai_approval_assistance_evidence_event
          for each row
        begin
          if new.event_type='STORED' then
            insert into ap_ai_approval_assistance_evidence_state (
              tenant_id,evidence_id,revision,state,delete_reason,tombstoned_at,
              deletion_request_hash,tombstone_hash,current_event_hash,updated_at
            ) values (
              new.tenant_id,new.evidence_id,1,'ACTIVE',null,null,
              null,null,new.event_hash,new.happened_at
            );
          else
            update ap_ai_approval_assistance_evidence_state
               set revision=2,
                   state='TOMBSTONED',
                   delete_reason=new.delete_reason,
                   tombstoned_at=new.happened_at,
                   deletion_request_hash=new.deletion_request_hash,
                   tombstone_hash=new.tombstone_hash,
                   current_event_hash=new.event_hash,
                   updated_at=new.happened_at
             where tenant_id=new.tenant_id
               and evidence_id=new.evidence_id
               and revision=1
               and state='ACTIVE'
               and current_event_hash=new.predecessor_hash;
            if row_count()<>1 then
              signal sqlstate '45000'
                set message_text='P4 tombstone event lost evidence-state CAS';
            end if;
          end if;
        end
        """.strip(),
        """
        create trigger trg_ai_assistance_event_update_guard_v49
          before update on ap_ai_approval_assistance_evidence_event
          for each row
          signal sqlstate '45000'
            set message_text='P4 evidence events are append-only'
        """.strip(),
        """
        create trigger trg_ai_assistance_event_delete_guard_v49
          before delete on ap_ai_approval_assistance_evidence_event
          for each row
          signal sqlstate '45000'
            set message_text='P4 evidence events cannot be deleted'
        """.strip(),
        """
        create trigger trg_ai_assistance_state_before_insert_v49
          before insert on ap_ai_approval_assistance_evidence_state
          for each row
        begin
          declare matching_event integer default 0;
          select count(*) into matching_event
            from ap_ai_approval_assistance_evidence_event event
           where event.tenant_id=new.tenant_id
             and event.evidence_id=new.evidence_id
             and event.revision=1
             and event.event_type='STORED'
             and event.event_hash=new.current_event_hash
             and event.happened_at=new.updated_at
             and event.delete_reason is null
             and event.deletion_request_hash is null
             and event.tombstone_hash is null;
          if new.revision<>1
            or new.state<>'ACTIVE'
            or new.delete_reason is not null
            or new.tombstoned_at is not null
            or new.deletion_request_hash is not null
            or new.tombstone_hash is not null
            or matching_event<>1 then
            signal sqlstate '45000'
              set message_text='P4 evidence state lacks matching stored event';
          end if;
        end
        """.strip(),
        """
        create trigger trg_ai_assistance_state_before_update_v49
          before update on ap_ai_approval_assistance_evidence_state
          for each row
        begin
          declare evidence_recorded_at datetime(6) default null;
          declare retained_until datetime(6) default null;
          declare matching_event integer default 0;
          select recorded_at,retention_until
            into evidence_recorded_at,retained_until
            from ap_ai_approval_assistance_evidence
           where tenant_id=new.tenant_id and evidence_id=new.evidence_id;
          select count(*) into matching_event
            from ap_ai_approval_assistance_evidence_event event
           where event.tenant_id=new.tenant_id
             and event.evidence_id=new.evidence_id
             and event.revision=2
             and event.event_type='TOMBSTONED'
             and event.predecessor_hash=old.current_event_hash
             and event.event_hash=new.current_event_hash
             and event.happened_at=new.tombstoned_at
             and event.delete_reason=new.delete_reason
             and event.deletion_request_hash=new.deletion_request_hash
             and event.tombstone_hash=new.tombstone_hash;
          if old.tenant_id<>new.tenant_id
            or old.evidence_id<>new.evidence_id
            or old.revision<>1
            or old.state<>'ACTIVE'
            or new.revision<>2
            or new.state<>'TOMBSTONED'
            or new.updated_at<>new.tombstoned_at
            or new.tombstoned_at<evidence_recorded_at
            or matching_event<>1 then
            signal sqlstate '45000'
              set message_text='P4 evidence state lacks matching tombstone event';
          end if;
          if new.delete_reason='RETENTION_EXPIRED'
            and new.tombstoned_at<retained_until then
            signal sqlstate '45000'
              set message_text='P4 retention-expired tombstone is premature';
          end if;
        end
        """.strip(),
        """
        create trigger trg_ai_assistance_state_delete_guard_v49
          before delete on ap_ai_approval_assistance_evidence_state
          for each row
          signal sqlstate '45000'
            set message_text='P4 evidence state cannot be deleted'
        """.strip()
    );

    private MySqlV50AiEvidenceGuards() {
    }

    static List<String> statements() {
        return STATEMENTS;
    }

    static int checksum() {
        String actual = sha256(String.join("\n;\n", STATEMENTS));
        if (!REVIEWED_SQL_SHA256.equals(actual)) {
            throw new IllegalStateException("MySQL H8 AI evidence guard SQL drift");
        }
        return REVIEWED_CHECKSUM;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}

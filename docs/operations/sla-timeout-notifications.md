# Durable SLA timeout notifications

This opt-in slice of #146 connects the existing governed OVERDUE action to the
existing transactional Outbox and organization notification API. It does not add
an HTTP endpoint, scheduler, database migration, or automatic approval/payment
operation. PROCESS_EXECUTION_FAILED detection and resolution notifications are
not implemented by this slice.

## Enable and disable

Configure `approval.sla.operational-notifications.enabled=true` explicitly.
The existing `approval.sla.execution.enabled` must also be enabled to run the SLA
worker. The existing generic connector and its Outbox dispatch must be enabled
and configured; startup fails rather than accepting an enabled source without
that delivery path. Connector secrets stay in deployment configuration. Default
notification creation is off; the original overdue state recording is retained.

The reserved internal Outbox route is `approval-sla-timeout`. It must not be used
as the generic connector's configured key. Other Outbox keys continue through
the original resolver. The configured generic connector key is captured in the
event, so changing it does not silently rebind old events to another connector.
The target URI and credentials remain operator-owned connector configuration.

Disabling the feature stops new notification events. Previously queued reserved
messages are rejected with `SLA_NOTIFICATION_DISABLED` and become DEAD through
the existing dispatcher, never sent to the payment callback. Drain or deliberately
stop dispatch before disabling when that is not the intended outcome. Retain and
inspect DEAD evidence; only authorized existing recovery may replay an event.
Do not repurpose the reserved route or edit payment records to clear an alert.

## Source authority and atomicity

The source uses the real claimed SLA execution intent, not HTTP status, process
age, a Prometheus alert, or a browser-supplied recipient. It locks the tenant's
SLA row and execution intent, verifies the exact current claim/version/owner and
unexpired lease, and checks the active SLA, bound overdue deadline, target,
policy version and responsibility snapshot. Time is read from the trusted
application clock after acquiring locks. Changed/paused targets and stale
workers cannot enqueue new events.

The original overdue sequence update and Outbox append participate in the same
transaction. Rollback loses both. A repeated sequence returns the original
idempotent state result without generating another event, including a worker
restart after committing the event but before acknowledging its action. Enabling
this feature does not backfill sequences already recorded by older deployments.
There is no transport call inside this transaction.

Event names preserve target scope: `PROCESS_TIMEOUT_DETECTED.v1`,
`TASK_TIMEOUT_DETECTED.v1`, or `COLLABORATION_TIMEOUT_DETECTED.v1`. A task timeout
is not mislabeled as a failed or timed-out whole process. The `SLA_TIMEOUT`
aggregate refers to the SLA instance. Payload version 1 includes only target and
SLA/policy identifiers, action sequence, recipient snapshot, deadline and
connector key. Form values, comments, attachments and raw exception details are
not copied. Existing tenant/request/trace correlation is retained; this does not
establish a new W3C span propagation or trace-export acceptance result.

## Asynchronous delivery and duplicate handling

The original Outbox dispatcher owns claims, retry/backoff, lease recovery and
terminal failure. The new adapter sends a fixed title/body and bounded metadata
through `OrganizationConnector.sendNotification`, using a deterministic SHA-256
key scoped to tenant, SLA instance and action sequence. It never invokes the
business/payment callback. Invalid events are rejected before calling the
connector, and provider exception text is not saved as an Outbox error.

A successful adapter receipt means the configured organization connector
acknowledged delivery, not that a human read it. The organization API does not
expose HTTP status; stored response code 0 is intentionally not a fabricated wire
status. Provider message IDs, retry state and DEAD evidence use existing fields.

Delivery remains at least once. The receiving organization integration must
persist and honor the supplied deduplication key to prevent duplicate external
notifications after an acknowledgement loss. This repository-side source ensures
one durable event per recorded action, not universal exactly-once human delivery.
Already-created events retain their detection-time recipient snapshot; opening
any referenced approval still requires ordinary current authorization.

## Verification boundaries

Normal Maven tests cover the fixed event/route contract, default-off wiring,
reserved-route rejection after disabling, existing non-overdue actions, and
PostgreSQL source rollback/concurrency/lease/state handling. The database test
also runs the existing SLA and Outbox workers, simulates a lost acknowledgement,
reconstructs the delivery adapter, and checks one receiver record using a unique
key in a **test-only PostgreSQL recipient table**. No production table or Flyway
version is added for that recipient fixture.

The fixture has seeded authoritative SLA/policy rows and a controlled organization
connector. It is not a new Flowable timer/browser/real-contact end-to-end test,
production notification-service deployment, collector outage drill, or proof of
all #146 outcomes. Inspect the current CI logs for execution results; source and
test existence alone are not acceptance. Existing Prometheus/Alertmanager rule
rehearsal and business-metrics evidence remain separate.

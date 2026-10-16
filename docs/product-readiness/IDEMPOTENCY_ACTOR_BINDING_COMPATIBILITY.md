# Actor-bound idempotency: upgrade compatibility

The idempotency guard binds each new command receipt to the trusted request operator
as well as the original request hash. Tenant, operation and idempotency-key identity
remain unchanged. Business projection and form-submission hashes are unchanged.

## Existing receipts

Receipts created before actor binding do not prove which operator may replay their
stored result. They therefore fail closed with a non-retryable idempotency conflict,
including when the original operator presents the original key and payload. There is
no legacy-hash fallback, stored-result fallback or automatic re-execution. Preserve
these receipts and their database state for review; do not rewrite their hashes,
delete them or change command keys merely to bypass the conflict.

New actor-bound receipts still allow the same operator to replay the same completed
command. A different operator using that key cannot receive the stored result or
rerun the command. A different key does not bypass the start/form-submission
business-key ownership checks.

## Deterministic local demo seed

The explicitly enabled purchase-payment demo uses fixed seed keys. Pointing an
upgraded server at a database with legacy seed receipts may stop startup with:

`PURCHASE_PAYMENT_DEMO_SEED_REPLAY_CONFLICT`

This diagnostic also covers genuine actor or payload conflicts; it does not prove
that every conflict is a legacy receipt. The seed stops at the conflict, retains the
original cause, and does not retry, invent replacement keys, clear data, or replay
legacy JSON. Earlier committed state remains available for investigation.

For preserved **local demo** data, inspect the read-only plan and explicitly select
restore-only startup:

```sh
node scripts/product-readiness/demo-backend.mjs plan-restored --json
node scripts/product-readiness/demo-backend.mjs start-restored
```

This keeps `approval.demo.purchase-payment.enabled=true`, preserving the governed
scenario, organization directory, assignee resolver, microsecond-precision demo
clock and any explicitly enabled payment sandbox. It sets the independent
`approval.demo.purchase-payment.seed.enabled=false` switch, disabling only the seed
runner and its seed-completion health indicator. The switch defaults to `true`;
values other than exact `true` or `false` fail startup. The entire demo configuration
remains local-profile-only. Direct local startup may set
`APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED=false` with the demo enabled, but the
ordinary `start` command deliberately forces seeding on, even if inherited
environment or Spring JSON settings attempt to disable it. Use `start-restored`
explicitly; unknown commands and options fail closed.

Restore-only startup still runs the ordinary local infrastructure, build and schema
startup lifecycle. It is not a read-only database inspection tool. It does not run
the fixed seed, reset data, migrate or re-authorize old receipts, or replace command
keys. It waits for Actuator `UP` and
`PURCHASE_PAYMENT_DEMO_RESTORE_ONLY_STARTED`, emitted after application runners
finish, then reports `BACKEND_LOCAL_RESTORE_ONLY_STARTED`. These markers prove only
application readiness with seeding disabled. They do not prove that a backup was
restored, that any expected business data exists, or that old data is consistent.
Even an empty database can become ready in this mode. No seed-success marker is
emitted; observing a seed-success marker causes the launcher to fail closed.

The capacity upgrade/restore rehearsal uses ordinary seeded startup on the exact
baseline and explicit restore-only startup on the candidate. Its original instance,
task and audit before/after equality, approval continuation, payment and Outbox
assertions remain mandatory; missing or changed restored records fail the rehearsal
regardless of startup readiness. Legacy command replay remains rejected. A fresh
demo requires a separately provisioned database; do not reset an existing database
as an automatic upgrade step. This workflow changes no production permissions and
does not establish production upgrade or recovery guarantees.

The runtime attachment, message, legacy form-publish and UI-schema-publish endpoints
return HTTP 409, `IDEMPOTENCY_CONFLICT`, and `retryable=false` for these conflicts.
Controllers with existing specialized conflict codes retain those contracts.

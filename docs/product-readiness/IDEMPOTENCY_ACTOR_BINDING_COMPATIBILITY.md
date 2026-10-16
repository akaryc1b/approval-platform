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

To inspect preserved data without running the fixed seed, start with
`approval.demo.purchase-payment.enabled=false` and the deployment's appropriate
authentication/connector configuration. This does not migrate or re-authorize old
receipts. A fresh demo requires a separately provisioned database; do not reset an
existing database as an automatic upgrade step.

The runtime attachment, message, legacy form-publish and UI-schema-publish endpoints
return HTTP 409, `IDEMPOTENCY_CONFLICT`, and `retryable=false` for these conflicts.
Controllers with existing specialized conflict codes retain those contracts.

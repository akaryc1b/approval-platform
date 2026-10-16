# Actor-bound command idempotency

`JdbcIdempotencyGuard` reserves the existing `(tenant_id, operation, idempotency_key)`
primary key. A successful replay requires the same exact `RequestContext.operatorId`
and the same caller-supplied payload hash. Request and trace IDs may change between
retries. The actor comes from the resolved request context, not a payload identity;
hosts must supply their authenticated operator in that context.

The command table's `request_hash` now stores a lowercase SHA-256 digest of these
three fields, in order:

1. `approval-command-idempotency:actor-bound:v1`
2. The exact operator ID
3. The original validated lowercase 64-hex request hash

Each field is UTF-8 encoded and prefixed with its byte length as a four-byte,
big-endian integer. Malformed Unicode is rejected; actor IDs are not trimmed or
Unicode-normalized. This format is separate from business/projection request
hashes, which retain their existing meaning. No schema migration is required.

The guard compares the bound digest before inspecting cached status, result type
or result data. A different actor or payload raises `IdempotencyConflictException`
without invoking the command. The existing database uniqueness, transaction and
rollback behavior remain in place, including concurrent requests for one key.

## Existing rows and upgrades

Rows written before actor binding contain an unbound payload hash. They fail closed
with an idempotency conflict, including retries by the original actor and both
`COMPLETED` and `IN_PROGRESS` records. The old format does not prove which actor
owns a cached response. There is no fallback to the raw hash, automatic conversion,
deletion, key rotation or automatic command re-execution.

Before retrying a command that conflicts after upgrade, inspect its authoritative
business state and audit history. Do not treat the conflict as permission to submit
the same action under a new key. Preserve historical command records for diagnosis.
Startup jobs that replay fixed pre-upgrade keys, including the ProductAlpha demo
seeder, can encounter this conflict and must handle the upgrade explicitly without
bypassing actor binding or repeating completed side effects.

Rollback to a version that stores raw hashes is also format-incompatible with rows
written by the bound guard. It reintroduces the earlier cross-actor replay weakness
for historical raw-hash rows. Plan recovery using a coherent application/database
checkpoint; do not rewrite command hashes to make either version accept them.
Mixed old/new application writers are not a supported transparent rollout: the
versions cannot replay each other's stored digest format, and old binaries do not
gain actor binding. Deploy the repaired version consistently before relying on this
protection; these source changes do not themselves perform a deployment.

## Regression coverage

`JdbcIdempotencyActorBindingIntegrationTest` uses the existing PostgreSQL
Testcontainers and Flyway setup. It covers durable same-actor replay, actor/payload
conflicts before cache decoding, legacy rows, namespace isolation, Unicode actor
identity, competing callers and transactional rollback. A run skipped because Docker
is unavailable is not evidence that the PostgreSQL behavior passed.

# M6-A-P9 Fault and Security Acceptance

## Status

- `NON_PRODUCTION_ACCEPTANCE_ONLY`
- `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`
- `APPROVAL_STATE_MUTATION_NOT_AUTHORIZED`

## Closed scenario matrix

Every scenario is fail-closed or no-effect. None grants production execution, retry, replay, recovery,
persistence, background execution or approval mutation.

| ID | Stable code | Phase | Required disposition | Dispatch count |
| --- | --- | --- | --- | ---: |
| `F01` | `SECRET_SOURCE_DISABLED` | `SECRET` | `REJECT_BEFORE_MATERIAL` | `0` |
| `F02` | `SECRET_BACKEND_UNAVAILABLE` | `SECRET` | `REJECT_BEFORE_MATERIAL` | `0` |
| `F03` | `CREDENTIAL_TENANT_MISMATCH` | `CREDENTIAL` | `REJECT_BEFORE_TOKEN` | `0` |
| `F04` | `CREDENTIAL_PROVIDER_MISMATCH` | `CREDENTIAL` | `REJECT_BEFORE_TOKEN` | `0` |
| `F05` | `CREDENTIAL_BINDING_DISABLED` | `CREDENTIAL` | `REJECT_BEFORE_TOKEN` | `0` |
| `F06` | `CREDENTIAL_NOT_YET_VALID` | `CREDENTIAL` | `REJECT_BEFORE_TOKEN` | `0` |
| `F07` | `CREDENTIAL_EXPIRED` | `CREDENTIAL` | `REJECT_BEFORE_TOKEN` | `0` |
| `F08` | `CREDENTIAL_VERSION_DRIFT` | `CREDENTIAL` | `REJECT_BEFORE_TOKEN` | `0` |
| `F09` | `CREDENTIAL_MATERIAL_MALFORMED` | `CREDENTIAL` | `REJECT_BEFORE_TOKEN` | `0` |
| `F10` | `CREDENTIAL_CALLBACK_FAILURE` | `CREDENTIAL` | `REJECT_BEFORE_TOKEN` | `0` |
| `F11` | `ROUTE_MISSING` | `ROUTE` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F12` | `ROUTE_DISABLED` | `ROUTE` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F13` | `ROUTE_STALE` | `ROUTE` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F14` | `ROUTE_UNSUPPORTED` | `ROUTE` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F15` | `KILL_SWITCH_BLOCKED` | `ADMISSION` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F16` | `KILL_SWITCH_REVISION_DRIFT` | `ADMISSION` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F17` | `KILL_SWITCH_UNAVAILABLE` | `ADMISSION` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F18` | `TOKEN_POLICY_DRIFT` | `TOKEN` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F19` | `TOKEN_ROUTE_DRIFT` | `TOKEN` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F20` | `TOKEN_ENDPOINT_UNAVAILABLE` | `TOKEN` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F21` | `TOKEN_RESPONSE_MALFORMED` | `TOKEN` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F22` | `TOKEN_LIFETIME_INVALID` | `TOKEN` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F23` | `TOKEN_SINGLE_FLIGHT_HANDOFF` | `TOKEN` | `CACHE_HANDOFF_NO_SECOND_ENDPOINT` | `0` |
| `F24` | `COORDINATOR_CLOSED` | `INVOCATION` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F25` | `REQUEST_OVERSIZE` | `INVOCATION` | `REJECT_BEFORE_DISPATCH` | `0` |
| `F26` | `RESPONSE_OVERSIZE` | `INVOCATION` | `UNKNOWN_AFTER_DISPATCH` | `1` |
| `F27` | `PROVIDER_REJECTED` | `TRANSPORT` | `PROVIDER_REJECTED` | `1` |
| `F28` | `TRANSPORT_TIMEOUT` | `TRANSPORT` | `UNKNOWN_AFTER_DISPATCH` | `1` |
| `F29` | `TRANSPORT_EXCEPTION` | `TRANSPORT` | `UNKNOWN_AFTER_DISPATCH` | `1` |
| `F30` | `TRANSPORT_UNKNOWN` | `TRANSPORT` | `UNKNOWN_AFTER_DISPATCH` | `1` |
| `F31` | `DIAGNOSTICS_SINK_FAILURE` | `DIAGNOSTICS` | `INVOCATION_RESULT_UNCHANGED` | `0` |
| `F32` | `PAGE_TOKEN_TAMPER` | `DIAGNOSTICS` | `HTTP_400_REDACTED` | `0` |
| `F33` | `PAGE_TOKEN_CROSS_TENANT` | `DIAGNOSTICS` | `HTTP_409_REDACTED` | `0` |
| `F34` | `PAGE_TOKEN_FILTER_DRIFT` | `DIAGNOSTICS` | `HTTP_409_REDACTED` | `0` |
| `F35` | `DIAGNOSTICS_UNAUTHORIZED` | `AUTHORIZATION` | `HTTP_401` | `0` |
| `F36` | `DIAGNOSTICS_FORBIDDEN` | `AUTHORIZATION` | `HTTP_403` | `0` |
| `F37` | `DIAGNOSTICS_PAGE_SIZE_INVALID` | `DIAGNOSTICS` | `HTTP_422_REDACTED` | `0` |
| `F38` | `METRIC_HIGH_CARDINALITY_REJECTED` | `OBSERVABILITY` | `NO_HIGH_CARDINALITY_LABEL` | `0` |
| `F39` | `RAW_TENANT_OUTPUT_REJECTED` | `DATA_MINIMIZATION` | `HASH_ONLY_OUTPUT` | `0` |
| `F40` | `SECRET_LITERAL_SCAN` | `DATA_MINIMIZATION` | `NO_SECRET_LITERAL` | `0` |
| `F41` | `FLYWAY_V49_REJECTED` | `REPOSITORY` | `V48_ONLY` | `0` |
| `F42` | `SECOND_AUTOMATIC_WORKFLOW_REJECTED` | `REPOSITORY` | `ONE_AUTOMATIC_WORKFLOW` | `0` |

## Acceptance invariants

- at least 24 fault/security scenarios are executable as permanent tests;
- dispatch count is exactly zero or one;
- `UNKNOWN_AFTER_DISPATCH` requires one recorded dispatch;
- all pre-dispatch failures record zero dispatch;
- diagnostic observation failure cannot alter the governed invocation result;
- raw tenant, subject, credential, Token and Provider response material are excluded;
- production source and configuration contain no committed usable Secret literal;
- no private-key, keystore or environment-secret artifact is introduced;
- Flyway remains through V48 and no V49 exists;
- exactly one automatic PR/main workflow remains;
- no POST/PUT/PATCH/DELETE connector-operations management route exists;
- no worker, scheduler, listener, retry, replay, recovery or reconciliation is introduced.

## Scope boundary

This acceptance matrix is synthetic and non-production. It does not call DingTalk, open a real
Secret Backend, configure a customer endpoint, persist diagnostics, or authorize a process command.

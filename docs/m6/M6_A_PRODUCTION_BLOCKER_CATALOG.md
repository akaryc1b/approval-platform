# M6-A Production Blocker Catalog

## Decision

Production connector execution remains `BLOCKED`. Every blocker below requires a separate owner,
evidence package and explicit governance decision. P9 closes none of them.

| ID | Blocker | Status |
| --- | --- | --- |
| `B01` | `REAL_SECRET_BACKEND_APPROVAL` | `BLOCKED` |
| `B02` | `CUSTOMER_ENDPOINT_APPROVAL` | `BLOCKED` |
| `B03` | `PRODUCTION_EGRESS_CONTROL` | `BLOCKED` |
| `B04` | `DURABLE_AUDIT_OWNERSHIP` | `BLOCKED` |
| `B05` | `ON_CALL_OWNER` | `BLOCKED` |
| `B06` | `INCIDENT_PLAYBOOK` | `BLOCKED` |
| `B07` | `LOAD_CAPACITY_EVIDENCE` | `BLOCKED` |
| `B08` | `DATA_RETENTION_APPROVAL` | `BLOCKED` |
| `B09` | `SECURITY_REVIEW` | `BLOCKED` |
| `B10` | `CHANGE_APPROVAL` | `BLOCKED` |
| `B11` | `PRODUCTION_KILL_SWITCH` | `BLOCKED` |
| `B12` | `TENANT_ALLOWLIST` | `BLOCKED` |
| `B13` | `TOKEN_ROTATION_OPERATIONS` | `BLOCKED` |
| `B14` | `PROVIDER_RATE_LIMIT_POLICY` | `BLOCKED` |
| `B15` | `PROVIDER_CONTRACT_APPROVAL` | `BLOCKED` |
| `B16` | `LEGAL_PRIVACY_APPROVAL` | `BLOCKED` |
| `B17` | `RELEASE_SIGN_OFF` | `BLOCKED` |
| `B18` | `OBSERVABILITY_BACKEND_APPROVAL` | `BLOCKED` |
| `B19` | `DISASTER_RECOVERY_PLAN` | `BLOCKED` |
| `B20` | `APPROVAL_MUTATION_SEPARATION` | `BLOCKED` |

## Non-substitution rules

- a successful CI Run is not production approval;
- synthetic fixtures are not customer configuration;
- process-local diagnostics are not durable audit;
- a code-level kill switch contract is not an operated production control;
- hash-only evidence is not a credential or endpoint approval;
- PR Draft acceptance is not Ready, auto-merge or merge authorization;
- P9 does not authorize approval, reject, transfer, withdraw, terminate or migrate commands.

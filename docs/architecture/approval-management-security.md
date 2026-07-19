# Approval Management Security Boundary

## Scope

This document defines the authorization boundary for Approval administration APIs. It covers process and form design, immutable version reads, publication, deployment, effective-release activation and rollback, and artifact transfer.

Runtime APIs for initiating forms, reading assigned tasks, approving, rejecting, commenting, uploading attachments and reading participant data continue to use their own participant and task-assignment rules. They are deliberately not authorized by the management interceptor.

## Closed capability model

Every management controller or method declares exactly one required capability through `@ApprovalManagementPermission`:

| Capability | Authority | Typical operations |
| --- | --- | --- |
| Read | `approval.management.read` | list/read drafts, versions, releases, deployment/effective state and structural diff |
| Design | `approval.management.design` | create, copy, update, validate, simulate and archive Form/Approval drafts |
| Publish | `approval.management.publish` | publication preflight and immutable Form/Release Package publication |
| Deploy | `approval.management.deploy` | deployment preflight and explicit Release Package deployment/retry |
| Activate | `approval.management.activate` | activate a deployed Release Package or roll back to a previously effective release |
| Transfer | `approval.management.transfer` | export/import Approval DSL and Release Package transfer envelopes |
| Administrator | `approval.management.admin` | explicit super-authority for all management capabilities |

Capabilities are exact. `publish` does not imply `deploy`, and `deploy` does not imply `activate`. This prevents a designer from silently becoming a production release operator.

A reflection-based coverage test asserts the expected capability on the management controllers. A newly added management endpoint without an annotation is not considered complete.

## Authority sources

### Principal mode — default

Production defaults to `principal`. The server requires an authenticated Servlet principal and checks `HttpServletRequest.isUserInRole` for the exact authority or `approval.management.admin`.

The host authentication adapter is responsible for creating the authenticated principal and mapping host roles/permissions to the canonical Approval authorities. Browser code cannot grant itself a role.

### Trusted-header mode — explicit opt-in

`trusted-header` mode exists for deployments where an authenticated edge gateway performs authorization mapping. The gateway may inject a bounded comma-separated header, by default:

```text
X-Approval-Trusted-Permissions
```

This mode is secure only when all of the following are true:

1. direct network access to the Approval service is blocked;
2. the gateway removes every inbound copy of the trusted header;
3. the gateway authenticates the caller and injects a newly generated header;
4. the service accepts traffic only from the trusted gateway network or identity;
5. the gateway also owns tenant and operator context mapping.

The header accepts at most 32 canonical lower-case authorities and 2 KiB. Empty tokens, unknown syntax, HTML/script fragments, upper-case aliases and oversized input are rejected.

## Configuration

```text
APPROVAL_MANAGEMENT_PERMISSIONS_ENFORCED=true
APPROVAL_MANAGEMENT_PERMISSION_SOURCE=principal
APPROVAL_MANAGEMENT_TRUSTED_HEADER=X-Approval-Trusted-Permissions
```

The `local` Spring profile disables this boundary for the repository's standalone development environment. Production deployments must not activate the local profile and must not disable the boundary.

Invalid authority-source configuration fails application startup rather than silently selecting a weaker mode.

## Failure behavior

Authorization failures return HTTP `403` with stable code:

```text
APPROVAL_MANAGEMENT_PERMISSION_DENIED
```

The response does not reveal which authorities the caller supplied. Missing authentication, malformed trusted headers and insufficient permissions share the same safe client message.

Denied requests produce a structured warning event:

```text
event=APPROVAL_MANAGEMENT_ACCESS_DENIED
```

Only bounded, sanitized tenant/operator/request identifiers, the required canonical authority and a low-cardinality reason are logged. Request bodies, query values, artifact contents, full headers and submitted permission sets are never logged.

## Observability

The interceptor emits only low-cardinality metrics:

- `approval.management.authorization`
  - tags: `requirement`, `outcome`
  - outcomes: `allowed`, `denied`, `bypassed`
- `approval.management.request.duration`
  - tags: `requirement`, `outcome`
  - outcomes: `success`, `client_error`, `server_error`

Tenant ID, operator ID, definition key, request ID, path and artifact hashes are intentionally excluded from metric tags. These values belong in bounded logs or the transactional audit store, not in time-series dimensions.

## Business audit separation

The authorization interceptor records access decisions and request latency. Successful business state changes continue to write transactional audit events in the same application transaction as the platform state change. Important events include:

- `FORM_PACKAGE_PUBLISHED`;
- `APPROVAL_RELEASE_PACKAGE_PUBLISHED`;
- `APPROVAL_RELEASE_DEPLOYED`;
- `APPROVAL_RELEASE_DEPLOYMENT_FAILED`;
- `APPROVAL_RELEASE_ACTIVATED`;
- `APPROVAL_RELEASE_ROLLED_BACK`;
- `APPROVAL_DESIGN_DRAFT_IMPORTED`.

Authorization logs do not replace business audit events, and business audit events do not replace the authorization boundary.

## Client responsibility

PC, H5 and WeChat clients display actions according to host UI permissions, but UI visibility is not an authorization boundary. The server always checks the declared capability.

The PC application sends tenant/operator/request/idempotency context required by current standalone adapters. In production, the trusted authentication layer must overwrite or independently validate those identities. A client-supplied operator or tenant header must never be trusted merely because it came from the Approval UI.

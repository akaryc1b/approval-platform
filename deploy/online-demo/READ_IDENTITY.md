# Private signed-read identity preflight

Tracking: #144 / #107, implementation in PR #148. This is an internal authentication milestone, **not a connected browser approval experience**.

## Implemented boundary

The explicit `online-demo` Spring profile installs `OnlineEvaluationReadIdentityFilter` before the existing `ApprovalIdentityContextFilter`. It requires the profile to be active alone, loopback binding, principal identity, enforced management permissions, health-only management exposure and disabled Connector/dispatch/migration execution. The base and local identity configurations are unchanged. Missing public key/generation or incompatible configuration prevents initialization.

The only business operation allowed by this profile is exact `GET /api/approval/tasks/pending`, without query parameters or a body. The other allowed route is private `GET /actuator/health`. Writes, alternate paths, management routes, client identity/authority/cookie/correlation headers and non-loopback connections are denied. A successful proof creates the existing `ApprovalPrincipal` with the canonical tenant and scenario business actor, **empty authorities/responsibilities**, and a short expiry. The existing identity filter remains responsible for trusted request headers. The signed proof header is removed before the business chain.

The adapter generates a fresh Ed25519 keypair per environment generation. Only the X.509 public key and random generation ID enter the backend configuration. The private signing key stays inside a bounded reset/preflight closure; its signer is disabled after the attempt. No signing endpoint, private-key export, browser credential or persistent gateway principal is provided. Nothing in the existing invitation controller is treated as platform authority.

Proofs bind protocol version, HTTP method, exact path, generation, actor, issue/expiry times and random nonce. Java verifies the Ed25519 signature, canonical encodings, exact fields, a maximum ten-second lifetime, and single use. The synchronized replay ledger is capped at 256 live nonces; it does not evict live entries to admit another proof. Expired entries may be removed. Invalid/regressing verification clocks permanently stop admission. There is no cross-slot key fallback. These proofs are internal per-request authentication, not general-purpose JWTs or durable user sessions.

## Actual command integration

The existing `online-demo-images-runtime.mjs run|ci` command now explicitly selects `readOnlyIdentity: true` for its two-slot rehearsal and requires `SIGNED_PENDING_READ_PREFLIGHT_PASSED` in the returned evidence. The standalone adapter/rehearsal default remains the existing non-authenticating mode for compatibility; that default cannot satisfy the command's new identity requirement.

Each of four environment generations (initial A/B, end-reset A, expiry-reset B) must pass thirteen real HTTP checks before its reset acknowledgement: an unsigned read is rejected, all five canonical business actors can read the fresh pending-task page, replay of each proof is rejected, spoofed identity is rejected, and a management path is refused. The response is validated as the existing `PendingTaskPage` object (`items`, `total`, `limit`, `offset`), not an invented array API. Empty tasks are expected because this phase does not seed or start a purchase.

These checks execute through the existing non-root namespace-sharing probe. Retained receipts contain check identifiers and the generation count, not signed proofs, private keys or response bodies. The existing network/storage marker checks, reset fencing and exact owned cleanup remain. Builds are reused; no new workflow/job, extra build, image push, host port or deployment is added. Reset and workflow time limits are unchanged.

## Verification and limitations

The Node test suite compiles the actual JDK verifier with `javac` and feeds it proofs signed by Node, testing actors, altered claims, signatures, replay capacity/expiry, clocks and configuration. This requires Java 17+ and fails rather than silently skipping missing Java. JUnit tests exercise both real servlet filters in one chain, current profile configuration, canonical resources and concurrent replay. Those Spring tests require the repository's Java 21/Maven verification and are not replaced by the standalone Java harness.

Successful local protocol tests do not prove the new packaged backend initialized correctly; the natural exact-source CI must run the real four-generation preflight and retain its receipt. An older image/reset run is not acceptance of this change.

**Remaining:** trusted browser-session-to-slot dispatch and revocation fencing, seeded visible PC/H5 approval, actual attachment/Outbox/signed-payment-sandbox isolation and reset, full outbound traffic controls, crash/operator recovery, image scans/reproducibility, and hosted capacity. The landing page/session API still state `businessAccess: NOT_CONNECTED`. No public URL or hosted service is delivered, and no administrative authority or approval write is enabled by this increment. The profile is deliberately insufficient for the complete evaluation product and must not be published as one.

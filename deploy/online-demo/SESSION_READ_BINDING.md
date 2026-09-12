# Session-bound pending reads

Tracking: #144 / #107, implementation in PR #148. This guide describes the default **read-only** mode. The separately opted-in API workflow is documented in [WORKFLOW_API.md](WORKFLOW_API.md); neither mode has completed PC/H5 browser acceptance.

`createEvaluationReadRuntime` in `scripts/product-readiness/online-demo/evaluation-read-runtime.mjs` joins the existing invitation controller, HTTPS handler and Docker slot adapter. It requires exact-source image inputs, a private recorder and externally supplied TLS material. It provisions both slots before returning the server; it never listens, builds, publishes or deploys automatically. The owner must close/dispose the runtime. A bounded absolute lifetime (default and maximum forty minutes) also disables admission and disposes its resources.

## Request path

```text
Secure host cookie + session-bound CSRF
→ existing controller resolves actor, slot, session revision and adapter-confirmed generation
→ adapter checks the current private generation, engine and exact probe
→ a retained per-generation signer issues a single-use proof
→ existing loopback-only pending-task API and ApprovalPrincipal
→ bounded typed page, followed by another session/generation check
```

Only exact `GET /api/approval/tasks/pending` is added to the HTTPS handler, and only when the controller explicitly has `SIGNED_PENDING_READ` capability. Query variants, writes and browser-supplied identity, routing or proof headers are rejected. No signing API, private key, target URL, generation or slot selector is returned to the browser. The existing not-connected mode remains the default for callers that do not supply this integration.

Role rotation, exit, expiry and operator reset cancel outstanding reads immediately. The adapter disables the old signer before replacing its generation. Every asynchronous boundary rechecks authority before returning a page. A transport that ignores cancellation retains its concurrency charge until it settles. Each slot permits at most two reads; a blocked slot does not consume the other slot's budget. Read cancellation is **not** a rollback guarantee for future writes; this increment authorizes no writes.

The landing page can read the selected role's pending tasks and renders names as text, never HTML. Its single-flight controls and absolute deadline remain; a read does not refresh the session, and role changes or uncertain responses remove prior task data. Script integrity is updated to the checked-in bytes. This is not an implementation of the existing PC/H5 approval screens.

## Existing CI integration

The permanent runtime test entrypoint additionally imports `product-readiness-online-demo-session-dispatch.test.mjs`; no existing suite is removed. The existing `online-demo-images-runtime.mjs` command already selects signed-read mode. Its slot rehearsal now retains live session readers and requires eight verified loopback HTTPS checks covering two cookie contexts, cross-context CSRF, revoked credentials, replacement and preservation of the other context. It reuses the same four stack generations and images, without another build or job.

The TLS harness creates an ephemeral certificate in a private temporary directory, verifies that certificate in its HTTPS client, and removes the temporary files. Its virtual test hostname is not a hosted URL. It checks HTTP clients, **not browser cookie-policy enforcement**. Success is separately recorded as `HTTPS_SESSION_READ_BINDING_PASSED`, with `realBrowserExecuted: false` and `seededApprovalExecuted: false`. Existing synthetic storage markers and network checks remain synthetic business evidence.

Signed-read preflight now retains only bounded stage, probe number, outcome, HTTP status and task count. It does not retain proof strings, keys, browser credentials, bodies or raw exception messages. This addresses missing diagnostics; it does not itself repair or prove a diagnosis of a failing real backend.

## Verification limits

The focused local suite executes real certificate-verified TLS, real Node HTTP subprocesses and the unchanged JDK signature verifier, but substitutes Docker and application storage. Its page tests use a controlled DOM. These checks do not establish PostgreSQL/Redis isolation, a Spring application pass, real browser acceptance, seeded approval or actual attachment/Outbox/payment cleanup. The full existing suites and new exact-source Docker run must still execute on the candidate.

Request-body-bound writes and real API/reset rehearsal code now exist in the explicit workflow mode, but actual Spring/Docker acceptance remains pending. Still missing from the product are existing PC/H5 authenticated routing and visible browser approval, verified real attachment/Outbox/signed-sandbox reset, complete egress controls and hosted operation. No public URL, production authority or real payment is enabled.

## Generation-binding integration

The reset acknowledgement has exactly `slotId`, `resetNonce`, `generation`, and `clean`. The nonce correlates the reset request; it is **not** the application generation. The generation is a fresh, nonzero, 32-character lowercase hexadecimal value returned by the Docker adapter. Missing, malformed, zero or immediately reused generations quarantine the slot. Both read and workflow transports receive this actual generation.

`businessBinding(cookie)` is an in-process gateway lookup, not an HTTP endpoint. It returns the trusted actor/tenant, slot, generation, revision and controller deadline. `expiresAt` uses the controller's monotonic clock; it is not a Unix timestamp and must not be compared with `Date.now()`. Every credential/actor rotation increments the revision while keeping the original deadline. Captured bindings are snapshots, not reusable bearer credentials: requests must revalidate the opaque session cookie, and retained bindings alone cannot authorize a replacement session.

`businessTarget(binding)` resolves only a READY, non-resetting, non-uncertain current generation to its owned backend and namespace-sharing transport. The controller and adapter retain independent checks before dispatch and after asynchronous work. Slot/container identifiers and generation never enter the public session payload. A late old-generation transport failure cannot lock a replacement session after its reset was acknowledged.

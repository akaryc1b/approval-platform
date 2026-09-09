# Session-bound workflow API — implementation, not browser acceptance

PR #148 / #144 / #107. This extends the existing private session/read implementation with an explicit `workflow: true` runtime option. Default callers remain read-only. There is no hosted URL, release or deployment. The PC/H5 applications have **not** been wired to this gateway or verified in browsers by this increment.

## Execution path

The existing controller resolves opaque HTTPS cookies to a slot, generation and canonical actor. Only selected literal approval reads, one multipart attachment upload, the existing purchase-payment form submission, and task approval are forwarded. Form submission uses the existing attachment-binding/validation service; the lower-level direct instance-start route is not exposed. No management, generic proxy or browser-selected destination is added.

Internal Ed25519 proofs bind method, complete target, actor, generation, request identity, content type, body fingerprint, idempotency key and a single-use nonce with a ten-second expiry. JSON is signed byte-for-byte. A multipart fingerprint covers the single file's canonical name, MIME type and SHA-256, allowing the Servlet container's cached parts to remain usable by the existing upload controller. Uploads are bounded to approximately one MiB and a small fixed file-type set. This validates framing/type/signature, **not malware-free content**.

Each generation reserves at most four form starts and sixteen uploads, including failed attempts. Commands are serialized per slot. Role changes cannot proceed during unresolved writes. A transport timeout or backend 5xx for a write locks further commands and role rotation until verified reset. Cancelling a client request does not claim that a committed approval was rolled back. Reset revokes the signer and replaces the owned containers/database; late responses cannot access or poison the next generation.

## Existing backend components

`OnlineEvaluationDatabaseConfiguration` delegates to the repository's existing demo migration authority, after Flowable initialization. A configuration/key guard runs before singleton initialization. The old private-read profile was missing this explicit migration activation; this is a source-derived initialization gap, **not a reproduced root-cause finding for Run 34117801071**.

Workflow wiring delegates to `PurchasePaymentDemoConfiguration` and `PurchasePaymentDemoPaymentSandboxConfiguration` for the existing scenario, Seed, organization connector, seeder, seed runner, health and signed sandbox. No business backend, schema migration, second Seed or payment implementation is created. The online workflow profile is exclusive, loopback-only and principal-authenticated. The sole configured Generic REST callback is the existing loopback sandbox. Other configured invocation/migration/AI-related capabilities remain closed; an internal Docker network is not a complete host/gateway egress policy.

## Executable API/reset rehearsal

The existing `online-demo-images-runtime.mjs run|ci` command runs the existing image and signed-read stages, then calls `executeEvaluationBusinessRehearsal`. It reuses built image IDs rather than rebuilding them, retains the existing job and forty-two-minute combined deadline, and leaves room for bounded cleanup.

The new stage creates two real slot runtimes and two certificate-verified HTTPS clients with separate cookies. Its production execution path uploads distinct attachments, starts through the real form API, checks cross-session instance/attachment/task denial, completes A through the canonical actors, observes real HTTP-503 Outbox rows, publishes an exact event allowlist through the existing recovery file and checks the signed sandbox ledger. It resets A, verifies old credentials and business IDs are unavailable, checks B's records and resources are unchanged, then completes B and verifies its controlled-clock expiry reset. Both stacks and temporary TLS files must be cleaned before success.

`evaluation-payment-evidence.mjs` issues only fixed read-only SQL against the actual `ap_approval_instance`, `ap_approval_attachment` and `ap_outbox` tables. Stored attachment bytes are hashed inside PostgreSQL and compared with metadata. It reuses `createExactEventAllowlist` and `verifyExactAcceptedPayments`. No table is directly mutated to advance or reset a workflow. Reset checks distinguish random session-created IDs from fixed Seed IDs that may legitimately reappear.

The operator's payment recovery API is private, not a browser-selected slot operation. The landing page remains the existing read UI; workflow-mode PC/H5 navigation and in-page recovery controls remain outstanding. The API-stage verdict `TWO_SESSION_REAL_BUSINESS_API_RESET_PASSED` must never be described as PC/H5 browser acceptance.

## Verification recorded for the local patch

Executed together: 78 Node tests, no failures/skips, covering real JDK compilation and Node-to-Java signatures, verified TLS/HTTP, request policies, write fencing, multipart parsing, evidence rejection and rehearsal control flow. **Docker, Spring application storage and payment side effects were substituted in these local tests.** The new five JUnit Servlet-chain cases were added but not executed. Full Maven/Checkstyle, actual PostgreSQL/Docker business rehearsal, existing full test aggregate, security scanner, PC/H5 builds and browser acceptance have not been run for this patch.

The executable real-runtime path is present in source, not accepted by those local substitutions. Required remaining work includes successful real compilation/runtime verification; existing PC/H5 authenticated transport, routing, page integration and two-browser acceptance; full outbound/abuse and operator/crash recovery verification; and eventual hosted-environment inputs. Do not merge, publish, deploy or close #144 on this patch's local test count.

## Merged environment binding

This workflow uses the same adapter-confirmed `generation` and rotating session revision as the default read mode; it does not translate the reset nonce into a generation. The uploaded binding changes and earlier API proposal have been reconciled, including their tests. A successful unit/control-flow result is not evidence that the Spring application, real Docker business rehearsal, or existing PC/H5 browser journey has passed. The existing browser front ends are not modified by this integration.

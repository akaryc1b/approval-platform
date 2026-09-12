# Session-bound workflow API — implementation, not browser acceptance

PR #148 / #144 / #107. The explicit `workflow: true` runtime option selects the
purchase-payment API. Default callers remain read-only. There is no hosted URL,
release or deployment. PC/H5 request adapters and the H5 form attachment path
are implemented, but application bootstrap, routing, image activation and the
complete two-browser journey remain unaccepted.

## Session and backend boundary

The existing controller resolves opaque HTTPS cookies to the actual adapter-
confirmed generation, slot, canonical actor and rotating session revision.
The reset nonce only correlates the acknowledgement. Only selected literal
approval reads, one multipart attachment upload, the existing purchase-payment
form submission, and task approval are forwarded. Form submission reuses the
existing attachment-binding/validation service; direct instance start,
management and browser-selected proxy destinations remain closed.

Internal Ed25519 proofs bind method, complete target, actor, generation, request
identity, content type, body fingerprint, idempotency key and a single-use nonce
with a ten-second expiry. JSON is signed byte-for-byte. Multipart fingerprints
cover the single file name, MIME type and SHA-256. Framing and type validation
are not a malware-free-content guarantee.

Each generation reserves at most four form starts and sixteen uploads, including
failed attempts. Writes are serialized per slot; unresolved writes prevent role
rotation. Transport timeout or backend 5xx locks subsequent writes until verified
reset. Cancelling an HTTP request does not roll back a committed approval.
Reset revokes signers and replaces owned resources; late old-generation results
cannot affect the replacement session.

`OnlineEvaluationDatabaseConfiguration` delegates to the existing demo migration
authority after Flowable initialization. This addressed a source-derived gap,
not a reproduced diagnosis of Run 34117801071. Workflow configuration delegates
to the existing scenario, Seed, organization connector, seeder and signed payment
sandbox. It adds no parallel backend, schema migration or payment implementation.
The exclusive online-demo profile stays loopback-only and principal-authenticated.
The sole callback is the existing loopback sandbox. Internal Docker networks
alone are not a complete host/gateway egress policy.

## Existing PC/H5 request adapters

`approvalFetch` and `mobileApprovalRequest` select the session transport only with
`VITE_APPROVAL_ONLINE_EVALUATION=true`. Simultaneous local-header mode is rejected.
Normal builds retain their prior transport, including Mini Program `uni.request`.
The image build does **not** enable the new flag yet.

The client first checks `/evaluation/session`, requires workflow capability and
uses same-origin cookies plus the current CSRF proof. It checks the same actor,
proof and bounded monotonic deadline before and after buffered business responses.
An old page cannot silently adopt a new role/session. Queued work does not extend
expiry; reads are bounded/serialized, duplicate queued writes are refused, and
uncertain mutations are never automatically retried. No backend address, slot,
private key or browser-supplied identity header is exposed. Rendering metadata
comes from the canonical demonstration scenario, not production identity settings.
Both client copies of `evaluation-session.ts` must stay byte-identical.

PC multipart uploads use the same `approvalFetch` path. The existing H5 form
button now selects native browser `File` objects in evaluation mode and delegates
to the existing attachment API through the same authenticated transport. Arbitrary
URLs and Uni temporary-path strings are rejected rather than fetched. The picker
cleans up after selection, cancellation, page exit or its bounded timeout.
The normal-mode `uni.chooseMessageFile`/`uni.uploadFile` path is retained.

Evaluation uploads accept at most 1020 KiB per file, fixed PDF/PNG/JPEG/TXT type
and extension pairs, and bounded ASCII filenames. Binary downloads use the same
session transport. The existing download API returns a temporary object URL with
page-exit/time-based cleanup; downstream preview/open-document UX has not been
validated in H5 browsers.

The landing page now accepts a workflow-capable session and exposes its existing
role/end controls without pretending to be the approval application. It explicitly
states that PC/H5 page entry is not yet supplied. It does not invent links, add
approval/payment controls or dispatch the read-only pending probe in workflow
mode. Static script integrity is updated together with the source; CSP is unchanged.

## Executable API/reset rehearsal

`online-demo-images-runtime.mjs run|ci` retains image and signed-read stages, then
calls the business rehearsal with the same built image IDs and bounded cleanup.
Its real execution path uses two runtimes and separate certificate-verified HTTPS
clients: upload attachments, submit forms, deny cross-session IDs, approve the
canonical stages, observe Outbox HTTP-503 pending state, publish exact event
allowlists and verify the existing signed sandbox ledger. It resets A, checks old
credentials and session-created IDs, preserves B, then completes B and checks
controlled-clock expiry. Both stacks and TLS files must be cleaned before success.

Evidence uses fixed read-only SQL against actual approval instance, attachment
and Outbox tables, verifies stored attachment hashes, and reuses exact-allowlist
and accepted-payment validators. No direct table mutation advances an approval.
Fixed Seed IDs are distinguished from random session-created IDs. The recovery
operator API is private. `TWO_SESSION_REAL_BUSINESS_API_RESET_PASSED`, if produced
by an actual run, is API evidence, not Playwright/PC/H5 acceptance.

## CI corrections and build reuse

Run 34340094504 stopped its image job at a stale signer-lifecycle assertion before
Docker execution. The test now checks diagnostic collection, conditional signer
retention, pre-reset revocation and close-time disposal. Signature and permission
checks are not removed or skipped.

The same run's Vben artifact shows sequential Quick Start, browser, payment and
capacity/recovery work exhausting the job budget during upgrade-baseline Maven
compilation. The payment CLI's CI branch now scopes the existing exact-build reuse
setting around **both** clean-data executions. Clean checkout SHA, tree, revision,
classes and marker checks still decide whether the backend build can be reused;
a miss still builds normally. Explicit `false` stays respected and the prior
environment is restored on success or failure. Data resets, browser operations,
the second clean run and cleanup are retained. Quick Start cold-start behavior,
workflow definitions, job timeouts and check selection are unchanged. Reduced
end-to-end runtime is expected but has not yet been measured for this candidate.

## Verification and remaining acceptance

The current selected-source validation ran **106 tests: 35 signed-read/JDK tests
and 71 client/file/landing/build-reuse tests, all passing without skips**. Actual
TLS requests exercise the existing controller and gateway and native multipart
bytes. Application dispatch, Docker, database and payment state are substituted.
Picker/landing checks use a controlled DOM, not a launched browser. Isolated
strict TypeScript checks use actual changed modules with surrounding API type
declarations and no `DOM.Iterable`; they are not full Vben/UniApp or Vue-SFC compilation.

The earlier 78 API tests and 204 integration tests are historical local records,
not new-candidate full-runtime results. Complete Maven/security checks, image
startup/business reset rehearsal, full PC/H5 builds, application bootstrap/static
routing and two real browser contexts must still pass. Required business proof
covers new attachments, purchase, every approval, Outbox/sandbox recovery and
reset/expiry of one evaluator while the other remains usable. Retain redacted
screenshots, traces and actual business evidence. Do not merge, deploy or close
#144 based on local unit-test counts. Outbound/abuse controls, operator/crash
recovery and hosted-environment inputs also remain outstanding.

Run 34428524151 passed the image contract stage but exposed TS2488 in UniApp's
full type check: its DOM library does not declare Headers/FormData iteration.
Both client copies now use the native `forEach` methods instead of iterable loops.
A regression test removes those iterator methods and exercises real multipart
cloning/header validation. No application tsconfig or dependency was relaxed.

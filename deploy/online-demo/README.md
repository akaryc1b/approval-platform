# Online evaluation — images, invitations and private reset stacks

Tracking: [#144](https://github.com/akaryc1b/approval-platform/issues/144), parent [#107](https://github.com/akaryc1b/approval-platform/issues/107), implementation [PR #148](https://github.com/akaryc1b/approval-platform/pull/148).

There is **no published evaluation URL or runnable hosted deployment**. Backend/PC/H5 packaging and startup checks, invitation/session controls and fixed HTTPS landing assets exist. A real independently disposable backend/database/cache slot adapter and its rehearsal are now implemented; trusted browser-to-business identity is still **not connected**. The page/API continue reporting `businessAccess: NOT_CONNECTED`. Selecting a role grants no platform principal or permission.

## Commands

Use a clean committed checkout and the repository-supported Node runtime. Real image/startup/reset work requires a local Linux Docker engine:

```bash
node scripts/product-readiness/online-demo-images.mjs plan --json
node scripts/product-readiness/online-demo-images.mjs build
node scripts/product-readiness/online-demo-images-runtime.mjs run
node --test scripts/tests/product-readiness-online-demo-images.test.mjs scripts/tests/product-readiness-online-demo-runtime.test.mjs
node --test scripts/tests/product-readiness-online-demo-sessions.test.mjs
node --test scripts/tests/product-readiness-online-demo-slots.test.mjs
```

`plan` is read-only. `build` packages backend, PC and H5 from one source archive without startup or publication. The runtime `run` command performs image startup/cleanup, then reuses those inspected images for the two-slot reset rehearsal. `ci` uses the existing event-identity and changed-path checks; documentation-only changes skip expensive execution and malformed events fail. No command publishes images, deploys, changes DNS or creates a Release.

## Image source and startup boundaries

Build pins are in `images/base-images.json`; temporary PostgreSQL 16/Redis 7 pins are in `images/runtime-images.json`. Both record official metadata provenance. Approved repositories and explicit nonzero SHA-256 references are required, with no floating fallback. Packaging accepts Linux amd64/arm64, but startup/reset targets **Linux amd64 only**. A parsed architecture or pin is not build, scan or runtime acceptance.

Builds share the exact committed `git archive`. Tracked changes, links, submodules and unsafe/generated paths are rejected; untracked credentials, dependency output and Git configuration are excluded. A narrow build environment excludes application/frontend secrets and host Java/Node injection variables. Docker daemon and registry settings remain operator-controlled during builds; this is not secret scanning or build-network isolation. The new reset runner specifically uses the local rootful Docker socket and rechecks image/engine identity.

The backend uses the existing Maven profile and explicit Boot repackage, requiring the launcher, dependencies and canonical demo resources. The runtime contains Java 21/JAR, checksum and licensing notices, not Maven/source tooling. Clients reuse pinned upstreams and overlays. Root dependency installation is unnecessary for the image helpers; PC retains a frozen workspace install. **H5 remains non-frozen**, with its resolved lock retained outside the public directory. Repeat-build reproducibility and image vulnerability scans remain unverified.

Static assets are bounded and inventoried, reject hidden files, links, maps and secret/backup formats, and are digest-checked during staging. Metadata/locks/licenses remain outside `/app/public`. Non-root static servers deny business, management, payment and unsupported-method routes; missing assets never fall back to HTML. Local-demo identity is disabled in builds; no-index/no-store/nosniff/frame-denial headers remain.

The image smoke creates a labelled internal Docker network without host ports or host mounts, disposable PostgreSQL/Redis, and resource-bounded non-root/read-only application/probe containers. Backend stays loopback-only, principal identity remains enabled and Connector/dispatch stay off. Checks cover health/JAR checksum, static source/lock inventories, representative served HTML/JS/CSS bytes, MIME types and denied routes. They are not browser approval acceptance. Local image IDs are not registry manifest digests.

Image receipts remain under `.runtime/online-demo-images/` and `.runtime/online-demo-image-runtime/`, retained by the existing permanent image job. Cleanup verifies exact ownership and resource absence, tries all owned resources, and never broadly prunes or deletes operator images. Build, startup, probe or cleanup failure cannot be a success. Partial/cancelled evidence is not acceptance, and killed runners cannot guarantee cleanup.

## Invitation/session controls and HTTPS

`evaluation-sessions.mjs` is the existing single-process controller. It derives non-administrative actors from the canonical purchase-payment scenario rather than introducing a second directory or Seed. It accepts one or two distinct slots and requires a trusted reset adapter; all slots start quarantined, including after restart. There is no success-by-default adapter.

Invitations are random 256-bit, single-use bearer credentials, default five-minute and maximum fifteen-minute lifetime, with sixteen outstanding entries and thirty redemption attempts/minute globally. Lookup retains digests, not bearer strings. A full pool does not consume an invitation. Sessions have an absolute thirty-minute maximum lifetime; permitted role changes rotate session/CSRF tokens without extending expiry. Browser status never returns slot IDs, backend addresses or tenant credentials.

Exit, expiry and trusted operator reset revoke access before reset begins. Exact nonce/slot acknowledgement is mandatory; failed, unconfirmed, late or timed-out reset cannot reopen a slot. Clock failure or disable revokes credentials. Adapters ignoring cancellation remain locked until settled; automatic failure has no unbounded retry loop. Snapshot diagnostics exclude bearer material and adapter errors. Configure the Docker adapter with the documented sixty-second controller reset bound; details and limitations are in [SLOT_RESET.md](SLOT_RESET.md).

The HTTPS factory requires external TLS material, an exact origin and lifecycle ownership. It does not listen automatically. Listening starts expiry sweeping; close disables admission. TLS must terminate at the handler; forwarded headers cannot authenticate plain HTTP. Host/Origin, CSRF, duplicate headers/cookies, JSON shape and request limits are enforced. The Secure/HttpOnly/SameSite=Strict `__Host-approval-evaluation` cookie has Path=/ and no Domain; rotation invalidates the old token and accepted exit clears it even if reset fails.

Only the Chinese `/evaluation` page, two exact fixed asset routes and session-control JSON routes are served. HTML and asset hashes are checked together before serving; scripts/styles use SRI and a restrictive CSP. The page uses text-only DOM updates, absolute countdown, single-flight operations and explicit read-only recovery after uncertain requests. Invitations never enter URLs or browser storage. There is no approval API proxy, public invitation issuer or public operator-reset route. Client-supplied tenant/operator/permission/Authorization headers are rejected.

TLS 1.2+, five-second handshake/header bounds, 8 KiB/24-field headers, 512-byte/two-second JSON bodies, sixteen connections, eight in-flight handlers and 120 requests/minute globally are configured limits, not measured hosted capacity. Responses remain no-store/no-index/no-referrer/nosniff/frame-denied.

## Real private-slot reset increment

[SLOT_RESET.md](SLOT_RESET.md) specifies the adapter, ownership/failure boundaries and exact rehearsal. Each slot gets separate PostgreSQL, Redis, backend, credentials and internal network. Replacement destroys only the selected generation, then requires fresh health and container checks before acknowledgement. Uncertain Docker mutations stay quarantined rather than being automatically retried.

The real rehearsal couples the existing session controller to those stacks, tests separate database/cache/tmpfs markers, four same-slot and four cross-slot TCP connections, end-session replacement and controlled-clock expiry replacement. The other slot must retain its resource identities and markers. Both stacks must be removed at the end. The marker table is not a platform/Flowable table, and the file marker is not an approval attachment. No business state is advanced by SQL.

Two new JSON receipts are retained in the existing runtime directory/artifact. `PRIVATE_TWO_SLOT_RESET_REHEARSAL_PASSED` requires probes and cleanup; the earlier `LOCAL_IMAGE_STARTUP_SMOKE_PASSED` alone does not prove reset. No extra workflow/job/build is added. Docker host/gateway access, crash-orphan recovery and adversarial host isolation are explicitly outside the internal-network claim.

## Verification and remaining delivery

**Docker is substituted in these unit tests** for adapter/orchestration cases; the separate CI runtime command uses real Docker. Unit coverage includes the actual session controller, ownership checks, credentials/expiry/failure fencing, simulated storage/network operations, real child-process timeout/abort behavior, TLS requests and DOM/transport fixtures. The previous runtime cases remain preserved in `product-readiness-online-demo-runtime-cases.mjs`; the same entrypoint imports them, the session/page suite and the new slot suite. No test-double pass establishes real Docker or browser results.

Still required: trusted business identity and dedicated online-demo configuration; two seeded visible PC/H5 scenarios; cross-evaluator business denial; real attachment/Outbox/signed-payment-sandbox reset; complete outbound limits; operator recovery/runbook; image reproducibility/scans; browser and measured hosted-capacity acceptance. Hosting/DNS/TLS/registry credentials must come from the deployment environment, never the repository. No public URL, real payment, customer data, production support or Release is authorized by these increments.

# Online evaluation — images and invitation/session control

Tracking: [#144](https://github.com/akaryc1b/approval-platform/issues/144), parent [#107](https://github.com/akaryc1b/approval-platform/issues/107), implementation [PR #148](https://github.com/akaryc1b/approval-platform/pull/148).

There is **no published evaluation URL or runnable hosted deployment**. Backend/PC/H5 image packaging and isolated startup checks exist. The separate invitation/session controller and HTTPS landing-page factory now exist, but are **not connected to a real disposable application-slot adapter or the approval APIs**. A session response explicitly reports `businessAccess: NOT_CONNECTED`; role selection does not create a platform principal or authorize business actions.

## Commands and existing image path

Use a clean committed checkout, the repository's supported Node version, and a Linux Docker engine for real image work:

```bash
node scripts/product-readiness/online-demo-images.mjs plan --json
node scripts/product-readiness/online-demo-images.mjs build
node scripts/product-readiness/online-demo-images-runtime.mjs run
node --test scripts/tests/product-readiness-online-demo-images.test.mjs scripts/tests/product-readiness-online-demo-runtime.test.mjs
node --test scripts/tests/product-readiness-online-demo-sessions.test.mjs
```

`plan` is read-only. `build` packages backend, PC, then H5 serially from the same exact Git archive; it does not start containers or publish images. `run` builds and starts disposable infrastructure/application containers, probes them and removes its owned resources. `ci` on the runtime entrypoint checks event identity and scopes the expensive work; documentation-only changes skip image execution, malformed events fail closed. No command publishes images, deploys, changes DNS or creates a Release.

The four build bases in `images/base-images.json` and PostgreSQL 16/Redis 7 runtime bases in `images/runtime-images.json` use official-source, digest-pinned references. Overrides require approved repositories and nonzero SHA-256 pins; there is no floating fallback. Packaging accepts `linux/amd64` and `linux/arm64`, but startup checks currently target **linux/amd64 only**. A parsed platform or pin is not a successful pull, vulnerability scan or runtime result.

Source commit/tree, version, timestamp, upstream commits and archive digest are retained. The builder rejects tracked changes, links, submodules and unsafe/generated tracked paths. Untracked credentials/build output and Git configuration are excluded from the archive. A narrow command environment excludes application/frontend credentials and host `NODE_OPTIONS`/`JAVA_TOOL_OPTIONS`; Docker daemon/registry settings remain operator-controlled. This is not build-network isolation or secret scanning.

Backend packaging reuses the existing Maven profile, explicitly repackages the Boot JAR, checks launcher/dependencies/canonical demo resources, and retains its checksum. Runtime is Java 21/JAR, not Maven/source tooling. PC/H5 reuse their pinned upstreams and overlays, bootstrapping each workspace once. The unnecessary root dependency install is absent; PC keeps its frozen workspace install. **H5 is still non-frozen**, with its resolved lock retained outside the public directory. Repeat-build reproducibility and image vulnerability scans remain pending.

Static assets are bounded, inventoried, digest-checked while staging, and reject links, hidden files, source maps and secret/backup formats. Inventories, resolved locks and licensing notices are outside `/app/public`. Client runtimes are non-root, static-only, and deny business/management/payment routes, unsupported methods and missing assets without HTML fallback. They set no-index/no-store/nosniff/frame-denial headers and disable local demo identity at build time.

## Real image startup, evidence and cleanup

The existing startup checker creates an ownership-labelled internal Docker network without published host ports or host mounts. PostgreSQL uses a generated temporary password and tmpfs data; Redis persistence is off. Application/probe containers are resource-bounded, non-root, read-only, capability-dropped and no-new-privileges. Backend retains loopback binding; the probe shares its network namespace. The fresh-test database permits Flowable schema initialization, while principal identity remains enabled and Connector/dispatch stay off.

Checks cover backend health/JAR checksum/management denials and packaged PC/H5 inventory/source/lock identity, representative served HTML/JS/CSS bytes, MIME types, headers and rejection routes. They do **not** establish seeded browser approval or evaluator data isolation. A local Docker image ID is not a registry manifest digest.

Receipts stay untracked in `.runtime/online-demo-images/` and `.runtime/online-demo-image-runtime/`. The existing permanent workflow's `online-images` job retains `approval-online-images-<run-id>` logs/JSON on failure. Its 38-minute execution budget plus cleanup fits the existing 45-minute job. Missing Docker, failed build/startup/probes or failed cleanup cannot become a pass. Cleanup attempts every exact owned container/network independently, validates ownership and absence, and never broadly prunes or deletes operator images. Forced runner termination can prevent cleanup; partial evidence never establishes success. Temporary credentials are excluded/redacted from bounded diagnostics.

## Invitation/session controller

`evaluation-sessions.mjs` is a single-process control-plane component. It reuses actors from `config/demo/purchase-payment-golden-path.json`: initiator plus expected workflow actors, excluding the seed administrator and unknown/administrative actors. It does not fork the business backend, Seed, schema or existing identity authority.

The constructor requires a trusted `resetSlot` adapter and one or two distinct slot IDs. **There is no success-by-default adapter.** All slots start `QUARANTINED`, including after a restart. The state path is:

```text
QUARANTINED → RESETTING → READY → ACTIVE
                              ↑       │
                              └ RESETTING ← credential revocation
Any failed/unconfirmed/timed-out reset → QUARANTINED
```

Only trusted in-process operator code may issue/revoke invitations or reset a named slot. These methods are not exposed as public administrator routes. Invitations are random 256-bit, single-use bearer values with default five-minute TTL, maximum fifteen minutes and at most sixteen outstanding entries. Redemption has a global thirty-attempt/minute budget. Invitation/session lookup stores SHA-256 digests rather than bearer strings. Issue results are secrets for private delivery and must never be logged or committed.

At most two sessions are active, each exclusively assigned to a ready slot. A valid invitation is not consumed when the pool is full. The default absolute session TTL is thirty minutes and cannot exceed thirty minutes. Role changes are restricted to the canonical business actors, rotate the session/CSRF credentials, invalidate the previous token and do not extend TTL. Browser responses contain no slot ID, backend endpoint, tenant credential or platform permission.

Exit, expiry and operator reset revoke credentials **before** calling the adapter. Every reset binds `slotId`, a fresh `resetNonce`, and an abort signal. A successful adapter must return exactly `{ slotId, resetNonce, clean: true }` for that invocation. The default bound is thirty seconds, configurable up to sixty seconds. Wrong/missing/extra acknowledgement fields, exceptions or deadlines quarantine the slot. Elapsed time is checked after adapter completion too, so event-loop blocking cannot bypass the timer.

A timed-out adapter which ignores abort keeps the slot locked until its promise settles; its late success cannot mark the slot ready. Explicit operator retry is possible only after settlement. Automatic reset failure is not retried in an unbounded loop. Monotonic-clock failure or disabling the controller revokes all credentials and prevents new admission. Snapshots expose counts/states/failure counts, never bearer material or adapter error text.

**An adapter acknowledgement is not independent cleanup evidence.** The real adapter must still prove per-slot PostgreSQL/Redis/attachment/Outbox/payment-sandbox isolation and cleanup before this can be an online evaluation service. The current tests use disposable filesystem and delayed/failing callback fixtures, not that missing database adapter. Do not replace it with a no-op acknowledgement.

## HTTPS control entrypoint and landing page

`evaluation-http.mjs` exports a request handler and `createEvaluationHttpsServer`. The factory requires externally supplied TLS material, an exact HTTPS origin and the controller; it does not listen automatically. On listening it starts the bounded expiry worker; closing disables admission and stops that worker. Only trusted deployment code may own this lifecycle. This increment intentionally supplies no standalone launch script, container gateway or anonymous invitation-issuer endpoint.

The visible Chinese page at `/evaluation` includes invitation entry, permitted role selection, remaining-session information and an end/reset control, with an explicit not-connected business notice. The only JSON routes are `GET /evaluation/session` and the three POST routes for invitation redemption, actor change and session end. Approval API, management and payment paths remain closed.

TLS must terminate at this handler; forwarded headers cannot make plain HTTP trusted. POST requires the exact Origin and JSON, while role change/end also require session-bound CSRF proof. Client tenant/operator/permission/Authorization headers, duplicate security headers/cookies, extra JSON fields, oversized/compressed bodies and cross-site requests are rejected. The cookie is `__Host-approval-evaluation`, Secure, HttpOnly, SameSite=Strict, Path=/, with no Domain; it is rotated on role change and cleared on accepted exit even when reset fails. TLS supports 1.2 or newer, with a five-second handshake bound; headers are capped at 8 KiB/24 fields, body at 512 bytes/two seconds, connections at sixteen, in-flight handlers at eight and requests at 120/minute globally. These are configured bounds, not measured hosted capacity.

Responses are no-store/no-index with nosniff, frame denial, no-referrer and a hash-based CSP without unsafe-inline/eval. Invitations are never put in URLs or browser storage. The page's script uses text-only DOM updates for response labels. No proxy request to the business backend is implemented and no browser-supplied role becomes a business principal.

## Verification and next integration

**Docker is substituted in these unit tests** for the orchestration cases; the separate real image CI command is not substituted. The existing runtime cases remain byte-identical in `product-readiness-online-demo-runtime-cases.mjs`; the original test entrypoint imports those cases and the invitation/session tests, so the same permanent image job runs both. No workflow change or new automatic workflow is needed for this increment.

Session tests exercise real certificate-verified loopback HTTPS, one-use/concurrent redemption, token rotation, CSRF/cross-session denial, expiry, failed/late/blocked resets, request bounds and shutdown. Certificates are generated in private temporary test directories and deleted. Filesystem reset fixtures are not PostgreSQL isolation proof, HTTP assertions are not browser visual acceptance, and no hosted TLS endpoint is claimed.

Next wire the controller to two genuinely independent disposable application stacks and the existing trusted business identity boundary, then verify seeded PC/H5 approval, cross-evaluator access denial, expiry/operator reset including attachments and Outbox, allowlisted egress, hosting capacity and operational disable/recovery. Keep the online-demo profile, image reproducibility/scans and actual browser acceptance outstanding until tested. Hosting/DNS/TLS/registry credentials must be injected by the chosen environment, never committed. No public URL, real payment, customer data, production support or Release is authorized here.

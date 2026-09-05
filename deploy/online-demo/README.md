# Online evaluation images — packaging and isolated startup

Tracking: [#144](https://github.com/akaryc1b/approval-platform/issues/144), parent [#107](https://github.com/akaryc1b/approval-platform/issues/107).

This directory packages the existing backend, Vben PC and UniApp H5 applications and provides a real Docker startup check. It is **not an online deployment**. There is no published URL, registry push, hosting automation, HTTPS gateway, invitation/session isolation, automatic evaluator reset or online-demo security profile in this slice.

## Entrypoints

From a clean committed checkout with Git, Node 22.18+ or 24, and a Linux Docker engine:

```bash
node scripts/product-readiness/online-demo-images.mjs plan --json
node scripts/product-readiness/online-demo-images.mjs build
node scripts/product-readiness/online-demo-images-runtime.mjs run
node --test scripts/tests/product-readiness-online-demo-images.test.mjs scripts/tests/product-readiness-online-demo-runtime.test.mjs
```

`plan` is read-only: it resolves the committed source and base-image pins without creating output or contacting Docker. `build` builds backend, PC, then H5 from one source archive, without starting or publishing containers. `run` performs those same real builds and then starts disposable infrastructure and application containers, checks their HTTP behavior, and cleans its owned resources. Missing Docker or failed builds are errors, not runtime skips.

Packaging defaults to `linux/amd64`; `--platform linux/arm64` is accepted by the packaging entrypoint only. The startup check is currently bounded to **linux/amd64**. Neither parsing an architecture nor a successful unit test establishes a successful build on that architecture.

The runtime check is also available as `node scripts/product-readiness/online-demo-images-runtime.mjs ci`. In GitHub Actions it validates the exact PR head or main push checkout against the immutable event and selects relevant packaging, application, dependency and workflow changes. Pure documentation changes skip the expensive build; malformed events, missing ancestry and stale checkouts fail rather than silently skipping. Manual workflow dispatch selects the check explicitly. Outside CI, use the explicit `run` command.

## Source and dependency boundaries

All four build bases are pinned in `images/base-images.json`. `images/runtime-images.json` separately pins PostgreSQL 16 and Redis 7 for the temporary startup check. Both manifests record the official `docker-library/repo-info` source commit and metadata paths. Every reference includes `@sha256:`; tags are descriptive, with no floating fallback. Packaging overrides require approved repositories and full nonzero SHA-256 digests. Source-verified pins are not vulnerability-scan results.

The builder rejects tracked working-tree changes, records the exact commit/tree, application revision, timestamp and pinned upstream commits, and feeds identical `git archive` bytes to all three builds. Untracked local credentials, dependency folders, build output and Git configuration are excluded. Tracked links, submodules and unsafe/generated paths are rejected. A committed secret is still prohibited: a source archive is not a secret scanner.

Only fixed build arguments and a narrow tool environment are passed. Host frontend variables, application credentials, Git tokens, proxy credentials, `NODE_OPTIONS` and `JAVA_TOOL_OPTIONS` are not build inputs. Operator Docker registry configuration and daemon networking remain outside this boundary. Build output may contain dependency diagnostics; handle it as operator evidence.

Backend packaging uses the existing `product-readiness-demo` Maven profile, installs the reactor, and explicitly runs `package spring-boot:repackage`. JAR-layout checks require the Boot launcher, dependencies and both canonical demo resources. The final image contains JRE/JAR, a checksum sidecar and licensing notices, not Maven/source/test tooling.

Clients reuse existing pinned upstreams and overlays. Each workspace is bootstrapped once before direct install/build, avoiding a later bootstrap resetting the resolved dependency state. Root and PC installs use frozen locks. **H5 retains the existing non-frozen install**; its resolved lock is preserved, but frozen resolution and repeat-build reproducibility are still outstanding. Normalized timestamps and immutable source do not imply identical image bytes.

Public static inventories are bounded and reject hidden files, links, source maps and secret/backup formats. Each file is checked while staging. The inventory and resolved lock live under `/opt/approval/`, outside `/app/public`; root and upstream licensing notices are retained. The input-derived image tag identifies build inputs, not a registry manifest digest or reproducibility guarantee.

## Real isolated startup check

The checker builds all three images, verifies source/archive labels, platform and declared runtime users, and uses immutable inspected image IDs to create containers. PostgreSQL, Redis and the Node HTTP probe are pulled using digest pins and their inspected IDs are recorded. It creates one randomly named, ownership-labelled **internal Docker network**, with no published host ports or host-directory mounts.

PostgreSQL uses a generated in-memory test password and a tmpfs data directory; Redis persistence is disabled and its data directory is tmpfs. These are disposable startup-test resources, not a hosted isolation or data-retention model. TCP PostgreSQL readiness avoids mistaking its temporary initialization-only Unix socket server for final readiness.

Backend, PC, H5 and probe containers have resource bounds, read-only root filesystems, dropped capabilities, no-new-privileges and bounded `/tmp`. Application users remain `10001:10001` for backend and `101:101` for clients. The backend keeps `SERVER_ADDRESS=127.0.0.1`. A short-lived Node probe shares its network namespace instead of widening its listener or publishing ports.

The fresh test database explicitly allows Flowable schema initialization. The test does not activate the local identity profile: it retains principal identity mode, disables the generic Connector/dispatcher and exposes only health through Actuator. It checks backend `UP`, the packaged JAR checksum, and rejection of management environment/metrics routes. **It does not run seeded approval actions or establish online authorization.**

For each real PC/H5 image, the checker verifies the embedded inventory's source identity, inventory digest, sizes and lock digest. Actual HTTP responses for `index.html`, one JavaScript file and one CSS file must match their inventory bytes/digests and MIME types. It also checks the static server's no-index/no-store/nosniff/frame-denial headers, API/management/payment/hidden/lock/map/missing-asset rejection, and unsupported-method rejection. These are packaged-static startup checks, not browser execution, screenshot acceptance or a complete business E2E.

The overall execution budget is 38 minutes, including builds; startup polling, commands and response bodies also have bounds. Cleanup has separate bounded commands. It attempts every recorded container independently, checks exact names and ownership labels before deletion, removes only owned container IDs/network, and verifies absence. It never runs a broad prune or deletes earlier local images. An uncertain create result is recorded before the command so cleanup can still locate the owned resource.

## Receipts, diagnostics and failure handling

Packaging receipts remain under `.runtime/online-demo-images/build-*/image-build.json`. A packaging-only success is `LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED`. A failed build records `FAILED`, stops subsequent targets and retains partial-image information. `localImageId` is a Docker image ID, **not** a registry manifest digest; `registryDigest` stays null because nothing is pushed.

Runtime receipts, resource scope, static inventories and bounded failure diagnostics are written under `.runtime/online-demo-image-runtime/run-*/`. A runtime success is `LOCAL_IMAGE_STARTUP_SMOKE_PASSED` **only when all checks and cleanup pass**. Startup failure, invalid bytes, timeout or cleanup failure cannot publish that status. Generated database credentials are excluded from receipts and redacted from diagnostic tails; full container environments are not retained.

A terminated CI runner or forced process kill cannot guarantee that JavaScript `finally` finishes. Partial `RUNNING` evidence is never acceptance; owned-resource scope is retained when possible, and disposable hosted-runner teardown is the final containment boundary. The checker does not claim a successful cleanup for a cancelled or incomplete run.

## Existing workflow integration and verification scope

The existing `.github/workflows/approval-platform-validation.yml` has one dedicated `online-images` job with a 45-minute limit, read-only repository permissions and no persisted checkout credentials. It executes **both complete image test suites**, then the path-scoped real build/startup command, and retains JSON receipts plus logs even on failure. No second automatic workflow, registry authentication, image push or deployment was introduced.

Image tests moved out of the general repository-hygiene aggregate into this dedicated job. They were not removed: changing that aggregate previously selected unrelated Quick Start, browser, golden-path and capacity runtimes. The first packaging candidate's Vben job exhausted its 45-minute budget; it was not accepted as green. Existing product-runtime selection rules and their tests are otherwise unchanged.

**Docker is substituted in these unit tests**. Tests exercise real temporary Git histories/archives, JAR-layout fixtures, actual loopback HTTP probes, and simulated Docker build/start/inspection/failure/cleanup paths. Where installed, a real non-root Nginx fixture also verifies static routes and headers; an absent Nginx binary produces an explicit fixture-test skip. Neither test doubles nor fixture servers establish actual application-image success.

Real image acceptance requires a successful exact-candidate `online-images` run and inspection of its build/runtime receipts and cleanup. Adding this checker is not itself that evidence. Vulnerability scanning, H5 frozen dependency resolution, repeat-build comparison, browser business E2E, evaluator isolation/reset and hosted capacity remain unverified. Raw runtime files remain untracked.

## Next within #144

Implement the dedicated fail-closed online-demo configuration, trusted invitation/session identity, two isolated evaluator lifecycles, expiry/operator reset, attachment and outbound-traffic restrictions, HTTPS gateway and visible PC/H5 E2E. Reuse the existing business backend, Seed, identity authorities and signed payment sandbox. Hosting, DNS/TLS, registry and deployment credentials must come from the chosen environment, never from committed files. Do not publish a public URL or treat startup-only images as a completed evaluation service.

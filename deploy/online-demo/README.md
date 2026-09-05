# Online evaluation images — packaging slice

Tracking: [#144](https://github.com/akaryc1b/approval-platform/issues/144), parent [#107](https://github.com/akaryc1b/approval-platform/issues/107).

This directory starts the online evaluation sandbox with three build targets: the existing executable backend, the existing Vben PC application, and the existing UniApp H5 application. It is **not a runnable online deployment**. There is no published URL, registry push, hosting automation, gateway, session isolation, automatic reset or online-demo security profile in this slice.

## Build entrypoint

From a clean committed checkout with Git, Node 22.18+ or 24, and a Docker builder:

```bash
node scripts/product-readiness/online-demo-images.mjs plan --json
node scripts/product-readiness/online-demo-images.mjs build
node --test scripts/tests/product-readiness-online-demo-images.test.mjs
```

`plan` reads the pinned commit and prints JSON without creating files, fetching dependencies, changing refs or contacting Docker. `build` consumes the same source-controlled base pins and builds backend, PC, then H5 serially. It does not start containers, push images, deploy, modify DNS or change any external service.

Default target: `linux/amd64`. `--platform linux/arm64` is also accepted as a build input; successful parsing is not proof that either platform has been built or tested. A matching native builder or correctly configured emulation is required.

All four base images are pinned in `images/base-images.json`. The manifest records the exact official `docker-library/repo-info` source commit and metadata paths used to obtain the multi-platform index digests. Tags are descriptive only: every reference includes `@sha256:`. An operator can override a pin with `--maven-image`, `--java-image`, `--node-image` or `--nginx-image`; an approved repository and a full nonzero SHA-256 are mandatory. There is no fallback to `latest` or a floating tag. These source-verified pins are not a vulnerability-scan result or a successful pull/build assertion.

## Source and dependency boundaries

The builder first rejects tracked working-tree changes. It records the exact commit, tree, application revision, commit timestamp and both existing upstream commits. Every image receives identical `git archive` bytes from that commit, not the working directory. Untracked `.env`, credentials, dependency folders, old artifacts and Git configuration are therefore outside the context. Links, submodules, malformed paths and generated runtime directories in the tracked tree are rejected.

The wrapper passes only fixed build arguments and a limited tool environment. It does not inherit `VITE_*`, application credentials, Git tokens, proxy credentials, `NODE_OPTIONS` or `JAVA_TOOL_OPTIONS` as build inputs. Registry configuration is left with the operator's Docker client. Docker daemon proxy configuration and build dependencies remain operator-controlled; this wrapper does not establish an outbound network sandbox for builds. Committing a secret is still prohibited: a Git archive is not a secret scanner. Build output may contain dependency diagnostics and should be handled as operator logs.

Backend packaging reuses the existing `product-readiness-demo` Maven profile, installs the reactor, and explicitly invokes `package spring-boot:repackage` for `approval-server`. A real `jar` inspection checks Boot layout, the launcher, dependencies and both canonical demo resources before copying the executable JAR into a Java 21 runtime image. The JAR and its SHA-256 sidecar are copied; Maven, source and test tooling are not.

Clients reuse the pinned upstream bootstrap and overlays. Each workspace is bootstrapped once, then installed/built directly so a later bootstrap cannot reset its resolved dependency state. Root and PC installs use frozen locks. **H5 preserves the repository's existing non-frozen install**: its actual resolved lock is retained in the image, and bit-reproducibility is explicitly unverified. Capturing that lock is not equivalent to installing from it reproducibly. Locking and repeat-build comparisons remain necessary before any reproducible-release claim.

The static staging helper permits only bounded public asset types, rejects hidden files, symlinks, maps and secret/backup formats, verifies bytes while staging and requires `index.html`. Per-file sizes/digests and the actual resolved dependency lock are placed under `/opt/approval/`, outside `/app/public`. Root licensing notices and each client's pinned upstream MIT license are retained.

## Runtime defaults are intentionally non-public

Backend runs as numeric user `10001:10001`, with `SERVER_ADDRESS=127.0.0.1` and no default local/online-demo/production profile activation. This is intentionally not enough to run an Internet-facing instance. A later isolated deployment must provide tested private service connectivity, a dedicated profile, credentials and seed lifecycle without reusing browser-controlled local identity headers.

PC/H5 images use the official digest-pinned Nginx base but replace its configuration and entrypoint. Nginx runs as `101:101` on port 8080 with temporary paths under `/tmp`. Only built static assets are served. GET/HEAD are allowed; business, management and payment-sandbox routes return 404. Missing JS/CSS/assets return 404 instead of HTML. API proxying is absent. `noindex`, `no-store`, `nosniff` and frame-denial headers apply. `/healthz` checks static serving only, not backend/database health. `VITE_APPROVAL_LOCAL_DEMO=false` is fixed at build time; no actor, tenant or secret is baked into the clients.

The SPA can be packaged, but its authenticated business flow is not accepted or made usable online by this step. HTTPS, invitation authentication, trusted session/role binding, isolation, reset, limits, egress controls and a complete visible PC/H5 business E2E remain required by #144. Do not publish these images or expose their containers as a completed evaluation service.

## Receipts and failure behavior

The wrapper writes one bounded JSON receipt to `.runtime/online-demo-images/build-*/image-build.json`. Runtime outputs remain untracked. A successful packaging receipt contains the source/archive/base identities and each locally inspected image ID, platform, user and matching source labels. It is labelled `LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED`.

A local Docker image ID is **not a registry manifest digest**. `registryDigest` stays null because this command never pushes. The input-derived tag identifies source and build inputs, not a guarantee of identical image bytes; repeated H5 dependency resolution may differ. Archive timestamps/output timestamps and public-file timestamps are normalized where supported, but complete byte reproducibility is not claimed.

A failed build/inspection writes `FAILED`, records the failing component and stops later targets. Earlier locally built images are deliberately retained rather than deleting operator-owned images. No partial result is upgraded to success. The receipt is not a signature, SBOM, supply-chain attestation, vulnerability report or online acceptance record.

## Verification scope

The packaging suite tests argument and pin validation, real temporary Git histories/archives, simulated Docker build success/failure/inspection, public-asset inventories, and real `jar` layout checks on fixtures. **Docker is substituted in these unit tests**; they do not build or execute application images.

Where Nginx is installed, an additional test runs the real server against static fixtures, as a non-root user, verifies routes/headers/denials and terminates it. Only fixture paths, port, worker count and log paths differ from the committed configuration. Absence of Nginx is reported as an explicit skipped runtime test, never as a server pass. This does not test the pinned container image or the built Vben/UniApp applications.

The tests are imported by the existing repository-hygiene suite, not a second automatic workflow. The first full image-build/pull, runtime, vulnerability scan, isolated-session and hosted-concurrency evidence are still pending. No public URL or production capacity claim is released by static tests.

## Next implementation within #144

Add a dedicated fail-closed online-demo configuration and isolated evaluator lifecycle, then a trusted HTTPS invitation gateway and operator reset. Reuse the existing backend, seed, identity authorities, Outbox/Connector and signed sandbox; do not create another business implementation. Bind image manifests, two independent sessions, expiration/reset failures, attachment/egress controls, actual browser behavior and measured hosted limits to the candidate before publication. Hosting, DNS/TLS and registry credentials must come from the chosen environment, never from committed files.

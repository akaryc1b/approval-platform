# Invitation-bound PC/H5 entry

The online image build selects `VITE_APPROVAL_ONLINE_EVALUATION=true`, with
`/evaluation/pc/` and `/evaluation/h5/` as the respective build bases. Ordinary
client builds keep their existing routing and identity integration.

The invitation page discovers a fixed application registry only after an active
workflow session exists. It links to the existing PC workbench, the existing H5
task center and, for the applicant, the existing H5 purchase form. It does not
approve tasks, synthesize login tokens or send the private backend address to the
browser. Role selection remains on the invitation page and rotates the session.

## Runtime boundary

`evaluation-application-export.mjs` exports only exact-source, already-built PC
and H5 images. Its temporary containers never start and have no network or host
mount. All copied files must match their build inventory before the application
registry can be constructed. Export containers and temporary directories have
owned cleanup paths; they are not persistent user data.

The HTTPS gateway serves inventoried files from an immutable in-memory snapshot,
not a request-derived filesystem path or arbitrary HTTP proxy. Both documents and
assets require a current session. Expired top-level document requests return to
`/evaluation`; expired API/asset requests are rejected. Missing paths never fall
back to HTML. Cookie, origin and identity-header checks also apply to static
requests. Static requests have separate bounded request/byte budgets, while the
business API retains its existing limits and CSRF checks.

PC bootstrapping verifies the session before mounting the application and installs
only the existing workbench route. H5 evaluation navigation allows only the task
list, task detail and canonical form page. Both clients verify live session changes
while visible; the request transport additionally checks before and after business
requests. Old tabs cannot silently adopt a replacement role or session. The server
continues to be the authority for every task, attachment, tenant and generation.

Only the exact task delegation/SLA **read** routes were added for prerequisites of
the existing detail pages. Administrative, delegation-write, transfer and arbitrary
SLA endpoints remain denied. HTTP 204 is a legitimate empty response; it must not
be converted into a failed body-stream read.

## Local operator entry

After the existing exact-source image runtime has produced a successful image
receipt, run on that same Docker host:

```sh
node scripts/product-readiness/online-demo-apps.mjs serve \
  <runtime-receipt.json> <localhost-key.pem> <localhost-cert.pem> 8443
```

This entry binds only `127.0.0.1`, uses HTTPS, creates two disposable private stacks
and prints two short-lived invitations to the operator's private console. Do not
copy those invitations, TLS keys or container environments into shared logs. The
entry refuses CI invocation; the CI path below owns its own ephemeral credentials.
SIGINT, SIGTERM or the maximum runtime lifetime revokes sessions and cleans owned
resources. This is not a public hosting/deployment command.

## Verification and provenance

The existing image-runtime CI entry builds once, retains its original signed-read
and real API/reset rehearsals, then runs the complete browser rehearsal twice
with the same built images and fresh private stacks each time. Missing Chromium, mismatched static artifacts, page failures, business
failures or cleanup failures fail this stage; there is no passing fallback.

`evaluation-browser.mjs` drives system Chromium through CDP, not Playwright. It
creates two independent incognito browser contexts, enters actual invitations,
uses the existing H5 file picker/form and existing PC/H5 approval controls, and
observes real responses without intercepting them. Successful submission and
approval cannot be replaced by direct API calls. Supplemental API reads and
negative access probes verify identities and database/payment postconditions.
The final payment-confirmation role uses H5; this is **not** WeChat native evidence.

The rehearsal preserves actual attachment hashes, Outbox/signed-sandbox recovery,
selected-session reset, continued operation of the other session and controlled
clock expiry checks. Its PNG screenshots and sanitized JSON network/action trace
omit credentials and response bodies; this JSON is not a Playwright trace archive.
Only the current run's actual browser receipt can establish browser acceptance.
Unit tests and earlier API-only receipts cannot establish it.

The current change is not evidence of a public URL, production payments, outbound
abuse controls, wall-clock expiry timing, crash recovery or hosted operation.

## Two clean runs and retained evidence

`online-demo-images-runtime.mjs ci` (or explicit local `run`) requires both
sequential browser runs to pass. There is no retry-on-failure loop: a failed run,
failed cleanup, source mismatch, exhausted shared deadline or reused business
identity prevents aggregate success. The second run starts only after the first
has returned a passing browser and stack cleanup receipt. Image build and static
export happen once; the existing 42-minute overall budget is not extended.

The first run keeps the original `evaluation-browser-rehearsal.json` location.
The second uses a new sibling directory ending in `-browser-repeat-2`, so both
are covered by the unchanged artifact-upload patterns. The first directory's
`evaluation-browser-repeat.json` reports `TWO_CLEAN_BROWSER_RUNS_PASSED` only when
both full runs succeeded, or `FAILED` with the attempted run number otherwise.
Each run retains its own detailed receipts and sanitized network/action trace.
A prior summary or browser-receipt directory is never silently overwritten.

Screenshots have monotonic numeric prefixes and exclusive file creation. The
initial and replacement `A-invited` captures therefore remain separate files.
The per-image size limit is retained and capture count is bounded at 64 per run.
These files are functional evidence, not a complete visual/accessibility audit.

## First complete functional baseline

[Validation #1751](https://github.com/akaryc1b/approval-platform/actions/runs/34693376046)
on `c3739f106b8974a9849ffe8599feee104e95597a` finished successfully, including all
10 jobs. Its one complete two-browser run reported
`TWO_BROWSER_PC_H5_BUSINESS_RESET_PASSED` in 118,018 ms. This historical result
predates the two-clean-run requirement; it is not evidence that the later
repeatability check has passed.

Artifact `approval-online-images-34693376046` (ID `10298206634`) is 1,041,142 bytes,
SHA-256 `58347b65844261a7e77e30ff4584ac68a3db3a349b67460f2d3d5d80acbe1621`.
It records two H5 uploads/submissions, ten visible PC/H5 approval decisions,
exact signed-sandbox recovery, selected reset, continued operation of the other
session, controlled expiry, and cleanup. It is loopback-only, not a hosted URL.

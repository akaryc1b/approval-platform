# Product-readiness publication privacy

The eight product-readiness envelope producers prepare a sanitized publication
copy before computing their final size, SHA-256, base64 and envelope totals.
Original capture files are never modified. The complete batch must succeed before
the producer appends anything to `root-install.log`; failure has a constant public
diagnostic and no raw fallback. Normal source identity and business acceptance
checks still run. This codec is not an authentication mechanism.

Each append uses an exclusive publication lock and a private same-directory
staging file. The existing log is copied in bounded chunks; after complete writes,
file synchronization and an identity check, an atomic rename publishes the whole
new log. Write/read/sync/rename failures leave the old log unchanged. A process
crash can leave a lock or staging file and block future publication; the helper
does not claim directory-sync durability across machine power loss. The workflow's
initial root-install redirect has finished before these envelope producers run.

## Native trace contract

The supported native capture is Playwright trace schema 8, validated against the
1.60.0 loader and snapshot renderer. JSON/JSONL structure, event order, timing,
statuses, business and actor identities, and resource references are retained.
Changing a protected identity rejects publication instead of silently breaking a
join. Changed content-addressed resources receive new hashes and all native
`sha1`/`_sha1` references are rebound, including DOM overrides and screenshots.
Original network sizes describe observed traffic; resource hashes describe the
published bytes. ZIP CRCs and enclosing file digests describe the published ZIP.

Embedded source resources sometimes contain fixture credential literals. Those
resources are explicitly transformed source evidence. `approval-sanitization.json`
lists each affected source path and its whole-source original/published SHA-256,
with a declaration that the published source is not the original source bytes.
The capture's actual source commit/tree/run identity is unchanged. No secret-to-
placeholder mapping or per-secret digest is emitted.

Assertions must remain exact. The only permitted exception is a credential in an
after-record's `result.received.ariaSnapshot` DOM diagnostic. The assertion's
before-record, predicate, expected criterion, outcome and all other after-record
fields must remain identical. Each exception is declared with trace name, record
index, call ID, field and transformation reason. An attempted change elsewhere in
an assertion rejects publication. This is diagnostic redaction, not a different
assertion or a rerun.

## Supported and rejected content

Discovery covers the complete producer batch before replacement. It recognizes
authentication values in nested/serialized JSON, HTTP header arrays/maps, cookies,
storage, password inputs and methods, text/source assignments, URLs, and plain
header diagnostics. Known credential echoes and JWT-shaped values are removed.
Semantic keys such as businessKey, taskDefinitionKey and idempotencyKey are not
classified as credentials merely because their names contain “key” or “token”.

JSON escaping and structured base64/base64url/text data URLs are inspected and
rewritten with their supported codec. HTML entities, percent encoding and Unicode
escapes are recursively inspected. When a known credential cannot be removed by a
supported representation-preserving replacement, publication is rejected. Opaque
compressed/base64 archive payloads, unsupported binary/data URL formats, malformed
JSON, unknown trace schemas and unknown archive members are rejected. The codec
does not claim to discover arbitrary steganography or every possible encoding.

Supported image/font formats have bounded structural validators. Unknown metadata,
trailing data and unsupported structures are rejected; decoded font names and
compressed font tables are checked. Screenshots and fonts are byte-identical.
This does not inspect visible screenshot text or provide an OCR privacy guarantee.
A screenshot known to display a credential must not be published under this
contract: exact pixel preservation and credential removal would conflict.

Archives, files, expanded batches, member counts, nesting, discovered credentials
and text processing have limits. Reads reject symlinks and check opened-file and
post-read identity/size/timestamps. Native ZIP framing, entry names, compression,
CRC and references are validated. Python failures never print input names, parser
details or raw content; the Node wrapper validates the returned base64, size,
digest and exact provenance schema before a producer can append an envelope.

## Subprocess diagnostics

The managed/checked process helpers publish only fixed readiness markers and
bounded typed numeric test/build summaries. Each stdout/stderr pipe has its own
bounded line framer. Oversized lines, ANSI/control tricks, partial lines and
freeform reporter errors do not fall back to raw output. Dynamic Quick Start
control values are consumed privately in memory. Exit status, timeouts, existing
readiness markers and acceptance gates retain their original meaning.

Public logs therefore lose freeform test names, stack/code frames, source snippets
and arbitrary build/install/reporter text. The safe projection explicitly reports
omitted output. Structured sanitized evidence retains supported diagnostics, but
some process failures will require an authorized local reproduction to diagnose.
An offline native loader/renderer check does not establish that a browser UI was
launched or that every screenshot was visually inspected.

## Regression selection

The existing repository-hygiene aggregate imports the Node publication tests and
safe-output tests; the Node wrapper executes the Python codec/framing suites.
The existing browser and capacity selectors both select privacy helper and test
changes, including sanitizer-only changes. No browser launch, retries, assertion
weakening, timeout increases or alternate CI skip is part of this change.

Run the focused synthetic suites with:

```sh
node --test scripts/tests/product-readiness-artifact-*.test.mjs scripts/tests/product-readiness-safe-output.test.mjs
```

Only synthetic canaries belong in committed tests. Historical captures and private
offline audit tooling are not repository fixtures or publication deliverables.

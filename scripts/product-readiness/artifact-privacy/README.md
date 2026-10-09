# Product-readiness publication privacy

The eight product-readiness envelope producers prepare a sanitized publication
copy before computing their final size, SHA-256, base64 and envelope totals.
Original capture files are never modified. The complete batch must succeed before
the producer appends anything to `root-install.log`; failure has a fixed-category
public diagnostic and no raw fallback. Normal source identity and business acceptance
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
An idle context's zero-byte `.network` member remains zero bytes. Blank JSONL
records and empty `.trace` members still reject publication.

The pinned producer stores test attachments, including automatic failure
`error-context` Markdown, as extensionless `resources/<40-lowercase-hex-SHA1>`
members. That narrow profile is supported only through native `.trace` after
records with an `attachments` list. Each attachment must have exactly `name`,
`contentType`, and `sha1`; the name is a string, the hash has the exact native
shape, and its after record has one matching before-record call ID in the same
trace. MIME must be exactly `text/markdown` or `image/png`. Repeated references
with the same hash and MIME are permitted for native deduplication. Conflicting
MIME declarations, unsupported MIME, unreferenced extensionless members, missing
resources, and mismatched original hashes reject the entire batch.

Records are parsed before resources, so ZIP member order cannot choose a codec.
Markdown must decode as strict UTF-8 without non-text control characters; it
passes through the existing credential discovery, redaction, and encoded-content
guards. PNG attachments use the existing structural and credential checks and
remain byte-identical. A declared MIME never bypasses those checks. Changed
Markdown receives a new extensionless content hash, and all native references
are rebound. This does not enable arbitrary extensionless text, additional MIME
types, or `.md` archive members, and does not change source provenance or the
assertion rules below.

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

## Publication rejection diagnostics

Before throwing the existing publication exception, the Node wrapper emits one
independent line to stderr: `EVIDENCE_REJECTION_V1 category=CODE`, terminated by
exactly one LF. The category describes the rejected guard or processing stage;
it does not identify an artifact, disclose input values, or establish the cause
of the browser failure. It survives even when the entire artifact batch is
rejected. The exception messages and `publicFailureDetail` projection are
unchanged, and a category never permits partial publication or a raw fallback.

The version-1 allowlist is:

- `REQUEST_INVALID`: request parsing or request-shape guard
- `INPUT_READ_FAILED`: file access or the existing file-identity guard
- `ARCHIVE_INVALID`: native ZIP framing or archive validation
- `CONTENT_INVALID`: supported content, JSON/UTF-8, or trace-schema validation
- `BINARY_INVALID`: binary validation, including credential-preservation guards
- `REFERENCE_INVALID`: native resource reference or content-hash validation
- `DISCOVERY_REJECTED`: credential discovery or its supported-codec guard
- `TRANSFORM_REJECTED`: a supported transformation cannot safely preserve content
- `IDENTITY_CHANGE`: an identity, object key, or evidence path would change
- `ASSERTION_CHANGE`: a change exceeds the existing received-DOM exception
- `RESOURCE_LIMIT`: an explicit existing resource-budget guard
- `SANITIZER_INTERNAL`: an unexpected sanitizer exception or unknown category
- `SANITIZER_PROCESS_FAILED`: spawning, process execution, or capture failed
- `SANITIZER_TERMINATED`: the sanitizer terminated without a normal exit status
- `SANITIZER_PROTOCOL_INVALID`: inconsistent status/streams or an unknown or
  malformed rejection line
- `SANITIZER_RESPONSE_INVALID`: success output failed the existing JSON, schema,
  provenance, canonical base64, size, digest, or total validation

Only the first twelve categories may come from Python. They are enum members
selected by explicit trusted guards or narrowly scoped parser/codec/I/O
boundaries. Unexpected exceptions remain `SANITIZER_INTERNAL`, even when their
stage is known. No category is read from evidence metadata, filenames, exception
messages, parser excerpts, or subprocess error text.

A Python rejection has exit status 1, empty stdout, and exactly one allowlisted
protocol line on stderr. Node verifies all three before emitting its own line.
Extra whitespace, CRLF, multiple lines, suffixes, unknown codes, wrapper-owned
codes, raw error text, and output alongside a rejection are protocol failures.
Success requires status 0 and empty stderr; its artifact/provenance schema is
unchanged. A missing executable, thrown spawn error, signal, invalid protocol,
or invalid success response maps to a Node-owned category. Subprocess output
is never forwarded. The existing safe process-output projection separately
allowlists these complete versioned lines.

## Subprocess diagnostics

An additive Playwright reporter retains the configured list reporter and emits
`BROWSER_FAILURE_V1 phase=PHASE category=CATEGORY reason=REASON` before artifact
publication. Quick Start records a closed operation phase and preserves the
first failing phase through cleanup. Category comes from the runner's terminal
status; reason is a private exact lookup of known helper diagnostics, including
the pinned runner/evaluate wrappers. Unknown or ambiguous exceptions stay
`UNKNOWN`. No exception, stack, path, URL or identifier is copied into the line.
Other tests without phase annotations report `UNAVAILABLE`.

The matrix also emits `BROWSER_CONTEXT_V1 engine=ENGINE pcCount=COUNT pcKind=KIND h5Count=COUNT h5Kind=KIND` on failed tests. Engine must match an exact configured project/browser pair. PC/H5 are the existing page roles; the observer retains only counts and an allowlisted native Error name class, never the error message, stack, URL or arbitrary name. Missing/malformed state and counts outside 0–999999 become UNKNOWN. Different observed classes become MULTIPLE. Zero counts require NONE. The original page-error assertion and count remain unchanged. At most 64 distinct context diagnostics per output recorder are retained; duplicates do not consume another slot and excess input emits the existing fixed omission marker.

All diagnostic protocols are advisory failure information, never acceptance,
readiness, control values or provenance. The projector checks their original
ASCII line before normalization. Invalid diagnostic-looking lines are omitted
without falling through to the existing readiness-marker parser. Browser and
publication failures retain their original nonzero outcome; diagnostics cannot
authorize a PASSED receipt or omit an unsafe artifact.

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

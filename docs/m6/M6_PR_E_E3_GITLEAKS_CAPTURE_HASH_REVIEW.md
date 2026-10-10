# Append-only Compiler capture hash review

The first local Compiler security preflight at
`1fa24382e351bed9023116d2e0ea356ca9b40b8c` retained 32 normalized Gitleaks
observations: the accepted 29 records plus three additions. Its scanner completed
with findings. The later local Semgrep launcher failed before scanning. That
attempt remains failed; this classification does not retroactively pass it or
claim completion of the other scanners. Raw scanner reports, candidate matches
and credential material were deliberately discarded. `rawFindingCount=32`
describes the observed count, not retained raw match bytes.

The three additions are `generic-api-key` observations at lines 158, 171 and 173
of `docs/operations/compiler-plugin-evidence/capture-execution.json`. Each is an
exact member of the `captureInputs` object, respectively identifying:

- `docs/m6/M6_A_DINGTALK_TOKEN_LIFECYCLE.md`
- `docs/m6/M6_A_PRODUCTION_SECRET_MATERIAL_SOURCE.md`
- `docs/m6/M6_A_SERVER_OWNED_CREDENTIAL_BINDING.md`

The [pinned review plan](m6-pr-e-e3-gitleaks-capture-hash-review.json) retains all
29 accepted normalized records, the scanner identity, exact source/collector
blobs, byte lengths, complete-file and line hashes, JSON pointers, and input
document blobs. The original receipt and collector stay unchanged. Each scalar
is recomputed as SHA-256 of the complete document bytes. This is positive
source-backed evidence for these three non-credential metadata observations,
not permission to accept other hexadecimal strings or locations.

The source proof uses actual hash-verified Git commit/tree/blob objects. The
reported introduction must be in the scanned Head's parent ancestry and itself
descend from accepted public main
[`0c55ff5e7d974022e5c76d9a88a4312d2af81827`](https://github.com/akaryc1b/approval-platform/commit/0c55ff5e7d974022e5c76d9a88a4312d2af81827).
Every introduction parent must lack the receipt path. Exact receipt/collector
blobs must be present at both introduction and scanned Head. All three document
paths/blobs must agree at accepted public main, introduction and scanned Head.
The receipt's source Head/tree, collector digest and successful capture-only
flags are checked against these anchors. The collector's pinned complete bytes
show the retained snapshot copy, whole-file hashing, map serialization and
after-capture equality check; the collector is never executed by this review.

Publication may change commit metadata or combine additive source changes, so
the actual reported introduction SHA and its derived fingerprint/finding IDs
are proved from Git objects. Local commit identities are not claimed as public
commits. Different paths, lines, rules, fields, bytes, sources, parents, fingerprints,
scanner identities, missing records and extra observations all fail closed.

The complete 32-observation E4 is unchanged. I2 attaches exactly three new
`NOT_APPLICABLE` decisions with `UNKNOWN` severity and a separate
`E3_GITLEAKS_CAPTURE_HASH_REVIEW` receipt. The accepted test-expression and Clean
public Git-reference proofs remain independently required on the same E4.
I3, I4 and R2B carry and replay all three source review receipts. All existing
output stages remain; the capture-hash stage is additional. Existing 28/29-only
invocations retain their byte-canonical behavior.

For the unchanged prior review inventory, I2 has seven current reviewed
findings, five append-only Gitleaks reviews, and I4 has 73 cumulative reviews
with 66 historical remediations. The three observations are retained current
findings, not deleted findings or historical remediations. All unrelated
decisions, unresolved findings and release blocks remain. No scanner rule,
path, regex or history suppression, exception, severity downgrade, readiness,
deployment or release clearance is introduced. Fresh exact-head scanner
evidence remains required after this source change.

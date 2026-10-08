# Server dependency graph transition

This is an append-only structural dependency transition. It is not a new E4
scan, finding disposition, vulnerability clearance, release authorization, or
replacement for a passing exact-head CI result.

## Immutable source and evidence

The [typed transition manifest](server-dependency-transition.json) is pinned by
both its complete file SHA-256 and canonical payload SHA-256 in
`scripts/security/server-dependency-graph-transition.mjs`.
The [source witness](server-dependency-source-witness.json) has separate byte and
canonical pins. It retains the seven-file source transition, Git blob and content
hashes, and the three completed Maven capture invocations with clean source
identity before and after each invocation.

- Accepted source predecessor: `a0717a017e94840141fc6841ff7bfb1226a3da6e`,
  tree `0c12c618fdd24d13caf5e37cc74fdb684a30fa0f`.
- Archival source witness: `921d5ec5a770cccdfa24f9cf809b0a3a609f9cbe`,
  tree `79c138d18075b54e800829eb903cb58a0b39b040`.
- [Unmodified captured E2](server-dependency-evidence-921d5ec5/M6_PR_E_E2_SBOM.json):
  raw SHA-256 `1f5e32f60e4bde4a608a24decbf913779128e1627f9128c7e0267e3f1c04927f`,
  canonical content SHA-256 `4c423ae6f24cbdd6a83c07f2176e254dfbee413a1e49ca9f28c3524a050b43b7`.
- Prior graph: `390773d2aa746a2203eec870e5dd0a8f97f81e91913bb016c9ef95b749c0e7b3`.
- Current graph: `6557cef5d2ef11c36a8efb89cf7c1c607c721e32cdbabf0228441bb585b3c829`.

The archival E2 was captured using the verified Maven 3.9.16 runtime, dependency
plugin 3.11.0, and hydrated local Maven repository. The committed source witness
retains invocation and output hashes, not a claim that these tests execute those
Maven commands again. Full raw capture logs remain separate evidence; the E2,
source identities, and typed inventory are sufficient for deterministic graph
reconstruction.

The archival source head is deliberately separate from the current scanner head.
An integration commit and later unchanged-graph commits get their own freshly
generated E2 content identity and current-head lineage receipt. The verifier does
not relabel the archival evidence or require future CI to remain on `921d5ec5`.

## Exact typed delta

The verifier changes only cloned evidence while reconstructing the prior graph:

- 90 exact, version-only component replacements, including BOM references and
  Maven source coordinates.
- 180 explicitly enumerated edge rewrites. Both before and after endpoints must
  match the version mapping; no unknown edge is discarded.
- Two ordered BOM additions: Jackson 2.21.7 at index 1 and Jackson 3.1.7 at index 2.
- Spring Boot's imported BOM changes from 4.0.2 to 4.0.8 at current index 4,
  returning to prior index 2 after the two additions are removed.
- 13 exact resolved-plugin coordinate replacements and the separately captured
  raw plugin-resolution hash transition from
  `4accac2e7bdf0765b2fea4a4b43b437e0f4a536ffa3047183107dc77c1dd14f1` to
  `e9a095fee4fdbf3293833511950288c1932fbafc492799ffd3fa7468bd02b072`.

The current inventory remains 237 components, 345 edges, 26 reactor roots and
329 resolved-plugin coordinates, with six imported BOMs rather than four.
Component scopes and licenses have zero changes. The 78 components with
`EVIDENCE_UNAVAILABLE` licenses remain unresolved metadata, not approved licenses.
Project Jackson 3.1.7 and plugin Jackson 3.1.5 are distinct inventory entries.

Reversal validates declaration uniqueness, inventory counts, exact endpoints and
BOM precedence. Only typed version/ref/source fields and explicitly declared
edges/BOM entries/plugin coordinates are reversed. Every other field remains in
the clone. The entire result must hash to the unchanged OTel graph; existing OTel
and foundation verifiers then reconstruct their unchanged historical baselines.
No whole-field historical snapshot replacement, package-prefix exception or
unknown-graph fallback is available.

## Admission and receipts

`verifyObservabilityGraph(e2, projection, BASE_GRAPH, expectedCommitSha)` validates
repository, E2 canonical digest and projection identity. For the server graph,
it also requires the E2 schema and a current head independently verified by the
scanner checkout boundary. Unknown graph baselines, including a caller presenting
its own graph as the baseline, are rejected.

The `APPROVAL_SERVER_DEPENDENCY_GRAPH_LINEAGE_V1` receipt includes the current
commit and current E2 digest, all four graph generations, all three transition
manifest pins, the immutable source witness, exact delta counts and inventory.
Both `findingReviewRequired` and `releaseBlocked` must remain `true`.
`requirePreservedGraph` compares the entire receipt against the current E4
identity, including its canonical digest and all fields. Its third argument is
mandatory for the server graph and binds the independently verified current
scanner head. The pgjdbc remediation verifier likewise requires
`{ expectedCommitSha }` as its third argument for the server graph; omitting this
independent current-head binding cannot preserve the historical remediation.
Historical foundation/OTel inputs and receipts retain their existing behavior.

Graph lineage does not accept an E4 scan, remove findings or replace a finding
review. The scanner must still consume the full, unmodified current E2. A
separate completed exact-head E4 scan and explicit finding dispositions are
required before making any claims about vulnerabilities. Release blocks remain.

## Verification and rollback

Run:

```sh
node --test scripts/tests/server-dependency-graph-transition.test.mjs \
  scripts/tests/ops-observability-graph-transition.test.mjs \
  scripts/tests/ops-observability-otel-graph.test.mjs
```

The new suite uses the actual archived E2 for full historical reconstruction,
then tests forged/rehashed manifests, current E2/projection/head mismatches,
duplicate/missing declarations, counts, flags, scopes, licenses, order, unknown
additions, receipt substitution and on-disk manifest/source/E2 tampering through
the public production entrypoint. Future-head fixtures are explicitly synthetic
binding tests, not scan evidence. Existing historical manifests are unchanged.

To withdraw this admission, revert the new server transition integration and
restore the preceding dependency source together. Keep the immutable evidence
and historical manifests available for audit. A source-only or verifier-only
partial rollback must fail graph admission rather than silently accept an
unrecognized graph. Re-run exact-head E2, E4 and the normal required gates after
any authorized rollback or subsequent source change.

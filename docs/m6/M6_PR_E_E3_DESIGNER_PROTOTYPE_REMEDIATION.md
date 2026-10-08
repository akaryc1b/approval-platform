# Designer prototype-pollution remediation evidence

This is an append-only verification boundary for one historical Semgrep review.
It is not a live scanner result or security-closure decision.

## Historical evidence remains unchanged

The frozen I2 review retains finding
`e6b83b7719d2bef5092127dd47f2a34c17d9c92ce3b9581b4381dd6b25432445`,
its `UNRESOLVED` disposition, original source blob
`fa3c224ac31df08cb3e2f130f7e4bef1fc750659`, rule, path and location.
The new JSON plan pins the entire historical review's canonical digest and the
corrected source, regression test and root package-script blobs. No historical
review, accepted finding-set contract or disposition is rewritten.

Actual CI #1805 at `d792d16ee84ea114d94edd2a20848b2d48263ded` still contains this
finding. Its E4 canonical digest is
`a4d20301a1c1907c3bcd987c04ac317f1e0116d1e3d3abd8c4d4d28a5776ff74`:
27 Gitleaks, 143 OSV, 3 Semgrep and 0 zizmor findings, 173 total.
Its historical I4 result remains 170 unresolved and 63 historically remediated.
Those numbers are not replaced by local fixtures.

## Current proof required before reconciliation

The natural full-scanner job must establish all of these:

1. The workflow event's exact Head is independently resolved. The checked-out
   tree must equal that Head's tree, and tracked working-tree/index changes are
   prohibited before scanning and before evidence emission. A synthetic PR
   merge commit is permitted only when its tree is identical to the PR Head.
2. The unchanged full scanner invocation succeeds. Required native report
   structures must be present. Gitleaks cannot synthesize an empty missing
   report. Successful zizmor SARIF runs require results arrays. OSV's required
   results/packages containers retain its native Go nil-slice representation;
   omitted optional package vulnerabilities remains a valid empty value.
   These checks do not claim package-level completeness or authoritative alerts.
3. Semgrep's results/errors/path structures and version are valid; errors are
   empty. Its actual scanned-path inventory must include the designer source.
   An explicitly skipped designer target is rejected, including normalized path
   spellings. Optional skipped-path inventory is reported honestly when absent.
   Coverage retains only counts, hashes and the required target path, with no
   raw report or source snippets.
4. The source, regression test and package file bytes match the pinned Git blobs
   and SHA-256 values, and those same blobs exist at the exact scanned Head.
5. The original finding ID is absent, and the same rule on the same normalized
   source path is absent at every location. The E4 payload's canonical digest,
   scanner/rules/image identities, checkout proof and target coverage bind the
   remediation receipt to the current successful scan.

Only then may that single historical review leave the *current review input*.
Every actual scanner finding remains in E4 and I1, and every current identity
must remain through I2, I3 and I4. R2B compares current Semgrep identities plus
the separately proven historical remediation with the unchanged frozen set.
It never inserts a finding into scanner output or reconstructs scanner input.
A newly present identity remains unresolved and fails the strict set boundary.

## Counting and lineage

I2 retains the original three historical reviews, two current reviews, and one
explicit historical remediation. I3 carries that receipt alongside the two
pgjdbc remediations. I4 carries the same receipt and the cumulative historical
records, including its 61 workflow remediations. The cumulative reviewed count
remains 68; the historically remediated count becomes 64 only after this proof.
Current counts always come from the actual scan.

The local synthetic 172-finding case has 169 unresolved and 3 not-applicable
current findings. It validates bookkeeping only. It is not evidence that a new
live scan has two Semgrep findings or that remediation has already occurred.

## Verification and remaining limits

The permanent suite covers malformed/missing reports, omitted/skipped targets,
relocated rules, source/test/package drift, stale internally consistent Heads,
merge-tree mismatch, pre/post tracked drift, receipt/counter tampering, dropped
current identities, and full CI-callback wiring with explicitly synthetic
subprocess output. Local runs do not execute the GitHub-only full scanners.
The static E2 output is byte-identical to the parent commit: package scripts
change no dependency projection. The scanner still receives the actual current
E2 graph; full Maven resolution and actual scans remain required in natural CI.

`AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE` remains in force.
`NO_SUPPRESSION`, `NO_SEVERITY_DOWNGRADE`, `NO_EXCEPTION`, `NO_READY`, `NO_MERGE`,
`NO_DEPLOYMENT`, and `NO_RELEASE` remain the boundaries of this evidence change.

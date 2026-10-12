# Checkstyle Commons Lang direct OSV diagnostic

The actual official OSV API query covered all **472 package targets**: 471 Maven
and one npm, including **453 zero-result targets** and 19 affected targets.
The complete normalized inventory decreased **22 → 21** against accepted Compiler
and natural-main CI 1838. Exactly `GHSA-j288-q9x7-2f5v` (`CVE-2025-48924`) for
`org.apache.commons:commons-lang3@3.17.0` was removed. Nothing was added or changed;
all 21 retained normalized records are identical in every field.

Collection ran from **2026-10-12T08:03:39.633328+00:00** through
**2026-10-12T08:04:55.021636+00:00**. One 46,921-byte batch POST and 15 unique
advisory GET requests succeeded against `https://api.osv.dev`, with no retries,
redirects, cookies or application authentication. Submitted package data was
limited to names, versions and ecosystems. Only returned advisory IDs were used
for detail requests. All input positions, zero results, advisory mappings and
full detail retrieval are represented in the retained evidence.

The exact request derives from the actual retained Checkstyle E2 and candidate
POM, bound to the sealed Checkstyle transition manifest. The E2's commit SHA
`4d685762d2db10497abe7668d1009b7e1a056796` identifies its committed baseline;
the query used the dirty precommit candidate inventory. It is not a query on a
committed final candidate tree. The only target-set removal is Lang 3.17.0;
Lang 3.18.0 was already represented. Remaining target references/scopes are unchanged.

This directory contains the complete target inventory, exact API request and
scanner-helper-equivalent bytes, derivation rows, complete query results,
normalized current and baseline findings, complete semantic comparison,
redacted HTTP status/hash provenance, and exact modification timestamps.
Raw advisory descriptions/details, HTTP headers, transport configuration,
credentials and environment values are excluded. Raw API responses remain
private. Hashes bind retained bytes; they do not independently authenticate a
remote execution.

All 15 detail timestamps carry additional fractional digits and agree with the
batch values at batch precision. Both exact strings are preserved. This does not
prove representation-only differences, exact nanosecond equality, absence of a
sub-microsecond change, or unchanged full advisory metadata. Baseline raw advisory
bodies are unavailable, so comparison establishes equality only of the complete
normalized records. Fixed versions retain the existing normalizer's union across
matching affected-package entries; they are not an upgrade recommendation or a
reachability conclusion.

This is a **direct API diagnostic**, not an OSV CLI invocation, canonical E4,
completed security preflight, graph admission, or release approval. The normalized
`E4_OSV_SCANNER` source class is retained only for format compatibility. No prior
finding disposition is transferred. **Release remains blocked.** Final source/CI
validation and explicit review remain necessary. The focused offline verifier
reconstructs inputs from the retained E2, checks source/manifest pins, full target
and finding attribution, every normalized comparison field, bounded HTTP
provenance, precision limits, and the complete public checksum inventory.

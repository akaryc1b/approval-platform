# Checkstyle-local Guava direct OSV diagnostic

The actual official OSV API query covered all **472 package targets**: 471 Maven
and one npm, including **454 zero-result targets** and 18 affected targets.
The complete normalized inventory decreased **21 → 19** against accepted
natural-main CI 1841. Exactly `GHSA-5mg8-w23w-74h3` (`CVE-2020-8908`) and
`GHSA-7g45-4rm6-3mm3` (`CVE-2023-2976`) for `com.google.guava:guava@31.0.1-jre`
were removed. Nothing was added or changed; all 19 retained normalized records
are identical in every field.

Collection ran from **2026-10-12T10:46:56.661572+00:00** through
**2026-10-12T10:47:37.908866+00:00**. One 46,922-byte batch POST and 13 unique
advisory GET requests succeeded against the official `api.osv.dev` host,
with no retries, redirects, cookies or application authentication. Submitted
package data was limited to names, versions and ecosystems. Only returned
advisory IDs were used for detail requests. All input positions, zero results,
advisory mappings and full detail retrieval are represented in this evidence.

The exact request derives from the actual retained Guava candidate E2 and POM,
bound to the sealed Guava transition manifest. The E2's commit SHA
`01aa72c1d2dfdc5a85dd0ff61e2e2a9e7f727a0c` identifies its committed baseline;
the query used the dirty precommit candidate inventory. It is not a query on a
committed final candidate tree. The target delta replaces four tuples: Guava
31.0.1-jre → 32.0.1-jre, checker-qual 3.12.0 → 3.33.0,
error_prone_annotations 2.7.1 → 2.18.0, and j2objc-annotations 1.3 → 2.8.
All retained target references and scopes are unchanged.

This directory contains the complete target inventory, exact API request and
scanner-helper-equivalent bytes, all 487 derivation rows, complete query results,
normalized current and baseline findings, complete semantic comparison,
redacted HTTP status/hash provenance, and exact modification timestamps.
Raw advisory descriptions/details, HTTP headers, transport configuration/URLs,
private filesystem paths, credentials and environment values are excluded.
Raw API responses remain private. Public HTTP provenance uses only the official
host, allowed endpoint and returned advisory ID. Hashes bind retained bytes;
they do not independently authenticate a remote execution.

All 13 detail timestamps carry additional fractional digits and agree with the
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
validation and explicit finding review remain necessary.

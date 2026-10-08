# Maven Site and Dependency plugin remediation

## Bounded source change

From accepted head `ea71ab67467779c7b4dc83c8a67508f6bfd73055` and natural CI
#1816, pin the root `pluginManagement` entries for Maven Site 3.12.1 → 3.22.0
and Maven Dependency 3.7.0 → 3.11.0. Override only Site's `org.jsoup:jsoup`
dependency to 1.23.2. The published Site 3.22.0 POM otherwise selects 1.22.1.
No Site goal is newly bound to the build lifecycle. Project BOMs do not manage
an isolated plugin realm.

The official [Site requirements](https://maven.apache.org/plugins/maven-site-plugin/plugin-info.html)
and [Dependency requirements](https://maven.apache.org/plugins/maven-dependency-plugin/plugin-info.html)
are Maven 3.6.3 and Java 8 minimum; the verified Maven 3.9.16 / Java 21 environment
satisfies them. Exact published POM, binary and source JAR bytes for both plugins
and jsoup were verified against official Maven Central checksums. URLs, byte
counts and independently calculated SHA-256 values are retained in
`site-dependency-plugin-evidence/official-artifact-provenance.json`.

## Actual inputs and goals

The all-26-project effective model and actual Maven resolution distinguish
inherited plugin management from the E2 collection tool. E2 already explicitly
invokes `maven-dependency-plugin:3.11.0:tree` and `:resolve-plugins`; that collector
and its arguments are unchanged. Root management removes the additional old
Dependency 3.7.0 realm from the resolved inventory.

Inspection of the checksum-verified Site source places the Jetty server
construction in `SiteRunMojo.execute()`. No first-party invocation or execution
binding of `site:run` or `site:site` existed at the accepted base. `site:site`
renders local documents; `site:run` starts Jetty. This is goal-specific context,
not proof that all affected APIs are unreachable. Resolved plugin inputs remain
real supply-chain inputs. No finding is dismissed because it has build scope.

The upgrade retains Site's supported Jetty 9.4 API line, now
9.4.58.v20250814. It does not force incompatible Jetty 12 artifacts into the
plugin or claim Jetty is free of known advisories. The
[jsoup 1.23.2 maintainer release notes](https://jsoup.org/news/release-1.23.2)
describe the nested-namespace parser and W3C conversion improvements, including
[the namespace tracking change](https://github.com/jhy/jsoup/pull/2556).
Actual scanner coverage, rather than inconsistent historical advisory prose,
determines which exact target returned an advisory.

## Exact graph transition

Actual 26-reactor resolution yields:

- 237 runtime/project components, 345 edges, 26 reactor roots and six imported
  BOMs, all byte-equivalent to the accepted graph
- The same pnpm, accepted Actions, license, scope and other graph fields
- Only Site and Dependency owner inventories changed; all 15 other owner
  dependency lists are unchanged, including Boot's Jackson 3.1.7 override
- 329 → 282 distinct plugin coordinates: 69 additions and 116 removals
- Actual report SHA-256 `fe5814b0c77a377d1237ca4f53243dbe1ff5401a53344d03a66aad7442165ec2`
- Candidate graph `e7a2f92ae31e016e6691eb8625311a32e930ef1103836ad28a45a9b4c27748b6`

The typed set delta reverses only declared plugin additions/removals and the
actual report hash, reconstructing exactly the prior Jackson plugin graph.
It never replaces a whole graph with a snapshot. The pinned prior Jackson,
server, OTel and foundation receipts are preserved. R3A and R4 independently
admit this descendant and retain their original runtime/advisory contracts.
The full E4 callback requires this exact current graph. Unknown graph changes,
stale heads, manifest/capture tampering, undeclared coordinates and forged
receipts fail closed. `releaseBlocked` and `findingReviewRequired` remain true.

## Actual OSV-only diagnostic

The candidate input has 486 exact ecosystem/name/version targets, including all
69 new targets and all non-finding targets. It was queried with the unchanged
OSV 2.5.0 normalizer and complete-coverage validator. The official destination
received package identities only. All 486 targets were reported.

The diagnostic returned 33 findings versus the accepted 63. This comprises
34 removed old-version records, four records rebound to the new Jetty version,
and 29 retained records that are byte-identical. Each of the four rebound
records preserves the old advisory ID, aliases, severity and fixed-version
metadata; none is a newly introduced advisory ID:

- Jetty HTTP: GHSA-qh8g-58pp-2wxh / CVE-2024-6763
- Jetty HTTP: GHSA-355h-qmc2-wpwf / CVE-2026-2332
- Jetty Security: GHSA-2fvj-hgj9-j2gr / CVE-2026-10050
- Jetty Server: GHSA-7p3p-8qv8-m2vh / CVE-2026-6790

All four remain unresolved. Jetty 9.4.58 returned four advisories, rather than
the preliminary projection of five. The resulting net reduction is 30 records,
not the preliminary estimate of 29. jsoup 1.23.2 returned no advisories in this
query. That observation is not a general safety guarantee.

The retained summary, full target coverage, normalized findings and comparison
are explicitly classified as an uncommitted-candidate, OSV-only diagnostic.
Their E2 commit field is the source base; they cannot serve as exact-head E4 or
all-scanner acceptance. No advisory, historical review or suppression record
was rewritten. Full natural exact-head CI and downstream review remain required.

## Compatibility and verification

Permanent tests verify source pins, reject duplicate/malformed/module/profile
changes, and inspect all 26 actual effective POMs. Goal-specific checks execute
actual Site help and local rendering in a disposable fixture that inherits the
candidate POM. They verify its effective model before execution and inspect
Maven's real class-realm list, preventing an old installed parent from silently
substituting dependencies. Markdown, raw HTML and nested XML output are checked.
Dependency 3.11.0 tree and plugin-resolution goals are executed as well.

A small Java probe loads only the actual Site realm, verifies the jsoup JAR code
source, and exercises malformed HTML, cleaning, 2,048-level bounded XML,
256-level namespace declarations, shadowing, prefix inheritance/rebinding,
cloning and serialization. It uses a 96 MiB heap and 30-second timeout. This is
bounded compatibility evidence, not exploitability analysis or a complete
vulnerability reproducer. No Site server, deployment or release goal is run.

The all-reactor build-only install requires both `-DskipTests` and the existing
`-Dapproval.persistence.tests.skip=true`. A first run with only `-DskipTests`
still executed the explicitly configured JDBC tests and failed without Docker;
that failed run is retained and is not counted as test success. The corrected
build-only invocation passed all 26 projects. An overly broad repackage attempt
including library modules correctly failed for a missing main class; the
application-only package/repackage check passed using the existing
`product-readiness-demo` profile. That profile flattens CI-friendly versions
for installed reactor POMs; an earlier application-only attempt without it
failed resolving the literal `${revision}` parent. Both failed commands remain
diagnostic records, and no source or check was weakened to obtain success.
Docker-backed acceptance is reserved for the required natural CI.

Rollback is the bounded POM/descendant integration revert. Historical evidence
stays immutable. These checks grant neither release readiness nor merge or
deployment authorization.

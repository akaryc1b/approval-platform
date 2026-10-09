# Maven Release plugin realm remediation

## Bounded change

The accepted source is `1aecc248d963556a9c00efbc55dd002cb7bc0a75`, with natural
CI #1817. Pin the inherited Maven Release plugin from 3.0.1 to 3.3.1 in root
`pluginManagement`. No release goal is bound to any lifecycle. Retain Site
3.22.0, Dependency 3.11.0, Site-local jsoup 1.23.2 and the Boot plugin's Jackson
3.1.7 dependencies unchanged.

The actual accepted owner report assigns 11 OSV records to Release 3.0.1.
Owner counts overlap because the same component may appear in several plugin
realms; they are not nested dependency paths or independently additive totals.
Build-only components remain scanner inputs and unresolved findings require
review regardless of scope.

Release 3.3.1 alone still selects old JGit/SSHD, Commons IO and Plexus versions.
The root plugin has exactly these eight plugin-local dependencies:

- JGit core and Apache SSH support: 5.13.5.202508271544-r
- SSHD OSGi, SFTP, core and common: 2.16.0
- Commons IO: 2.20.0
- Plexus Utils: 4.0.3

No project dependency, imported BOM, unrelated owner or scanner collection goal
is changed. EdDSA 0.3.0 remains present; no nonexistent version is substituted
and its advisory is not dismissed.

## Official compatibility basis

The [Release plugin requirements](https://maven.apache.org/maven-release/maven-release-plugin/plugin-info.html)
are Maven 3.6.3 and Java 8 minimum. The exact descriptor in the checksum-verified
3.3.1 binary agrees. The project uses verified Maven 3.9.16 and a complete Java
21 JDK.

The published SCM 2.2.1 JGit provider POM explicitly manages SSHD OSGi/SFTP to
2.16.0 for its integration-test compatibility, as shown in the
[official dependency-management report](https://maven.apache.org/scm/maven-scm-providers/maven-scm-providers-git/maven-scm-provider-jgit/dependency-management.html).
That dependency management does not propagate to consumers of the provider.
Explicit plugin-local alignment restores this family in the Release realm.
JGit stays on its 5.13 patch line rather than crossing a major API boundary.
Commons IO follows the same SCM release's managed version; Plexus Utils stays
on its patched 4.0 line. These are compatibility choices, not assertions that
a version is universally safe.

Exact official POM/JAR URLs, published checksums, byte sizes and independent
SHA-256 hashes are in `release-plugin-evidence/official-artifact-provenance.json`.
The all-reactor Maven resolution and actual loaded realm are authoritative for
what this project uses; the upstream descriptor alone is not substituted for
the effective realm.

## Strict graph lineage

Actual 26-project Maven resolution produces:

- 237 project/runtime components, 345 edges, 26 roots and six BOMs, unchanged
- All 16 other plugin-owner inventories unchanged
- 282 → 272 distinct plugin coordinates: 26 additions and 36 removals
- Plugin report SHA-256 `2cbd1cd9f5fbfabab0b2ce7f8a2c7f5fe3f9bd43bf91b3db1bb444b513a70fcb`
- Candidate graph `7a719e7a07e9c7c51c5dddc228f4f00cb89ec7a62b4731067d2d977bc72ebc63`
- 486 → 475 exact OSV targets, including 25 added and 36 removed targets

The typed delta reverses only declared plugin-coordinate set changes and the
report hash. It must reconstruct the exact previously accepted Site/Dependency
graph. All prior Site, Jackson, server, OTel and foundation receipts remain
unchanged and are preserved in the new receipt. R3A, R4 and E4 admit this exact
descendant while retaining their existing advisory/runtime contracts. Unknown
graph changes, stale heads, incomplete target coverage and modified captures,
manifests or receipts fail closed. Graph admission does not dispose of findings.

The retained candidate E2 and owner report describe an uncommitted-POM capture,
with the accepted source base in the commit field. They are explicitly not
clean exact-head scanner or CI evidence. A fresh complete exact-head E4 is
required after publication, and release remains blocked.

## Actual OSV-only diagnostic

On 2026-10-09, pinned OSV 2.5.0 queried the same 475 exact
ecosystem/name/version targets derived from the retained candidate E2. The
input SHA-256 is
`29fb9b1a286d58d08550977676fca28115e00e202f5b4aa66b2cb6b5b0636ff8`.
The unchanged repository normalizer and complete-coverage validator verified
all 475 targets, including non-finding and newly added targets. Only package
identities were supplied to `https://api.osv.dev`.

The actual query returned 25 findings across 22 exact package targets. Direct
comparison with accepted natural CI #1817 E4 gives 33 → 25: eight records
removed, no added or changed retained records, and 25 byte-identical retained
records. The recovered prior E4 content digest, source head, E2 binding and
full prior OSV coverage were independently checked. The eight removed records
belong to old JGit, SSHD and Commons Lang targets; these are record reductions,
not eight distinct advisories or vulnerability review dispositions.

All 25 remaining records are build-plugin findings and remain unresolved.
EdDSA 0.3.0 still returns GHSA-p53j-g8pw-4w5f. Neither build-only scope nor an
upgraded version is treated as a general safety guarantee or a suppression.

The redacted summary, complete target coverage, normalized findings and direct
accepted-E4 comparison are in `release-plugin-evidence/osv-diagnostic/`.
Actual raw query output and execution diagnostics are retained locally; public
files omit raw vulnerability prose, source code, credentials and personal
data. Scanner installation used the baseline-checksummed official Go 1.26.5
archive and exact OSV module version, whose origin matches the pinned source
commit.

This is a new OSV-only query of an unchanged retained precommit capture. Its
E2 commit field remains the capture's source base, and it is not fresh full
Maven resolution, clean exact-head E4, all-scanner acceptance or release
clearance. The recovered candidate source head/tree are recorded separately.
Fresh natural exact-head CI and explicit finding review remain required.

## Verification boundaries

Source/model tests enforce the exact pin and local dependencies, reject
malformed/duplicate/module/profile overrides and inspect all 26 real effective
POMs. Compatibility checks use the actual read-only `release:help` goal and
inspect its loaded class realm. Local Java API probes are confined to a
disposable local fixture; no production remote, credential or repository is
used. No release preparation, tag, branch, deployment or SCM push is executed.

All-reactor build-only checks use `product-readiness-demo`, `-DskipTests` and
`-Dapproval.persistence.tests.skip=true`; these are build checks, not test
success. Application-only package/repackage checks avoid invoking an
application goal on library modules. Docker-backed test acceptance belongs to
natural required CI. Local security preflight is separate from exact-head E4.

Rollback is a bounded revert of the POM/descendant integration. Historical
artifacts remain immutable. This phase grants no merge, deployment or
production-readiness authorization.

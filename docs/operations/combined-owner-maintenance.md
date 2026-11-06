# Combined owner maintenance

This candidate updates build-plugin private realms only: Assembly 3.8.0,
Checkstyle-local Archiver 4.10.4, Aircompressor 2.0.3 in both owners, and
BeanUtils 1.11.0 in Site, Checkstyle and Dependency. Runtime dependencies,
all Plexus Utils coordinates, pnpm and workflow configuration are unchanged.
Rollback is reverting this owner-only POM change and its exact continuation.

## Fresh recovery verification (2026-10-26)

The root POM matches SHA-256
`9e91a467ef498a2b20c6cd971a336ea643d9c911f6dff9c651d07742ad778b7b`.
This is a fresh recovery, not restoration of the lost earlier 52-file candidate.
Independent reviews verified the seven source edits against the accepted parent.

Actual Maven 3.9.16 / Java 21 commands captured 26 normal and 26
`product-readiness-demo` effective models, 17 owner realms and the complete
runtime graph. Exactly four owners change; 13 remain unchanged. The original
invalid `-Pdemo` invocation was rejected as evidence and corrected, not counted
as a pass. Captured source/model summaries and exact owner reports are pinned in
`combined-owner-evidence/capture-manifest.json`.

Ordinary standalone fixture executions passed:

- Assembly `single`: ZIP, TAR.GZ and TAR.SNAPPY contain the same exact normal file.
- Site `site`: actual HTML rendered with an explicit fixture skin.
- Dependency `tree` and `build-classpath`.
- Checkstyle `check` and `checkstyle`; an ordinary star-import violation is rejected.
- Aircompressor framed round trip and decoding the produced Snappy archive.
- BeanUtils populate, string-to-integer conversion, copy, property get and set.

The direct Java harness is `scripts/tests/fixtures/CombinedOwnerNormalApis.java`.
Its arguments are a well-formed TAR.SNAPPY input and a disposable decoded TAR
output. Initial fixture XML, descriptor and skin setup failures were retained
locally; only corrected executions support the results above. These are normal
compatibility checks, not vulnerability reproduction.

## Acceptance boundaries

The captured E2 graph is
`9b7e635903e6011c24aa92bf7ab6638dd698d62fd8a4c75505e2455b42f0c774`,
with 457 exact package targets. The independently approved 464-target Utils
query is **not** scanner coverage for this graph. Fresh standard CI scanner and
full callback acceptance remain required. Modeled callback tests do not claim
an external query or absence of new advisories.

Historical source views are explicitly labeled and separately authenticated.
They do not substitute old dependencies for current runtime verification.
All findings remain retained and release blocking stays enabled. Restricted
Plexus vulnerability reproduction and paused NPE probes are not run. No
production deployment, credential rotation or access changes are included.

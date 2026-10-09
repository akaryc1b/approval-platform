# Spring Boot Maven plugin Jackson remediation

## Bounded change

The accepted `28a9db4cf6372793ce32801d9812ab688d348b02` graph from natural CI
#1812 contains 70 OSV findings across 42 package/version targets, all carrying
`build-plugin` scope. Scope alone is not a disposition.

The root POM now overrides only `tools.jackson.core:jackson-core` and
`tools.jackson.core:jackson-databind` inside the managed
`org.springframework.boot:spring-boot-maven-plugin:4.0.8`, from 3.1.5 to 3.1.7.
Both use the already reviewed `${jackson3-bom.version}` property. Maven project
BOM management does not manage a plugin's isolated dependency realm. The Boot
plugin and all project/runtime dependency versions remain unchanged.

This is the smallest coherent follow-on to the runtime Jackson remediation:
it removes the two obsolete plugin coordinates while preserving every runtime
component, runtime edge, reactor root, imported BOM and other plugin coordinate.
It does not upgrade Site, Release, Checkstyle or the Boot plugin's HTTP libraries.

## Actual owner and goal analysis

The retained full `dependency:resolve-plugins` report has SHA-256
`e9a095fee4fdbf3293833511950288c1932fbafc492799ffd3fa7468bd02b072`, equal to
#1812's E2 plugin-resolution digest. It is an earlier local capture with exactly
the accepted coordinate set, not a newly fabricated #1812 raw artifact. It
places both 3.1.5 Jackson dependencies under the Boot Maven plugin. The published
[Boot buildpack POM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-buildpack-platform/4.0.8/spring-boot-buildpack-platform-4.0.8.pom)
confirms the path through `spring-boot-buildpack-platform:4.0.8`.

Inspection of checksum-verified official Boot 4.0.8 source JARs establishes:

- `BuildImageMojo.execute()` creates the buildpack `Builder` and calls its build
  method. The buildpack client parses Docker responses, image metadata and local
  Docker configuration with `SharedJsonMapper`, `JsonStream` and `MappedObject`.
- `JsonStream` and the image metadata reader use `InputStream` parsing. The
  shared mapper does not enable default typing in its factory configuration.
- The repository's demo launcher invokes `spring-boot:run`. No `build-image`
  invocation or lifecycle binding was found in first-party POMs, workflows or
  scripts at the accepted base. The inspected run/repackage implementations do
  not call the buildpack JSON entry points above.
- The plugin realm is still a real build input. Users can invoke additional
  goals, and this inspection does not prove every affected API unreachable.
  None of the seven findings is dismissed as automatically inapplicable.

Official source archives:
[Boot Maven plugin sources](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-maven-plugin/4.0.8/spring-boot-maven-plugin-4.0.8-sources.jar),
[Boot buildpack sources](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-buildpack-platform/4.0.8/spring-boot-buildpack-platform-4.0.8-sources.jar).

## Advisory-specific conditions

The maintainer advisories identify different prerequisites. The patch removes
the vulnerable versions rather than treating the common plugin owner as proof
of those prerequisites.

| Advisory | Relevant condition | First fixed 3.1 release |
| --- | --- | --- |
| [GHSA-7hhh-6rmp-j9qf](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf) | Malformed token through the DataInput parser | 3.1.7 |
| [GHSA-p6pp-m3f8-5c89](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89) | Adversarial numeric text through the number-validation path | 3.1.7 |
| [GHSA-cxp5-3px4-pw24](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24) | Identity-enabled collections/maps with reverse-resolved object references | 3.1.7 |
| [GHSA-wv8q-qhhj-9h54](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54) | Reused name-based polymorphic deserializer with a fallback and many unknown IDs | 3.1.7 |
| [GHSA-gx83-3vf8-gh7j](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-gx83-3vf8-gh7j) | Polymorphic Comparable property without a suitable explicit validator | 3.1.6 |
| [GHSA-q4xh-88c3-wmh7](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-q4xh-88c3-wmh7) | Oversized strings bound to XML Duration/XMLGregorianCalendar | 3.1.6 |
| [GHSA-wjgm-6hv5-3cvf](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf) | Untrusted Path URI plus a side-effecting installed filesystem provider | 3.1.6 |

The [3.1.7 release notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7)
confirm the maintenance release; it includes the 3.1.6 fixes.

## Verification and evidence boundaries

Actual Java 21 / Maven 3.9.16 resolution changes only the two plugin coordinates
and the raw plugin-report digest. The new report SHA-256 is
`39b63f7f24afa2aca54cde7698d1ee76c0d5107f203faa301084b3c493f695cf`.
The narrow descendant transition retains the prior server graph and historical
receipts. Source/effective-POM contracts reject missing, duplicate, downgraded or
module-overridden plugin dependencies.

The permanent CI-only compatibility probe resolves the actual Boot plugin
classpath, verifies the loaded Jackson JAR code sources, exercises Boot's shared
mapper, streaming Docker JSON and image metadata parser, and checks that a
malformed DataInput token produces a bounded error. It makes no Docker request,
does not build an image, and is not an exploitability or full application test.
The all-reactor effective-POM check covers 26 projects. A bounded local
negative control using the old 3.1.5 JARs fails this token-limit assertion
(32,981-character error); the candidate 3.1.7 produces a 471-character error.
This demonstrates the regression check distinguishes the old and fixed parser,
without claiming that this DataInput path is used by the repository build.

An actual standalone OSV diagnostic used all 533 candidate targets. A canonical
set comparison proved zero additions to the already approved 535-target dataset;
only the two 3.1.5 targets were removed because 3.1.7 was already present in the
runtime inventory. Coverage was complete. Results were 63 findings, exactly the
previous findings minus the seven above; all other normalized records were
unchanged. Both 3.1.7 targets returned no advisories. This diagnostic used the
uncommitted candidate POM and is explicitly not an exact-head E4 acceptance.

Local verification also passed a build-only 26-project install and executable
JAR repackaging. An initial isolated `spring-boot:repackage` invocation correctly
failed because `package` was not in the same lifecycle; the corrected
`package spring-boot:repackage` invocation passed. No plugin or dependency check was removed
to make that packaging goal succeed. `-DskipTests` on these packaging checks
means they are build-only evidence, not a full runtime acceptance.

Natural exact-head CI and full scanner/downstream receipt acceptance remain
required after publication. Docker-backed integration tests and a Docker image
build were not run locally. The remaining 63 plugin records and other release
blockers remain open; this change does not grant release clearance.

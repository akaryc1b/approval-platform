# Compiler-owned Commons IO continuation

The root Maven Compiler Plugin stays at 3.14.0. Its sole added dependency is
Commons IO 2.20.0, using `maven.compiler.commons-io.version`. Application dependencies,
the Compiler configuration, and every other plugin owner retain their accepted
values. The independent RuoYi overlays remain unchanged.

The retained actual offline capture resolves 17 plugin owners across 413 owner
occurrences. Compiler alone replaces IO 2.11.0 with 2.20.0. The global plugin union
therefore drops from 271 to 270 coordinates; IO 2.20.0 already belongs to other
owners. All 237 application components, 345 edges, imported BOMs, scopes, licenses,
and 26 reactor identities remain exact. Every effective model matches accepted
main after reversing only the Compiler IO property/dependency additions and
normalizing the two checkout prefixes. The host SDK retains its Java 17 target;
the other projects retain Java 21.

`compiler-plugin-transition.json` binds these observations to the exact accepted
main `0c55ff5e7d974022e5c76d9a88a4312d2af81827` and the complete preserved natural-main
Clean/Release lineage. The original Clean source witness and every older evidence
file remain unchanged. Its successor validates the exact new root POM bytes,
reverses the two exact additions, and requires the original bytes. Source/model
contracts similarly reverse only validated Compiler additions before the original
Release/Clean checks. The candidate graph's 473 OSV targets are an input inventory,
not a finding count or a completed scan.

## Actual normal-CI regression coverage

The existing automatic boundary test imports the Compiler tests. Under ordinary
`GITHUB_ACTIONS=true`, the live test cannot silently skip. Local execution requires
`COMPILER_PLUGIN_COMPATIBILITY=true` and an explicit reviewed Maven repository;
local resolution is always offline. The runner checks Maven 3.9.16 and JDK 21,
uses isolated settings/environment and fresh disposable projects, bounds process
groups, output and execution time, and retains its raw evidence directory. It
does not invoke a repository lifecycle, install, deployment, SCM or browser goal.

The fixture parent copies the validated Compiler declaration and pins directly
from current source. Versionless fully qualified `compile` and `testCompile`
goals prove inheritance. Tests exercise:

- Main and dependent test output, Java 21 records, UTF-8 content and parameter names.
- Repeated unchanged compile/testCompile with unchanged class timestamps.
- Changed main and test behavior, saved input/output lists, added source/nested
  classes, and removal of stale tracked output after deleting the source.
- Expected nonzero invalid main/test compilation with actual compiler diagnostics.
- Space/Unicode paths, and an explicit bounded test processor producing separately
  executed main and test generated classes in their respective output directories.
- All 26 actual effective models with retained Compiler/Clean/Release contracts.

A passive Java agent observes actual class definitions without loading or
transforming them. JVM class-load records corroborate its origins. Compiler's
resolved owner has 14 artifacts; its private plugin realm has 12 JARs, because
Maven supplies the `javax.inject` and `slf4j-api` imports. The runner checks exact
realm membership and artifact hashes, including unchanged Shared Utils 3.4.2.
Observed incremental goals load Shared Incremental and relevant Shared Utils
classes. Ordinary compilation in the local cases did not naturally load Commons
IO classes; absence is not a non-reachability or applicability claim.

The separate 63-assertion Java probe intentionally constructs an isolated loader
from the actual observed Compiler realm. It verifies JAR bytes and class origins,
Shared FileUtils multi-buffer equal/unequal content, IOUtils short reads with
under-reported `available()`, and Shared Utils XML reader/factory/writer APIs with
ordinary UTF-8/UTF-16 BOMs and local paths/URLs. These are explicit API linkage and
behavior checks, not proof those IO APIs ran during Compiler goals.

Static inspection of all 14 accepted owner artifacts found direct IO calls only
in four Shared Utils classes. All 12 referenced IO method/constructor descriptors
remain present in 2.20.0, with selected inheritance and Java 8 base bytecode
preserved. Apache's [Compiler dependency report](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.14.0/dependencies.html)
and [plugin dependency configuration guide](https://maven.apache.org/guides/mini/guide-configuring-plugins.html#using-the-dependencies-tag)
describe this isolated dependency mechanism. [Commons IO's advisory](https://commons.apache.org/proper/commons-io/security.html)
lists CVE-2024-47554 for versions below 2.14.0; this context does not substitute for
fresh complete scanner evidence and explicit finding review.

## Evidence limits and rollback

Archived capture receipts are actual local precommit observations with collector
and generator/input witnesses. They are explicitly not final committed-source CI,
scanner acceptance, reviewer acceptance or production authorization. Synthetic
coverage/callback tests do not execute scanners or assert real vulnerability
absence. The previous public Git-reference proof and historical decisions remain
unchanged, and `releaseBlocked` stays true.

Regression coverage is Linux in-process javac with explicit bounded fixtures. It
does not assert Windows/forked-javac behavior, every annotation processor, or
exhaustive Commons IO compatibility. A rollback requires reverting the complete
Compiler successor change together, preserving historical evidence and rerunning
normal verification; deleting or editing old scanner evidence is not a rollback.

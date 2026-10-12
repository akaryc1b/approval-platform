# Checkstyle Guava compatibility

The local offline comparison passed for Guava `31.0.1-jre` to `32.0.1-jre` in Maven Checkstyle Plugin `3.6.0`, with Checkstyle `9.3` and Commons Lang `3.18.0` retained. Both variants passed the same 15 check/report goal cases, the four actual external Guava member references, 13 Checkstyle consumer behavior assertions, and three negative controls. This is bounded local compatibility evidence. It is not canonical CI evidence, scanner evidence, release authorization, or a claim about vulnerable API reachability.

## Retained results

- [model-verification.json](model-verification.json) is the unmodified actual Guava model-stage receipt. Each side captured the 26 projects in the normal reactor. The commands did not explicitly activate profiles. The two host overlay POMs were source-guarded; they were not separately executed through Maven. Activated-profile comparisons belong to the separate synthetic model tests.
- [compatibility-verification.json](compatibility-verification.json) records both Guava variants, exact artifact and source hashes, goal outcomes, natural class origins, member and behavior hashes, the retained-receipt assertion audit, historical Lang acceptance, and declared license/class-file metadata.
- [The separate graph capture](README.md) establishes the actual owner delta. Its capture-only manifest is unchanged. [The complete local OSV diagnostic](osv-diagnostic/README.md) supplies the separate version/advisory comparison.

The shared runner has an explicit `lang` or `guava` selector. It retains the historical Lang transition by materializing the exact accepted Lang root POM before its own exact reversal. The accepted Lang evidence files remain unchanged. Fresh Lang execution also passed both 26-model captures, 15 goals per variant, 58 external member references, 24 behavior assertions, and its three negative controls.

The six actual Python stages ran sequentially. The unchanged four Node live-integration assertion bodies then checked the retained actual receipts. The full Node live test runner was not executed locally; its natural CI wiring remains in place. Ten named mocked-harness tests also passed, covering both transitions, cold-cache preparation, selection, origins, source materialization, and containment. Actual local stages used the already verified offline cache; network/cold-cache preparation was exercised by mocks only.

## Actual private realm and goals

Each natural goal observed 54 private realm artifacts and the existing Maven API import. Guava changes exactly four coordinates in that realm; the other 50 coordinates and JAR bytes match:

| Artifact | Baseline | Candidate |
| --- | --- | --- |
| `com.google.guava:guava` | `31.0.1-jre` | `32.0.1-jre` |
| `org.checkerframework:checker-qual` | `3.12.0` | `3.33.0` |
| `com.google.errorprone:error_prone_annotations` | `2.7.1` | `2.18.0` |
| `com.google.j2objc:j2objc-annotations` | `1.3` | `2.8` |

The 15 preserved cases cover passing sources, unchanged cache, changed content with the same timestamp, changed/restored/deleted main sources, missing final newline, tabs, wildcard/unused/illegal imports, test-source violations, invalid configuration, and passing/violating HTML reports. Expected rule counts, selected files, goal exit semantics, and HTML summaries match between variants. In particular, the report goal retains its default successful exit when its report contains a violation.

Passive observation during the natural invalid-configuration goal records `ModuleReflectionUtil`, Guava `ClassPath`, `ClassPath$ClassInfo`, and `ImmutableSet` in the actual selected plugin realm. Required class origins, exact private URL roster/order, JAR hashes, and independent JVM class-load output are checked. The goal fails for the expected missing module. This establishes natural failure-path discovery and selected-Guava ownership. **No successful custom-module natural Maven goal was run.**

## Consumer member and behavior replay

The separate replay loads the exact byte-verified, observed private JARs in their observed order, together with the observed Maven logging import. It inspects external class-file references to the selected Guava class inventory, fails on unknown target members or invocation kinds, and checks public member descriptors, static/instance form, and actual defining JARs. The only external consumer is Checkstyle `ModuleReflectionUtil`, with these four references:

| Symbolic owner/member | Descriptor | Use |
| --- | --- | --- |
| `ClassPath.from` | `(Ljava/lang/ClassLoader;)Lcom/google/common/reflect/ClassPath;` | static invocation |
| `ClassPath.getTopLevelClasses` | `(Ljava/lang/String;)Lcom/google/common/collect/ImmutableSet;` | virtual invocation |
| `ImmutableSet.stream` | `()Ljava/util/stream/Stream;` | virtual invocation, inherited public method |
| `ClassPath$ClassInfo.load` | `()Ljava/lang/Class;` | virtual method handle |

Both variants have the same reference-set hash `e2c5e81005d7620b6fa48d4157f9e7413b05f9f8e07176c7e461ac9af2d2b833` and invocation-kind hash `b1a75191bee01709fc1a6640aca183aa853d9d8c630188d053a96634a616feff`. The baseline/candidate replay inspected 8,650/8,678 external class files. Guava's own classes are excluded from that consumer count; the separately owned `failureaccess` classes are not misattributed to Guava.

Successful calls into the real `ModuleReflectionUtil` and `PackageObjectFactory` verify the exact eight import modules, duplicate package deduplication, empty and missing packages, nonrecursive discovery, the exact combined import/file-filter inventory, rejection of ordinary and abstract classes, three standard module-name spellings, and two repeated missing-short-name failures. These are 13 semantic assertions with the same behavior hash `7cc09a02a3ef749d7d5610bee3b3a773ba233b950e97c65fef24a0547b9301fe` on both versions. They are supplemental consumer replay, not proof that the successful third-party custom-module path ran inside a natural goal.

Missing Guava, the opposite Guava version, and a changed JAR hash are each rejected by both variants. Existing stage command budgets, command deadlines, output bounds, isolated settings/home/temp, offline boundaries, source seals, and owned Maven process-group cleanup remain in force. The 660/720-second stage budgets govern subprocess work; no new independently enforced whole-Python alarm is claimed.

## Version, JDK, and declared licenses

The [Guava 32.0.0 release](https://github.com/google/guava/releases/tag/v32.0.0) documents fixes for CVE-2020-8908 and CVE-2023-2976 and reports no binary-incompatible changes in the Guava artifact. The [32.0.1 release](https://github.com/google/guava/releases/tag/v32.0.1) corrects the Windows behavior introduced in 32.0.0. The JRE flavor preserves the existing Maven plugin dependency flavor; no Android artifact is substituted. The [tagged Guava POM](https://github.com/google/guava/blob/v32.0.1/guava/pom.xml) and [parent POM](https://github.com/google/guava/blob/v32.0.1/pom.xml) provide the upstream artifact metadata.

Those upstream sources and the separate complete OSV result establish fixed-version attribution. Local execution establishes the actual plugin consumer compatibility described above. It did not test Windows or temporary-file security behavior. The actual external Checkstyle consumer inventory contains none of the temporary-file APIs; that is not a general non-reachability or exploitability conclusion.

Both variants ran with Maven `3.9.16` and Java `21.0.12.1`; launcher, Java, javac, full captured Maven/JDK runtime, and artifact hashes were sealed. Actual target JAR class-file metadata is retained in the JSON: candidate Guava, checker-qual, and error-prone annotations have major version 52; candidate j2objc annotations has major version 51. Across the transition, error-prone annotations changes from major 51 to 52 and j2objc annotations from 49 to 51; Guava and checker-qual remain at 52. All fit the tested Java 21 runtime. Class-file compatibility alone is not a behavioral compatibility claim, and no other JDK was executed.

The actual POM declarations retain Apache 2.0 for Guava and error-prone annotations and MIT for checker-qual. For j2objc, the label changes from `The Apache Software License, Version 2.0` to `Apache License, Version 2.0`; its Apache URL and `repo` distribution value remain equal. The JSON preserves the exact before/after declarations and POM-chain hashes. This records declared metadata, not legal clearance or blanket byte equality of license fields.

The separate graph receipt's `licenseChangeCount=0` concerns unchanged E2 application-component metadata. It does not describe this plugin-POM declaration comparison or erase the j2objc label change.

## Retained failed attempts

The first Guava baseline probe failed because its immutable `Set.of` fixture argument rejected the `contains(null)` check used by the Checkstyle factory constructor. The correction wraps the identical package set in `LinkedHashSet`; all assertions and expected contents remain. The original probe and failed receipt remain retained and excluded from acceptance. Only both affected Guava fixture stages and the assertion audit were rerun.

Four earlier successful stages retain their original, unused Guava-probe hash. None compiled or invoked that probe, and all executed source bindings match the final source. The audit checks their exact original receipt hashes and this narrow reuse condition.

A separate synthetic outer-wrapper cleanup control failed in the restricted process namespace. Its owning tool session exited and the fixtures' known lifespan elapsed, but descendant cleanup was not observed or claimed. That wrapper was not used for actual compatibility execution. Direct stages used the existing runner's owned Maven-session cleanup, and their command receipts record complete capture and process-group cleanup.

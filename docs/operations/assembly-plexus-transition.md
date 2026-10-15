# Assembly-private Plexus Utils continuation

The root plugin-management override retains Assembly 3.7.1 and changes only its
private Plexus Utils dependency from 4.0.0 to 4.0.3. Assembly remains inactive.
The retained actual paired model and owner captures cover 26 default-profile
projects and 17 plugin owners. All other owners, runtime components and edges,
BOMs, scopes, licenses, and plugin configuration remain identical.

The actual graph removes one global coordinate because Plexus Utils 4.0.3 was
already present under another owner. The complete query input therefore contains
468 targets. Graph admission does not establish current finding absence,
compatibility acceptance, reviewer approval, or release approval.

## Accepted predecessor and current admission

The predecessor is accepted main1857, head
`9b03965b8c7f6015301fd3db729172cd0f2cca3b`, tree
`285fe452f197c222940b190f7d417bc1b55d5178`. Its original E2 and full JaCoCo/AntRun
lineage are retained in `assembly-plexus-evidence`. Their byte and canonical
hashes, the retained server-remediation receipt, and the inherited artifact
inventory have independent code-level pins. Candidate manifest sealing cannot
select different predecessor anchors.

The new route compares the exact current owner report and complete graph against
that predecessor, then rereads current source, candidate evidence, accepted
lineage, and inherited artifact bytes immediately before returning its receipt.
It neither caches successful validation between calls nor replays historical
semantic callbacks. The 137-file, 8,189,857-byte flat inventory matches the
predecessor's 138 admitted byte-read inputs with only `pom.xml` removed. That
current root is checked through the existing exact source-reversal chain, now
extended by the one Assembly addition; all 28 current POMs remain bound to the
capture. Historical evidence files retain their exact bytes.

Omitting historical interpretation is bounded to previously admitted immutable
inputs. Mutating those bytes, shortening or replacing the inventory, rewriting
internal hashes, or fabricating accepted-base provenance fails against the
independent anchors. Changes to current graph, source, or toolchain still fail
the current checks. A late edit before the final receipt also fails, and a
subsequent call validates freshly restored bytes.

R3A continues checking current runtime paths, exact Tomcat JAR identity, source
markers and plugin-owner evidence, while preserving its historical unresolved
HTTP review. Server remediation continues requiring a current exact-head,
complete four-scanner E4, complete OSV target coverage, fixed targets and actual
current advisory absence. The pinned accepted server receipt replaces only the
historical graph/input bridge. No scanner or release gate is waived.

## Observed Assembly compatibility

The [retained compatibility summary](assembly-plexus-evidence/compatibility-summary.json)
records twelve actual offline `maven-assembly-plugin:3.7.1:single` goals: six
cases for each Plexus version, using Maven 3.9.16 and JDK 21.0.12.1. The commands,
including observer compilation, took 24.796 seconds in the reviewed local run;
each goal had a 60-second limit and the complete fixture a 900-second limit.
Source, runtime and cached JAR/POM identities were unchanged afterward. Full
native receipts and logs were independently reviewed; public evidence retains
their hashes, normalized paths, artifact coordinates and loader roles.

Both variants naturally loaded 40 of the 55 defined cross-type consumers,
including 11 of the 12 direct Assembly consumers. Each goal separately resolved
all 55 effective consumers, 122 type-reference rows and 255 consumer-scoped
member-reference rows, including static/instance shape, access and descriptors.
These checks do not claim that every member executed. The
[member manifest](assembly-plexus-evidence/compatibility-members.tsv) retains the
exact checked rows. Maven supplies three inventoried XML consumer definitions
from its own Plexus 3.6.1 distribution: `Xpp3Dom`, `XmlPullParser` and
`XmlPullParserException`. Their actual defining bytes are checked separately
from the shadowed private XML 3.0.0 definitions. The private utility classes and
XML readers/builders retain their expected plugin origins. Actual JDK 21 loads
use the Java 10 `BaseIOUtil` and Java 11 `BaseFileUtils` multi-release entries.
The observer records natural loads before a fixture-local terminal-goal event
performs noninitializing symbolic checks through the existing consumer loaders.

The upgrade has one explicit upstream default-exclusion change; the archives
are **not byte-identical**. The real directory, ZIP, JAR and TAR outputs establish:

| Descriptor path and setting | Plexus 4.0.0 | Plexus 4.0.3 |
| --- | --- | --- |
| File set or dependency unpack, omitted/true defaults | Root and nested regular `.gitignore` included | Those regular files excluded |
| File set or dependency unpack, explicit false | All tested ignore files and `.git` content included | Identical selection and retained payloads/modes |
| Ordinary hidden files and children of `.gitignore` directories | Included | Included |

The fixture has eight removed prefixed files per format: root and nested
`.gitignore` in the file-set and unpack omitted/true outputs. For the two
file-set omitted/true `.gitignore` directories, directory/ZIP/JAR output
synthesizes the parent with mode `0755` instead of retaining its explicit
`0750` entry. TAR omits those two explicit directory entries while retaining
their child files. All other file bytes, modes, directory membership/modes,
fixed archive timestamps, filtered Unicode text, merged Plexus component XML
and JAR manifest are unchanged. The comparator permits only these enumerated
differences; extra removal, addition, metadata or payload changes fail.

Descriptor discovery remains identical: ordinary and nested XML, XML in an
ordinary hidden directory and XML beneath a `.gitignore` directory are found;
`.git` XML is excluded. A regular `.gitignore` does not match `**/*.xml`.
Missing/malformed main descriptors and missing/malformed component descriptors
retain their respective nonzero failure categories, attributable diagnostics
and absence of output artifacts. Discovery is tested independently of component
descriptors.

Outside the compatibility fixture, no checked-in Assembly invocation or Assembly
descriptor uses the changed defaults, and all 26 effective models preserve
Assembly's inactive state. This
supports unchanged repository-defined build paths, not every external
consumer. A future descriptor that intentionally needs these files can use the
options actually tested here, within the relevant file set or unpack options:

```xml
<fileSet>
  <!-- existing directory/output/include configuration -->
  <useDefaultExcludes>false</useDefaultExcludes>
</fileSet>
```

```xml
<dependencySet>
  <!-- existing dependency selection/output configuration -->
  <unpack>true</unpack>
  <unpackOptions>
    <useDefaultExcludes>false</useDefaultExcludes>
  </unpackOptions>
</dependencySet>
```

Explicit false also includes the tested `.git` content; it is not added to any
repository configuration by this change. Hosted CI enables the same offline
twelve-goal fixture, with at most two separate Assembly-owner preparations from
official Central when pinned cache entries are missing. Ordinary local test
imports remain inert unless the sealed fixture is explicitly selected.

## Validation and rollback

`assembly-plexus-graph-transition.test.mjs` checks actual retained graph
substitution, negative owner/runtime/scope/license mutations, source and ancestor
artifact mutations, independent trust anchors, final-read freshness, and the
current R3A/server boundaries. Existing JaCoCo source/model fixtures explicitly
reverse the Assembly addition before testing their unchanged predecessor model.
The aggregate registers Assembly graph, model, real-goal compatibility and
complete retained-query diagnostics alongside all existing gates.
The existing synthetic CI-callback harness also exercises the complete current
intake and triage chain with Assembly admission, plus seven unchanged refusal
cases. Its modeled scanner envelope is contract-test input, not scanner evidence.

Rollback reverts this bounded slice, including its exact private dependency
addition and new admission/evidence integration. The predecessor POM is retained
byte-for-byte; no global Plexus property, other plugin owner or runtime dependency
is involved. Rolling back restores the previously affected 4.0.0 coordinate and
does not resolve its advisory.

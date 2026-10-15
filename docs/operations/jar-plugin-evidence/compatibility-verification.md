# JAR Plugin 3.5.0 → 3.5.1 local compatibility

The paired local fixture passed all eight cases per version: selection and
default ignores, empty input, initial/unchanged/changed incremental builds,
main/classified/test attachments, the expected duplicate-main rejection, and
JAR → accepted Boot 4.0.8 repackage → executable main. The candidate commands
explicitly use JAR 3.5.1. All 21 subprocesses completed with complete capture
and process-group cleanup: 16 Maven commands, 28 JAR goals, two Boot goals,
three compilations and two executable-main markers.

This is local, precommit evidence from the worktree based on accepted
`f1c99eaa`. It is not a canonical CI result. The complete result, input seal,
exact command plan and receipt-consumer verification are retained alongside
this document; `compatibility-local-execution.json` binds their hashes and
the exact executed source closure.

Every paired archive matched decompressed member bytes, CRCs, modes, fixed
timestamps and manifest values. All six selection archives compressed the
11 MiB incompressible member to 11,537,856 bytes, above the source-backed
10,000,000-byte maximum backing-store offload threshold. The 64 KiB
compressible member compressed to 79 bytes. Default exclusion behavior was
equal across the pair: root/nested `.cvsignore` and `.gitignore` excluded;
`.gitattributes` and `.hgignore` retained. Explicit false retained all four.

Each actual native JAR realm resolved the five-consumer, 53-member symbolic
subset plus its absent-member negative control. Passive natural definitions
and their effective Java 21 multi-release entries were independently matched
to pinned artifact bytes and separate JVM class-load origins. Symbolic member
resolution does not assert invocation of every referenced member. Codec
behavior coverage is ZIP/JAR; no Snappy, tar.Snappy, XZ or Zstd execution is
claimed. The accepted Boot realm and both tiny executable-main outcomes were
observed without HTTP, sockets, Docker or production application startup.

The sampled 100 ms output guard observed a peak of 110,144,034 bytes and 206
entries against 256 MiB and 2,048-entry limits, with no violation. Each native
class-load file had a 4 MiB limit; the largest was 937,139 bytes. Sampling can
miss transient overshoot between scans. Source, runtime and cache seals were
unchanged after cleanup.

The first attempt is preserved separately. Its baseline selection and empty
cases passed, and all four completed subprocesses exited zero and cleaned up.
The next strict agent record was refused because a native JVM log line was
interleaved after an otherwise exact class hash. The correction uses separate
bounded native log files and keeps exact hash/origin validation. The successful
retry used a fresh output tree and independently reviewed seal.

The new hosted Node gate calls the same fixture through an Assembly-style
portable helper. It uses fixed Central settings and at most two bounded
owner-resolution commands before offline execution. This hosted preparation
was reviewed statically and was not executed locally. The frozen Node receipt
validator successfully consumed the actual local result with subprocess
execution stubbed and retained the complete 4,305,362-byte JSON log record.
Independent review found that initial Node reserialization rounded six
nanosecond timestamps above `2^53`. Emission now preserves the validated
original JSON lexemes, removing only literal formatting CR/LF/tab bytes.
The added large-integer regression and five existing retention checks pass;
Python independently confirms exact JSON equality, including all integers,
between the raw result and emitted record. The rejected earlier emission is
preserved in local evidence. `compatibility-consumer-source-continuation.json`
binds the original execution seal to the two corrected consumer/test sources;
the other nineteen executed source inputs are unchanged. This consumer-only
correction did not rerun Maven or alter the raw successful receipt.
Future hosted runs must retain their own full validated goal and preparation
receipts. Ordinary reactor `mvn verify` remains separate source packaging
evidence. These checks do not establish scanner clearance or release approval.

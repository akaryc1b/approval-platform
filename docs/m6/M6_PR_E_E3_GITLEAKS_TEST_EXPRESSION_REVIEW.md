# Append-only Gitleaks test-expression review

Natural CI #1806 at `a3271c7f60f6afe259f521bc27fe64caf87338a5` successfully
emitted canonical E4 `61409f8a66f2201c91cc054c7b6ebd84ee6043267675d56d02b14f46a1abcbd8`.
It contained 28 Gitleaks, 143 OSV, 2 Semgrep and 0 zizmor findings, 173 total.
The designer source appeared in Semgrep's 1603 scanned paths and its historical
prototype warning was absent. The job then correctly failed the old R2B
27-identity Gitleaks boundary. This document does not retroactively pass that run.

## Exact new finding and source proof

The added finding is
`d1585860c64fd87f3762e58910483b1bd6a495678f1353ae491c15b2bfa04bfb`,
from rule `vault-service-token`, in the historical commit above at
`scripts/tests/m6-pr-e-e3-designer-prototype-remediation.test.mjs:245`.
The pinned historical source blob is `c7ecb29d12a545adfccf2f1fe7b0520ea3ebdbe2`.
The plan binds its full-file SHA-256, exact line SHA-256, complete normalized
finding metadata, scanner version/source/asset, full-history mode and redaction.

The source is an arrow callback assigning a fixed repeated character to a test
object's digest property. The receiver identifier is `s`; the property identifier
is `currentE4CanonicalSha256`. It contains no credential literal. A fixed,
anchored source grammar validates that exact assignment without executing it.
The verifier requires the complete historical Git blob, not just a finding ID
or a self-declared digest.

The pinned [Gitleaks 8.30.1 rule](https://raw.githubusercontent.com/gitleaks/gitleaks/v8.30.1/config/gitleaks.toml)
recognizes the legacy Vault token spelling by a one-letter receiver-like prefix,
a dot and 24 alphanumeric characters. The property's digits mean the rule's
all-letter exception does not apply. This is normal scanner behavior matching
JavaScript source syntax. The positive source evidence supports only this
exact finding's `NOT_APPLICABLE` review.

The current callback receiver is renamed to avoid producing another historical
match. The token-shaped source expression is deliberately not copied into new
plans, tests or this document. The historical commit is retained unchanged and
remains included in the full-history scan.

## Retention and accounting

The new append-only review is separate from the frozen I2 and scanner-identity
contracts. The actual finding remains in E4, intake, and every current triage
stage. Its source metadata and `UNKNOWN` severity are unchanged. I2 attaches one
review with a canonical receipt, carried through I3 and I4. This is a reviewed
false positive, not a remediated or deleted historical scanner event.

The shared complete-E4 provenance verifier guards this review independently of
the prototype path: schema, canonical content, exact checkout/Head, all four
scanner inventories and current totals must agree. Serialized triage retains all
current identities and derives cumulative review counts from current review
evidence plus unique historical remediations; numeric-only credit is rejected.

R2B requires the original exact 27 identities plus this one source-proven
addition. Additional findings, copied expressions at other commits or locations,
missing historical identities, metadata drift and unreviewed scanner changes
fail closed. Counts are computed from the actual current findings, never by
removing the new finding from the scanner result.

If the same actual 173-finding inventory persists, the final accounting is:
4 not-applicable, 169 unresolved, 69 cumulatively reviewed and 64 historically
remediated. The extra reviewed item does not increase the remediation count.
Local replay of the unchanged #1806 E4 verifies this accounting under the new
logic; it is not a new natural-CI result. A fresh natural run at the corrected
Head remains required.

No suppression, ignore file, history rewrite, raw candidate retention, severity
downgrade or exception is added. Authoritative GitHub inventory remains unavailable.
No readiness, merge, deployment or release is authorized by this evidence.

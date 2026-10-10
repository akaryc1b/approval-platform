# Clean candidate evidence

The parent [graph transition](../clean-plugin-transition.md) binds the actual retained E2, exact owner report, uncommitted POM, source witness and accepted main #1834 predecessor. The source witness describes the original inventory/model collection and preserves its precommit limits. It does not claim model-review, compatibility or independent acceptance.

The appended [OSV diagnostic review package](osv-diagnostic/README.md) is an exact copy of the separately verified publication-safe package. Its [checksum manifest](osv-diagnostic/public-file-checksums.sha256) hashes to `c6b944a0b9b2e5d1048d1f952bd007bc81ddf5bbdfbbe837e2de22f3b7dcd77d` and covers eight files; the manifest is the ninth copied file. No raw reports, execution logs, credentials or transport files are included.

The actual uncommitted candidate received complete official OSV coverage for 474/474 targets, including 453 with zero findings. The [comparison with accepted main #1834](osv-diagnostic/comparison-to-main1834.json) records 25 to 23 findings: exactly the two Commons IO 2.6 identities were removed, all 23 retained normalized records are unchanged, and no findings were added. Accepted-main raw advisory bodies are unavailable, so this is equality of all retained normalized metadata, not raw advisory bodies.

The package remains standalone precommit OSV-only diagnostic evidence. It is not exact-head E4, an all-scanner result, a historical finding disposition, natural CI acceptance or release clearance. Its E2 commit field names the accepted source base; the POM was uncommitted. The original source witness and graph author record remain unchanged, and `releaseBlocked=true` persists.

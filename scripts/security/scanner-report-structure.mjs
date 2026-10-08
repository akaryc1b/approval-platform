const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const goSlice = value => value === null || Array.isArray(value);

// OSV v2.5.0 models/results.go: results and packages are non-omitempty Go
// slices (a native nil slice can be null); vulnerabilities is omitempty.
// These are structural checks, not package-level scan-completeness assertions.
export function requireOsvReport(raw) {
  requireValue(object(raw) && Object.hasOwn(raw, 'results') && goSlice(raw.results), 'OSV results array or native nil slice required');
  for (const source of raw.results || []) {
    requireValue(object(source) && Object.hasOwn(source, 'packages') && goSlice(source.packages), 'OSV packages array or native nil slice required');
    for (const entry of source.packages || []) {
      requireValue(object(entry) && object(entry.package), 'OSV package metadata required');
      requireValue(!Object.hasOwn(entry, 'vulnerabilities') || goSlice(entry.vulnerabilities), 'OSV vulnerabilities must be an array when present');
      for (const vulnerability of entry.vulnerabilities || []) {
        requireValue(object(vulnerability) && typeof vulnerability.id === 'string' && vulnerability.id.length > 0,
          'OSV vulnerability identity required');
      }
    }
  }
  return raw;
}

// Gitleaks v8.30.1 initializes its finding slice with make(..., 0), so a
// successful empty report is an actual JSON [] file, never a missing artifact.
export function requireGitleaksReport(raw) {
  requireValue(Array.isArray(raw), 'Gitleaks report array required');
  for (const finding of raw) requireValue(object(finding), 'Gitleaks finding object required');
  return raw;
}

// SARIF 2.1.0 section 3.14.23 requires [] for successful zero findings;
// missing/null results can denote failure to start or begin analysis.
export function requireZizmorReport(raw) {
  requireValue(object(raw) && raw.version === '2.1.0' && Array.isArray(raw.runs) && raw.runs.length > 0,
    'zizmor SARIF version and nonempty runs required');
  for (const run of raw.runs) {
    requireValue(object(run) && run.tool?.driver?.name === 'zizmor', 'zizmor SARIF tool identity required');
    requireValue(Array.isArray(run.results), 'zizmor successful SARIF results array required');
    requireValue(!Object.hasOwn(run, 'invocations') || Array.isArray(run.invocations), 'zizmor SARIF invocations must be an array when present');
    for (const invocation of run.invocations || []) {
      requireValue(object(invocation) && invocation.executionSuccessful !== false,
        'zizmor SARIF invocation failed');
    }
    for (const finding of run.results || []) {
      requireValue(object(finding) && typeof finding.ruleId === 'string' && finding.ruleId.length > 0,
        'zizmor finding rule identity required');
    }
  }
  return raw;
}

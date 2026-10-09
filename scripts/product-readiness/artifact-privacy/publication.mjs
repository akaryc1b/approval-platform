import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
export { appendPublicationEnvelope } from './transaction.mjs';

export const publicationPolicy = 'approval-mock-auth-publication-v1';
const sanitizer = fileURLToPath(new URL('./sanitize.py', import.meta.url));
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/u.test(value);
const safeReference = value => typeof value === 'string' && value.length < 1024
  && /^[a-zA-Z0-9_@./-]+$/u.test(value) && !value.split('/').includes('..');
const keys = (value, expected) => value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).sort().join(',') === [...expected].sort().join(',');

function validProvenance(value, path) {
  if (!path.endsWith('.zip')) return keys(value, ['policy']) && value.policy === publicationPolicy;
  return keys(value, ['schemaVersion', 'policy', 'traceSchemaVersion', 'credentialPlaceholder',
    'changedSources', 'changedAssertionDiagnostics', 'resourceReferencesRebound',
    'screenshotsAndFonts', 'transportSizes'])
    && value.schemaVersion === 1 && value.policy === publicationPolicy && value.traceSchemaVersion === 8
    && value.credentialPlaceholder === '[REDACTED-MOCK-AUTH]'
    && value.screenshotsAndFonts === 'byte-identical'
    && value.transportSizes === 'original observed sizes; resource hashes bind published bytes'
    && Number.isSafeInteger(value.resourceReferencesRebound) && value.resourceReferencesRebound >= 0
    && Array.isArray(value.changedSources) && value.changedSources.length <= 8192
    && value.changedSources.every(item => keys(item, ['path', 'originalSha256', 'publishedSha256', 'transformation'])
      && safeReference(item.path) && item.path.startsWith('resources/src@')
      && digest(item.originalSha256) && digest(item.publishedSha256)
      && item.transformation === 'credential redaction; published source is not the original source bytes')
    && Array.isArray(value.changedAssertionDiagnostics) && value.changedAssertionDiagnostics.length <= 8192
    && value.changedAssertionDiagnostics.every(item => keys(item, ['trace', 'recordIndex', 'callId', 'field',
      'transformation', 'assertionPredicateExpectedCriterionAndOutcome'])
      && safeReference(item.trace) && item.trace.endsWith('.trace') && safeReference(item.callId)
      && Number.isSafeInteger(item.recordIndex) && item.recordIndex >= 0
      && item.field === 'result.received.ariaSnapshot'
      && item.transformation === 'credential redaction in received DOM diagnostic only'
      && item.assertionPredicateExpectedCriterionAndOutcome === 'unchanged');
}

// Construct the entire sanitized batch before any envelope append or stdout write.
// The codec never emits raw input on failure, including malformed ZIP/JSON errors.
export function preparePublicationFiles(paths) {
  const result = spawnSync('python3', [sanitizer], {
    input: JSON.stringify({ paths }),
    encoding: 'utf8',
    shell: false,
    maxBuffer: 192 * 1024 * 1024,
    timeout: 120_000,
  });
  if (result.error || result.status !== 0) {
    throw new Error('EVIDENCE_PUBLICATION_REJECTED: unsupported or unsafe evidence');
  }
  let prepared;
  try {
    prepared = JSON.parse(result.stdout);
  } catch {
    throw new Error('EVIDENCE_PUBLICATION_REJECTED: invalid sanitizer response');
  }
  if (!keys(prepared, ['policy', 'files']) || prepared?.policy !== publicationPolicy || !Array.isArray(prepared.files)
      || prepared.files.length !== paths.length) {
    throw new Error('EVIDENCE_PUBLICATION_REJECTED: invalid sanitizer response');
  }
  let total = 0;
  for (const [index, file] of prepared.files.entries()) {
    if (!keys(file, ['size', 'sha256', 'base64', 'sanitization']) || typeof file.base64 !== 'string' || !Number.isSafeInteger(file.size)
        || file.size < 0 || file.size > 64 * 1024 * 1024
        || !digest(file.sha256) || !validProvenance(file.sanitization, paths[index])) {
      throw new Error('EVIDENCE_PUBLICATION_REJECTED: invalid sanitizer response');
    }
    const bytes = Buffer.from(file.base64, 'base64');
    total += bytes.length;
    if (bytes.length !== file.size || bytes.toString('base64') !== file.base64
        || createHash('sha256').update(bytes).digest('hex') !== file.sha256
        || total > 128 * 1024 * 1024) {
      throw new Error('EVIDENCE_PUBLICATION_REJECTED: invalid sanitizer response');
    }
  }
  return prepared.files;
}

// Exception messages can contain command output, URLs, parser snippets or paths.
// Detailed failures belong in the sanitized evidence, never an unfiltered CI log.
export function publicFailureDetail(error) {
  if (error?.message?.startsWith('EVIDENCE_PUBLICATION_REJECTED:')) {
    return 'EVIDENCE_PUBLICATION_REJECTED';
  }
  const names = new Set(['Error', 'TypeError', 'SyntaxError', 'RangeError', 'AssertionError', 'UsageError']);
  const name = names.has(error?.name) ? error.name : 'Error';
  return `${name}; details withheld from public log`;
}

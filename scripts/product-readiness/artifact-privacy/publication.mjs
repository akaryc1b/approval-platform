import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { writeSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
export { appendPublicationEnvelope } from './transaction.mjs';

export const publicationPolicy = 'approval-mock-auth-publication-v1';
const sanitizerRejectionCategories = Object.freeze([
  'REQUEST_INVALID', 'INPUT_READ_FAILED', 'ARCHIVE_INVALID', 'CONTENT_INVALID',
  'BINARY_INVALID', 'REFERENCE_INVALID', 'DISCOVERY_REJECTED', 'TRANSFORM_REJECTED',
  'IDENTITY_CHANGE', 'ASSERTION_CHANGE', 'RESOURCE_LIMIT', 'SANITIZER_INTERNAL',
]);
export const publicationRejectionCategories = Object.freeze([
  ...sanitizerRejectionCategories,
  'SANITIZER_PROCESS_FAILED', 'SANITIZER_TERMINATED', 'SANITIZER_PROTOCOL_INVALID',
  'SANITIZER_RESPONSE_INVALID',
]);
const sanitizer = fileURLToPath(new URL('./sanitize.py', import.meta.url));
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/u.test(value);
const safeReference = value => typeof value === 'string' && value.length < 1024
  && /^[a-zA-Z0-9_@./-]+$/u.test(value) && !value.split('/').includes('..');
const keys = (value, expected) => value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).sort().join(',') === [...expected].sort().join(',');

function rejectPublication(category, invalidResponse = false) {
  // The line is independent of any producer catch block or artifact publication.
  // Sink failures must not replace the existing constant publication exception.
  try {
    writeSync(2, `EVIDENCE_REJECTION_V1 category=${category}\n`);
  } catch { /* No raw fallback. */ }
  throw new Error(invalidResponse
    ? 'EVIDENCE_PUBLICATION_REJECTED: invalid sanitizer response'
    : 'EVIDENCE_PUBLICATION_REJECTED: unsupported or unsafe evidence');
}

function sanitizerRejection(result) {
  if (result.status !== 1 || result.stdout !== '' || typeof result.stderr !== 'string') {
    return 'SANITIZER_PROTOCOL_INVALID';
  }
  // Compare the complete protocol string, including its single final LF. Never
  // trim, parse arbitrary fields, or echo even a fragment of subprocess output.
  return sanitizerRejectionCategories.find(category =>
    result.stderr === `EVIDENCE_REJECTION_V1 category=${category}\n`)
    ?? 'SANITIZER_PROTOCOL_INVALID';
}

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
  let result;
  try {
    result = spawnSync('python3', [sanitizer], {
      input: JSON.stringify({ paths }),
      encoding: 'utf8',
      shell: false,
      maxBuffer: 192 * 1024 * 1024,
      timeout: 120_000,
    });
  } catch {
    rejectPublication('SANITIZER_PROCESS_FAILED');
  }
  if (result.error) rejectPublication('SANITIZER_PROCESS_FAILED');
  if (result.signal || result.status === null) rejectPublication('SANITIZER_TERMINATED');
  if (result.status !== 0) rejectPublication(sanitizerRejection(result));
  if (result.stderr !== '') rejectPublication('SANITIZER_PROTOCOL_INVALID', true);
  let prepared;
  try {
    prepared = JSON.parse(result.stdout);
  } catch {
    rejectPublication('SANITIZER_RESPONSE_INVALID', true);
  }
  if (!keys(prepared, ['policy', 'files']) || prepared?.policy !== publicationPolicy || !Array.isArray(prepared.files)
      || prepared.files.length !== paths.length) {
    rejectPublication('SANITIZER_RESPONSE_INVALID', true);
  }
  let total = 0;
  for (const [index, file] of prepared.files.entries()) {
    if (!keys(file, ['size', 'sha256', 'base64', 'sanitization']) || typeof file.base64 !== 'string' || !Number.isSafeInteger(file.size)
        || file.size < 0 || file.size > 64 * 1024 * 1024
        || !digest(file.sha256) || !validProvenance(file.sanitization, paths[index])) {
      rejectPublication('SANITIZER_RESPONSE_INVALID', true);
    }
    const bytes = Buffer.from(file.base64, 'base64');
    total += bytes.length;
    if (bytes.length !== file.size || bytes.toString('base64') !== file.base64
        || createHash('sha256').update(bytes).digest('hex') !== file.sha256
        || total > 128 * 1024 * 1024) {
      rejectPublication('SANITIZER_RESPONSE_INVALID', true);
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

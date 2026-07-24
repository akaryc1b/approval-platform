import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const coreRoot = path.join(
  root,
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core',
);
const spiRoot = path.join(
  root,
  'server-modules/approval-ai-spi/src/main/java/io/github/akaryc1b/approval/ai/spi',
);
const coreTestRoot = path.join(
  root,
  'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core',
);

function source(directory, name) {
  const file = path.join(directory, `${name}.java`);
  assert.equal(existsSync(file), true, `${name}.java must exist`);
  return readFileSync(file, 'utf8');
}

test('external secret resolver SPI cannot access or return secret material', () => {
  const resolver = source(spiRoot, 'AiExternalSecretResolver');
  const request = source(spiRoot, 'AiExternalSecretResolutionRequest');
  const result = source(spiRoot, 'AiExternalSecretResolutionResult');
  const production = `${resolver}\n${request}\n${result}`;

  assert.doesNotMatch(production, /System\.getenv|System\.getProperty|@Value\s*\(/);
  assert.doesNotMatch(production, /import\s+java\.net\.|HttpClient|WebClient|RestClient/);
  assert.doesNotMatch(
    production,
    /\b(String|byte\[\])\s+(secret|secretValue|token|password|privateKey|clientSecret)\b/,
  );
  assert.match(request, /cannot authorize secret material access/);
  assert.match(request, /cannot authorize network access/);
  assert.match(result, /must remain metadata-only and zero-call/);
  assert.match(result, /cannot authorize production enablement/);
});

test('DNS and TLS trust evidence remains precomputed and zero-call', () => {
  const dns = source(coreRoot, 'AiDnsResolutionEvidence');
  const tls = source(coreRoot, 'AiTlsPeerEvidence');
  const policy = source(coreRoot, 'AiEndpointTrustPolicy');
  const assessment = source(coreRoot, 'AiEndpointTrustAssessment');
  const production = `${dns}\n${tls}\n${policy}\n${assessment}`;

  assert.doesNotMatch(production, /import\s+java\.net\.|InetAddress|Socket|SSLContext/);
  assert.doesNotMatch(production, /System\.getenv|System\.getProperty/);
  assert.match(dns, /must be precomputed and zero-call/);
  assert.match(tls, /must be precomputed and zero-call/);
  assert.match(policy, /cannot authorize network access/);
  assert.match(assessment, /must remain offline and zero-call/);
  assert.match(assessment, /DNS_REBINDING_DETECTED/);
  assert.match(assessment, /REDIRECT_OBSERVED/);
  assert.match(assessment, /TLS_PIN_MISMATCH/);
});

test('kill switch and activation lease cannot grant execution authority', () => {
  const killSwitch = source(coreRoot, 'AiProviderKillSwitch');
  const lease = source(coreRoot, 'AiProviderActivationLease');

  assert.doesNotMatch(killSwitch, /\bENABLED\b|\bACTIVE\b/);
  assert.match(killSwitch, /DISABLED/);
  assert.match(killSwitch, /FAULT_DRILL_ONLY/);
  assert.match(killSwitch, /cannot authorize Provider or network access/);
  assert.doesNotMatch(lease, /\bACTIVE\b|\bGRANTED\b/);
  assert.match(lease, /NOT_GRANTED/);
  assert.match(lease, /cannot grant execution authority/);
  assert.match(lease, /cannot authorize production enablement/);
});

test('two-person activation review remains non-authorizing', () => {
  const review = source(coreRoot, 'AiProviderActivationReviewBundle');

  assert.match(review, /two distinct approved reviewers and roles/);
  assert.match(review, /REVIEW_COMPLETE/);
  assert.match(review, /activation review cannot authorize execution/);
  assert.match(review, /cannot authorize production enablement/);
  assert.doesNotMatch(review, /\.advise\s*\(/);
});

test('activation plan is permanently non-executable and cannot create a lease', () => {
  const plan = source(coreRoot, 'AiProviderActivationPlan');

  assert.match(plan, /NON_EXECUTABLE_REVIEW_ONLY/);
  assert.match(plan, /M6-D activation plans must remain non-executable/);
  assert.match(plan, /activation plan cannot authorize production or approval automation/);
  assert.match(plan, /AI_ENDPOINT_TRUST_BLOCKED/);
  assert.match(plan, /AI_ACTIVATION_LEASE_NOT_REVIEW_ONLY/);
  assert.doesNotMatch(plan, /\.advise\s*\(/);
  assert.doesNotMatch(plan, /System\.getenv|System\.getProperty|java\.net/);
});

test('all deterministic implementations remain test-only', () => {
  const deterministicResolver = path.join(
    coreTestRoot,
    'DeterministicExternalSecretResolver.java',
  );
  assert.equal(existsSync(deterministicResolver), true);

  const productionFiles = [
    source(spiRoot, 'AiExternalSecretResolver'),
    source(coreRoot, 'AiEndpointTrustAssessment'),
    source(coreRoot, 'AiProviderActivationPlan'),
  ].join('\n');
  assert.doesNotMatch(productionFiles, /class\s+DeterministicExternalSecretResolver/);
  assert.doesNotMatch(productionFiles, /implements\s+AiExternalSecretResolver/);
});

import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const moduleRoot = path.join(root, 'server-modules/approval-ai-openai');
const sourceRoot = path.join(moduleRoot, 'src/main/java');
const testRoot = path.join(moduleRoot, 'src/test/java');
const packageRoot = path.join(
  sourceRoot,
  'io/github/akaryc1b/approval/ai/openai',
);
const packageTestRoot = path.join(
  testRoot,
  'io/github/akaryc1b/approval/ai/openai',
);
const serverPomPath = path.join(root, 'apps/server/pom.xml');
const migrationRoot = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
);

const p6dProductionNames = [
  'OpenAiResponsesEndpointPolicy.java',
  'OpenAiResponsesHttpCodec.java',
  'OpenAiResponsesJdkSecureNetwork.java',
  'OpenAiResponsesNetworkSupport.java',
  'OpenAiResponsesRequestProfileValidator.java',
  'OpenAiResponsesSecureHttpSender.java',
  'OpenAiResponsesTransportAdmission.java',
  'OpenAiResponsesTransportControls.java',
  'OpenAiResponsesTransportException.java',
];
const acceptedOpenAiProductionNames = [
  'OpenAiEnvironmentCredentialMaterialSource.java',
  'OpenAiResponsesEndpointPolicy.java',
  'OpenAiResponsesHttpCodec.java',
  'OpenAiResponsesJdkSecureNetwork.java',
  'OpenAiResponsesNetworkSupport.java',
  'OpenAiResponsesProtocol.java',
  'OpenAiResponsesRequestEncoder.java',
  'OpenAiResponsesRequestProfileValidator.java',
  'OpenAiResponsesResponseDecoder.java',
  'OpenAiResponsesSecureHttpSender.java',
  'OpenAiResponsesTransportAdmission.java',
  'OpenAiResponsesTransportControls.java',
  'OpenAiResponsesTransportException.java',
  'OpenAiResponsesTransportPort.java',
].sort();
const p6dTestNames = [
  'OpenAiResponsesHttpFramingTest.java',
  'OpenAiResponsesSecureHttpSenderSecurityTest.java',
  'OpenAiResponsesSecureHttpSenderTest.java',
  'OpenAiResponsesSecureHttpSenderTestSupport.java',
  'OpenAiResponsesTransportAdmissionTest.java',
];

function filesUnder(directory) {
  if (!existsSync(directory)) return [];
  const output = [];
  for (const entry of readdirSync(directory)) {
    const absolute = path.join(directory, entry);
    if (statSync(absolute).isDirectory()) output.push(...filesUnder(absolute));
    else output.push(absolute);
  }
  return output;
}

function text(file) {
  return readFileSync(file, 'utf8');
}

function production(name) {
  return text(path.join(packageRoot, name));
}

function testSource(name) {
  return text(path.join(packageTestRoot, name));
}

function assertOrder(source, markers) {
  let previous = -1;
  for (const marker of markers) {
    const index = source.indexOf(marker);
    assert.ok(index > previous, `${marker} must retain P6-D ordering`);
    previous = index;
  }
}

test('P6-D freezes one exact endpoint, admission sequence and one-attempt sender', () => {
  for (const name of [...p6dProductionNames, ...p6dTestNames]) {
    const directory = name.includes('Test') ? packageTestRoot : packageRoot;
    assert.equal(existsSync(path.join(directory, name)), true, `missing ${name}`);
  }

  const endpoint = production('OpenAiResponsesEndpointPolicy.java');
  const failure = production('OpenAiResponsesTransportException.java');
  const admission = production('OpenAiResponsesTransportAdmission.java');
  const controls = production('OpenAiResponsesTransportControls.java');
  const profile = production('OpenAiResponsesRequestProfileValidator.java');
  const sender = production('OpenAiResponsesSecureHttpSender.java');
  const network = production('OpenAiResponsesJdkSecureNetwork.java');
  const http = production('OpenAiResponsesHttpCodec.java');
  const support = production('OpenAiResponsesNetworkSupport.java');
  const port = production('OpenAiResponsesTransportPort.java');

  for (const required of [
    /SCHEME = "https"/,
    /HOST = "api\.openai\.com"/,
    /PORT = 443/,
    /PATH = "\/v1\/responses"/,
    /candidate\.getUserInfo\(\) != null/,
    /candidate\.getQuery\(\) != null/,
    /candidate\.getFragment\(\) != null/,
    /candidate\.normalize\(\)\.getRawPath\(\)/,
    /EXACT_URI\.toASCIIString\(\)\.equals\(candidate\.toASCIIString\(\)\)/,
  ]) assert.match(endpoint, required);

  for (const required of [
    /KILL_SWITCH_DISABLED/,
    /KILL_SWITCH_DRIFT/,
    /CIRCUIT_OPEN/,
    /RATE_LIMITED/,
    /COST_POLICY_STALE/,
    /COST_LIMIT_EXCEEDED/,
    /DNS_UNSAFE/,
    /DNS_DRIFT/,
    /CONNECTION_DRIFT/,
    /TLS_HOSTNAME_MISMATCH/,
    /TLS_CHAIN_INVALID/,
    /REDIRECT_REJECTED/,
    /RESPONSE_TOO_LARGE/,
  ]) assert.match(failure, required);

  for (const required of [
    /requireKillSwitch\(\)/,
    /circuitBreaker\.tryAcquire/,
    /rateLimiter\.reserve/,
    /costPolicy\.estimate/,
    /revalidateBeforeSecret/,
    /revalidateBeforeDispatch/,
    /markDispatched/,
    /maximumOutputTokens > 16_384/,
  ]) assert.match(admission, required);

  for (const required of [
    /Math\.multiplyExact/,
    /perTenantLimit/,
    /globalLimit/,
    /maximumTenants/,
    /tenantBuckets\.entrySet\(\)\.removeIf/,
    /HALF_OPEN/,
    /openDuration/,
  ]) assert.match(controls, required);

  for (const required of [
    /STRICT_DUPLICATE_DETECTION/,
    /REQUEST_FIELDS/,
    /"input_text"/,
    /"json_schema"/,
    /RESPONSE_FORMAT_NAME/,
    /maximumOutputTokens > 16_384/,
    /requireExactFields/,
  ]) assert.match(profile, required);

  assertOrder(sender, [
    'admission.admit',
    'secureNetwork.resolve',
    'secureNetwork.connect',
    'permit.revalidateBeforeSecret',
    'exchangeWithSecret',
    'TransportEvidence.verified',
  ]);
  const secretHelper = sender.slice(
    sender.indexOf('private ExchangeResult exchangeWithSecret'),
  );
  assertOrder(secretHelper, [
    'credentialSource.openLease',
    'lease.useMaterial',
    'permit.revalidateBeforeDispatch',
    'permit.markDispatched',
    'channel.exchange',
    'permit.record',
  ]);
  assert.match(sender, /implements OpenAiResponsesTransportPort/);
  assert.match(sender, /result\.statusCode\(\) >= 300 && result\.statusCode\(\) <= 399/);
  assert.doesNotMatch(sender, /for\s*\([^)]*attempt|while\s*\([^)]*retry|fallback/i);

  for (const required of [
    /SSLContext\.getDefault\(\)/,
    /setEndpointIdentificationAlgorithm\("HTTPS"\)/,
    /new SNIHostName\(endpoint\.host\(\)\)/,
    /"TLSv1\.3"/,
    /"TLSv1\.2"/,
    /InetAddress\.getAllByName\(endpoint\.host\(\)\)/,
    /new InetSocketAddress\(selected, endpoint\.port\(\)\)/,
    /selected\.equals\(plain\.getInetAddress\(\)\)/,
    /leaf\.checkValidity/,
  ]) assert.match(network, required);

  for (const required of [
    /Authorization: Bearer/,
    /X-Client-Request-Id/,
    /Content-Length/,
    /headers\.get\("transfer-encoding"\)/,
    /headers\.get\("content-encoding"\)/,
    /headers\.putIfAbsent/,
    /MAXIMUM_TRANSPORT_RESPONSE_BYTES/,
    /REDIRECT_REJECTED/,
  ]) assert.match(http, required);

  assert.match(support, /isPublicAddress/);
  assert.match(support, /first == 100 && second >= 64 && second <= 127/);
  assert.match(support, /first == 198 && \(second == 18 \|\| second == 19\)/);
  assert.match(support, /first == 0x20 && second == 0x02/);
  assert.match(port, /record TransportEvidence/);
  assert.match(port, /attemptCount < 0 \|\| attemptCount > 1/);
  assert.match(port, /expectedEvidence\.equals\(evidenceHash\)/);
});

test('P6-D grants network and Secret authority to one exact isolated path only', () => {
  const allProduction = filesUnder(sourceRoot).filter(file => file.endsWith('.java'));
  const openAiNamed = allProduction
    .filter(file => /openai/i.test(path.basename(file)))
    .map(file => path.basename(file))
    .sort();
  assert.deepEqual(openAiNamed, acceptedOpenAiProductionNames);

  const endpointLiteralFiles = allProduction
    .filter(file => /api\.openai\.com/.test(text(file)))
    .map(file => path.basename(file));
  assert.deepEqual(endpointLiteralFiles, ['OpenAiResponsesEndpointPolicy.java']);

  const networkImportFiles = allProduction
    .filter(file => /import\s+(?:java\.net|javax\.net\.ssl)\./.test(text(file)))
    .map(file => path.basename(file))
    .sort();
  assert.deepEqual(networkImportFiles, [
    'OpenAiResponsesEndpointPolicy.java',
    'OpenAiResponsesJdkSecureNetwork.java',
    'OpenAiResponsesNetworkSupport.java',
  ]);

  const authorizationFiles = allProduction
    .filter(file => /Authorization: Bearer/.test(text(file)))
    .map(file => path.basename(file));
  assert.deepEqual(authorizationFiles, ['OpenAiResponsesHttpCodec.java']);

  const leaseUseFiles = allProduction
    .filter(file => /\.useMaterial\s*\(/.test(text(file)))
    .map(file => path.basename(file));
  assert.deepEqual(leaseUseFiles, ['OpenAiResponsesSecureHttpSender.java']);

  const implementations = allProduction
    .filter(file => /implements\s+OpenAiResponsesTransportPort/.test(text(file)))
    .map(file => path.basename(file));
  assert.deepEqual(implementations, ['OpenAiResponsesSecureHttpSender.java']);

  const providerProduction = allProduction.map(text).join('\n');
  for (const forbidden of [
    /@Component\b/,
    /@Service\b/,
    /@Configuration\b/,
    /@Bean\b/,
    /@RestController\b/,
    /@PostMapping\b/,
    /@Scheduled\b/,
    /ApprovalAssistanceSynchronousOrchestrator/,
    /ApprovalAssistanceDurableEvidenceStore/,
    /JdbcTemplate/,
    /DataSource/,
    /\.(approve|reject|returnTask|transfer|withdraw|terminate|migrate|publish|activate)\s*\(/,
  ]) assert.doesNotMatch(providerProduction, forbidden);

  assert.doesNotMatch(text(serverPomPath), /approval-ai-openai/);
});

test('P6-D tests are deterministic, zero-egress and prove fail-closed ordering', () => {
  const tests = p6dTestNames.map(testSource).join('\n');
  for (const required of [
    /exactAdmissionIsSingleDispatchAndHashOnly/,
    /rateReservationRollsBackBeforeDispatchAndCommitsAfterDispatch/,
    /circuitOpensAndAllowsOnlyOneHalfOpenProbe/,
    /expiredTenantBucketsAreReclaimedWithoutUnboundingMemory/,
    /exactSenderUsesOneVerifiedChannelAndZeroizesScopedSecret/,
    /killSwitchDriftAfterTlsBlocksBeforeSecretLeaseAndDispatch/,
    /unsafeDnsEvidenceFailsBeforeTlsSecretAndDispatch/,
    /redirectsAreRejectedAfterExactlyOneAttempt/,
    /providerHttpFailureRemainsBoundedAndSingleAttempt/,
    /futureOrStaleDnsEvidenceFailsClosedBeforeTlsAndSecret/,
    /malformedApiKeyBytesCannotReachTheHttpHeader/,
    /nonTextOrNonStrictRequestProfileFailsBeforeNetwork/,
    /specialPurposeAddressClassesAreRejected/,
    /contentLengthResponseIsParsedExactly/,
    /chunkedFailureResponseRemainsBounded/,
    /duplicateOrAmbiguousLengthHeadersFailClosed/,
  ]) assert.match(tests, required);

  for (const forbidden of [
    /System\.getenv/,
    /api\.openai\.com/,
    /new\s+Socket\s*\(/,
    /InetAddress\.getAllByName/,
    /OPENAI_API_KEY\s*=/,
  ]) assert.doesNotMatch(tests, forbidden);

  assert.match(tests, /implements OpenAiResponsesNetworkSupport\.SecureNetwork/);
  assert.match(tests, /exchangeCount\.get\(\)/);
  assert.match(tests, /allZero\(fixture\.network\(\)\.lastSecret\)/);
});

test('P6-D remains unwired, migration-free and unable to start P6-E', () => {
  const versioned = filesUnder(migrationRoot).map((file) => {
    const name = path.basename(file);
    const match = /^V(\d+)__/.exec(name);
    return match ? { name, version: Number(match[1]) } : null;
  }).filter(Boolean);
  assert.deepEqual(versioned.filter(({ version }) => version >= 50), []);

  const applicationProduction = filesUnder(path.join(
    root,
    'apps/server/src/main/java',
  )).filter(file => file.endsWith('.java')).map(text).join('\n');
  assert.doesNotMatch(applicationProduction, /OpenAiResponsesSecureHttpSender/);
  assert.doesNotMatch(applicationProduction, /OpenAiResponsesTransportAdmission/);
  assert.doesNotMatch(applicationProduction, /approval-ai-openai/);

  const automaticWorkflows = filesUnder(path.join(root, '.github/workflows'))
    .filter(file => /\.ya?ml$/.test(file))
    .filter(file => /^\s{0,4}(pull_request|push):\s*$/m.test(text(file)))
    .map(file => path.basename(file));
  assert.deepEqual(automaticWorkflows, ['approval-platform-validation.yml']);
});

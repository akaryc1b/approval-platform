import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const auditPath = path.join(
  root,
  'docs/m6/M6_E_P6_OPENAI_PROVIDER_ACTIVATION_AUDIT.md',
);
const openAiProductionRoot = path.join(
  root,
  'server-modules/approval-ai-openai/src/main/java',
);
const openAiPackageRoot = path.join(
  openAiProductionRoot,
  'io/github/akaryc1b/approval/ai/openai',
);
const openAiSourcePath = path.join(
  openAiPackageRoot,
  'OpenAiEnvironmentCredentialMaterialSource.java',
);
const senderPath = path.join(
  openAiPackageRoot,
  'OpenAiResponsesSecureHttpSender.java',
);
const serverPomPath = path.join(root, 'apps/server/pom.xml');
const serverRuntimeConfigPath = path.join(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalAssistanceProductionConfiguration.java',
);
const migrationRoot = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
);
const productionRoots = [
  path.join(root, 'server-modules/approval-ai-spi/src/main/java'),
  path.join(root, 'server-modules/approval-ai-core/src/main/java'),
  openAiProductionRoot,
  path.join(root, 'apps/server/src/main/java'),
];
const restControllerAnnotation = /^[ \t]*@RestController\b/m;
const forbiddenProviderCouplings = [
  /ApprovalAssistanceSynchronousOrchestrator/,
  /ApprovalAssistanceDurableEvidenceStore/,
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

test('P6-A frozen profile remains exact after the later P6-E activation slice', () => {
  assert.equal(existsSync(auditPath), true, 'missing P6 OpenAI activation audit');
  const audit = text(auditPath);

  for (const required of [
    /P6_PROVIDER_SELECTED_IMPLEMENTATION_NOT_AUTHORIZED/,
    /vendor: `OPENAI`/,
    /provider ID: `openai-responses`/,
    /provider type: `REMOTE`/,
    /protocol profile: `OPENAI_RESPONSES_V1`/,
    /API operation: `POST \/v1\/responses`/,
    /endpoint host: `api\.openai\.com`/,
    /port: `443`/,
    /model snapshot: `gpt-5-mini-2025-08-07`/,
    /floating model aliases: prohibited/,
    /`store`: exactly `false`/,
    /`background`: exactly `false`/,
    /`stream`: exactly `false`/,
    /`tools`: empty/,
    /`tool_choice`: exactly `none`/,
    /`previous_response_id`: absent/,
    /`conversation`: absent/,
    /`text\.format\.type`: exactly `json_schema`/,
    /`text\.format\.strict`: exactly `true`/,
    /process environment variable: `OPENAI_API_KEY`/,
    /non-secret version evidence variable: `OPENAI_API_KEY_VERSION`/,
    /connect timeout: maximum `2 seconds`/,
    /total Provider timeout: maximum `15 seconds`/,
    /exactly one HTTP attempt/,
    /zero automatic retry/,
    /zero post-invocation fallback/,
    /CI must not read `OPENAI_API_KEY` and must not call `api\.openai\.com`/,
    /P6-A.*audit and permanent boundary/s,
    /P6-B.*Secret environment source/s,
    /P6-C.*request encoder/s,
    /P6-D.*DNS\/TLS\/SSRF/s,
    /P6-E.*invocation service/s,
    /P6-F.*incident runbook/s,
    /IMPLEMENTATION_NOT_AUTHORIZED_UNTIL_P6_A_ACCEPTANCE/,
  ]) {
    assert.match(audit, required);
  }

  for (const forbidden of [
    /second Provider is authorized/i,
    /redirects: allowed/i,
    /automatic retry: enabled/i,
    /post-invocation fallback: enabled/i,
    /client supplies.*(?:Provider|model|Secret)/i,
    /P6-A authorizes production OpenAI call/i,
  ]) {
    assert.doesNotMatch(audit, forbidden);
  }
});

test('accepted OpenAI production inventory remains exact', () => {
  const productionFiles = productionRoots
    .flatMap(filesUnder)
    .filter(file => file.endsWith('.java'));
  const openAiNamedFiles = productionFiles
    .filter(file => /openai/i.test(path.basename(file)))
    .map(file => path.relative(root, file).replaceAll('\\', '/'))
    .sort();
  const prefix = 'server-modules/approval-ai-openai/src/main/java/'
    + 'io/github/akaryc1b/approval/ai/openai/';
  assert.deepEqual(openAiNamedFiles, [
    `${prefix}OpenAiEnvironmentCredentialMaterialSource.java`,
    `${prefix}OpenAiResponsesAdvisoryProvider.java`,
    `${prefix}OpenAiResponsesEndpointPolicy.java`,
    `${prefix}OpenAiResponsesHttpCodec.java`,
    `${prefix}OpenAiResponsesJdkSecureNetwork.java`,
    `${prefix}OpenAiResponsesNetworkSupport.java`,
    `${prefix}OpenAiResponsesProductionRuntimeFactory.java`,
    `${prefix}OpenAiResponsesProtocol.java`,
    `${prefix}OpenAiResponsesRequestEncoder.java`,
    `${prefix}OpenAiResponsesRequestProfileValidator.java`,
    `${prefix}OpenAiResponsesResponseDecoder.java`,
    `${prefix}OpenAiResponsesRuntimeUsageLedger.java`,
    `${prefix}OpenAiResponsesSecureHttpSender.java`,
    `${prefix}OpenAiResponsesTransportAdmission.java`,
    `${prefix}OpenAiResponsesTransportControls.java`,
    `${prefix}OpenAiResponsesTransportException.java`,
    `${prefix}OpenAiResponsesTransportPort.java`,
  ]);

  const environmentTokenFiles = productionFiles
    .filter(file => /OPENAI_API_KEY(?:_VERSION)?/.test(text(file)))
    .map(file => path.relative(root, file).replaceAll('\\', '/'))
    .sort();
  assert.deepEqual(environmentTokenFiles, [
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalAssistanceProductionConfiguration.java',
    `${prefix}OpenAiEnvironmentCredentialMaterialSource.java`,
  ]);

  const systemEnvironmentFiles = productionFiles
    .filter(file => /System\.getenv/.test(text(file)))
    .map(file => path.relative(root, file).replaceAll('\\', '/'));
  assert.deepEqual(systemEnvironmentFiles, [
    `${prefix}OpenAiEnvironmentCredentialMaterialSource.java`,
  ]);

  const openAiSource = text(openAiSourcePath);
  for (const required of [
    /implements CredentialMaterialSource/,
    /OPENAI_API_KEY/,
    /OPENAI_API_KEY_VERSION/,
    /CredentialMaterialLease\.takeOwnership/,
    /AI_ADVISORY_GENERATE/,
    /CredentialMaterialType\.API_KEY/,
  ]) assert.match(openAiSource, required);

  const openAiFiles = filesUnder(openAiProductionRoot)
    .filter(file => file.endsWith('.java'));
  const openAiProviderSource = openAiFiles.map(text).join('\n');
  const nonProviderAiSource = productionFiles
    .filter(file => !file.startsWith(openAiProductionRoot))
    .filter(file => {
      const name = path.basename(file);
      return file.includes('/approval-ai-')
        || /ApprovalAssistance|AiProvider|AiAdvisory|AiExternalSecret/.test(name);
    })
    .map(text)
    .join('\n');

  assert.doesNotMatch(
    '/** This SPI must never become a @RestController. */',
    restControllerAnnotation,
  );
  assert.match('@RestController\nfinal class ForbiddenController {}', restControllerAnnotation);
  assert.doesNotMatch(openAiProviderSource, restControllerAnnotation);

  for (const forbidden of forbiddenProviderCouplings) {
    assert.doesNotMatch(openAiProviderSource, forbidden);
  }
  for (const forbidden of [
    /@Component\b/,
    /@Service\b/,
    /@Configuration\b/,
    /@Bean\b/,
    /@PostMapping\b/,
    /@Scheduled\b/,
    /JdbcTemplate/,
    /DataSource/,
  ]) assert.doesNotMatch(openAiProviderSource, forbidden);

  for (const forbidden of [
    /api\.openai\.com/,
    /import\s+java\.net\./,
    /import\s+javax\.net\.ssl\./,
    /Authorization\s*[:=]/,
    /Bearer\s+/,
  ]) assert.doesNotMatch(nonProviderAiSource, forbidden);

  const implementations = openAiFiles
    .filter(file => /implements\s+OpenAiResponsesTransportPort/.test(text(file)))
    .map(file => path.basename(file));
  assert.deepEqual(implementations, ['OpenAiResponsesSecureHttpSender.java']);
  assert.equal(existsSync(senderPath), true);

  const serverPom = text(serverPomPath);
  assert.equal((serverPom.match(/<artifactId>approval-ai-openai<\/artifactId>/g) || []).length, 1);

  const runtimeConfig = text(serverRuntimeConfigPath);
  assert.match(runtimeConfig, /getProperty\(ENABLED, "false"\)/);
  assert.match(runtimeConfig, /OPENAI_API_KEY_VERSION/);
  assert.doesNotMatch(runtimeConfig, /getProperty\("OPENAI_API_KEY"\)|System\.getenv/);

  const versioned = filesUnder(migrationRoot).map((file) => {
    const name = path.basename(file);
    const match = /^V(\d+)__/.exec(name);
    return match ? { name, version: Number(match[1]) } : null;
  }).filter(Boolean);
  assert.deepEqual(
    versioned.filter(({ version }) => version === 49).map(({ name }) => name),
    ['V49__create_ai_approval_assistance_durable_evidence.sql'],
  );
  assert.deepEqual(versioned.filter(({ version }) => version >= 50), []);
});
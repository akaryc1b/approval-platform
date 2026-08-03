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
const openAiSourcePath = path.join(
  root,
  'server-modules/approval-ai-openai/src/main/java/' +
    'io/github/akaryc1b/approval/ai/openai/' +
    'OpenAiEnvironmentCredentialMaterialSource.java',
);
const serverPomPath = path.join(root, 'apps/server/pom.xml');
const migrationRoot = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
);
const productionRoots = [
  path.join(root, 'server-modules/approval-ai-spi/src/main/java'),
  path.join(root, 'server-modules/approval-ai-core/src/main/java'),
  path.join(root, 'server-modules/approval-ai-openai/src/main/java'),
  path.join(root, 'apps/server/src/main/java'),
];
const restControllerAnnotation = /^[ \t]*@RestController\b/m;

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

test('P6-A selects one exact OpenAI Responses profile without implementation authority', () => {
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

test('P6-A profile permits only the P6-B Secret source and still no transport or invocation', () => {
  const productionFiles = productionRoots
    .flatMap(filesUnder)
    .filter(file => file.endsWith('.java'));
  const openAiNamedFiles = productionFiles
    .filter(file => /openai/i.test(path.basename(file)))
    .map(file => path.relative(root, file).replaceAll('\\', '/'));
  assert.deepEqual(openAiNamedFiles, [
    'server-modules/approval-ai-openai/src/main/java/' +
      'io/github/akaryc1b/approval/ai/openai/' +
      'OpenAiEnvironmentCredentialMaterialSource.java',
  ]);

  const environmentTokenFiles = productionFiles
    .filter(file => /OPENAI_API_KEY(?:_VERSION)?/.test(text(file)))
    .map(file => path.relative(root, file).replaceAll('\\', '/'));
  assert.deepEqual(environmentTokenFiles, [
    'server-modules/approval-ai-openai/src/main/java/' +
      'io/github/akaryc1b/approval/ai/openai/' +
      'OpenAiEnvironmentCredentialMaterialSource.java',
  ]);

  const systemEnvironmentFiles = productionFiles
    .filter(file => /System\.getenv/.test(text(file)))
    .map(file => path.relative(root, file).replaceAll('\\', '/'));
  assert.deepEqual(systemEnvironmentFiles, [
    'server-modules/approval-ai-openai/src/main/java/' +
      'io/github/akaryc1b/approval/ai/openai/' +
      'OpenAiEnvironmentCredentialMaterialSource.java',
  ]);

  const openAiSource = text(openAiSourcePath);
  for (const required of [
    /implements CredentialMaterialSource/,
    /OPENAI_API_KEY/,
    /OPENAI_API_KEY_VERSION/,
    /CredentialMaterialLease\.takeOwnership/,
    /AI_ADVISORY_GENERATE/,
    /CredentialMaterialType\.API_KEY/,
  ]) {
    assert.match(openAiSource, required);
  }

  const aiRelevantSource = productionFiles
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

  for (const forbidden of [
    /api\.openai\.com/,
    /java\.net\./,
    /HttpClient/,
    /WebClient/,
    /RestClient/,
    /Authorization\s*[:=]/,
    /Bearer\s+/,
    /@PostMapping\([^\n]*assistance/i,
    restControllerAnnotation,
    /@Scheduled\b/,
    /ApprovalAssistanceSynchronousOrchestrator/,
    /ApprovalAssistanceDurableEvidenceStore/,
  ]) {
    assert.doesNotMatch(aiRelevantSource, forbidden);
  }

  assert.doesNotMatch(text(serverPomPath), /approval-ai-openai/);

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

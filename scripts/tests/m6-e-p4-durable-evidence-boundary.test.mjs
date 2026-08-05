import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const migrationRoot = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
);
const migrationPath = path.join(
  migrationRoot,
  'V49__create_ai_approval_assistance_durable_evidence.sql',
);
const evidencePath = path.join(
  root,
  'server-modules/approval-ai-core/src/main/java/' +
    'io/github/akaryc1b/approval/ai/core/ApprovalAssistanceDurableEvidence.java',
);
const storePortPath = path.join(
  root,
  'server-modules/approval-ai-core/src/main/java/' +
    'io/github/akaryc1b/approval/ai/core/ApprovalAssistanceDurableEvidenceStore.java',
);
const jdbcStorePath = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/java/' +
    'io/github/akaryc1b/approval/persistence/jdbc/' +
    'JdbcApprovalAssistanceDurableEvidenceStore.java',
);
const integrationTestPath = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/test/java/' +
    'io/github/akaryc1b/approval/persistence/jdbc/' +
    'JdbcApprovalAssistanceDurableEvidenceStoreIntegrationTest.java',
);
const generationServicePath = path.join(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/' +
    'ApprovalAssistanceGenerationService.java',
);
const generationControllerPath = path.join(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/' +
    'ApprovalAssistanceGenerationController.java',
);
const productionConfigPath = path.join(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/' +
    'ApprovalAssistanceProductionConfiguration.java',
);

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

test('P4 durable evidence is exact hash-only tenant-safe internal infrastructure', () => {
  for (const requiredPath of [
    migrationPath,
    evidencePath,
    storePortPath,
    jdbcStorePath,
    integrationTestPath,
    generationServicePath,
    generationControllerPath,
    productionConfigPath,
  ]) {
    assert.equal(existsSync(requiredPath), true, `missing P4 source ${requiredPath}`);
  }

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

  const migration = text(migrationPath);
  const executableSql = migration.lines
    ? migration.lines().filter((line) => !line.trim().startsWith('--')).join('\n')
    : migration.split('\n').filter((line) => !line.trim().startsWith('--')).join('\n');
  assert.equal((migration.match(/create table ap_ai_approval_assistance_/g) ?? []).length, 3);
  assert.equal((migration.match(/create index idx_ai_assistance_/g) ?? []).length, 5);
  assert.equal((migration.match(/foreign key \(tenant_id,evidence_id\)/g) ?? []).length, 2);
  assert.equal((migration.match(/deferrable initially deferred/g) ?? []).length, 2);
  for (const required of [
    /ap_ai_approval_assistance_evidence \(/,
    /ap_ai_approval_assistance_evidence_state \(/,
    /ap_ai_approval_assistance_evidence_event \(/,
    /revision in \(1,2\)/,
    /state in \('ACTIVE','TOMBSTONED'\)/,
    /not retry_attempted and not post_invocation_fallback_attempted/,
    /knowledge_source_id='none'/,
    /ap_guard_ai_assistance_evidence_v49/,
    /ap_guard_ai_assistance_state_v49/,
    /ap_guard_ai_assistance_event_v49/,
    /ap_verify_ai_assistance_state_event_v49/,
    /ap_verify_ai_assistance_event_state_v49/,
    /new\.tombstoned_at<evidence_recorded_at/,
    /new\.happened_at<evidence_recorded_at/,
    /P4 evidence state lacks matching append-only event/,
    /P4 append-only event lacks matching evidence state/,
  ]) {
    assert.match(migration, required);
  }
  assert.doesNotMatch(executableSql, /\b(?:text|json|jsonb|bytea)\b/i);
  assert.doesNotMatch(
    executableSql,
    /^\s*(?:raw|payload|body|content|summary|observation_text|risk_text|recommendation_text|limitation_text)[a-z0-9_]*\s+/im,
  );

  const evidence = text(evidencePath);
  const storePort = text(storePortPath);
  const core = `${evidence}\n${storePort}`;
  for (const required of [
    /projectionEvidenceHash/,
    /executionEvidenceHash/,
    /versionEvidenceHash/,
    /outcomeEvidenceHash/,
    /updateCanonicalValue/,
    /M6-E-P4-PROVIDER-VALUE-EVIDENCE-V1/,
    /M6-E-P4-OUTCOME-EVIDENCE-V1/,
    /KnowledgeSourceVersion\.none\(\)/,
    /MAXIMUM_RETENTION/,
    /Provider attempts must be zero or one/,
    /P4 evidence cannot represent retry or post-invocation fallback/,
    /evidenceHash must match canonical P4 durable evidence/,
    /TombstoneDisposition/,
    /RETENTION_BLOCKED/,
    /EvidenceState\.TOMBSTONED/,
  ]) {
    assert.match(core, required);
  }
  for (const forbidden of [
    /import\s+java\.sql\./,
    /import\s+javax\.sql\./,
    /import\s+org\.springframework\./,
    /import\s+org\.flowable\./,
    /@RestController\b/,
    /@Scheduled\b/,
  ]) {
    assert.doesNotMatch(core, forbidden);
  }

  const jdbcStore = text(jdbcStorePath);
  for (const required of [
    /transactions\.execute\(status -> storeOnce\(evidence\)\)/,
    /transactions\.execute\(status -> tombstoneOnce\(command\)\)/,
    /on conflict do nothing/,
    /for update of e,s/,
    /insertStoredEvent\(eventId, evidence, eventHash\)/,
    /insertActiveState\(evidence, eventHash\)/,
    /insertTombstoneEvent\(eventId, command, current, eventHash\)/,
    /updateTombstoneState/,
    /RETENTION_BLOCKED/,
    /REVISION_CONFLICT/,
  ]) {
    assert.match(jdbcStore, required);
  }
  for (const forbidden of [
    /@RestController\b/,
    /@Scheduled\b/,
    /org\.flowable/,
    /java\.net\./,
    /HttpClient/,
    /ApprovalCommand/,
    /SecretMaterial/,
  ]) {
    assert.doesNotMatch(jdbcStore, forbidden);
  }

  const productionConfig = text(productionConfigPath);
  assert.match(productionConfig, /new JdbcApprovalAssistanceDurableEvidenceStore\(/);
  assert.match(productionConfig, /ApprovalAssistanceDurableEvidenceStore/);

  const generationService = text(generationServicePath);
  assert.equal((generationService.match(/evidenceStore\.store\s*\(/g) ?? []).length, 1);
  assert.match(generationService, /StoreDisposition\.CONFLICT/);
  assert.doesNotMatch(generationService, /JdbcApprovalAssistanceDurableEvidenceStore/);

  const generationController = text(generationControllerPath);
  assert.doesNotMatch(
    generationController,
    /(?:Jdbc)?ApprovalAssistanceDurableEvidenceStore|evidenceStore\.store/,
  );

  const integration = text(integrationTestPath);
  for (const required of [
    /storesAndReplaysExactEvidenceAsOneActiveRevision/,
    /sameRequestWithDifferentEvidenceIdentityConflicts/,
    /sameEvidenceIdentityWithDifferentContentConflicts/,
    /sameEvidenceIdentityIsIsolatedByTenant/,
    /retentionExpiredReasonIsBlockedBeforeRetention/,
    /permittedEarlyTombstoneIsDurableAndExactlyReplayable/,
    /concurrentExactStoreProducesOneStoreAndOneReplay/,
    /concurrentExactTombstoneProducesOneTransitionAndOneReplay/,
    /evidenceEventsAndStateRejectPhysicalMutationOrDeletion/,
    /stateWithoutMatchingEventIsRejectedAtCommit/,
    /eventWithoutMatchingStateIsRejectedAtCommit/,
    /wrongPredecessorAndTimeInversionAreRejectedBeforeCommit/,
    /schemaContainsNoRawPayloadTextJsonOrBinaryColumn/,
  ]) {
    assert.match(integration, required);
  }
});

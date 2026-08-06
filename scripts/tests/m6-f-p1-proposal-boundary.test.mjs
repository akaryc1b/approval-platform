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
const testRoot = path.join(
  root,
  'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core',
);

function source(directory, name) {
  const file = path.join(directory, `${name}.java`);
  assert.equal(existsSync(file), true, `${name}.java must exist`);
  return readFileSync(file, 'utf8');
}

test('P1 Proposal is typed, closed and permanently non-executable', () => {
  const proposal = source(coreRoot, 'ControlledAutomationProposal');

  assert.match(proposal, /NON_EXECUTABLE_PROPOSAL/);
  assert.match(proposal, /requiresHumanConfirmation/);
  assert.match(proposal, /ReauthenticationRequirement/);
  assert.match(proposal, /tenantEvidenceHash/);
  assert.match(proposal, /operatorEvidenceHash/);
  assert.match(proposal, /SourceAdvisoryEvidence/);
  assert.match(proposal, /TargetResourceEvidence/);
  assert.match(proposal, /whitelistVersion/);
  assert.match(proposal, /PolicyEvidence/);
  assert.match(proposal, /lineageHash/);
  assert.match(proposal, /ProposalStatus\.PROPOSED/);
  assert.doesNotMatch(proposal, /\b(EXECUTING|EXECUTED|SUCCEEDED)\b/);
  assert.doesNotMatch(
    proposal,
    /\b(apiKey|bearerToken|sessionCredential|permissionToken|confirmationToken|rawPrompt|rawProviderOutput|javaClassName|dynamicModule|httpBody)\b/,
  );
});

test('P1 factory requires explicit user action and empty whitelist fails closed', () => {
  const factory = source(coreRoot, 'ControlledAutomationProposalFactory');
  const whitelist = source(coreRoot, 'ControlledAutomationActionWhitelist');

  assert.match(factory, /CreationTrigger\.EXPLICIT_USER_ACTION/);
  assert.match(factory, /ACTION_NOT_WHITELISTED/);
  assert.match(factory, /TRIGGER_NOT_ALLOWED/);
  assert.match(factory, /EXPIRY_NOT_ALLOWED/);
  assert.match(factory, /PARAMETER_SCHEMA_MISMATCH/);
  assert.match(factory, /RISK_NOT_ALLOWED/);
  assert.match(factory, /MAXIMUM_PROPOSAL_LIFETIME/);
  assert.match(whitelist, /static ControlledAutomationActionWhitelist empty/);
  assert.match(whitelist, /return Optional\.empty\(\)/);
  assert.match(whitelist, /grants no command authority/);
});

test('P1 has no Provider, command, persistence, connector or automatic executor path', () => {
  const production = [
    source(coreRoot, 'ControlledAutomationActionWhitelist'),
    source(coreRoot, 'ControlledAutomationProposal'),
    source(coreRoot, 'ControlledAutomationProposalFactory'),
  ].join('\n');

  assert.doesNotMatch(
    production,
    /ApprovalMessageService|PurchasePaymentTaskActionService|ProcessMigrationService/,
  );
  assert.doesNotMatch(production, /AiAdvisoryProvider|\.advise\s*\(/);
  assert.doesNotMatch(production, /ConnectorInvocation|ConnectorProvider/);
  assert.doesNotMatch(production, /JdbcTemplate|DataSource|java\.sql|javax\.sql/);
  assert.doesNotMatch(production, /HttpClient|WebClient|RestClient|java\.net/);
  assert.doesNotMatch(production, /@Scheduled|TaskScheduler|SchedulingConfigurer/);
  assert.doesNotMatch(production, /RuntimeService|TaskService|ProcessMigrationService|ACT_/);
});

test('P1 automatic triggers are represented only as fail-closed inputs', () => {
  const proposal = source(coreRoot, 'ControlledAutomationProposal');
  const factory = source(coreRoot, 'ControlledAutomationProposalFactory');

  for (const trigger of [
    'ADVISORY_CALLBACK',
    'PAGE_LOAD',
    'LISTENER',
    'POLLING',
    'PROVIDER_CALLBACK',
    'SCHEDULED',
    'WEBHOOK',
  ]) {
    assert.match(proposal, new RegExp(trigger));
  }
  assert.match(factory, /trigger\(\) != CreationTrigger\.EXPLICIT_USER_ACTION/);
  assert.doesNotMatch(factory, /switch\s*\([^)]*trigger/);
});

test('P1 test fixture cannot change the production empty whitelist decision', () => {
  const production = [
    source(coreRoot, 'ControlledAutomationActionWhitelist'),
    source(coreRoot, 'ControlledAutomationProposal'),
    source(coreRoot, 'ControlledAutomationProposalFactory'),
  ].join('\n');
  const tests = source(testRoot, 'ControlledAutomationProposalFactoryTest');
  const decision = readFileSync(
    path.join(root, 'docs/m6/M6_F_ACTION_WHITELIST_DECISION.md'),
    'utf8',
  );

  assert.doesNotMatch(production, /TEST_ONLY_NON_EXECUTABLE_ACTION/);
  assert.match(tests, /TEST_ONLY_NON_EXECUTABLE_ACTION/);
  assert.match(decision, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(decision, /Action count: `0`/);
  assert.match(decision, /P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND/);
});

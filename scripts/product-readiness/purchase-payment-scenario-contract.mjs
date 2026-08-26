#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentFile = fileURLToPath(import.meta.url);
const root = resolve(dirname(currentFile), '../..');
const manifestPath = resolve(root, 'config/demo/purchase-payment-golden-path.json');
const controllerPath = resolve(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/PurchasePaymentController.java',
);
const templatePath = resolve(
  root,
  'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/template/PurchasePaymentTemplate.java',
);

const requiredNonClaims = [
  'DETERMINISTIC_DEMO_SEED_NOT_APPLIED',
  'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
  'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
  'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
];

function fail(message) {
  throw new Error(message);
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function requireObject(value, path) {
  if (!isPlainObject(value)) fail(`${path} must be an object`);
  return value;
}

function requireArray(value, path) {
  if (!Array.isArray(value)) fail(`${path} must be an array`);
  return value;
}

function requireString(value, path) {
  if (typeof value !== 'string' || value.trim() !== value || value.length === 0) {
    fail(`${path} must be a non-empty trimmed string`);
  }
  return value;
}

function requireNullableString(value, path) {
  if (value === null) return null;
  return requireString(value, path);
}

function exactKeys(value, expected, path) {
  const actual = Object.keys(requireObject(value, path)).sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    fail(`${path} keys must be exactly ${wanted.join(', ')}`);
  }
}

function requireSafeKeys(value, path = '$') {
  if (Array.isArray(value)) {
    value.forEach((item, index) => requireSafeKeys(item, `${path}[${index}]`));
    return;
  }
  if (!isPlainObject(value)) return;
  for (const key of Object.keys(value)) {
    if (key === '__proto__' || key === 'prototype' || key === 'constructor') {
      fail(`${path} contains forbidden key ${key}`);
    }
    requireSafeKeys(value[key], `${path}.${key}`);
  }
}

function uniqueStrings(values, path) {
  const strings = requireArray(values, path).map((value, index) =>
    requireString(value, `${path}[${index}]`));
  if (new Set(strings).size !== strings.length) {
    fail(`${path} must not contain duplicates`);
  }
  return strings;
}

function isAsciiIdentifier(value) {
  for (const character of value) {
    const code = character.codePointAt(0);
    const allowed = (code >= 48 && code <= 57)
      || (code >= 65 && code <= 90)
      || (code >= 97 && code <= 122)
      || character === '-'
      || character === '_';
    if (!allowed) return false;
  }
  return true;
}

function requireIdentifier(value, path) {
  const identifier = requireString(value, path);
  if (!isAsciiIdentifier(identifier)) {
    fail(`${path} must use only ASCII letters, digits, '-' or '_'`);
  }
  return identifier;
}

function moneyToCents(value, path) {
  const amount = requireString(value, path);
  const parts = amount.split('.');
  if (parts.length !== 2 || parts[0].length === 0 || parts[1].length !== 2) {
    fail(`${path} must use an exact two-decimal money string`);
  }
  for (const part of parts) {
    for (const character of part) {
      if (character < '0' || character > '9') {
        fail(`${path} must contain only decimal digits`);
      }
    }
  }
  return (BigInt(parts[0]) * 100n) + BigInt(parts[1]);
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (isPlainObject(value)) {
    return `{${Object.keys(value).sort().map((key) =>
      `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function requireLiteral(source, literal, sourceName) {
  if (!source.includes(literal)) {
    fail(`${sourceName} is missing literal contract: ${literal}`);
  }
}

function validateUsers(directory) {
  exactKeys(directory, ['connectorKey', 'source', 'users'], 'directory');
  const connectorKey = requireIdentifier(directory.connectorKey, 'directory.connectorKey');
  const source = requireIdentifier(directory.source, 'directory.source');
  if (connectorKey !== source) {
    fail('directory connectorKey and source must match for the demo contract');
  }

  const users = requireArray(directory.users, 'directory.users');
  if (users.length !== 6) {
    fail('directory.users must contain exactly six deterministic demo users');
  }
  const byId = new Map();
  for (const [index, userValue] of users.entries()) {
    const path = `directory.users[${index}]`;
    const user = requireObject(userValue, path);
    exactKeys(
      user,
      ['id', 'displayName', 'roleCodes', 'positionCodes', 'managerId'],
      path,
    );
    const id = requireIdentifier(user.id, `${path}.id`);
    if (byId.has(id)) fail(`duplicate demo user id ${id}`);
    const normalized = {
      id,
      displayName: requireString(user.displayName, `${path}.displayName`),
      roleCodes: uniqueStrings(user.roleCodes, `${path}.roleCodes`).map((role, roleIndex) =>
        requireIdentifier(role, `${path}.roleCodes[${roleIndex}]`)),
      positionCodes: uniqueStrings(user.positionCodes, `${path}.positionCodes`)
        .map((position, positionIndex) =>
          requireIdentifier(position, `${path}.positionCodes[${positionIndex}]`)),
      managerId: requireNullableString(user.managerId, `${path}.managerId`),
    };
    byId.set(id, normalized);
  }
  for (const user of byId.values()) {
    if (user.managerId !== null && !byId.has(user.managerId)) {
      fail(`manager ${user.managerId} for ${user.id} is not present in the demo directory`);
    }
  }
  return { connectorKey, source, byId };
}

function validateApiContract(apiContract, controllerSource, userIds) {
  exactKeys(apiContract, ['basePath', 'headers', 'operations'], 'apiContract');
  if (apiContract.basePath !== '/api/approval') {
    fail('apiContract.basePath must be /api/approval');
  }
  const expectedHeaders = [
    'X-Tenant-Id',
    'X-Operator-Id',
    'X-Request-Id',
    'Idempotency-Key',
    'X-Trace-Id',
  ];
  const headers = uniqueStrings(apiContract.headers, 'apiContract.headers');
  if (JSON.stringify(headers) !== JSON.stringify(expectedHeaders)) {
    fail('apiContract.headers must match the PurchasePaymentController request context headers');
  }

  const expectedOperations = [
    ['publish', 'POST', '/definitions/purchase-payment/publish', 'demo-admin'],
    ['start', 'POST', '/instances/purchase-payment', 'demo-employee'],
    ['pendingTasks', 'GET', '/tasks/pending', 'ACTIVE_TASK_ACTOR'],
    ['approveTask', 'POST', '/tasks/{taskId}/approve', 'ACTIVE_TASK_ACTOR'],
    ['instance', 'GET', '/instances/{instanceId}', 'demo-admin'],
    ['timeline', 'GET', '/instances/{instanceId}/timeline', 'demo-admin'],
  ];
  const operations = requireArray(apiContract.operations, 'apiContract.operations');
  if (operations.length !== expectedOperations.length) {
    fail('apiContract.operations length is invalid');
  }
  for (let index = 0; index < expectedOperations.length; index += 1) {
    const path = `apiContract.operations[${index}]`;
    const operation = requireObject(operations[index], path);
    exactKeys(operation, ['name', 'method', 'path', 'actorId'], path);
    const actual = [operation.name, operation.method, operation.path, operation.actorId];
    if (JSON.stringify(actual) !== JSON.stringify(expectedOperations[index])) {
      fail(`${path} does not match the governed API sequence`);
    }
    if (operation.actorId !== 'ACTIVE_TASK_ACTOR' && !userIds.has(operation.actorId)) {
      fail(`api operation actor ${operation.actorId} is not a deterministic demo user`);
    }
  }

  const sourceBindings = [
    '@RequestMapping("/api/approval")',
    '@PostMapping("/definitions/purchase-payment/publish")',
    '@PostMapping("/instances/purchase-payment")',
    '@GetMapping("/tasks/pending")',
    '@PostMapping("/tasks/{taskId}/approve")',
    '@GetMapping("/instances/{instanceId}")',
    '@GetMapping("/instances/{instanceId}/timeline")',
    'private static final String TENANT_ID = "X-Tenant-Id";',
    'private static final String OPERATOR_ID = "X-Operator-Id";',
    'private static final String REQUEST_ID = "X-Request-Id";',
    'private static final String IDEMPOTENCY_KEY = "Idempotency-Key";',
    'private static final String TRACE_ID = "X-Trace-Id";',
  ];
  for (const binding of sourceBindings) {
    requireLiteral(controllerSource, binding, 'PurchasePaymentController');
  }
}

function validateWorkflow(manifest, templateSource, users) {
  const request = requireObject(manifest.request, 'request');
  exactKeys(
    request,
    ['businessKey', 'amount', 'supplier', 'purchaseOrderReference', 'attachmentIds'],
    'request',
  );
  requireIdentifier(request.businessKey, 'request.businessKey');
  const cents = moneyToCents(request.amount, 'request.amount');
  if (cents < 1_000_000n) {
    fail('request.amount must exercise the high-value finance-review branch at or above 10000.00');
  }
  if (requireString(request.supplier, 'request.supplier').length > 200) {
    fail('request.supplier exceeds the controller limit');
  }
  if (requireString(
    request.purchaseOrderReference,
    'request.purchaseOrderReference',
  ).length > 100) {
    fail('request.purchaseOrderReference exceeds the controller limit');
  }
  const attachmentIds = uniqueStrings(request.attachmentIds, 'request.attachmentIds');
  if (attachmentIds.length < 1) {
    fail('request.attachmentIds must contain at least one attachment');
  }

  const rules = requireObject(manifest.assigneeRules, 'assigneeRules');
  exactKeys(
    rules,
    [
      'connectorKey',
      'initiatorUserId',
      'financeReviewerRoleCode',
      'financeApproverPositionCode',
      'maximumFinanceApprovers',
    ],
    'assigneeRules',
  );
  if (rules.connectorKey !== users.connectorKey) {
    fail('assigneeRules.connectorKey must match directory.connectorKey');
  }
  exactKeys(
    rules.initiatorUserId,
    ['source', 'objectType', 'value'],
    'assigneeRules.initiatorUserId',
  );
  if (rules.initiatorUserId.source !== users.source) {
    fail('initiator source must match the demo directory source');
  }
  if (rules.initiatorUserId.objectType !== 'user') {
    fail('initiator objectType must be user');
  }
  if (rules.initiatorUserId.value !== 'demo-employee') {
    fail('initiator must be demo-employee');
  }

  const employee = users.byId.get('demo-employee');
  if (!employee || employee.managerId !== 'demo-manager') {
    fail('demo-employee must resolve deterministically to demo-manager');
  }
  const reviewerRole = requireIdentifier(
    rules.financeReviewerRoleCode,
    'assigneeRules.financeReviewerRoleCode',
  );
  const reviewers = [...users.byId.values()]
    .filter((user) => user.roleCodes.includes(reviewerRole));
  if (reviewers.length !== 1 || reviewers[0].id !== 'demo-finance-reviewer') {
    fail('finance reviewer role must resolve to exactly demo-finance-reviewer');
  }
  const approverPosition = requireIdentifier(
    rules.financeApproverPositionCode,
    'assigneeRules.financeApproverPositionCode',
  );
  const approvers = [...users.byId.values()]
    .filter((user) => user.positionCodes.includes(approverPosition))
    .map((user) => user.id)
    .sort();
  if (!Number.isInteger(rules.maximumFinanceApprovers)
    || rules.maximumFinanceApprovers !== 2) {
    fail('maximumFinanceApprovers must be exactly 2 for the deterministic countersign contract');
  }
  const expectedApprovers = ['demo-finance-approver-a', 'demo-finance-approver-b'];
  if (JSON.stringify(approvers) !== JSON.stringify(expectedApprovers)) {
    fail('finance approver position must resolve to the two deterministic countersigners');
  }

  const expectedWorkflow = [
    ['managerApproval', 'SINGLE', ['demo-manager'], 'APPROVED', 'pc'],
    ['financeReview', 'SINGLE', ['demo-finance-reviewer'], 'APPROVED', 'h5'],
    ['financeCountersign', 'ALL', expectedApprovers, 'APPROVED', 'h5'],
    ['paymentConfirmation', 'SINGLE', ['demo-employee'], 'APPROVED', 'wechat'],
  ];
  const workflow = requireArray(manifest.expectedWorkflow, 'expectedWorkflow');
  if (workflow.length !== expectedWorkflow.length) {
    fail('expectedWorkflow must contain exactly four purchase-to-payment stages');
  }
  for (let index = 0; index < expectedWorkflow.length; index += 1) {
    const path = `expectedWorkflow[${index}]`;
    const stage = requireObject(workflow[index], path);
    exactKeys(
      stage,
      ['taskDefinitionKey', 'mode', 'actorIds', 'decision', 'client'],
      path,
    );
    const actual = [
      requireIdentifier(stage.taskDefinitionKey, `${path}.taskDefinitionKey`),
      requireIdentifier(stage.mode, `${path}.mode`),
      uniqueStrings(stage.actorIds, `${path}.actorIds`),
      requireIdentifier(stage.decision, `${path}.decision`),
      requireIdentifier(stage.client, `${path}.client`),
    ];
    if (JSON.stringify(actual) !== JSON.stringify(expectedWorkflow[index])) {
      fail(`${path} does not match the governed high-value purchase-to-payment path`);
    }
  }

  const paymentStage = workflow.at(-1);
  if (paymentStage.actorIds.length !== 1
    || paymentStage.actorIds[0] !== rules.initiatorUserId.value) {
    fail('payment confirmation actor must equal the governed initiator');
  }
  if (paymentStage.client !== 'wechat') {
    fail('payment confirmation must run in the WeChat client');
  }

  const templateBindings = [
    'public static final String DEFINITION_KEY = "purchase-payment";',
    'public static final int PROCESS_VERSION = 3;',
    'public static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("10000.00");',
    'public static final String PAYMENT_CONFIRMATION_TASK_KEY = "paymentConfirmation";',
    '"managerApproval"',
    '"financeReview"',
    '"financeCountersign"',
    'PAYMENT_CONFIRMATION_TASK_KEY,',
    'INITIATOR_ASSIGNEE_VARIABLE,',
  ];
  for (const binding of templateBindings) {
    requireLiteral(templateSource, binding, 'PurchasePaymentTemplate');
  }
}

function validateEvidenceBoundaries(manifest) {
  exactKeys(
    manifest.clientEvidence,
    ['pc', 'h5', 'wechatMiniProgram', 'sharedBusinessKeys'],
    'clientEvidence',
  );
  for (const key of ['pc', 'h5', 'wechatMiniProgram']) {
    if (manifest.clientEvidence[key] !== 'NOT_YET_EXECUTED') {
      fail(`clientEvidence.${key} must remain NOT_YET_EXECUTED until a real client run exists`);
    }
  }
  const expectedSharedKeys = [
    'tenantId',
    'businessKey',
    'instanceId',
    'taskIds',
    'auditEventIds',
    'finalStatus',
  ];
  const sharedKeys = uniqueStrings(
    manifest.clientEvidence.sharedBusinessKeys,
    'clientEvidence.sharedBusinessKeys',
  );
  if (JSON.stringify(sharedKeys) !== JSON.stringify(expectedSharedKeys)) {
    fail('clientEvidence.sharedBusinessKeys must preserve the cross-client evidence identity set');
  }

  exactKeys(manifest.sandbox, ['kind', 'provider', 'status', 'production'], 'sandbox');
  if (manifest.sandbox.kind !== 'PAYMENT_SANDBOX') {
    fail('sandbox.kind must be PAYMENT_SANDBOX');
  }
  if (manifest.sandbox.provider !== 'UNASSIGNED') {
    fail('sandbox.provider must remain UNASSIGNED');
  }
  if (manifest.sandbox.status !== 'NOT_YET_CONFIGURED') {
    fail('sandbox.status must remain NOT_YET_CONFIGURED');
  }
  if (manifest.sandbox.production !== false) {
    fail('sandbox.production must remain false');
  }

  const nonClaims = uniqueStrings(manifest.nonClaims, 'nonClaims');
  if (JSON.stringify(nonClaims) !== JSON.stringify(requiredNonClaims)) {
    fail('nonClaims must preserve the exact non-acceptance vocabulary');
  }
}

export function readScenario() {
  return JSON.parse(readFileSync(manifestPath, 'utf8'));
}

export function validateScenario(
  manifest,
  {
    controllerSource = readFileSync(controllerPath, 'utf8'),
    templateSource = readFileSync(templatePath, 'utf8'),
  } = {},
) {
  requireSafeKeys(manifest);
  exactKeys(
    manifest,
    [
      'schemaVersion',
      'status',
      'tenant',
      'directory',
      'request',
      'assigneeRules',
      'expectedWorkflow',
      'apiContract',
      'clientEvidence',
      'sandbox',
      'nonClaims',
    ],
    '$',
  );
  if (manifest.schemaVersion !== 1) fail('schemaVersion must be 1');
  exactKeys(manifest.status, ['contract', 'seed', 'execution', 'sandbox'], 'status');
  if (manifest.status.contract !== 'PURCHASE_PAYMENT_SCENARIO_CONTRACT_AVAILABLE') {
    fail('status.contract is invalid');
  }
  if (manifest.status.seed !== 'NOT_YET_APPLIED') {
    fail('status.seed must be NOT_YET_APPLIED');
  }
  if (manifest.status.execution !== 'NOT_YET_EXECUTED') {
    fail('status.execution must be NOT_YET_EXECUTED');
  }
  if (manifest.status.sandbox !== 'NOT_YET_CONFIGURED') {
    fail('status.sandbox must be NOT_YET_CONFIGURED');
  }

  exactKeys(manifest.tenant, ['id', 'displayName'], 'tenant');
  requireIdentifier(manifest.tenant.id, 'tenant.id');
  requireString(manifest.tenant.displayName, 'tenant.displayName');

  const users = validateUsers(manifest.directory);
  validateWorkflow(manifest, templateSource, users);
  validateApiContract(manifest.apiContract, controllerSource, new Set(users.byId.keys()));
  validateEvidenceBoundaries(manifest);

  const paymentStage = manifest.expectedWorkflow.at(-1);
  const canonical = canonicalJson(manifest);
  return {
    schemaVersion: 1,
    manifest: 'config/demo/purchase-payment-golden-path.json',
    manifestSha256: createHash('sha256').update(canonical, 'utf8').digest('hex'),
    tenantId: manifest.tenant.id,
    businessKey: manifest.request.businessKey,
    amount: manifest.request.amount,
    deterministicUserCount: users.byId.size,
    approvalStageCount: manifest.expectedWorkflow.length,
    paymentConfirmationActorId: paymentStage.actorIds[0],
    paymentConfirmationClient: paymentStage.client,
    claim: 'PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED',
    seed: 'DETERMINISTIC_DEMO_SEED_NOT_APPLIED',
    execution: 'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
    sandbox: 'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
    productionPayment: 'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
  };
}

function printHelp() {
  console.log(`Usage: node scripts/product-readiness/purchase-payment-scenario-contract.mjs [options]\n\nOptions:\n  --json  Print machine-readable JSON.\n  --help  Show this help.\n\nThis command validates the deterministic purchase-payment scenario contract. It\ndoes not apply seed data, start services, execute approvals, contact a sandbox,\nor verify a production payment integration.`);
}

function main() {
  const argumentsSet = new Set(process.argv.slice(2));
  const allowedArguments = new Set(['--help', '--json']);
  const unknownArguments = [...argumentsSet]
    .filter((argument) => !allowedArguments.has(argument));
  if (argumentsSet.has('--help')) {
    printHelp();
    return;
  }
  if (unknownArguments.length > 0) {
    console.error(`Unknown option(s): ${unknownArguments.join(', ')}`);
    process.exitCode = 2;
    return;
  }

  try {
    const report = validateScenario(readScenario());
    if (argumentsSet.has('--json')) {
      console.log(JSON.stringify(report, null, 2));
      return;
    }
    console.log('Approval Platform Purchase-Payment Scenario Contract');
    console.log(`Manifest: ${report.manifest}`);
    console.log(`SHA-256: ${report.manifestSha256}`);
    console.log(`Tenant: ${report.tenantId}`);
    console.log(`Business key: ${report.businessKey}`);
    console.log(`Amount: ${report.amount}`);
    console.log(`Users: ${report.deterministicUserCount}`);
    console.log(`Approval stages: ${report.approvalStageCount}`);
    console.log(`Payment confirmation actor: ${report.paymentConfirmationActorId}`);
    console.log(`Payment confirmation client: ${report.paymentConfirmationClient}`);
    console.log(report.claim);
    console.log(report.seed);
    console.log(report.execution);
    console.log(report.sandbox);
    console.log(report.productionPayment);
  } catch (error) {
    console.error(`PURCHASE_PAYMENT_SCENARIO_CONTRACT_FAILED: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === currentFile) main();

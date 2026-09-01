import {
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { resolve } from 'node:path';

import {
  repositoryRoot,
  sourceIdentity,
} from '../quick-start/contract.mjs';

export { repositoryRoot, sourceIdentity };

export const manifestPath = resolve(
  repositoryRoot,
  'config/demo/browser-accessibility-matrix.json',
);
export const outputRoot = resolve(
  repositoryRoot,
  '.runtime/browser-accessibility',
);

const allowedCommands = new Set(['ci', 'plan', 'run']);
const requiredProjectIds = [
  'system-chromium',
  'bundled-firefox',
  'bundled-webkit',
];
const requiredClaims = [
  'PC_H5_CHROMIUM_COMPATIBILITY_BASELINE_PASSED',
  'PC_H5_FIREFOX_COMPATIBILITY_SMOKE_PASSED',
  'PC_H5_WEBKIT_ENGINE_COMPATIBILITY_SMOKE_PASSED',
  'PC_AUTHENTICATED_KEYBOARD_TASK_FLOW_PASSED',
  'BASELINE_AUTOMATED_ACCESSIBILITY_PASSED',
  'PC_H5_CJK_RENDERING_MATRIX_PASSED',
  'BROWSER_ACCESSIBILITY_MATRIX_PUBLISHED',
];
const requiredNonClaims = [
  'FULL_BROWSER_COMPATIBILITY_NOT_VERIFIED',
  'SAFARI_BROWSER_NOT_VERIFIED',
  'AUTHENTICATION_KEYBOARD_ACCESSIBILITY_NOT_VERIFIED',
  'H5_KEYBOARD_TASK_NAVIGATION_NOT_VERIFIED',
  'FULL_WCAG_CONFORMANCE_NOT_VERIFIED',
  'SCREEN_READER_MANUAL_TEST_NOT_VERIFIED',
  'MYSQL_8_4_NOT_VERIFIED',
];

export class UsageError extends Error {}

function requiredInteger(value, label, minimum, maximum) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${label} must be between ${minimum} and ${maximum}`);
  }
  return value;
}

function requiredString(value, label) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`${label} is required`);
  }
  return value.trim();
}

function readJson(path) {
  if (!existsSync(path)) throw new Error(`missing required file: ${path}`);
  return JSON.parse(readFileSync(path, 'utf8'));
}

function resolveGovernedScenario(manifest) {
  const quickStartRelative = requiredString(
    manifest.scenarioSource,
    'scenarioSource',
  );
  const quickStart = readJson(resolve(repositoryRoot, quickStartRelative));
  const scenarioRelative = requiredString(
    quickStart.scenarioManifest,
    'quick-start scenarioManifest',
  );
  const scenario = readJson(resolve(repositoryRoot, scenarioRelative));
  return {
    tenantId: requiredString(scenario.tenant?.id, 'scenario tenant.id'),
    businessKey: requiredString(
      scenario.request?.businessKey,
      'scenario request.businessKey',
    ),
    pcActorId: requiredString(
      quickStart.clients?.pc?.actorId,
      'quick-start clients.pc.actorId',
    ),
    h5ActorId: requiredString(
      quickStart.clients?.h5?.actorId,
      'quick-start clients.h5.actorId',
    ),
  };
}

export function loadContract() {
  const manifest = readJson(manifestPath);
  if (manifest.schemaVersion !== 1) {
    throw new Error('browser accessibility schemaVersion must be 1');
  }
  if (manifest.locale !== 'zh-CN') {
    throw new Error('browser accessibility locale must be zh-CN');
  }
  const projectIds = manifest.projects?.map(project => project.id);
  if (JSON.stringify(projectIds) !== JSON.stringify(requiredProjectIds)) {
    throw new Error(
      `browser projects must be ${requiredProjectIds.join(', ')}`,
    );
  }
  const engines = manifest.projects.map(project => project.engine);
  if (JSON.stringify(engines) !== JSON.stringify([
    'chromium',
    'firefox',
    'webkit',
  ])) {
    throw new Error('browser engines must be chromium, firefox and webkit');
  }
  if (manifest.projects[2]?.runtime !== 'PLAYWRIGHT_WEBKIT') {
    throw new Error('WebKit must remain identified as Playwright WebKit');
  }
  for (const claim of requiredClaims) {
    if (!manifest.claims?.includes(claim)) {
      throw new Error(`missing bounded claim: ${claim}`);
    }
  }
  for (const nonClaim of requiredNonClaims) {
    if (!manifest.nonClaims?.includes(nonClaim)) {
      throw new Error(`missing required non-claim: ${nonClaim}`);
    }
  }
  const thresholds = {
    criticalViolations: requiredInteger(
      manifest.thresholds?.criticalViolations,
      'thresholds.criticalViolations',
      0,
      0,
    ),
    seriousViolations: requiredInteger(
      manifest.thresholds?.seriousViolations,
      'thresholds.seriousViolations',
      0,
      0,
    ),
    minimumUniqueCjkGlyphHashes: requiredInteger(
      manifest.thresholds?.minimumUniqueCjkGlyphHashes,
      'thresholds.minimumUniqueCjkGlyphHashes',
      6,
      64,
    ),
    minimumInkPixels: requiredInteger(
      manifest.thresholds?.minimumInkPixels,
      'thresholds.minimumInkPixels',
      1,
      10000,
    ),
    minimumContrastRatio: Number(
      manifest.thresholds?.minimumContrastRatio,
    ),
  };
  if (!Number.isFinite(thresholds.minimumContrastRatio)
      || thresholds.minimumContrastRatio < 3
      || thresholds.minimumContrastRatio > 21) {
    throw new Error('thresholds.minimumContrastRatio must be between 3 and 21');
  }
  const maximumRuntimeSeconds = requiredInteger(
    manifest.maximumRuntimeSeconds,
    'maximumRuntimeSeconds',
    300,
    3600,
  );
  return {
    ...manifest,
    maximumRuntimeSeconds,
    scenario: resolveGovernedScenario(manifest),
    thresholds,
  };
}

export function parseArguments(args) {
  const command = args.find(argument => !argument.startsWith('-')) || 'run';
  const options = {
    command,
    help: false,
    json: false,
  };
  for (const argument of args) {
    if (argument === '--help' || argument === '-h') options.help = true;
    else if (argument === '--json') options.json = true;
    else if (argument !== command) {
      throw new UsageError(`unknown option: ${argument}`);
    }
  }
  if (!allowedCommands.has(options.command)) {
    throw new UsageError(`unknown command: ${options.command}`);
  }
  return options;
}

export function usage() {
  return [
    'Usage: node scripts/product-readiness/browser-accessibility.mjs <command>',
    '',
    'Commands:',
    '  plan   Print the governed matrix without starting a runtime',
    '  run    Execute the real PC/H5 browser and accessibility matrix',
    '  ci     Execute only when the existing path scope selects the runtime',
    '',
    'Options:',
    '  --json Print the plan as JSON',
  ].join('\n');
}

export function printPlan(contract, jsonOutput) {
  const plan = {
    entrypoint: 'pnpm demo:runtime:browser-accessibility',
    locale: contract.locale,
    scenarioSource: contract.scenarioSource,
    scenario: contract.scenario,
    projects: contract.projects,
    viewports: contract.viewports,
    thresholds: contract.thresholds,
    evidenceRoot: '.runtime/browser-accessibility/<run-id>/',
    claims: contract.claims,
    nonClaims: contract.nonClaims,
    stages: [
      'reuse demo-quickstart.mjs and its backend/client/data lifecycle',
      'keep the accepted Quick Start runtime attached',
      'install bounded Playwright Firefox and WebKit runtimes',
      'run Chromium, Firefox and Playwright WebKit evidence projects',
      'exercise the authenticated PC task/detail/dialog path by keyboard',
      'audit PC/H5 critical controls, contrast and CJK glyph rendering',
      'stop Quick Start and require its cleanup evidence',
      'publish matrix claims only after runtime and cleanup succeed',
    ],
  };
  console.log(jsonOutput ? JSON.stringify(plan, null, 2) : [
    'Approval Platform browser/accessibility matrix',
    JSON.stringify(plan, null, 2),
  ].join('\n'));
}

export function runIdentifier() {
  return `${new Date().toISOString().replace(/[:.]/gu, '-')}`
    + `-${process.pid.toString(16)}`
    + `-${Math.random().toString(16).slice(2, 10)}`;
}

export function writeJson(path, value) {
  mkdirSync(resolve(path, '..'), { recursive: true, mode: 0o700 });
  writeFileSync(
    path,
    `${JSON.stringify(value, null, 2)}\n`,
    { encoding: 'utf8', mode: 0o600 },
  );
}

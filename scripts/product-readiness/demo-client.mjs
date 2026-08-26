#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const manifestPath = resolve(
  repositoryRoot,
  'config/demo/cross-client-local-demo.json',
);
const scenarioPath = resolve(
  repositoryRoot,
  'config/demo/purchase-payment-golden-path.json',
);
const clientCommands = new Set(['pc', 'h5', 'wechat']);
const commands = new Set(['plan', ...clientCommands]);

class UsageError extends Error {}

function usage() {
  return `Usage: node scripts/product-readiness/demo-client.mjs <command> [options]\n\nCommands:\n  plan      Print the deterministic cross-client launch and handoff plan.\n  pc        Start the PC workbench against the local backend.\n  h5        Start the UniApp H5 approval center against the local backend.\n  wechat    Prepare the WeChat Mini Program development build.\n\nOptions:\n  --actor <id>              Demo actor from the governed scenario manifest.\n  --backend-origin <origin> Local/private backend origin (default http://127.0.0.1:8080).\n  --port <number>           PC or H5 development port.\n  --skip-install            Reuse an already installed generated workspace.\n  --json                    Machine-readable output for plan.\n  --help                    Show this help.\n\nThe launcher starts one client role only. It does not execute or prove the\ncomplete PC/H5/WeChat scenario, WeChat physical-device behavior, payment\nsandbox delivery, browser compatibility, accessibility, capacity or recovery.`;
}

function optionValue(values, index, name) {
  const current = values[index];
  const inlinePrefix = `${name}=`;
  if (current.startsWith(inlinePrefix)) {
    return { consumed: 1, value: current.slice(inlinePrefix.length) };
  }
  if (current !== name) return undefined;
  const value = values[index + 1];
  if (!value || value.startsWith('--')) {
    throw new UsageError(`${name} requires a value`);
  }
  return { consumed: 2, value };
}

function parseArguments(argv) {
  const values = argv.filter(value => value !== '--');
  const command = values.shift() || 'plan';
  if (!commands.has(command)) throw new UsageError(`Unknown command: ${command}`);

  const options = {
    actor: undefined,
    backendOrigin: undefined,
    command,
    help: false,
    json: false,
    port: undefined,
    skipInstall: false,
  };

  for (let index = 0; index < values.length;) {
    const value = values[index];
    if (value === '--help') {
      options.help = true;
      index += 1;
      continue;
    }
    if (value === '--json') {
      options.json = true;
      index += 1;
      continue;
    }
    if (value === '--skip-install') {
      options.skipInstall = true;
      index += 1;
      continue;
    }
    const actor = optionValue(values, index, '--actor');
    if (actor) {
      options.actor = actor.value;
      index += actor.consumed;
      continue;
    }
    const backendOrigin = optionValue(values, index, '--backend-origin');
    if (backendOrigin) {
      options.backendOrigin = backendOrigin.value;
      index += backendOrigin.consumed;
      continue;
    }
    const port = optionValue(values, index, '--port');
    if (port) {
      options.port = port.value;
      index += port.consumed;
      continue;
    }
    throw new UsageError(`Unknown option: ${value}`);
  }

  if (command === 'plan' && (
    options.actor
    || options.backendOrigin
    || options.port
    || options.skipInstall
  )) {
    throw new UsageError('plan accepts only --json and --help');
  }
  if (command !== 'plan' && options.json) {
    throw new UsageError('--json is only available for plan');
  }
  if (command === 'wechat' && options.port) {
    throw new UsageError('--port is not available for wechat');
  }
  return options;
}

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function requireText(value, name) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`${name} must be a non-empty string`);
  }
  return value.trim();
}

function requireStringList(value, name) {
  if (!Array.isArray(value) || value.length === 0) {
    throw new Error(`${name} must be a non-empty array`);
  }
  const normalized = value.map((item, index) =>
    requireText(item, `${name}[${index}]`));
  if (new Set(normalized).size !== normalized.length) {
    throw new Error(`${name} must not contain duplicates`);
  }
  return normalized;
}

function normalizePort(value, name = '--port') {
  const port = typeof value === 'number' ? value : Number.parseInt(value, 10);
  if (!Number.isInteger(port) || port < 1024 || port > 65_535) {
    throw new UsageError(`${name} must be an integer between 1024 and 65535`);
  }
  return port;
}

function loadContract() {
  const manifest = readJson(manifestPath);
  const scenario = readJson(scenarioPath);
  if (manifest.schemaVersion !== 1) {
    throw new Error('unsupported cross-client schemaVersion');
  }
  if (manifest.scenarioManifest !== 'config/demo/purchase-payment-golden-path.json') {
    throw new Error('cross-client scenarioManifest is not canonical');
  }
  if (manifest.tenantId !== scenario.tenant.id) {
    throw new Error('cross-client tenant does not match the golden path');
  }
  if (manifest.businessKey !== scenario.request.businessKey) {
    throw new Error('cross-client businessKey does not match the golden path');
  }
  if (manifest.connectorKey !== scenario.directory.connectorKey) {
    throw new Error('cross-client connectorKey does not match the golden path');
  }

  const scenarioActors = new Set(scenario.directory.users.map(user => user.id));
  for (const command of clientCommands) {
    const client = manifest.clients?.[command];
    if (!client) throw new Error(`missing client contract: ${command}`);
    requireText(client.label, `${command}.label`);
    requireText(client.route, `${command}.route`);
    const actors = requireStringList(client.allowedActors, `${command}.allowedActors`);
    if (!actors.includes(client.defaultActor)) {
      throw new Error(`${command}.defaultActor is not allowed`);
    }
    for (const actor of actors) {
      if (!scenarioActors.has(actor)) {
        throw new Error(`${command} references unknown actor ${actor}`);
      }
    }
    if (command !== 'wechat') {
      normalizePort(client.defaultPort, `${command}.defaultPort`);
    }
  }

  const expected = scenario.expectedWorkflow.flatMap(step =>
    step.actorIds.map(actorId => `${step.taskDefinitionKey}:${actorId}`));
  const handoff = manifest.expectedHandoff.map(step =>
    `${step.taskDefinitionKey}:${step.actorId}`);
  if (JSON.stringify(handoff) !== JSON.stringify(expected)) {
    throw new Error('cross-client handoff does not match expectedWorkflow');
  }
  requireStringList(manifest.evidenceKeys, 'evidenceKeys');
  requireStringList(manifest.nonClaims, 'nonClaims');
  return { manifest, scenario };
}

function isPrivateIpv4(hostname) {
  const octets = hostname.split('.').map(value => Number.parseInt(value, 10));
  if (octets.length !== 4 || octets.some(value => !Number.isInteger(value))) {
    return false;
  }
  if (octets.some(value => value < 0 || value > 255)) return false;
  return octets[0] === 10
    || octets[0] === 127
    || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31)
    || (octets[0] === 192 && octets[1] === 168);
}

function normalizeBackendOrigin(value) {
  let origin;
  try {
    origin = new URL(value);
  } catch {
    throw new UsageError('--backend-origin must be an absolute URL');
  }
  const hostname = origin.hostname.toLowerCase();
  const localHost = hostname === 'localhost'
    || hostname === '::1'
    || isPrivateIpv4(hostname);
  if (origin.protocol !== 'http:' || !localHost || origin.username || origin.password) {
    throw new UsageError('--backend-origin must be a local/private HTTP origin');
  }
  if (origin.pathname !== '/' || origin.search || origin.hash) {
    throw new UsageError('--backend-origin must not contain a path, query or hash');
  }
  return origin.origin;
}

function pnpmExecutable() {
  return process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm';
}

function runNodeChecked(label, args) {
  console.log(`\n==> ${label}`);
  const result = spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    env: process.env,
    shell: false,
    stdio: 'inherit',
  });
  if (result.error) {
    throw new Error(`${label} could not start: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status}`);
  }
}

function runPnpmChecked(label, args) {
  console.log(`\n==> ${label}`);
  const result = spawnSync(pnpmExecutable(), args, {
    cwd: repositoryRoot,
    env: process.env,
    shell: false,
    stdio: 'inherit',
  });
  if (result.error) {
    throw new Error(`${label} could not start: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status}`);
  }
}

function resolvedClient(manifest, options) {
  const client = manifest.clients[options.command];
  const actor = options.actor?.trim() || client.defaultActor;
  if (!client.allowedActors.includes(actor)) {
    throw new UsageError(
      `actor ${actor} is not allowed for ${options.command}; expected one of `
      + client.allowedActors.join(', '),
    );
  }
  const backendOrigin = normalizeBackendOrigin(
    options.backendOrigin || manifest.defaultBackendOrigin,
  );
  const port = options.command === 'wechat'
    ? undefined
    : normalizePort(options.port || client.defaultPort);
  return { actor, backendOrigin, client, port };
}

function launchPlan(manifest) {
  return {
    schemaVersion: 1,
    tenantId: manifest.tenantId,
    businessKey: manifest.businessKey,
    backend: 'pnpm demo:backend:start',
    clients: {
      pc: 'pnpm demo:client:pc -- --actor demo-manager',
      h5: 'pnpm demo:client:h5 -- --actor demo-finance-reviewer',
      wechatA: 'pnpm demo:client:wechat -- --actor demo-finance-approver-a',
      wechatB: 'pnpm demo:client:wechat -- --actor demo-finance-approver-b',
      finalRead: 'pnpm demo:client:pc -- --actor demo-employee --port 5778',
    },
    expectedHandoff: manifest.expectedHandoff,
    evidenceKeys: manifest.evidenceKeys,
    nonClaims: manifest.nonClaims,
  };
}

function printPlan(manifest, jsonOutput) {
  const plan = launchPlan(manifest);
  if (jsonOutput) {
    console.log(JSON.stringify(plan, null, 2));
    return;
  }
  console.log('Approval Platform local cross-client plan');
  console.log(JSON.stringify(plan, null, 2));
}

function clientEnvironment(manifest, resolved, command) {
  const common = {
    ...process.env,
    VITE_APPROVAL_CONNECTOR: 'standalone',
    VITE_APPROVAL_CONNECTOR_KEY: manifest.connectorKey,
    VITE_APPROVAL_LOCAL_DEMO: 'true',
    VITE_APPROVAL_OPERATOR_ID: resolved.actor,
    VITE_APPROVAL_TENANT_ID: manifest.tenantId,
  };
  if (command === 'pc') {
    return {
      ...common,
      APPROVAL_DEMO_BACKEND_URL: resolved.backendOrigin,
      VITE_APPROVAL_API_URL: '/approval-api/api',
      VITE_PORT: String(resolved.port),
    };
  }
  if (command === 'h5') {
    return {
      ...common,
      VITE_APPROVAL_API_URL: '/api',
      VITE_APPROVAL_H5_API_URL: '/api',
      VITE_APPROVAL_WEIXIN_API_URL: `${resolved.backendOrigin}/api`,
      VITE_APP_PORT: String(resolved.port),
      VITE_APP_PROXY_ENABLE: 'true',
      VITE_APP_PROXY_PREFIX: '/approval-api',
      VITE_SERVER_BASEURL: resolved.backendOrigin,
    };
  }
  return {
    ...common,
    VITE_APPROVAL_API_URL: '/api',
    VITE_APPROVAL_H5_API_URL: '/api',
    VITE_APPROVAL_WEIXIN_API_URL: `${resolved.backendOrigin}/api`,
    VITE_APP_PROXY_ENABLE: 'false',
    VITE_SERVER_BASEURL: resolved.backendOrigin,
    VITE_WX_APPID: process.env.VITE_WX_APPID || '',
  };
}

function clientArguments(command) {
  if (command === 'pc') return ['web:dev'];
  if (command === 'h5') return ['mobile:dev:h5'];
  return ['mobile:dev:weixin'];
}

function installClient(command) {
  if (command === 'pc') {
    runPnpmChecked('Install generated Vben workspace', ['web:install']);
    return;
  }
  runPnpmChecked('Install generated UniApp workspace', ['mobile:install']);
}

function clientLocation(command, resolved) {
  const operator = encodeURIComponent(resolved.actor);
  if (command === 'pc') {
    return `http://127.0.0.1:${resolved.port}${resolved.client.route}`
      + `?demoOperator=${operator}`;
  }
  if (command === 'h5') {
    return `http://127.0.0.1:${resolved.port}/#${resolved.client.route}`
      + `?demoOperator=${operator}`;
  }
  return `${resolved.client.route}?demoOperator=${operator}`;
}

async function startClient(manifest, options) {
  const resolved = resolvedClient(manifest, options);
  runNodeChecked('Verify deterministic purchase-payment scenario', [
    'scripts/product-readiness/purchase-payment-scenario-contract.mjs',
  ]);
  if (!options.skipInstall) installClient(options.command);

  console.log('\nApproval Platform local client role');
  console.log(`client=${options.command}`);
  console.log(`actor=${resolved.actor}`);
  console.log(`tenant=${manifest.tenantId}`);
  console.log(`businessKey=${manifest.businessKey}`);
  console.log(`backend=${resolved.backendOrigin}`);
  console.log(`location=${clientLocation(options.command, resolved)}`);
  console.log('LOCAL_CROSS_CLIENT_ROLE_CONFIGURED');
  console.log('CROSS_CLIENT_RUNTIME_NOT_EXECUTED');
  console.log('PURCHASE_APPROVAL_E2E_NOT_EXECUTED');

  const child = spawn(pnpmExecutable(), clientArguments(options.command), {
    cwd: repositoryRoot,
    env: clientEnvironment(manifest, resolved, options.command),
    shell: false,
    stdio: 'inherit',
  });
  const [code, signal] = await once(child, 'exit');
  if (code !== 0) {
    throw new Error(
      `local ${options.command} client exited with code `
      + `${code ?? '<none>'} signal ${signal ?? '<none>'}`,
    );
  }
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }
  const { manifest } = loadContract();
  if (options.command === 'plan') {
    printPlan(manifest, options.json);
    return;
  }
  await startClient(manifest, options);
}

main().catch(error => {
  console.error(`LOCAL_CROSS_CLIENT_COMMAND_FAILED: ${error.message}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});

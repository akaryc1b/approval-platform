import { createHash } from 'node:crypto';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import type {
  APIRequestContext,
  Page,
  Response,
} from '@playwright/test';
import { expect } from '@playwright/test';

export const tenantId = 'demo-purchase-payment';
export const businessKey = 'DEMO-PP-0001';
export const backendOrigin = requiredEnvironment(
  'APPROVAL_DEMO_BACKEND_ORIGIN',
);
export const evidenceDirectory = requiredEnvironment(
  'APPROVAL_DEMO_EVIDENCE_DIR',
);
export const pcUrl = requiredEnvironment('APPROVAL_DEMO_PC_URL');
export const h5Url = requiredEnvironment('APPROVAL_DEMO_H5_URL');

export interface PendingTask {
  businessKey: string;
  instanceId: string;
  taskDefinitionKey: string;
  taskId: string;
}

interface TimelineItem {
  action: string;
  eventId: string;
  operatorId: string;
  requestId: string;
}

function requiredEnvironment(name: string) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

export function selectedHeaders(response: Response) {
  const headers = response.request().headers();
  return {
    requestId: headers['x-request-id'],
    traceId: headers['x-trace-id'],
    tenantId: headers['x-tenant-id'],
    operatorId: headers['x-operator-id'],
  };
}

function pageItems(payload: unknown): PendingTask[] {
  const direct = payload as { items?: PendingTask[] };
  if (Array.isArray(direct?.items)) return direct.items;
  const wrapped = payload as { data?: { items?: PendingTask[] } };
  if (Array.isArray(wrapped?.data?.items)) return wrapped.data.items;
  throw new Error('pending-task response does not contain an items array');
}

export async function pendingResponse(page: Page, actorId: string) {
  const response = await page.waitForResponse(async candidate => {
    if (candidate.request().method() !== 'GET'
      || !candidate.url().includes('/api/approval/tasks/pending')
      || candidate.status() !== 200) {
      return false;
    }
    const headers = candidate.request().headers();
    if (headers['x-tenant-id'] !== tenantId
      || headers['x-operator-id'] !== actorId) {
      return false;
    }
    try {
      return pageItems(await candidate.json())
        .some(item => item.businessKey === businessKey);
    } catch {
      return false;
    }
  }, { timeout: 60_000 });
  const task = pageItems(await response.json())
    .find(item => item.businessKey === businessKey);
  if (!task) throw new Error(`${actorId} response lost ${businessKey}`);
  return { response, task };
}

function demoHeaders(actorId: string, suffix: string) {
  const requestId = `pc-h5-runtime-${suffix}`;
  return {
    Accept: 'application/json',
    'X-Operator-Id': actorId,
    'X-Request-Id': requestId,
    'X-Tenant-Id': tenantId,
    'X-Trace-Id': requestId,
  };
}

export async function pendingForActor(
  request: APIRequestContext,
  actorId: string,
) {
  const query = new URLSearchParams({
    keyword: businessKey,
    limit: '20',
    offset: '0',
  });
  const response = await request.get(
    `${backendOrigin}/api/approval/tasks/pending?${query}`,
    { headers: demoHeaders(actorId, `${actorId}-pending`) },
  );
  expect(response.status()).toBe(200);
  return pageItems(await response.json())
    .filter(item => item.businessKey === businessKey);
}

export async function timeline(
  request: APIRequestContext,
  instanceId: string,
) {
  const response = await request.get(
    `${backendOrigin}/api/approval/instances/${instanceId}/timeline`,
    { headers: demoHeaders('demo-admin', 'timeline') },
  );
  expect(response.status()).toBe(200);
  const payload = await response.json() as { items?: TimelineItem[] };
  return payload.items || [];
}

export async function startedInstance(request: APIRequestContext) {
  const query = new URLSearchParams({
    keyword: businessKey,
    limit: '20',
    offset: '0',
  });
  const response = await request.get(
    `${backendOrigin}/api/approval/instances/started?${query}`,
    { headers: demoHeaders('demo-employee', 'started') },
  );
  expect(response.status()).toBe(200);
  const payload = await response.json() as {
    items?: Array<{
      businessKey: string;
      currentTaskDefinitionKey?: string;
      instanceId: string;
      status: string;
    }>;
  };
  const match = (payload.items || [])
    .find(item => item.businessKey === businessKey);
  if (!match) throw new Error(`started instance ${businessKey} is missing`);
  return match;
}

export function screenshotEvidence(file: string) {
  const absolute = resolve(evidenceDirectory, file);
  return {
    file,
    sha256: createHash('sha256')
      .update(readFileSync(absolute))
      .digest('hex'),
  };
}

export function writeEvidence(value: unknown) {
  const target = resolve(
    evidenceDirectory,
    'pc-h5-runtime-evidence.json',
  );
  const temporary = `${target}.tmp`;
  writeFileSync(
    temporary,
    `${JSON.stringify(value, null, 2)}\n`,
    { encoding: 'utf8', mode: 0o600 },
  );
  renameSync(temporary, target);
}

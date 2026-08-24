import { mkdirSync, renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import type {
  APIRequestContext,
  ConsoleMessage,
  Page,
  Request,
  Response,
} from '@playwright/test';

import {
  authoritativeActors,
  backendOrigin,
  businessKey,
  evidenceDirectory,
  tenantId,
} from './product-readiness-pc-h5-runtime-api';

const maximumEntries = 80;
const maximumTextLength = 12_000;
const diagnosticActors = [
  authoritativeActors.managerApproval,
  authoritativeActors.financeReview,
  ...authoritativeActors.financeCountersign,
] as const;

interface RequestSummary {
  at: string;
  actorId?: string;
  failure?: string;
  method: string;
  requestId?: string;
  tenantId?: string;
  traceId?: string;
  url: string;
}

interface ResponseSummary extends RequestSummary {
  body?: unknown;
  bodyError?: string;
  status: number;
}

export interface PageRuntimeDiagnostics {
  actorId: string;
  client: 'h5' | 'pc';
  consoleErrors: string[];
  failedRequests: RequestSummary[];
  page: Page;
  pageErrors: string[];
  pendingBodyReads: Promise<void>[];
  requests: RequestSummary[];
  responses: ResponseSummary[];
}

function bounded(value: unknown, limit = maximumTextLength) {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  if (!text) return '';
  return text.length <= limit ? text : `${text.slice(0, limit)}…`;
}

function errorText(error: unknown) {
  return error instanceof Error
    ? error.stack || error.message
    : bounded(error);
}

function selectedRequestHeaders(request: Request) {
  const headers = request.headers();
  return {
    actorId: headers['x-operator-id'],
    requestId: headers['x-request-id'],
    tenantId: headers['x-tenant-id'],
    traceId: headers['x-trace-id'],
  };
}

function requestSummary(request: Request): RequestSummary {
  return {
    at: new Date().toISOString(),
    method: request.method(),
    url: request.url(),
    ...selectedRequestHeaders(request),
  };
}

function retainRecent<T>(items: T[], value: T) {
  items.push(value);
  if (items.length > maximumEntries) items.shift();
}

function consoleError(message: ConsoleMessage) {
  const location = message.location();
  const source = location.url
    ? ` @ ${location.url}:${location.lineNumber ?? 0}:${location.columnNumber ?? 0}`
    : '';
  return bounded(`${message.type()}: ${message.text()}${source}`);
}

export function attachPageRuntimeDiagnostics(
  page: Page,
  client: 'h5' | 'pc',
  actorId: string,
): PageRuntimeDiagnostics {
  const diagnostics: PageRuntimeDiagnostics = {
    actorId,
    client,
    consoleErrors: [],
    failedRequests: [],
    page,
    pageErrors: [],
    pendingBodyReads: [],
    requests: [],
    responses: [],
  };

  page.on('request', request => {
    retainRecent(diagnostics.requests, requestSummary(request));
  });
  page.on('requestfailed', request => {
    retainRecent(diagnostics.failedRequests, {
      ...requestSummary(request),
      failure: request.failure()?.errorText || 'unknown request failure',
    });
  });
  page.on('response', response => {
    const summary: ResponseSummary = {
      ...requestSummary(response.request()),
      status: response.status(),
    };
    retainRecent(diagnostics.responses, summary);
    if (new URL(response.url()).pathname === '/api/approval/tasks/pending') {
      const bodyRead = response.json()
        .then(body => {
          summary.body = body;
        })
        .catch(error => {
          summary.bodyError = errorText(error);
        });
      diagnostics.pendingBodyReads.push(bodyRead);
    }
  });
  page.on('console', message => {
    if (message.type() === 'error') {
      retainRecent(diagnostics.consoleErrors, consoleError(message));
    }
  });
  page.on('pageerror', error => {
    retainRecent(diagnostics.pageErrors, errorText(error));
  });

  return diagnostics;
}

async function pageSnapshot(diagnostics: PageRuntimeDiagnostics) {
  await Promise.allSettled(diagnostics.pendingBodyReads);
  const { page } = diagnostics;
  const [title, bodyText, taskItemCount, taskCardCount] = await Promise.all([
    page.title().catch(error => `TITLE_ERROR: ${errorText(error)}`),
    page.locator('body').innerText({ timeout: 5_000 })
      .catch(error => `BODY_ERROR: ${errorText(error)}`),
    page.locator('.task-item').count().catch(() => -1),
    page.locator('.task-card').count().catch(() => -1),
  ]);
  return {
    actorId: diagnostics.actorId,
    bodyText: bounded(bodyText),
    client: diagnostics.client,
    consoleErrors: diagnostics.consoleErrors,
    currentUrl: page.url(),
    failedRequests: diagnostics.failedRequests,
    pageErrors: diagnostics.pageErrors,
    requests: diagnostics.requests,
    responses: diagnostics.responses,
    taskCardCount,
    taskItemCount,
    title: bounded(title),
  };
}

function apiHeaders(actorId: string, suffix: string) {
  const requestId = `pc-h5-runtime-diagnostic-${suffix}`;
  return {
    Accept: 'application/json',
    'Cache-Control': 'no-store',
    'X-Operator-Id': actorId,
    'X-Request-Id': requestId,
    'X-Tenant-Id': tenantId,
    'X-Trace-Id': requestId,
  };
}

async function readJson(
  request: APIRequestContext,
  actorId: string,
  suffix: string,
  url: string,
) {
  try {
    const response = await request.get(url, {
      headers: apiHeaders(actorId, suffix),
      timeout: 8_000,
    });
    const text = await response.text();
    let body: unknown = text;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      // Bounded raw text remains diagnostic evidence.
    }
    return {
      actorId,
      body,
      status: response.status(),
      url,
    };
  } catch (error) {
    return {
      actorId,
      error: errorText(error),
      url,
    };
  }
}

function items(payload: unknown) {
  const direct = payload as { items?: unknown[] };
  if (Array.isArray(direct?.items)) return direct.items;
  const wrapped = payload as { data?: { items?: unknown[] } };
  if (Array.isArray(wrapped?.data?.items)) return wrapped.data.items;
  return [];
}

function taskIdentity(value: unknown) {
  const task = value as Record<string, unknown>;
  return {
    businessKey: task.businessKey ?? null,
    instanceId: task.instanceId ?? task.processInstanceId ?? null,
    taskDefinitionKey: task.taskDefinitionKey ?? null,
    taskId: task.taskId ?? task.id ?? null,
  };
}

async function backendSnapshot(
  request: APIRequestContext,
  knownProcessInstanceId?: string,
) {
  const pendingReads = await Promise.all(diagnosticActors.map(actorId => {
    const query = new URLSearchParams({
      keyword: businessKey,
      limit: '20',
      offset: '0',
    });
    return readJson(
      request,
      actorId,
      `${actorId}-pending`,
      `${backendOrigin}/api/approval/tasks/pending?${query}`,
    );
  }));
  const pending = pendingReads.map(result => ({
    ...result,
    assignmentSemantics: 'operator-scoped pending task visibility',
    matchingTasks: 'body' in result
      ? items(result.body)
          .filter(value => (value as { businessKey?: string }).businessKey === businessKey)
          .map(taskIdentity)
      : [],
  }));
  const discoveredInstanceId = knownProcessInstanceId
    || pending.flatMap(result => result.matchingTasks)
      .map(task => task.instanceId)
      .find(value => typeof value === 'string');

  const startedQuery = new URLSearchParams({
    keyword: businessKey,
    limit: '20',
    offset: '0',
  });
  const started = await readJson(
    request,
    'demo-employee',
    'started',
    `${backendOrigin}/api/approval/instances/started?${startedQuery}`,
  );
  const processState = discoveredInstanceId
    ? await readJson(
        request,
        'demo-admin',
        'instance',
        `${backendOrigin}/api/approval/instances/${discoveredInstanceId}`,
      )
    : { error: 'processInstanceId unavailable' };
  const processTimeline = discoveredInstanceId
    ? await readJson(
        request,
        'demo-admin',
        'timeline',
        `${backendOrigin}/api/approval/instances/${discoveredInstanceId}/timeline`,
      )
    : { error: 'processInstanceId unavailable' };

  return {
    assignmentSource: 'config/demo/purchase-payment-golden-path.json',
    authoritativeActors,
    businessKey,
    pending,
    processInstanceId: discoveredInstanceId ?? null,
    processState,
    processTimeline,
    started,
    tenantId,
  };
}

function writeAtomically(fileName: string, value: string) {
  mkdirSync(evidenceDirectory, { mode: 0o700, recursive: true });
  const target = resolve(evidenceDirectory, fileName);
  const temporary = `${target}.tmp`;
  writeFileSync(temporary, value, { encoding: 'utf8', mode: 0o600 });
  renameSync(temporary, target);
}

function diagnosticScreenshotFile(diagnostics: PageRuntimeDiagnostics) {
  const actor = diagnostics.actorId.replace(/[^0-9a-z-]+/giu, '-');
  return `${diagnostics.client}-${actor}-runtime-failure.png`;
}

async function diagnosticScreenshot(diagnostics: PageRuntimeDiagnostics) {
  const file = diagnosticScreenshotFile(diagnostics);
  try {
    await diagnostics.page.screenshot({
      fullPage: true,
      path: resolve(evidenceDirectory, file),
    });
    return { file, status: 'CAPTURED' };
  } catch (error) {
    return { error: errorText(error), file, status: 'FAILED' };
  }
}

export async function writeRuntimeFailureDiagnostics({
  error,
  pages,
  processInstanceId,
  request,
}: {
  error: unknown;
  pages: PageRuntimeDiagnostics[];
  processInstanceId?: string;
  request: APIRequestContext;
}) {
  mkdirSync(evidenceDirectory, { mode: 0o700, recursive: true });
  const pageStates = await Promise.all(pages.map(pageSnapshot));
  const screenshots = await Promise.all(pages.map(diagnosticScreenshot));
  const backend = await backendSnapshot(request, processInstanceId);
  const evidence = {
    schemaVersion: 1,
    evidenceKind: 'PC_H5_BROWSER_RUNTIME_FAILURE_DIAGNOSTICS_V1',
    status: 'FAILED',
    capturedAt: new Date().toISOString(),
    commitSha: process.env.APPROVAL_DEMO_EXACT_HEAD_SHA
      || process.env.GITHUB_SHA
      || null,
    githubRunId: process.env.GITHUB_RUN_ID || null,
    actorIds: pages.map(page => page.actorId),
    tenantId,
    businessKey,
    processInstanceId: backend.processInstanceId,
    error: {
      message: error instanceof Error ? error.message : bounded(error),
      name: error instanceof Error ? error.name : typeof error,
      stack: errorText(error),
    },
    pages: pageStates,
    screenshots,
    backend,
  };
  writeAtomically(
    'runtime-diagnostics.json',
    `${JSON.stringify(evidence, null, 2)}\n`,
  );
  writeAtomically(
    'runtime-diagnostics.md',
    [
      '# PC/H5 runtime failure diagnostics',
      '',
      `- tenantId: \`${tenantId}\``,
      `- businessKey: \`${businessKey}\``,
      `- processInstanceId: \`${backend.processInstanceId ?? 'UNAVAILABLE'}\``,
      `- error: \`${bounded(evidence.error.message, 1_000)}\``,
      '- machine evidence: `runtime-diagnostics.json`',
      `- screenshots: ${screenshots.map(value => `\`${value.file}\``).join(', ')}`,
      '- Playwright trace and error context remain under `playwright/`.',
      '',
    ].join('\n'),
  );
  console.error(`PC_H5_RUNTIME_DIAGNOSTICS=${JSON.stringify({
    businessKey,
    processInstanceId: backend.processInstanceId,
    tenantId,
    pageStates: pageStates.map(page => ({
      actorId: page.actorId,
      client: page.client,
      consoleErrorCount: page.consoleErrors.length,
      currentUrl: page.currentUrl,
      failedRequestCount: page.failedRequests.length,
      pageErrorCount: page.pageErrors.length,
      requestCount: page.requests.length,
      responseCount: page.responses.length,
      taskCardCount: page.taskCardCount,
      taskItemCount: page.taskItemCount,
    })),
  })}`);
}

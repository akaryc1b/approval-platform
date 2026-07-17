#!/usr/bin/env node

import { ConnectorClient, ConnectorHttpError, loadConfiguration } from './src/client.mjs';
import { executeCommand, parseArguments } from './src/commands.mjs';
import { redact, safeErrorMessage } from './src/redaction.mjs';

const HELP = `Approval Platform Connector Smoke CLI

Usage:
  node cli.mjs auth
  node cli.mjs user --user-id 10086
  node cli.mjs search [--keyword alice] [--department-id 100] [--role admin]
                      [--position manager] [--active true] [--page 0] [--size 20]
  node cli.mjs manager-chain --user-id 10086 [--levels 10]
  node cli.mjs suite [--user-id 10086] [--levels 10] [--size 10]

Required environment:
  APPROVAL_HOST_URL       HTTPS host URL, or loopback HTTP for local development
  APPROVAL_TENANT_ID      Connector tenant ID
  APPROVAL_KEY_ID         HMAC key ID
  APPROVAL_HOST_SECRET    UTF-8 secret or base64:<value>, at least 32 bytes
  APPROVAL_SOURCE         generic, ruoyi5, ruoyi6, or another configured source

Required for auth/suite:
  APPROVAL_CREDENTIAL     Bearer/Sa-Token credential; never pass this on the CLI

Optional environment:
  APPROVAL_CREDENTIAL_TYPE  Defaults to SA_TOKEN
  APPROVAL_TIMEOUT_MS       Defaults to 10000
`;

let client;
try {
  const { command, options } = parseArguments(process.argv.slice(2));
  if (command === 'help' || command === '--help' || command === '-h') {
    process.stdout.write(HELP);
  } else {
    const configuration = loadConfiguration();
    client = new ConnectorClient(configuration);
    const result = await executeCommand(client, command, options);
    process.stdout.write(`${JSON.stringify(redact(result), null, 2)}\n`);
  }
} catch (error) {
  const diagnostic = {
    code: error instanceof ConnectorHttpError ? error.code : 'SMOKE_CLIENT_ERROR',
    details: error instanceof ConnectorHttpError ? error.details : null,
    message: safeErrorMessage(error, [
      process.env.APPROVAL_HOST_SECRET,
      process.env.APPROVAL_CREDENTIAL,
    ]),
    requestId: error instanceof ConnectorHttpError ? error.requestId : null,
    retryable: error instanceof ConnectorHttpError ? error.retryable : false,
    status: error instanceof ConnectorHttpError ? error.status : 0,
  };
  process.stderr.write(`${JSON.stringify(redact(diagnostic), null, 2)}\n`);
  process.exitCode = 1;
} finally {
  client?.destroy();
}

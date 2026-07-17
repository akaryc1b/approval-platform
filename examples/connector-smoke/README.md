# Connector Smoke CLI

Zero-dependency Node.js 22 client for the signed Generic REST connector contract.

The same executable can target:

- Generic REST hosts;
- RuoYi-Vue-Plus 5.X Host Starter;
- RuoYi-Vue-Plus 6.X Host Starter;
- custom hosts that implement `docs/GENERIC_REST_CONNECTOR.md`.

## Security rules

- HMAC secrets and bearer/Sa-Token credentials are read only from environment variables.
- Sensitive CLI options such as `--credential`, `--token`, `--secret` and `--signature` are rejected.
- Remote hosts must use HTTPS. Plain HTTP is accepted only for loopback development.
- URLs containing credentials, query strings or fragments are rejected.
- Structured error details redact credentials, tokens, signatures, passwords and secrets.
- The in-memory HMAC key buffer is overwritten when the client exits.

Do not commit a populated environment file.

## Configuration

Copy the template outside version control or export the variables directly:

```bash
cp examples/connector-smoke/.env.example /tmp/approval-connector.env
set -a
. /tmp/approval-connector.env
set +a
```

Required variables:

```text
APPROVAL_HOST_URL
APPROVAL_TENANT_ID
APPROVAL_KEY_ID
APPROVAL_HOST_SECRET
APPROVAL_SOURCE
```

`auth` and `suite` additionally require:

```text
APPROVAL_CREDENTIAL
```

The Starter and CLI must use the same tenant ID, key ID and HMAC secret.

## Commands

Check authentication:

```bash
node examples/connector-smoke/cli.mjs auth
```

Read one user:

```bash
node examples/connector-smoke/cli.mjs user --user-id 10086
```

Search users:

```bash
node examples/connector-smoke/cli.mjs search \
  --keyword alice \
  --role finance-manager \
  --active true \
  --page 0 \
  --size 20
```

Resolve a manager chain:

```bash
node examples/connector-smoke/cli.mjs manager-chain \
  --user-id 10086 \
  --levels 10
```

Run the authentication, current-user lookup, search and manager-chain checks together:

```bash
node examples/connector-smoke/cli.mjs suite
```

Output contains only the host response after recursive redaction. Errors are written as structured JSON to stderr and return a non-zero exit code.

## Signing compatibility

The CLI uses the same canonical JSON and signature input as the Java Host SDK:

```text
<epoch-seconds>\n<nonce>\n<canonical-json-body>
```

Header value:

```text
v1=<lowercase-hex-hmac-sha256>
```

Object keys are sorted recursively before signing. Arrays retain input order.

## Tests

```bash
node --test examples/connector-smoke/test/*.test.mjs
node --check examples/connector-smoke/cli.mjs
```

The tests cover the shared Java/Node signature vector, canonical JSON, HTTP headers, URL safety, redaction and sensitive argument rejection.

## RuoYi menu entry

Version-specific MySQL examples are located under:

```text
examples/connector-smoke/ruoyi-menu/
```

They create an external top-level menu that opens the independently deployed Approval Platform web application. Review IDs, role assignment and the target URL before execution.

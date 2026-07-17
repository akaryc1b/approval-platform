# Generic Spring Host Example

Runnable Spring Boot 4 implementation of the Approval Platform Generic REST host contract.

Use this example when the host system is not RuoYi or when building a new connector from scratch.

## Included behavior

- all authentication and organization endpoints from `docs/GENERIC_REST_CONNECTOR.md`;
- shared Java Host SDK HMAC verification;
- timestamp freshness checks;
- nonce replay protection;
- structured error envelopes;
- fixture users, departments, roles, positions and manager chains;
- constant-time demo bearer-token validation;
- Actuator health endpoint;
- executable JAR and non-root Dockerfile.

The fixture principal is:

```text
user ID: 100
username: alice
manager: bob (user ID 200)
```

## Development-only boundaries

This example uses `InMemoryReplayGuard`, which is safe only for tests and single-node development. A production host must replace it with a distributed atomic implementation such as Redis `SET NX` with TTL.

The organization directory is in memory. Replace `ExampleOrganizationDirectory` with the host's user/department services while preserving the response contracts and stable external IDs.

The configured bearer token is a demonstration credential, not a user authentication system.

## Run locally

Use Java 21:

```bash
mvn -B -ntp -pl examples/generic-spring-host -am package
```

Export configuration outside version control:

```bash
export EXAMPLE_HOST_SECRET='0123456789abcdef0123456789abcdef'
export EXAMPLE_HOST_TOKEN='smoke-token'
export EXAMPLE_HOST_TENANT_ID='demo'
export EXAMPLE_HOST_KEY_ID='demo-key'
```

Start the application:

```bash
java -jar examples/generic-spring-host/target/approval-generic-spring-host-example-0.1.0-SNAPSHOT.jar
```

Health check:

```bash
curl --fail http://127.0.0.1:18080/actuator/health
```

## Verify with the Smoke CLI

In another shell:

```bash
export APPROVAL_HOST_URL='http://127.0.0.1:18080'
export APPROVAL_TENANT_ID='demo'
export APPROVAL_KEY_ID='demo-key'
export APPROVAL_HOST_SECRET='0123456789abcdef0123456789abcdef'
export APPROVAL_SOURCE='generic'
export APPROVAL_CREDENTIAL='smoke-token'

node examples/connector-smoke/cli.mjs suite
```

The suite verifies:

1. authentication;
2. current-user lookup;
3. user search;
4. manager-chain resolution.

## Docker

Build the JAR, then build the image:

```bash
mvn -B -ntp -pl examples/generic-spring-host -am package
docker build -t approval-generic-host:local examples/generic-spring-host
```

Run without embedding secrets in the image:

```bash
docker run --rm -p 18080:18080 \
  -e EXAMPLE_HOST_SECRET='0123456789abcdef0123456789abcdef' \
  -e EXAMPLE_HOST_TOKEN='smoke-token' \
  approval-generic-host:local
```

## Production replacement checklist

- replace fixture authentication with the host identity provider;
- replace the in-memory directory with authoritative organization services;
- replace `InMemoryReplayGuard` with distributed atomic storage;
- keep secrets in a secret manager;
- use HTTPS between Approval Platform and the host;
- preserve HMAC verification before JSON parsing and business calls;
- preserve tenant, request and trace identifiers;
- add connector-specific audit events and metrics;
- keep host framework entities out of connector responses.

# Examples

## Available

### Connector Smoke CLI

```text
examples/connector-smoke
```

A zero-dependency Node.js 22 client for authentication, user lookup, user search and manager-chain verification against:

- Generic REST hosts;
- RuoYi-Vue-Plus 5.X Host Starter;
- RuoYi-Vue-Plus 6.X Host Starter.

The example also includes version-specific RuoYi MySQL menu SQL.

### Generic Spring Host

```text
examples/generic-spring-host
```

A runnable Spring Boot 4 reference host with:

- all signed authentication and organization endpoints;
- Java Host SDK verification;
- fixture users, departments, roles, positions and manager chains;
- executable JAR and non-root Dockerfile;
- end-to-end verification through the Connector Smoke CLI.

### Purchase Payment Approval

```text
examples/purchase-payment
```

Versioned Approval DSL and Form Schema fixtures for the first end-to-end business workflow. The process includes manager approval, an amount-based finance review branch and parallel finance countersign.

## Planned

- non-Java REST/Webhook host example;
- purchase-payment application APIs, PostgreSQL projections and PC/mobile completion flow.

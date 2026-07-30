# SDK adapter binding v1

## Scope

This contract binds a logical endpoint, server-issued authentication context, credential reference/lease and adapter lifecycle before transport policy execution. It does not contain an endpoint address, DNS name, usable credential material, network client, clock, scheduler, persistence or delivery runtime.

## Binding version

- `bindingVersion` is `1`; unknown versions fail closed.
- An endpoint descriptor is server-owned and identifies a logical adapter kind, audience, supported operations and credential reference.
- Duplicate supported operations are invalid.
- A request whose operation is not declared by both the endpoint and transport policy is rejected before authentication or credential resolution.

## Server authentication context

An authentication-context resolver receives only logical endpoint ID, operation, request ID and trace ID. It issues a nominal server context containing tenant, operator, permission-snapshot hash, audit reference and an active interval.

The context cannot be supplied by `ApprovalRequest`. It is active only when:

```text
authenticatedAt <= evaluatedAt < expiresAt
```

## Credential reference and lease

The endpoint contains only:

- provider ID;
- credential ID;
- credential kind.

A credential provider issues a nominal short-lived lease containing references and binding evidence only. No bearer value, private key, certificate bytes, header value or password enters the SDK contract.

The deterministic binding ID is:

```text
<endpoint-id>\n<context-id>\n<credential-id>\n<operation>
```

A lease must match the endpoint, server context, operation and credential reference. Every issued lease is released with `completed`, `binding_failed` or `adapter_failed`.

## Adapter lifecycle

A security-bound adapter follows:

```text
created -> open -> closed
```

Exchange is valid only while open. Every attempt receives the same request identity, endpoint descriptor, authentication context and credential lease. Context or lease expiry before an attempt maps to an unauthorized structured response and never refreshes itself inside the SDK.

## Trust boundary

Public requests, compatibility profiles, transport policies and logical endpoint descriptors cannot provide trusted tenant, operator, permission, authority or audit evidence. Authentication contexts and leases are issued by server-side resolver/provider abstractions.

## Fixture

`fixtures/adapter-binding-v1.json` is deterministic cross-language test data. Java and TypeScript must produce the same binding result, lifecycle, lease release, transport trace and request-identity preservation result.

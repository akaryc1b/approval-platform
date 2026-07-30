# SDK compatibility governance v1

## Scope

This contract is pure negotiation metadata. It does not perform discovery, HTTP, subscription persistence, event delivery or credential resolution.

## Manifest and semantic versions

- `manifestVersion` is `1`; every unknown manifest version is rejected fail-closed.
- SDK and contract versions use strict `major.minor.patch` semantic versions without prerelease or build suffixes.
- A client older than `minimumClientVersion` is rejected before schema, protocol or capability selection.
- `supportedUntil` is an RFC 3339 UTC instant. At or after that instant the contract is outside its declared support window and negotiation fails closed.

## Selection

The server lists event schemas and Webhook protocols in preference order. Negotiation selects the first server-preferred value accepted by the client. No common event schema or Webhook protocol is a terminal incompatibility.

Capabilities are requested explicitly:

- an unknown required capability fails closed;
- an unknown optional capability is omitted and returned as a warning;
- duplicate capability requests are invalid;
- enabled capabilities preserve client request order.

## Deprecation and sunset

A deprecation notice contains `deprecatedSince`, `sunsetAt` and `replacement`.

- Before `deprecatedSince`, the capability is enabled without a warning.
- From `deprecatedSince` until `sunsetAt`, the capability remains enabled with a warning.
- At or after `sunsetAt`, a required capability fails negotiation and an optional capability is omitted with a warning.
- `sunsetAt` must be later than `deprecatedSince`.

## Trust boundary

Client compatibility profiles contain SDK versions, accepted schemas/protocols and capability requirements only. They cannot provide trusted tenant, operator, permission, authority or audit evidence. Those values remain server-derived.

## Fixture

`fixtures/sdk-compatibility-v1.json` is deterministic, fixture-only data shared by Java and TypeScript tests. The capability prefixed with `fixture.` is not a production capability declaration.

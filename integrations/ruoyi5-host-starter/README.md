# RuoYi-Vue-Plus 5.X Host Starter

This integration exposes the Approval Platform Generic REST host contract from a RuoYi-Vue-Plus 5.X application.

## Upstream baseline

```text
branch: 5.X
commit: e49f02f89e17ee5a4cc14048af99cc83d72872a7
revision: 5.6.2
Java: 17
Spring Boot: 3.5.15
```

The committed source is an overlay. `scripts/upstream/bootstrap-ruoyi5.mjs` generates a clean workspace under `.upstream/ruoyi5`, verifies the exact commit, and injects the Starter into `ruoyi-extend`.

## Enable the Starter

Add the dependency to the RuoYi application module:

```xml
<dependency>
    <groupId>org.dromara</groupId>
    <artifactId>ruoyi-approval-host-starter</artifactId>
</dependency>
```

The Starter is disabled by default. Configure tenant-scoped keys through encrypted configuration or a secret manager:

```yaml
approval:
  host:
    enabled: true
    source: ruoyi5
    allowed-clock-skew: 5m
    nonce-ttl: 10m
    tenants:
      "000000":
        key-id: approval-key-2026-01
        secret: ${APPROVAL_HOST_SECRET_000000}
```

A secret may be plain UTF-8 or prefixed with `base64:`. Production secrets must contain at least 32 bytes.

## Security model

- All endpoints are exempt from the normal interactive Sa-Token interceptor with `@SaIgnore`.
- Requests are authenticated by tenant-scoped HMAC before JSON parsing or RuoYi service invocation.
- Nonces are reserved through Redis atomic `setIfAbsent` after successful signature validation.
- Token credentials are supplied inside the signed authentication request and are never logged.
- Tenant-scoped database queries execute inside `TenantHelper.dynamic(...)`.
- The tenant in the signed header must match the tenant stored in the Sa-Token login session.

## Exposed endpoints

```text
POST /api/approval-connector/v1/authenticate
POST /api/approval-connector/v1/organization/users/find
POST /api/approval-connector/v1/organization/users/search
POST /api/approval-connector/v1/organization/departments/find
POST /api/approval-connector/v1/organization/roles/find
POST /api/approval-connector/v1/organization/roles/members
POST /api/approval-connector/v1/organization/positions/find
POST /api/approval-connector/v1/organization/positions/members
POST /api/approval-connector/v1/organization/users/manager-chain
```

## Build locally

Install the Host SDK first with Java 21; it emits Java 17 bytecode:

```bash
mvn -B -ntp -pl integrations/host-sdk -am -DskipTests install
```

Then use Java 17:

```bash
node scripts/upstream/bootstrap-ruoyi5.mjs
mvn -B -ntp -f .upstream/ruoyi5/pom.xml \
  -pl ruoyi-extend/ruoyi-approval-host-starter \
  -am -DskipTests package
```

The CI workflow performs these steps against the pinned upstream source.

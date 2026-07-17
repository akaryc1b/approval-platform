# RuoYi-Vue-Plus 6.X Host Starter

This integration exposes the Approval Platform Generic REST host contract from a RuoYi-Vue-Plus 6.X application.

## Upstream baseline

```text
branch: 6.X
commit: da5f30cae2deb174a1ba37a2ad41ff1ba42c9f38
revision: 6.0.0-BETA
Java: 21
Spring Boot: 4.1.0
```

The committed source is an overlay. `scripts/upstream/bootstrap-ruoyi6.mjs` creates `.upstream/ruoyi6`, verifies the exact commit, injects the Starter into `ruoyi-extend`, and wires it into the generated `ruoyi-admin` application.

## Tenancy boundary

The pinned 6.X baseline has no `ruoyi-common-tenant` module and its `LoginUser` carries no tenant ID. The default adapter therefore binds one RuoYi deployment to exactly one Approval Platform tenant.

```yaml
approval:
  host:
    enabled: true
    source: ruoyi6
    tenant-id: approval-tenant-a
    tenant-name: Primary RuoYi 6 deployment
    allowed-clock-skew: 5m
    nonce-ttl: 10m
    tenants:
      approval-tenant-a:
        key-id: approval-key-2026-01
        secret: ${APPROVAL_HOST_SECRET}
```

Requests for any other platform tenant are rejected before organization or authentication services are invoked. A host project with its own tenancy extension may replace the `Ruoyi6TenantBridge` bean.

## Security

- all connector requests are HMAC verified through the shared Host SDK;
- the connector path is added to RuoYi `SecurityProperties.excludes` without replacing existing exclusions;
- Redis atomic `setIfAbsent` semantics provide distributed nonce replay protection;
- raw Sa-Token credentials and signing secrets are never returned in errors;
- invalid signatures do not reserve nonces;
- endpoints are disabled unless `approval.host.enabled=true`.

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

```bash
mvn -B -ntp -pl integrations/host-sdk -am -DskipTests install
node scripts/upstream/bootstrap-ruoyi6.mjs
mvn -B -ntp -f .upstream/ruoyi6/pom.xml -pl ruoyi-admin -am -DskipTests package
```

The dedicated CI workflow builds the complete generated application and runs every `Ruoyi6*Test` contract test against the pinned upstream source.

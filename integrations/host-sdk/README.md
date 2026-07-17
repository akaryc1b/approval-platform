# Approval Host SDK

Framework-neutral host integration support for RuoYi and other systems.

## Responsibilities

- Verify signed requests from approval-platform
- Resolve tenant credentials
- Expose common connector response contracts
- Protect host systems from leaking framework entities

## Rules

- No Flowable dependency
- No approval database dependency
- No RuoYi dependency
- No direct ORM entity exposure

Implementations:

- ruoyi5-host-starter
- ruoyi6-host-starter
- generic-host-starter

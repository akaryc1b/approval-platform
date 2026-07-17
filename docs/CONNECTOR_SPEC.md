# Connector Specification

连接器让审批平台接入不同身份、组织、文件、消息和业务系统，同时保持核心独立。

## 连接器能力

- Authentication Provider
- Organization Provider
- File Storage Provider
- Notification Provider
- Business Callback Provider
- Form Data Source Provider
- External Todo Provider

## 统一组织模型

```text
Tenant
User
Department
Role
Position
Group
ManagerRelation
```

连接器返回平台快照对象，不把宿主实体和 ORM 对象传入核心。

## 外部请求头

```text
Authorization
X-Tenant-Id
X-Request-Id
Idempotency-Key
X-Signature
X-Timestamp
```

## 业务事件

- `PROCESS_STARTED`
- `TASK_CREATED`
- `TASK_COMPLETED`
- `PROCESS_APPROVED`
- `PROCESS_REJECTED`
- `PROCESS_WITHDRAWN`
- `PROCESS_TERMINATED`
- `PROCESS_CANCELED`

Webhook 必须签名、可重试、可去重并记录投递结果。

## 首批连接器

- Generic REST/OIDC
- RuoYi-Vue-Plus 5.X
- RuoYi-Vue-Plus 6.X
- DingTalk
- Feishu

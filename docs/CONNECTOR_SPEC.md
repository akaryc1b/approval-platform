# Connector Specification

连接器让审批平台接入不同身份、组织、文件、消息和业务系统，同时保持审批核心独立。公共 Java 契约位于：

```text
server-modules/approval-connector-spi
```

该模块只依赖 JDK，禁止依赖 Spring、Flowable、Sa-Token、RuoYi 或特定 HTTP 客户端。

## 能力发现

每个实现提供一个 `ConnectorProvider`，声明名称、版本和能力：

```text
AUTHENTICATION
ORGANIZATION
FILE_STORAGE
NOTIFICATION
BUSINESS_CALLBACK
FORM_DATA_SOURCE
EXTERNAL_TODO
```

平台必须根据能力集合启用功能，不能假定所有连接器都支持全部端口。

## 调用上下文

每次调用都必须携带 `ConnectorContext`：

```text
connectorKey
platformTenantId
requestId
traceId
requestedAt
```

连接器不得从 ThreadLocal、HTTP Request 或宿主静态工具类隐式获取租户和用户上下文。

## 稳定外部标识

所有宿主对象使用 `ExternalId`：

```text
source:objectType:value
```

示例：

```text
ruoyi5:user:10086
ruoyi6:department:2001
dingtalk:user:manager01
feishu:tenant:cli_a123
```

平台数据库保存完整三元组，不把外部数值 ID 转换为平台主键后丢失来源。关系快照也使用 `ExternalId`。

## 统一组织快照

当前公共快照包括：

- `TenantSnapshot`
- `UserSnapshot`
- `DepartmentSnapshot`
- `RoleSnapshot`
- `PositionSnapshot`

快照集合和属性在创建时防御性复制，连接器返回后不得通过原集合修改审批历史。

默认策略：

```text
任务创建后：办理人与组织关系固化为快照
尚未创建的后续节点：按当前组织数据重新解析
```

## 七类端口

### AuthenticationConnector

将 OIDC、OAuth2、Sa-Token、自定义 Token 或办公平台免登凭据转换为：

- 当前用户快照；
- 当前租户快照；
- 平台权限集合；
- 凭据失效时间。

### OrganizationConnector

提供：

- 用户搜索与详情；
- 部门、角色、岗位详情；
- 角色和岗位成员解析；
- 直属主管与连续主管链解析。

搜索支持页码和游标两种分页方式，单页最大 500 条。

### FileConnector

采用两阶段上传：

```text
createUploadSession
外部客户端上传文件
completeUpload
```

平台服务不需要中转大文件。下载必须签发有限时效 URL。

### NotificationConnector

统一发送站内信、邮件、短信、App Push、钉钉或飞书卡片。消息必须携带幂等键，并返回渠道消息 ID 与投递状态。

### BusinessCallbackConnector

向业务系统投递流程事件。事件至少包含：

```text
eventId
eventType
aggregateType
aggregateId
occurredAt
idempotencyKey
payload
```

投递结果区分成功、可重试失败和永久失败。

### FormDataSourceConnector

为动态表单提供外部数据，例如合同、客户、项目、采购订单和资产。查询必须携带表单上下文，不允许组件直接访问宿主数据库。

### ExternalTodoConnector

同步第三方待办，支持：

```text
PENDING
APPROVED
REJECTED
TRANSFERRED
COMPLETED
CANCELED
```

同一个 `externalTaskKey` 的重复同步必须幂等。

## Generic REST 回调协议

Generic REST 连接器发送 UTF-8 JSON 请求，事件对象使用稳定键顺序生成签名正文。默认请求头：

```text
Content-Type: application/json
X-Approval-Key-Id
X-Approval-Timestamp
X-Approval-Nonce
X-Approval-Signature
X-Approval-Event-Id
X-Tenant-Id
X-Request-Id
X-Trace-Id                 可选
Idempotency-Key
```

租户配置的附加请求头不能覆盖以上保留头。

### 签名规范

当前签名版本为 `v1`：

```text
canonical = timestamp + "\n" + nonce + "\n" + body
signature = "v1=" + hex(HMAC-SHA256(tenantSecret, canonical))
```

约束：

- `timestamp` 是 UTC Unix 秒；
- `nonce` 每次投递随机生成；
- 租户密钥至少 32 字节；
- 接收方使用常量时间比较签名；
- 接收方在业务处理前校验时间窗口；
- 默认建议时间偏差不超过 5 分钟；
- 验签成功后仍需通过 Inbox 使用消息 ID 和载荷哈希去重。

### HTTP 结果分类

```text
2xx                 DELIVERED
408 / 425 / 429     RETRYABLE_FAILURE
5xx                 RETRYABLE_FAILURE
其他非 2xx          PERMANENT_FAILURE
网络异常和超时       RETRYABLE_FAILURE
```

连接器禁止自动跟随重定向，避免签名请求被转发至未配置地址。

## Transactional Outbox

审批业务事务只追加 `ap_outbox`，不在事务内执行远程 HTTP 调用。投递器在事务提交后异步领取并投递消息。

状态：

```text
PENDING
IN_FLIGHT
DELIVERED
DEAD
```

生产约束：

- `(tenant_id, connector_key, idempotency_key)` 唯一；
- PostgreSQL 领取使用 `FOR UPDATE SKIP LOCKED`；
- 领取后记录 `locked_by` 和 `locked_until`；
- 完成、重试和死信更新必须匹配当前工作线程；
- 租约过期后允许其他工作线程重新领取；
- 旧工作线程不能覆盖新工作线程的结果；
- 可重试失败使用带抖动的指数退避；
- 达到最大次数或永久失败进入 `DEAD`；
- 回调失败不能回滚已完成的审批任务。

## Inbox 去重

外部 webhook 在验签后进入 `ap_inbox`。主键为：

```text
tenant_id + consumer_key + message_id
```

状态：

```text
PROCESSING
COMPLETED
FAILED
```

处理规则：

- 首次收到消息时获得处理租约；
- 已完成消息返回重复结果，不再次执行；
- 正在处理且租约有效时返回处理中；
- 同一消息 ID 使用不同载荷哈希时拒绝处理；
- 失败消息和过期租约允许重新领取；
- 完成和失败更新必须匹配当前工作线程；
- Inbox 只能减少重复执行风险，跨数据库业务命令仍必须自身幂等。

## 业务事件

首批标准事件：

- `PROCESS_STARTED`
- `TASK_CREATED`
- `TASK_COMPLETED`
- `PROCESS_APPROVED`
- `PROCESS_REJECTED`
- `PROCESS_WITHDRAWN`
- `PROCESS_TERMINATED`
- `PROCESS_CANCELED`

事件版本必须进入事件类型或元数据，消费者不能依赖未声明字段。

## 首批实现

- Generic REST/OIDC
- RuoYi-Vue-Plus 5.X
- RuoYi-Vue-Plus 6.X
- DingTalk
- Feishu

RuoYi 5.X 和 6.X 的 Starter 使用不同 Java/Spring Boot 构建基线，但必须通过同一套 Connector 合同测试。审批核心不直接依赖任一 Starter。

# Generic REST Connector Contract

Generic REST 合同用于审批平台与宿主系统之间的跨进程连接。RuoYi-Vue-Plus 5.X、6.X 和普通业务系统都可以实现同一组端点。

## 传输要求

生产环境必须使用 HTTPS。所有请求使用 UTF-8 JSON，并携带：

```text
Content-Type: application/json
Accept: application/json
X-Approval-Key-Id
X-Approval-Timestamp
X-Approval-Nonce
X-Approval-Signature
X-Approval-Operation
X-Tenant-Id
X-Request-Id
X-Trace-Id                  可选
```

签名正文：

```text
canonical = timestamp + "\n" + nonce + "\n" + rawBody
signature = "v1=" + hex(HMAC-SHA256(tenantSecret, canonical))
```

宿主必须先校验 Key ID、时间窗口、签名和租户映射，再执行认证或组织查询。请求正文必须使用收到的原始字节进行验签，不能解析后重新序列化再验签。

## 响应信封

成功响应：

```json
{
  "data": {}
}
```

错误响应：

```json
{
  "error": {
    "code": "USER_NOT_FOUND",
    "message": "User does not exist"
  }
}
```

宿主可以返回 `X-Request-Id`，平台会将其记录为外部请求 ID。

HTTP 约定：

```text
200 / 204    成功
400          请求字段或外部 ID 不合法
401 / 403    凭据、签名或权限失败
404          查询对象不存在
408 / 425    可重试
409          业务冲突，默认不可重试
429          可重试
5xx          可重试
```

## 外部 ID

所有用户、部门、租户等对象都使用：

```json
{
  "source": "ruoyi5",
  "objectType": "user",
  "value": "10086"
}
```

`source` 必须能区分不同宿主和版本。RuoYi 5.X 与 6.X 推荐分别使用 `ruoyi5`、`ruoyi6`。

## 认证

### POST `/api/approval-connector/v1/authenticate`

操作名：

```text
authentication.authenticate.v1
```

请求：

```json
{
  "credentialType": "bearer",
  "credential": "host-token-value",
  "attributes": {
    "device": "web"
  }
}
```

`credential` 属于敏感信息，宿主和平台都不得记录正文或明文 Token。

成功响应：

```json
{
  "data": {
    "principal": {
      "id": {"source":"ruoyi5","objectType":"user","value":"10086"},
      "username": "alice",
      "displayName": "Alice",
      "email": "alice@example.com",
      "mobile": "13800000000",
      "active": true,
      "departmentIds": [
        {"source":"ruoyi5","objectType":"department","value":"2001"}
      ],
      "roleCodes": ["finance"],
      "positionCodes": ["accountant"],
      "managerId": {"source":"ruoyi5","objectType":"user","value":"10001"},
      "attributes": {}
    },
    "tenant": {
      "id": {"source":"ruoyi5","objectType":"tenant","value":"000000"},
      "name": "Default Tenant",
      "active": true,
      "attributes": {}
    },
    "permissions": ["approval:task:complete"],
    "expiresAt": "2026-07-17T13:00:00Z",
    "attributes": {}
  }
}
```

## 组织查询

统一前缀：

```text
/api/approval-connector/v1/organization
```

### 用户详情

```text
POST /users/find
operation: organization.users.find.v1
```

请求：

```json
{
  "id": {"source":"ruoyi5","objectType":"user","value":"10086"}
}
```

对象不存在返回 `404`，不能返回一个空用户对象。

### 用户搜索

```text
POST /users/search
operation: organization.users.search.v1
```

请求：

```json
{
  "query": {
    "keyword": "ali",
    "departmentId": {"source":"ruoyi5","objectType":"department","value":"2001"},
    "roleCode": "finance",
    "positionCode": "accountant",
    "active": true
  },
  "page": {
    "page": 0,
    "size": 20,
    "cursor": null
  }
}
```

响应：

```json
{
  "data": {
    "items": [],
    "nextCursor": null,
    "total": 0
  }
}
```

`total` 无法计算时返回 `-1`。单页 `size` 最大 500。

### 部门详情

```text
POST /departments/find
operation: organization.departments.find.v1
```

部门响应字段：

```text
id
name
parentId
managerId
active
attributes
```

### 角色详情与成员

```text
POST /roles/find
operation: organization.roles.find.v1

POST /roles/members
operation: organization.roles.members.v1
```

请求字段为：

```json
{"code":"finance"}
```

成员响应：

```json
{"data":{"items":[]}}
```

### 岗位详情与成员

```text
POST /positions/find
operation: organization.positions.find.v1

POST /positions/members
operation: organization.positions.members.v1
```

请求字段为：

```json
{"code":"accountant"}
```

### 主管链

```text
POST /users/manager-chain
operation: organization.users.manager-chain.v1
```

请求：

```json
{
  "userId": {"source":"ruoyi5","objectType":"user","value":"10086"},
  "maximumLevels": 5
}
```

响应顺序必须从直属主管开始向上排列。不得包含原用户，不得形成循环，最大层级范围为 1 到 100。

## 快照规则

宿主返回的是审批快照，不是数据库实体：

- 不返回密码、盐、Token、身份证号等敏感字段；
- `attributes` 只放审批规则真正需要的扩展字段；
- 用户、角色、岗位和部门停用状态必须明确返回；
- 任务创建后平台固化快照，宿主后续变更不修改历史任务；
- 未创建的后续节点可以重新查询当前组织数据。

## RuoYi Starter 责任

RuoYi 5.X/6.X Starter 只负责：

- 使用 Sa-Token 校验传入宿主 Token；
- 读取当前租户、用户和权限；
- 调用系统模块查询用户、部门、角色和岗位；
- 转换为本合同中的快照；
- 校验平台请求的 HMAC 签名；
- 不嵌入 Flowable，不访问审批平台数据库。

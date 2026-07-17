# RuoYi-Vue-Plus Host Starters

该目录维护两个独立生成与构建的宿主 Starter：

```text
ruoyi5-host-starter   Java 17 / Spring Boot 3
ruoyi6-host-starter   Java 21 / Spring Boot 4
```

两个 Starter 实现同一份 [`Generic REST Connector Contract`](../../docs/GENERIC_REST_CONNECTOR.md)，但不共享 Spring Boot、RuoYi 或 Sa-Token 二进制依赖。

## 固定上游

```text
5.X e49f02f89e17ee5a4cc14048af99cc83d72872a7
6.X da5f30cae2deb174a1ba37a2ad41ff1ba42c9f38
```

锁定信息分别位于：

- `ruoyi5-upstream.json`
- `ruoyi6-upstream.json`

升级 RuoYi 时必须显式更新提交 SHA，并重新运行两个版本的合同测试。

## 源码组织

计划结构：

```text
connectors/ruoyi-host/
├── common-overlay/             # Java 17 兼容的签名验证、协议 DTO 和控制器支持
├── ruoyi5-overlay/             # 5.X LoginHelper、TenantHelper、系统服务桥接
├── ruoyi6-overlay/             # 6.X 登录上下文、租户和系统服务桥接
├── contract-tests/             # 两个版本共用的 HTTP 合同测试
├── ruoyi5-upstream.json
└── ruoyi6-upstream.json
```

生成脚本会把公共源码和版本源码复制到固定上游工作区，再通过 RuoYi 自身 Maven Reactor 编译。生成的上游源码不提交到当前仓库。

## Starter 责任

- 校验审批平台 HMAC 请求；
- 根据 `X-Tenant-Id` 解析宿主租户；
- 使用传入宿主 Token 调用 Sa-Token 获取登录用户；
- 返回用户、租户和权限快照；
- 查询用户、部门、角色、岗位和主管链；
- 不引入 Flowable；
- 不访问审批平台数据库；
- 不允许审批平台直接查询 RuoYi 数据库。

## 安全要求

- 生产环境只允许 HTTPS；
- 租户密钥通过环境变量或密钥管理系统注入；
- Token、请求正文和 HMAC 密钥不得写入日志；
- 时间窗口、nonce 和请求 ID 在业务执行前校验；
- Starter 端点默认不进入普通业务权限菜单，但必须限制来源网络与 Key ID；
- 返回快照时过滤密码、身份证号和不必要的敏感信息。

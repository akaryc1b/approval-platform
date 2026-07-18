# Approval Platform Mobile

移动端基于 Unibest 4.4.1、UniApp Vue 3 和 Wot Design Uni 1.14.0。目标平台包括 H5、微信小程序、钉钉/飞书内嵌环境和 App。

与 PC 端一致，移动端采用“固定上游提交 + 审批覆盖层”的生成模式：

```text
apps/mobile/
├── upstream.json   # Unibest commit 和 Wot UI 精确版本
└── overlay/        # 审批页面、API、运行环境和导航配置
```

完整生成工作区位于 `.upstream/unibest`，不会提交到 Git。

## 命令

```bash
pnpm mobile:bootstrap
pnpm mobile:install
pnpm mobile:dev:h5
pnpm mobile:typecheck
pnpm mobile:build:h5
pnpm mobile:build:weixin
pnpm mobile:clean
```

## 当前页面

- 审批首页和工作台：真实待办、已处理、我发起、搜索和分页；
- 审批详情：采购付款数据、时间线和审批操作；
- 审批讨论：评论、@流程参与人、一层回复、真实附件上传和下载；
- 消息中心：催办、抄送、@提及、未读、全部已读和消息直达目标评论；
- 发起审批：读取当前租户真实发布的 Form Schema 版本，不再展示写死的演示模板；
- 动态表单页：使用 Wot UI 从后端 Schema 渲染 TEXT、MONEY 和 ATTACHMENT 字段；
- Renderer 校验：必填、金额下限和附件数量基础检查；
- 个人中心：审批讨论、消息未读入口和当前运行身份。

移动 Renderer 与 PC Renderer 消费同一份不可变 Form Schema。当前 M2 第一批只完成表单加载、渲染和填写检查，通用表单提交和审批实例数据快照将在后续增量中接入，不使用虚假提交结果替代。

## 审批 API 配置

```dotenv
VITE_APPROVAL_API_URL = '/api'
VITE_APPROVAL_CONNECTOR = 'standalone'
VITE_APPROVAL_TENANT_ID = 'tenant-a'
VITE_APPROVAL_OPERATOR_ID = 'manager-1'
```

H5 可以通过同源网关转发 `/api/approval/**`。微信小程序和 App 应配置可访问的 HTTPS API 完整地址，并完成平台域名白名单配置。生产部署必须由登录或宿主启动适配器提供可信租户和操作人身份。

## 架构约束

- 页面不直接依赖 RuoYi、钉钉或飞书 SDK；
- 平台差异通过启动环境和 Connector 适配；
- 动态表单使用平台自有 Form Schema 和移动 Renderer；
- PC 与移动端共享协议、校验和权限逻辑，但不共享 Vue 页面组件；
- 移动端不读取 Flowable 表；
- 修改生成工作区无效，所有业务变更必须进入 `apps/mobile/overlay`。

单个评论附件最大 10 MiB、最多 20 个。独立部署默认使用 PostgreSQL `bytea` 保存附件二进制，生产可通过附件存储端口替换为对象存储。

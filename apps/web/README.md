# Approval Platform Web

PC 管理端基于 Vben Admin 5.7.0 的 `web-ele` 应用和 Element Plus。为避免长期维护完整上游演示仓库，本项目采用“固定上游提交 + 审批覆盖层”的方式。

## 目录

```text
apps/web/
├── upstream.json   # 上游仓库、标签和精确 commit
└── overlay/        # 覆盖到上游工作区的审批业务代码
```

生成后的完整 Vben 工作区位于 `.upstream/vben`，该目录不提交到 Git。

## 命令

```bash
pnpm web:bootstrap
pnpm web:install
pnpm web:dev
pnpm web:typecheck
pnpm web:build
pnpm web:clean
```

## 审批 API 配置

PC 审批页面使用独立的原始 JSON 客户端，不改变 Vben 原有登录、菜单和 `{code,data}` 请求链路。

```dotenv
VITE_APPROVAL_API_URL=/api
VITE_APPROVAL_TENANT_ID=tenant-a
VITE_APPROVAL_OPERATOR_ID=manager-1
```

租户和操作人配置目前用于纵向链路联调。生产环境必须由可信登录或宿主适配器提供身份，不能允许最终用户自行修改。`VITE_APPROVAL_API_URL=/api` 假设网关或反向代理把 `/api/approval/**` 转发到审批服务。

## 上游管理规则

- 必须同时锁定 tag 和 commit SHA；
- 启动脚本发现 SHA 不一致时会删除并重新生成上游工作区；
- `src/api/approval`、`src/router/routes/modules`、`src/views/approval`、`src/components/approval` 和 `src/platform/approval` 由本项目覆盖层管理；
- 审批业务代码不得直接修改 `.upstream` 中的文件；
- 升级 Vben 时必须修改 `upstream.json`，记录兼容性验证和本地补丁；
- 保留 Vben MIT License 和第三方声明。

## 当前模块

- 审批工作台：待我处理、我已处理、我发起的、搜索、分页和真实协作动作；
- 审批讨论：评论、@流程参与人、一层回复、真实附件上传和权限下载；
- 消息与协作：催办、抄送、@提及、未读、回执和消息直达评论；
- 动态表单：已发布版本列表、JSON 编辑、服务端校验、不可变发布和 Element Plus 运行时预览；
- 表单 Renderer：从平台 Form Schema 渲染 TEXT、MONEY 和 ATTACHMENT，不在页面重复定义字段；
- 流程设计器、流程管理和运维控制台：后续 M2/M4 工作入口。

Form Schema 发布后不可修改。需要调整字段时必须发布新版本，运行中的审批继续绑定原始版本。PC 预览与移动端 Renderer 消费同一份后端 Schema。

单个评论附件最大 10 MiB、最多 20 个。独立部署默认使用 PostgreSQL `bytea` 保存附件二进制；生产可通过附件存储端口替换为 MinIO、S3 或其他对象存储。

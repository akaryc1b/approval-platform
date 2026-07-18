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
# 拉取并验证固定版本，应用审批覆盖层
pnpm web:bootstrap

# 安装完整 Vben workspace 依赖
pnpm web:install

# 启动 Element Plus 应用
pnpm web:dev

# 类型检查和生产构建
pnpm web:typecheck
pnpm web:build

# 删除生成工作区
pnpm web:clean
```

## 审批 API 配置

PC 审批页面使用独立的原始 JSON 客户端，不改变 Vben 原有登录、菜单和 `{code,data}` 请求链路。

本地或部署环境需要提供：

```dotenv
VITE_APPROVAL_API_URL=/api
VITE_APPROVAL_TENANT_ID=tenant-a
VITE_APPROVAL_OPERATOR_ID=manager-1
```

`VITE_APPROVAL_TENANT_ID` 和 `VITE_APPROVAL_OPERATOR_ID` 目前用于 M1 纵向链路联调。生产环境必须由可信登录或宿主适配器提供身份，不能允许最终用户自行修改。

`VITE_APPROVAL_API_URL=/api` 假设网关或反向代理把 `/api/approval/**` 转发到审批服务。

## 上游管理规则

- 必须同时锁定 tag 和 commit SHA；
- 启动脚本发现 SHA 不一致时会删除并重新生成上游工作区；
- `src/api/approval`、`src/router/routes/modules`、`src/views/approval`、`src/components/approval` 和 `src/platform/approval` 由本项目覆盖层管理；
- 审批业务代码不得直接修改 `.upstream` 中的文件；
- 升级 Vben 时必须修改 `upstream.json`，记录兼容性验证和本地补丁；
- 保留 Vben MIT License 和第三方声明。

## 当前模块

- 审批工作台：待我处理、我已处理、我发起的、搜索、分页和真实协作动作；
- 审批讨论：待办、已处理、我发起和抄送审批共用评论线程，支持 @流程参与人和一层回复；
- 评论附件：真实文件选择、上传、受权限控制的下载和附件元数据展示；
- 消息直达：催办、抄送和 @提及消息打开对应审批讨论，提及消息高亮目标评论；
- 消息与协作：未读消息、全部已读、催办、抄送和逐人已读回执；
- 审批详情：采购付款数据、附件、时间线、同意、驳回、重提和转办；
- 流程设计器：页面骨架；
- 动态表单：页面骨架；
- 流程管理：页面骨架；
- 运维控制台：页面骨架；
- 宿主无关的菜单、按钮权限和审批运行身份适配边界。

单个评论附件最大 10 MiB、最多 20 个。上传后的附件在评论发布时绑定审批；绑定后仅合法审批参与者和消息接收人能够下载。

独立部署默认使用 PostgreSQL `bytea` 保存附件二进制。应用层通过附件存储端口隔离，生产部署可以替换为 MinIO、S3 或其他对象存储，不改变页面接口和权限语义。

平台内用户消息使用审批消息表保存，业务完成回调继续通过事务 Outbox 异步发送，二者职责独立。

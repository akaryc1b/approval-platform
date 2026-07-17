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

## 上游管理规则

- 必须同时锁定 tag 和 commit SHA；
- 启动脚本发现 SHA 不一致时会删除并重新生成上游工作区；
- `src/router/routes/modules`、`src/views/approval` 和 `src/platform/approval` 由本项目覆盖层管理；
- 审批业务代码不得直接修改 `.upstream` 中的文件；
- 升级 Vben 时必须修改 `upstream.json`，记录兼容性验证和本地补丁；
- 保留 Vben MIT License 和第三方声明。

## 当前模块

- 审批工作台；
- 流程设计器；
- 动态表单；
- 流程管理；
- 运维控制台；
- 宿主无关的菜单和按钮权限适配接口。

当前页面属于可构建工程骨架。真实数据、身份认证和动态菜单将在纵向业务链路与 Connector SDK 中接入。

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
# 拉取并验证 Unibest，应用审批覆盖层和精确依赖
pnpm mobile:bootstrap

# 安装依赖
pnpm mobile:install

# H5 开发
pnpm mobile:dev:h5

# 类型检查
pnpm mobile:typecheck

# 构建 H5 和微信小程序
pnpm mobile:build:h5
pnpm mobile:build:weixin

# 删除生成工作区
pnpm mobile:clean
```

## 当前页面

- 审批工作台：展示真实待办总数和最新任务；
- 待办列表：支持搜索、分页、刷新和空状态；
- 审批详情：展示采购付款数据、附件和审批时间线；
- 审批操作：调用幂等的真实“同意”接口；
- 发起审批模板；
- 个人中心：展示当前租户、操作人、连接器和 API 环境。

## 审批 API 配置

覆盖层通过以下环境变量接入审批服务：

```dotenv
VITE_APPROVAL_API_URL = '/api'
VITE_APPROVAL_CONNECTOR = 'standalone'
VITE_APPROVAL_TENANT_ID = 'tenant-a'
VITE_APPROVAL_OPERATOR_ID = 'manager-1'
```

H5 可以通过同源网关将 `/api/approval/**` 转发到审批服务。微信小程序、App 等无法使用浏览器同源代理的环境，应配置可访问的 HTTPS API 完整地址，并完成平台域名白名单配置。

仓库中的租户和操作人仅用于本地纵向链路联调。生产部署必须由登录或宿主启动适配器提供可信身份，不能直接信任用户可修改的环境变量或请求参数。

## 架构约束

- 页面不直接依赖 RuoYi、钉钉或飞书 SDK；
- 平台差异通过启动环境和 Connector 适配；
- 动态表单使用平台自有 Form Schema 和移动 Renderer；
- PC 与移动端共享协议、校验和权限逻辑，但不共享 Vue 页面组件；
- 移动端查询平台投影和审计 API，不读取 Flowable 表；
- 修改生成工作区无效，所有业务变更必须进入 `apps/mobile/overlay`。

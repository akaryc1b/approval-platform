# Approval Platform Mobile

移动端基于 Unibest 4.4.1、UniApp Vue 3 和 Wot Design Uni 1.14.0。目标平台包括 H5、微信小程序、钉钉/飞书内嵌环境和 App。

与 PC 端一致，移动端采用“固定上游提交 + 审批覆盖层”的生成模式：

```text
apps/mobile/
├── upstream.json   # Unibest commit 和 Wot UI 精确版本
└── overlay/        # 审批页面、运行环境和导航配置
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

- 审批工作台；
- 待办列表；
- 审批详情与操作区；
- 发起审批模板；
- 个人中心和运行环境展示。

当前页面是可构建的产品骨架。审批动作只展示交互反馈，真实任务提交将在采购付款纵向链路中通过平台 API 接入。

## 架构约束

- 页面不直接依赖 RuoYi、钉钉或飞书 SDK；
- 平台差异通过启动环境和 Connector 适配；
- 动态表单使用平台自有 Form Schema 和移动 Renderer；
- PC 与移动端共享协议、校验和权限逻辑，但不共享 Vue 页面组件；
- 修改生成工作区无效，所有业务变更必须进入 `apps/mobile/overlay`。

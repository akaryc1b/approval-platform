# Contributing

## 开发原则

1. 先定义领域语义，再实现 Flowable、HTTP 或 UI 适配。
2. 不允许业务模块直接访问 Flowable `ACT_*` 表。
3. 不允许 `approval-domain` 依赖 Spring、Flowable、RuoYi 或具体数据库。
4. 新增审批操作必须同时补充：权限、审计事件、幂等策略、并发策略和组合测试。
5. 修改 Approval DSL 或 Form Schema 时必须提供版本迁移方案与兼容性测试。
6. 通用功能进入公开核心；公司、客户和行业专用逻辑进入外部扩展仓库。

## 分支与提交

- 分支使用 `feat/`、`fix/`、`docs/`、`refactor/` 等前缀。
- 一个 PR 尽量形成完整纵向闭环：协议、后端、前端、移动端、测试和文档。
- 禁止提交密钥、真实用户数据和客户专有信息。

## 质量门禁

提交前至少执行：

```bash
mvn verify
pnpm install
pnpm check
```

涉及流程状态变化的修改必须包含集成测试；涉及并发或自动审批的修改必须包含幂等和竞争条件测试。

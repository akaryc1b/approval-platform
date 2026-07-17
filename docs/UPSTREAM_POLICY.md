# 上游框架管理策略

## 锁定版本

- Flowable：8.0.0；
- Spring Boot：4.0.2；
- Vben Admin：5.7.0；
- Unibest：4.4.1。

版本升级通过独立 PR 完成，不直接跟随 `main` 或 Snapshot。

## PC

以 Vben Admin `web-ele` 为工程基础，保留布局、权限、主题、国际化、请求和基础组件。审批模块通过适配层使用框架能力，避免依赖内部未公开 API。

## Mobile

以 Unibest + Wot UI v2 为工程基础，保留路由、请求、状态、多环境和多端构建。审批表单使用平台自有 Schema 和移动 Renderer。

## 第三方代码

引入上游源码时必须：

- 保留原始许可证和 NOTICE；
- 在 `THIRD_PARTY_LICENSES.md` 记录来源、版本和修改；
- 将上游代码与平台业务模块隔离；
- 维护必要补丁清单；
- 安全修复优先，普通功能升级按计划评估。

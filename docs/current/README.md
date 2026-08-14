# Current Documentation

本目录是默认分支当前事实的权威入口。它回答“现在主线里有什么、如何组成、如何运行、哪些组合被接受”，但不保存某次验收的历史身份，也不代表已经发布或支持生产。

## 页面

- [`architecture.md`](architecture.md)：当前模块、数据流、信任边界和安全不变量；
- [`operations.md`](operations.md)：当前启动、升级、备份、恢复、默认关闭项和 incident 边界；
- [`compatibility.md`](compatibility.md)：机器生成的运行时、数据库、协议和 Flyway 兼容矩阵；
- [`capability-status.md`](capability-status.md)：机器生成的能力状态表；
- [`capability-status.json`](capability-status.json)：供文档站、Release 流程和自动检查消费的机器可读状态。

## 权威规则

1. Current 不手写完整 Git SHA、Workflow Run、PR 或 Artifact 身份；这些只进入 Acceptance 或 Release。
2. Current 不把 Implemented、Tested、Accepted、Merged、Released 和 Production Supported 合并成一个“完成”状态。
3. Capability Status 和 Compatibility 只能通过 `scripts/generate-capability-status.mjs` 生成。
4. 架构和运维正文随默认分支语义变化更新，不绑定易过期的单一 commit。
5. 默认分支不是 Release；Release 事实只由 `docs/releases/<tag>/` 和真实发布对象共同建立。

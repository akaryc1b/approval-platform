# Release Records

Release 文档回答“一个真实发布版本交付了什么”。它与默认分支、阶段验收和 Roadmap 相互独立。

## 版本目录准入

只有同时满足以下条件，才允许创建 `docs/releases/<tag>/`：

1. 对应 Git tag 存在；
2. 对应 GitHub Release 存在；
3. release manifest 绑定精确 commit；
4. 应用制品、SBOM 和摘要齐备；
5. Compatibility 与 Operations 已冻结为发布快照；
6. Release 等级、已知限制和支持政策明确；
7. 发布验证与恢复演练完成。

默认分支、版本号字段、Draft PR、测试通过或 Formal Acceptance 都不能替代真实 Release。

## Candidate workspace

[`next/`](next/) 用于准备候选材料。它不是版本目录，不得被产品、运维或用户解释为已经发布。

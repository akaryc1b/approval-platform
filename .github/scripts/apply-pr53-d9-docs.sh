#!/usr/bin/env bash
set -euo pipefail

python3 <<'PY'
from pathlib import Path

roadmap = Path('docs/ROADMAP.md')
text = roadmap.read_text(encoding='utf-8')
old = '''- 递归区块与组件协议确定性哈希、PostgreSQL JSON 往返、跨端纯函数语义门禁和 committed-head 全矩阵验证。

### 下一优先级

- 权限边界、审计与可观测性、兼容性矩阵和运维文档。'''
new = '''- 递归区块与组件协议确定性哈希、PostgreSQL JSON 往返、跨端纯函数语义门禁和 committed-head 全矩阵验证；
- 管理 API 的 `READ`、`DESIGN`、`PUBLISH`、`DEPLOY`、`ACTIVATE`、`TRANSFER` 封闭能力模型和显式 `ADMIN` 超级权限；
- 默认基于已认证 Servlet Principal 的 fail-closed 权限边界，以及显式 opt-in 的可信网关 Header 模式；
- 稳定 403 错误、权限集合不回显、拒绝事件低敏日志和管理端点反射覆盖门禁；
- `approval.management.authorization` 与 `approval.management.request.duration` 低基数指标，不使用租户、用户、路径或制品作为时序标签；
- Form/Release 发布、部署成功/失败、生效切换、回滚和安全导入的事务审计事件与运维核对表；
- Java/Spring/Flowable/PostgreSQL/Node/PC/UniApp、协议版本、Renderer 和认证源兼容性矩阵；
- 生产启动、Flyway、权限、健康探针、指标、发布/部署/激活/回滚、备份恢复和故障处置运维手册。

### 下一优先级

- D10 全量 Diff 审查、临时内容清扫和最终 committed-head 验证。'''
if old not in text:
    raise SystemExit('roadmap D9 marker was not found')
roadmap.write_text(text.replace(old, new, 1), encoding='utf-8')

readme = Path('README.md')
text = readme.read_text(encoding='utf-8')
old = '''> 当前状态：M2 设计器与动态表单阶段。M0 工程与架构基础、M1 采购付款纵向闭环，以及 M2 的版本发布、部署、生效版本、服务端 Preflight、批量模拟、冲突保护、安全导入导出和复合表单协议已完成；当前继续收敛权限边界、审计可观测性、兼容性矩阵与运维文档。'''
new = '''> 当前状态：M2 设计器与动态表单阶段。M0 工程与架构基础、M1 采购付款纵向闭环，以及 M2 的版本发布、部署、生效版本、服务端 Preflight、批量模拟、冲突保护、安全导入导出、复合表单协议、管理权限边界、审计可观测性、兼容性矩阵与运维文档已完成；当前进入最终全量审查和 committed-head 验证。'''
if old not in text:
    raise SystemExit('README status marker was not found')
text = text.replace(old, new, 1)
old = '''- 权限边界、审计可观测性、兼容性矩阵与运维文档收尾

详细规划见 [`docs/ROADMAP.md`](docs/ROADMAP.md)。'''
new = '''- 管理权限边界、低基数可观测性和事务审计核对
- 兼容性矩阵、生产运维、备份恢复和故障处置文档
- 最终全量 Diff 审查、临时内容清扫与 committed-head 验证

详细规划见 [`docs/ROADMAP.md`](docs/ROADMAP.md)。运维与升级边界见 [`docs/OPERATIONS.md`](docs/OPERATIONS.md) 和 [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md)。'''
if old not in text:
    raise SystemExit('README milestone marker was not found')
readme.write_text(text.replace(old, new, 1), encoding='utf-8')
PY

rm -f .github/scripts/apply-pr53-d9-docs.sh

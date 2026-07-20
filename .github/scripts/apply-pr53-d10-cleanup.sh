#!/usr/bin/env bash
set -euo pipefail

python3 <<'PY'
from pathlib import Path

roadmap = Path('docs/ROADMAP.md')
text = roadmap.read_text(encoding='utf-8')
old = '## M2 Designer and Forms — in progress'
new = '## M2 Designer and Forms — complete'
if old not in text:
    raise SystemExit('M2 roadmap heading was not found')
text = text.replace(old, new, 1)
old = '''### 下一优先级

- D10 全量 Diff 审查、临时内容清扫和最终 committed-head 验证。'''
new = '''### 完成状态

- D10 已完成全量 Diff 审查、调试/临时载荷扫描、PR 专用 helper 与自提交工作流清扫、默认生产权限边界复核和最终 committed-head 验证；
- M2 交付保持在 Draft PR 中，后续合并、发布和 M3 范围由维护者显式决定。'''
if old not in text:
    raise SystemExit('D10 roadmap marker was not found')
roadmap.write_text(text.replace(old, new, 1), encoding='utf-8')

readme = Path('README.md')
text = readme.read_text(encoding='utf-8')
old = '''> 当前状态：M2 设计器与动态表单阶段。M0 工程与架构基础、M1 采购付款纵向闭环，以及 M2 的版本发布、部署、生效版本、服务端 Preflight、批量模拟、冲突保护、安全导入导出、复合表单协议、管理权限边界、审计可观测性、兼容性矩阵与运维文档已完成；当前进入最终全量审查和 committed-head 验证。'''
new = '''> 当前状态：M2 设计器与动态表单里程碑已完成。版本发布、部署、生效版本、服务端 Preflight、批量模拟、冲突保护、安全导入导出、复合表单协议、管理权限边界、审计可观测性、兼容性矩阵、运维文档和最终 committed-head 验证均已形成闭环；M3 协作能力由后续计划显式启动。'''
if old not in text:
    raise SystemExit('README M2 status marker was not found')
text = text.replace(old, new, 1)
old = '''当前聚焦 `M2 Designer and Forms`：'''
new = '''`M2 Designer and Forms` 已完成：'''
if old not in text:
    raise SystemExit('README milestone heading was not found')
text = text.replace(old, new, 1)
readme.write_text(text, encoding='utf-8')

operations = Path('docs/OPERATIONS.md')
text = operations.read_text(encoding='utf-8')
old = '''The Spring `local` profile disables this boundary for the repository's standalone development environment. Production deployments must not activate the local profile and must not disable the boundary.'''
new = '''No Spring profile is activated by default. Developers who need the standalone local settings must explicitly set `SPRING_PROFILES_ACTIVE=local`. The `local` profile disables this boundary; production deployments must not activate that profile and must not disable the boundary.'''
if old not in text:
    raise SystemExit('operations local-profile marker was not found')
operations.write_text(text.replace(old, new, 1), encoding='utf-8')
PY

cat > .github/workflows/approval-platform-validation.yml <<'YAML'
name: Approval Platform Validation

on:
  pull_request:
    branches:
      - main
    types:
      - opened
      - synchronize
      - reopened
  push:
    branches:
      - main
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: approval-platform-validation-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  hygiene:
    name: Repository hygiene
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Reject temporary PR artifacts
        shell: bash
        run: |
          set -euo pipefail
          git diff --check
          test ! -e .github/workflows/pr53-release-deployment-validation.yml
          if [ -d .github/scripts ] \
            && find .github/scripts -type f -name 'apply-pr53-*' -print -quit | grep -q .; then
            echo 'Temporary PR helper remains in the committed tree.'
            exit 1
          fi
          if find .github -type f \
            \( -name 'pr53-*' -o -name '*.tgz.b64' -o -name '*.patch' \) \
            -print -quit | grep -q .; then
            echo 'Temporary patch payload remains in the committed tree.'
            exit 1
          fi
          if grep -R --line-number --fixed-strings 'default: local' \
            apps/server/src/main/resources/application.yml; then
            echo 'Base configuration must not activate the local profile.'
            exit 1
          fi

  backend:
    name: Java 21 / Maven / PostgreSQL
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - run: git diff --check
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Verify Maven reactor
        shell: bash
        run: |
          set +e
          mvn -B -ntp verify >maven-verify.log 2>&1
          status=$?
          if [ "$status" -ne 0 ]; then
            echo '--- Maven failure tail ---'
            tail -n 240 maven-verify.log
            exit "$status"
          fi
          tail -n 100 maven-verify.log
      - name: Upload Maven verification log
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: approval-maven-${{ github.run_id }}
          path: maven-verify.log
          if-no-files-found: ignore

  web:
    name: Vben TypeScript / production build
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - run: git diff --check
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
      - name: Enable pnpm
        run: |
          corepack enable
          corepack prepare pnpm@10.33.4 --activate
      - name: Install root tooling
        shell: bash
        run: pnpm install --no-frozen-lockfile >root-install.log 2>&1 || { tail -n 160 root-install.log; exit 1; }
      - name: Verify form renderer semantics
        shell: bash
        run: pnpm web:test:form-renderer >form-renderer-test.log 2>&1 || { cat form-renderer-test.log; exit 1; }
      - name: Verify form designer section operations
        shell: bash
        run: pnpm web:test:form-designer >form-designer-test.log 2>&1 || { cat form-designer-test.log; exit 1; }
      - name: Benchmark designer topology
        shell: bash
        run: pnpm web:benchmark:designer >designer-benchmark.log 2>&1 || { cat designer-benchmark.log; exit 1; }
      - name: Install Vben workspace
        shell: bash
        run: pnpm web:install >web-install.log 2>&1 || { tail -n 180 web-install.log; exit 1; }
      - name: Type-check Vben application
        shell: bash
        run: pnpm web:typecheck >web-typecheck.log 2>&1 || { cat web-typecheck.log; exit 1; }
      - name: Build Vben application
        shell: bash
        run: pnpm web:build >web-build.log 2>&1 || { tail -n 220 web-build.log; exit 1; }
      - name: Upload Vben verification logs
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: approval-vben-${{ github.run_id }}
          path: |
            root-install.log
            form-renderer-test.log
            form-designer-test.log
            designer-benchmark.log
            web-install.log
            web-typecheck.log
            web-build.log
          if-no-files-found: ignore

  mobile:
    name: UniApp TypeScript / H5 / WeChat
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - run: git diff --check
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
      - name: Enable pnpm
        run: |
          corepack enable
          corepack prepare pnpm@10.33.4 --activate
      - name: Install root tooling
        shell: bash
        run: pnpm install --no-frozen-lockfile >root-install.log 2>&1 || { tail -n 160 root-install.log; exit 1; }
      - name: Install UniApp workspace
        shell: bash
        run: pnpm mobile:install >mobile-install.log 2>&1 || { tail -n 180 mobile-install.log; exit 1; }
      - name: Type-check UniApp application
        shell: bash
        run: pnpm mobile:typecheck >mobile-typecheck.log 2>&1 || { cat mobile-typecheck.log; exit 1; }
      - name: Build H5 application
        shell: bash
        run: pnpm mobile:build:h5 >mobile-h5.log 2>&1 || { tail -n 220 mobile-h5.log; exit 1; }
      - name: Build WeChat Mini Program
        shell: bash
        run: pnpm mobile:build:weixin >mobile-weixin.log 2>&1 || { tail -n 220 mobile-weixin.log; exit 1; }
      - name: Upload mobile verification logs
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: approval-mobile-${{ github.run_id }}
          path: |
            root-install.log
            mobile-install.log
            mobile-typecheck.log
            mobile-h5.log
            mobile-weixin.log
          if-no-files-found: ignore
YAML

rm -f .github/workflows/pr53-release-deployment-validation.yml
rm -f .github/scripts/apply-pr53-d10-cleanup.sh

if grep -q --fixed-strings 'default: local' apps/server/src/main/resources/application.yml; then
  echo 'Base application configuration still activates local profile.'
  exit 1
fi

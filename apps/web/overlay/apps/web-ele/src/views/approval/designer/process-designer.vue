<script lang="ts" setup>
import { nextTick, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElDivider,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElScrollbar,
  ElSelect,
  ElSpace,
  ElTabPane,
  ElTabs,
  ElTag,
} from 'element-plus';
import { useApprovalDesigner } from './use-approval-designer';

const designer = useApprovalDesigner();
const route = useRoute();
const createVisible = ref(false);
const nodeKinds = [
  { label: '开始', value: 'START' },
  { label: '审批', value: 'APPROVAL' },
  { label: '处理', value: 'HANDLE' },
  { label: '条件', value: 'CONDITION' },
  { label: '并行拆分', value: 'PARALLEL_SPLIT' },
  { label: '并行汇聚', value: 'PARALLEL_JOIN' },
  { label: '结束', value: 'END' },
] as const;
const createForm = ref({
  definitionKey: 'expense-approval',
  definitionVersion: 1,
  formPackageVersion: 1,
  name: '费用审批',
  source: 'BLANK' as 'BLANK' | 'COPY' | 'PURCHASE_PAYMENT_TEMPLATE',
  sourceDefinitionVersion: 1,
});

onMounted(async () => {
  await designer.loadDrafts();
  const importedDraftId = typeof route.query.draftId === 'string'
    ? route.query.draftId
    : undefined;
  if (importedDraftId) await designer.openDraft(importedDraftId);
});

async function submitCreate() {
  const created = await designer.createDraft(createForm.value);
  if (created) createVisible.value = false;
}

function statusType(status?: string) {
  if (status === 'PUBLISHED') return 'success';
  if (status === 'ARCHIVED') return 'info';
  if (status === 'VALIDATED') return 'warning';
  return 'primary';
}

async function focusNode(subject?: string) {
  if (!subject || !designer.focusNode(subject)) return;
  await nextTick();
  const element = [...document.querySelectorAll<HTMLElement>('[data-approval-node-id]')]
    .find(candidate => candidate.dataset.approvalNodeId === designer.selectedNodeId.value);
  element?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  element?.focus({ preventScroll: true });
}
</script>

<template>
  <Page title="流程设计" description="设计、检查、模拟并发布审批流程">
    <div class="designer-shell">
      <aside class="left-panel">
        <ElCard shadow="never">
          <template #header>
            <div class="panel-title">
              <strong>流程草稿</strong>
              <ElButton type="primary" size="small" @click="createVisible = true">新建</ElButton>
            </div>
          </template>
          <ElInput
            v-model="designer.keyword.value"
            clearable
            placeholder="搜索名称或标识"
            @keyup.enter="designer.loadDrafts"
          />
          <ElSelect
            v-model="designer.status.value"
            class="full-width filter"
            clearable
            placeholder="全部状态"
            @change="designer.loadDrafts"
          >
            <ElOption label="草稿" value="DRAFT" />
            <ElOption label="已检查" value="VALIDATED" />
            <ElOption label="已发布" value="PUBLISHED" />
            <ElOption label="已归档" value="ARCHIVED" />
          </ElSelect>
          <ElScrollbar height="300px">
            <button
              v-for="item in designer.drafts.value"
              :key="item.draftId"
              class="draft-item"
              :class="{ active: designer.draft.value?.draftId === item.draftId }"
              @click="designer.openDraft(item.draftId)"
            >
              <span>{{ item.name }}</span>
              <small>{{ item.definitionKey }} · r{{ item.revision }}</small>
              <ElTag :type="statusType(item.status)" size="small">{{ item.status }}</ElTag>
            </button>
            <ElEmpty v-if="!designer.drafts.value.length" description="暂无流程草稿" />
          </ElScrollbar>
        </ElCard>

        <ElCard shadow="never" class="component-card">
          <template #header><strong>节点组件</strong></template>
          <div class="component-grid">
            <ElButton :disabled="!designer.editable.value" @click="designer.addNode('APPROVAL')">审批</ElButton>
            <ElButton :disabled="!designer.editable.value" @click="designer.addNode('HANDLE')">处理</ElButton>
            <ElButton :disabled="!designer.editable.value" @click="designer.addNode('CONDITION')">条件</ElButton>
            <ElButton :disabled="!designer.editable.value" @click="designer.addNode('PARALLEL')">并行</ElButton>
          </div>
        </ElCard>
      </aside>

      <main class="canvas-panel">
        <ElCard v-if="designer.draft.value" shadow="never">
          <template #header>
            <div class="panel-title toolbar">
              <div>
                <strong>{{ designer.draft.value.name }}</strong>
                <ElTag :type="statusType(designer.draft.value.status)" size="small">
                  {{ designer.draft.value.status }}
                </ElTag>
                <span class="muted">revision {{ designer.draft.value.revision }}</span>
              </div>
              <ElSpace wrap>
                <ElTag v-if="designer.saveState.value === 'conflict'" type="danger">版本冲突</ElTag>
                <ElTag v-else type="info">{{ designer.saveState.value }}</ElTag>
                <ElButton :disabled="!designer.undoStack.value.length" @click="designer.undo">撤销</ElButton>
                <ElButton :disabled="!designer.redoStack.value.length" @click="designer.redo">重做</ElButton>
                <ElButton :disabled="!designer.editable.value" @click="designer.save()">保存</ElButton>
                <ElButton v-if="designer.saveState.value === 'conflict'" type="warning" @click="designer.reloadServerVersion">加载服务端版本</ElButton>
                <ElButton @click="designer.validate">服务端检查</ElButton>
                <ElButton type="primary" :disabled="!designer.editable.value" @click="designer.publish">发布</ElButton>
              </ElSpace>
            </div>
          </template>

          <div v-if="designer.saveState.value === 'conflict'" class="conflict-panel">
            <ElAlert
              type="warning"
              show-icon
              title="服务器上的草稿已更新；系统已生成本地、基线和服务端三方对比。"
            />
            <div v-if="designer.conflict.value" class="conflict-summary">
              <span>本地基于 revision {{ designer.draft.value.revision }}</span>
              <span>服务端 revision {{ designer.conflict.value.serverDraft.revision }}</span>
            </div>
            <div v-if="designer.conflict.value" class="conflict-columns">
              <section>
                <strong>本地修改</strong>
                <div class="change-tags">
                  <ElTag
                    v-for="change in designer.conflict.value.analysis.localChanges"
                    :key="`local-${change.path}`"
                    size="small"
                  >{{ change.type }} · {{ change.path }}</ElTag>
                  <span v-if="!designer.conflict.value.analysis.localChanges.length" class="muted">无本地差异</span>
                </div>
              </section>
              <section>
                <strong>服务端修改</strong>
                <div class="change-tags">
                  <ElTag
                    v-for="change in designer.conflict.value.analysis.serverChanges"
                    :key="`server-${change.path}`"
                    size="small"
                    type="info"
                  >{{ change.type }} · {{ change.path }}</ElTag>
                  <span v-if="!designer.conflict.value.analysis.serverChanges.length" class="muted">无服务端差异</span>
                </div>
              </section>
            </div>
            <ElAlert
              v-if="designer.conflict.value?.analysis.overlappingPaths.length"
              type="error"
              show-icon
              :title="`检测到重叠修改：${designer.conflict.value.analysis.overlappingPaths.join('、')}`"
              description="重叠字段不会自动合并，请加载服务端版本后手动重做。"
            />
            <ElSpace v-if="designer.conflict.value" wrap>
              <ElButton
                type="primary"
                :disabled="!designer.conflict.value.analysis.canAutoMerge"
                @click="designer.applySafeConflictMerge"
              >安全合并非重叠修改</ElButton>
              <ElButton type="warning" @click="designer.reloadServerVersion">放弃本地并加载服务端</ElButton>
            </ElSpace>
          </div>

          <div class="node-tools">
            <ElInput
              v-model="designer.nodeSearch.value"
              clearable
              placeholder="搜索节点名称、标识或类型"
            />
            <ElSelect
              v-model="designer.nodeKindFilter.value"
              clearable
              collapse-tags
              multiple
              placeholder="筛选节点类型"
            >
              <ElOption
                v-for="kind in nodeKinds"
                :key="kind.value"
                :label="kind.label"
                :value="kind.value"
              />
            </ElSelect>
            <ElTag type="info">
              显示 {{ designer.renderedNodes.value.length }} / {{ designer.visibleNodes.value.length }}，共 {{ designer.topology.value.orderedNodes.length }} 个节点
            </ElTag>
            <ElButton size="small" @click="designer.collapseAllBranches">折叠全部分支</ElButton>
            <ElButton size="small" @click="designer.expandAllBranches">展开全部分支</ElButton>
          </div>

          <div class="node-actions">
            <ElButton size="small" @click="designer.moveNode(-1)">上移</ElButton>
            <ElButton size="small" @click="designer.moveNode(1)">下移</ElButton>
            <ElButton size="small" @click="designer.copyNode">复制节点</ElButton>
            <ElButton size="small" type="danger" @click="designer.deleteNode">删除节点</ElButton>
            <span class="shortcut-hint">⌘/Ctrl+S 保存 · ⌘/Ctrl+Z 撤销 · Shift+⌘/Ctrl+Z 重做 · Delete 删除</span>
          </div>

          <div class="tree-canvas">
            <ElEmpty v-if="!designer.visibleNodes.value.length" description="没有匹配的节点" />
            <template v-for="node in designer.renderedNodes.value" :key="node.id">
              <button
                class="flow-node"
                :class="[
                  `kind-${node.kind.toLowerCase()}`,
                  { selected: designer.selectedNodeId.value === node.id },
                ]"
                :data-approval-node-id="node.id"
                @click="focusNode(node.id)"
              >
                <span>{{ node.name }}</span>
                <small>{{ node.kind }} · {{ node.id }}</small>
                <small>
                  入 {{ designer.topology.value.incomingById.get(node.id)?.length ?? 0 }} ·
                  出 {{ designer.topology.value.outgoingById.get(node.id)?.length ?? 0 }}
                </small>
              </button>

              <template v-if="node.kind === 'CONDITION'">
                <button class="branch-toggle" @click="designer.toggleBranchCollapse(node.id)">
                  条件分支 {{ node.routes.length + 1 }} 条 ·
                  {{ designer.isBranchCollapsed(node.id) ? '展开' : '折叠' }}
                </button>
                <div v-if="!designer.isBranchCollapsed(node.id)" class="branch-grid">
                  <div v-for="(route, index) in node.routes" :key="`${node.id}-${index}`" class="branch-card">
                    <strong>条件 {{ index + 1 }}</strong>
                    <small>{{ route.condition.field }} {{ route.condition.operator }} {{ route.condition.value }}</small>
                    <small>目标：{{ route.next }}</small>
                  </div>
                  <div class="branch-card">
                    <strong>默认路由</strong>
                    <small>目标：{{ node.defaultNext }}</small>
                  </div>
                </div>
              </template>

              <template v-if="node.kind === 'PARALLEL_SPLIT'">
                <button class="branch-toggle" @click="designer.toggleBranchCollapse(node.id)">
                  并行分支 {{ node.branches.length }} 条 ·
                  {{ designer.isBranchCollapsed(node.id) ? '展开' : '折叠' }}
                </button>
                <div v-if="!designer.isBranchCollapsed(node.id)" class="branch-grid">
                  <div v-for="branch in node.branches" :key="branch.id" class="branch-card">
                    <strong>{{ branch.name }}</strong>
                    <small>入口：{{ branch.next }}</small>
                    <small>汇聚：{{ node.joinNodeId }}</small>
                  </div>
                </div>
              </template>

              <div v-if="node.kind !== 'END' && !designer.filtersActive.value" class="connector">↓</div>
            </template>
            <ElButton
              v-if="designer.hasMoreVisibleNodes.value"
              class="load-more"
              @click="designer.showMoreNodes"
            >加载更多节点（每次 120 个）</ElButton>
          </div>
        </ElCard>
        <ElEmpty v-else description="请选择或新建流程草稿" />
      </main>

      <aside class="right-panel">
        <ElCard shadow="never">
          <ElTabs model-value="properties">
            <ElTabPane label="属性" name="properties">
              <ElForm v-if="designer.draft.value" label-position="top">
                <ElFormItem label="流程名称">
                  <ElInput v-model="designer.draft.value.name" :disabled="!designer.editable.value" />
                </ElFormItem>
                <ElFormItem label="Form Package 版本">
                  <ElInputNumber
                    v-model="designer.draft.value.formPackage.packageVersion"
                    :disabled="!designer.editable.value"
                    :min="1"
                  />
                </ElFormItem>
                <ElDivider>当前节点</ElDivider>
                <template v-if="designer.selectedNode.value">
                  <ElFormItem label="节点名称">
                    <ElInput v-model="designer.selectedNode.value.name" :disabled="!designer.editable.value" />
                  </ElFormItem>
                  <ElFormItem label="节点标识">
                    <ElInput :model-value="designer.selectedNode.value.id" disabled />
                  </ElFormItem>

                  <template v-if="designer.selectedNode.value.kind === 'APPROVAL'">
                    <ElFormItem label="审批模式">
                      <ElSelect v-model="designer.selectedNode.value.mode.type" :disabled="!designer.editable.value">
                        <ElOption label="单人" value="SINGLE" />
                        <ElOption label="全部人" value="ALL" />
                        <ElOption label="任意一人" value="ANY" />
                      </ElSelect>
                    </ElFormItem>
                    <ElFormItem label="审批人规则">
                      <ElSelect v-model="designer.selectedNode.value.assignee.resolver" :disabled="!designer.editable.value">
                        <ElOption label="变量用户" value="VARIABLE_USER" />
                        <ElOption label="变量用户列表" value="VARIABLE_USER_LIST" />
                        <ElOption label="发起人上级" value="INITIATOR_MANAGER" />
                      </ElSelect>
                    </ElFormItem>
                    <ElFormItem label="输入变量">
                      <ElInput v-model="designer.selectedNode.value.assignee.variable" :disabled="!designer.editable.value" />
                    </ElFormItem>
                    <ElFormItem label="通过目标">
                      <ElSelect v-model="designer.selectedNode.value.next" :disabled="!designer.editable.value">
                        <ElOption v-for="option in designer.nodeOptions.value" :key="option.value" v-bind="option" />
                      </ElSelect>
                    </ElFormItem>
                    <ElFormItem label="驳回目标">
                      <ElSelect v-model="designer.selectedNode.value.rejectNext" clearable :disabled="!designer.editable.value">
                        <ElOption v-for="option in designer.nodeOptions.value" :key="option.value" v-bind="option" />
                      </ElSelect>
                    </ElFormItem>
                  </template>

                  <template v-else-if="designer.selectedNode.value.kind === 'HANDLE'">
                    <ElFormItem label="处理人变量">
                      <ElInput v-model="designer.selectedNode.value.assignee.variable" :disabled="!designer.editable.value" />
                    </ElFormItem>
                    <ElFormItem label="下一节点">
                      <ElSelect v-model="designer.selectedNode.value.next" :disabled="!designer.editable.value">
                        <ElOption v-for="option in designer.nodeOptions.value" :key="option.value" v-bind="option" />
                      </ElSelect>
                    </ElFormItem>
                  </template>

                  <template v-else-if="designer.selectedCondition.value">
                    <div v-for="(route, index) in designer.selectedCondition.value.routes" :key="index" class="route-box">
                      <ElInput v-model="route.condition.field" placeholder="Form 字段" />
                      <ElSelect v-model="route.condition.operator">
                        <ElOption label="≥" value="GREATER_THAN_OR_EQUAL" />
                        <ElOption label=">" value="GREATER_THAN" />
                        <ElOption label="≤" value="LESS_THAN_OR_EQUAL" />
                        <ElOption label="<" value="LESS_THAN" />
                        <ElOption label="=" value="EQUAL" />
                        <ElOption label="≠" value="NOT_EQUAL" />
                      </ElSelect>
                      <ElInputNumber v-model="route.condition.value" />
                      <ElSelect v-model="route.next">
                        <ElOption v-for="option in designer.nodeOptions.value" :key="option.value" v-bind="option" />
                      </ElSelect>
                      <ElButton text type="danger" @click="designer.removeConditionRoute(index)">删除路由</ElButton>
                    </div>
                    <ElButton @click="designer.addConditionRoute">添加条件路由</ElButton>
                  </template>

                  <template v-else-if="designer.selectedParallel.value">
                    <div v-for="(branch, index) in designer.selectedParallel.value.branches" :key="branch.id" class="route-box">
                      <ElInput v-model="branch.name" />
                      <ElSelect v-model="branch.next">
                        <ElOption v-for="option in designer.nodeOptions.value" :key="option.value" v-bind="option" />
                      </ElSelect>
                      <ElButton
                        text
                        type="danger"
                        :disabled="designer.selectedParallel.value.branches.length <= 2"
                        @click="designer.removeBranch(index)"
                      >删除分支</ElButton>
                    </div>
                    <ElButton @click="designer.addBranch">添加并行分支</ElButton>
                  </template>
                </template>
              </ElForm>
            </ElTabPane>

            <ElTabPane label="检查" name="validation">
              <ElSpace direction="vertical" fill class="full-width">
                <ElButton @click="designer.validate">运行服务端检查</ElButton>
                <div
                  v-for="issue in designer.validation.value?.issues"
                  :key="`${issue.code}-${issue.subject}`"
                  class="result-item"
                >
                  <ElAlert
                    :title="`${issue.code} · ${issue.subject}`"
                    :description="issue.message"
                    :type="issue.severity === 'ERROR' ? 'error' : issue.severity === 'WARNING' ? 'warning' : 'info'"
                    show-icon
                  />
                  <ElButton
                    v-if="designer.resolveNodeId(issue.subject)"
                    size="small"
                    @click="focusNode(issue.subject)"
                  >定位节点</ElButton>
                </div>
              </ElSpace>
            </ElTabPane>

            <ElTabPane label="模拟" name="simulation">
              <ElForm label-position="top">
                <ElFormItem label="金额">
                  <ElInputNumber v-model="designer.amount.value" :min="0" />
                </ElFormItem>
                <ElFormItem
                  v-for="node in designer.approvalNodes.value"
                  :key="node.id"
                  :label="node.name"
                >
                  <ElSelect v-model="designer.decisions.value[node.id]">
                    <ElOption label="同意" value="APPROVE" />
                    <ElOption label="驳回" value="REJECT" />
                  </ElSelect>
                </ElFormItem>
                <ElButton @click="designer.simulate">服务端模拟</ElButton>
              </ElForm>
              <template v-if="designer.simulation.value">
                <ElAlert
                  class="result-alert"
                  :title="designer.simulation.value.simulation.status"
                  :description="designer.simulation.value.pathSummary"
                  :type="designer.simulation.value.simulation.completed ? 'success' : 'warning'"
                  show-icon
                />
                <div class="simulation-path">
                  <ElButton
                    v-for="step in designer.simulation.value.simulation.steps"
                    :key="`${step.sequence}-${step.nodeId}`"
                    size="small"
                    @click="focusNode(step.nodeId)"
                  >
                    {{ step.sequence }}. {{ step.nodeName }} · {{ step.outcome }}
                  </ElButton>
                </div>
                <div
                  v-for="issue in designer.simulation.value.simulation.issues"
                  :key="`${issue.code}-${issue.nodeId}`"
                  class="result-item"
                >
                  <ElAlert
                    :title="`${issue.code} · ${issue.nodeId}`"
                    :description="issue.message"
                    type="warning"
                    show-icon
                  />
                  <ElButton size="small" @click="focusNode(issue.nodeId)">定位节点</ElButton>
                </div>
              </template>
            </ElTabPane>

            <ElTabPane label="DSL" name="dsl">
              <ElInput :model-value="designer.json.value" type="textarea" :rows="22" readonly />
            </ElTabPane>

            <ElTabPane label="发布" name="release">
              <ElSpace direction="vertical" fill class="full-width">
                <ElButton type="primary" :disabled="!designer.editable.value" @click="designer.publish">发布 Release Package</ElButton>
                <ElButton :disabled="!designer.editable.value" type="danger" plain @click="designer.archive">归档草稿</ElButton>
                <template v-if="designer.published.value">
                  <ElAlert title="发布完成" type="success" show-icon />
                  <code>DSL {{ designer.published.value.releasePackage.definitionHash }}</code>
                  <code>BPMN {{ designer.published.value.releasePackage.bpmnHash }}</code>
                  <code>Package {{ designer.published.value.releasePackage.packageHash }}</code>
                </template>
              </ElSpace>
            </ElTabPane>
          </ElTabs>
        </ElCard>
      </aside>
    </div>

    <ElDialog v-model="createVisible" title="新建流程草稿" width="520px">
      <ElForm label-position="top">
        <ElFormItem label="创建方式">
          <ElSelect v-model="createForm.source">
            <ElOption label="空白流程" value="BLANK" />
            <ElOption label="采购付款模板" value="PURCHASE_PAYMENT_TEMPLATE" />
            <ElOption label="复制已发布版本" value="COPY" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="流程标识"><ElInput v-model="createForm.definitionKey" /></ElFormItem>
        <ElFormItem label="流程名称"><ElInput v-model="createForm.name" /></ElFormItem>
        <ElFormItem label="DSL 版本"><ElInputNumber v-model="createForm.definitionVersion" :min="1" /></ElFormItem>
        <ElFormItem label="Form Package 版本"><ElInputNumber v-model="createForm.formPackageVersion" :min="1" /></ElFormItem>
        <ElFormItem v-if="createForm.source === 'COPY'" label="来源 DSL 版本">
          <ElInputNumber v-model="createForm.sourceDefinitionVersion" :min="1" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createVisible = false">取消</ElButton>
        <ElButton type="primary" @click="submitCreate">创建</ElButton>
      </template>
    </ElDialog>
  </Page>
</template>

<style scoped>
.designer-shell { display: grid; grid-template-columns: 270px minmax(480px, 1fr) 360px; gap: 12px; min-height: 720px; }
.panel-title { align-items: center; display: flex; justify-content: space-between; gap: 10px; }
.toolbar { flex-wrap: wrap; }
.left-panel, .right-panel { min-width: 0; }
.component-card { margin-top: 12px; }
.component-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.full-width { width: 100%; }
.filter { margin: 10px 0; }
.draft-item { background: transparent; border: 1px solid var(--el-border-color-light); border-radius: 8px; cursor: pointer; display: grid; gap: 4px; margin-bottom: 8px; padding: 10px; text-align: left; width: 100%; }
.draft-item.active { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.draft-item small, .muted, .flow-node small, .branch-card small { color: var(--el-text-color-secondary); }
.node-tools { align-items: center; display: grid; grid-template-columns: minmax(180px, 1fr) minmax(180px, 1fr) auto auto auto; gap: 8px; margin: 12px 0; }
.node-actions { align-items: center; display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
.shortcut-hint { color: var(--el-text-color-secondary); font-size: 12px; margin-left: auto; }
.tree-canvas { align-items: center; content-visibility: auto; display: flex; flex-direction: column; padding: 18px; }
.flow-node { background: var(--el-bg-color); border: 2px solid var(--el-border-color); border-radius: 10px; cursor: pointer; display: grid; gap: 4px; min-width: 240px; padding: 12px 18px; }
.flow-node.selected { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px var(--el-color-primary-light-8); }
.kind-start, .kind-end { border-radius: 24px; }
.kind-condition { border-color: var(--el-color-warning); }
.kind-parallel_split, .kind-parallel_join { border-color: var(--el-color-success); }
.connector { color: var(--el-text-color-secondary); font-size: 22px; line-height: 28px; }
.branch-toggle { background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-light); border-radius: 999px; color: var(--el-text-color-regular); cursor: pointer; font-size: 12px; margin-top: 8px; padding: 5px 12px; }
.branch-grid { display: grid; grid-template-columns: repeat(2, minmax(160px, 1fr)); gap: 8px; margin: 10px 0; width: 100%; }
.branch-card, .route-box { border: 1px solid var(--el-border-color-light); border-radius: 8px; display: grid; gap: 8px; padding: 10px; }
.route-box { margin-bottom: 10px; }
.result-alert { margin-top: 12px; }
.result-item { align-items: flex-start; display: grid; gap: 6px; grid-template-columns: 1fr auto; width: 100%; }
.simulation-path { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.load-more { margin-top: 16px; }
.conflict-panel { display: grid; gap: 12px; margin-bottom: 12px; }
.conflict-summary { display: flex; flex-wrap: wrap; gap: 16px; color: var(--el-text-color-secondary); }
.conflict-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.conflict-columns section { border: 1px solid var(--el-border-color-light); border-radius: 8px; display: grid; gap: 8px; padding: 10px; }
.change-tags { display: flex; flex-wrap: wrap; gap: 6px; }
code { overflow-wrap: anywhere; }
@media (max-width: 1280px) { .designer-shell { grid-template-columns: 240px 1fr; } .right-panel { grid-column: 1 / -1; } .node-tools { grid-template-columns: 1fr 1fr; } }
@media (max-width: 820px) { .designer-shell { grid-template-columns: 1fr; } .right-panel { grid-column: auto; } .conflict-columns, .node-tools, .result-item { grid-template-columns: 1fr; } .shortcut-hint { margin-left: 0; width: 100%; } }
</style>

<script lang="ts" setup>
import { onMounted, ref } from 'vue';
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
const createVisible = ref(false);
const createForm = ref({
  definitionKey: 'expense-approval',
  definitionVersion: 1,
  formPackageVersion: 1,
  name: '费用审批',
  source: 'BLANK' as 'BLANK' | 'COPY' | 'PURCHASE_PAYMENT_TEMPLATE',
  sourceDefinitionVersion: 1,
});

onMounted(designer.loadDrafts);

async function submitCreate() {
  await designer.createDraft(createForm.value);
  createVisible.value = false;
}

function statusType(status?: string) {
  if (status === 'PUBLISHED') return 'success';
  if (status === 'ARCHIVED') return 'info';
  if (status === 'VALIDATED') return 'warning';
  return 'primary';
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
                <ElButton :disabled="!designer.editable.value" @click="designer.save()">保存</ElButton>
                <ElButton v-if="designer.saveState.value === 'conflict'" type="warning" @click="designer.reload">安全重载</ElButton>
                <ElButton @click="designer.validate">服务端检查</ElButton>
                <ElButton type="primary" :disabled="!designer.editable.value" @click="designer.publish">发布</ElButton>
              </ElSpace>
            </div>
          </template>

          <ElAlert
            v-if="designer.saveState.value === 'conflict'"
            type="warning"
            show-icon
            title="服务器上的草稿已更新；请重载后继续编辑，避免覆盖他人修改。"
          />

          <div class="node-actions">
            <ElButton size="small" @click="designer.moveNode(-1)">上移</ElButton>
            <ElButton size="small" @click="designer.moveNode(1)">下移</ElButton>
            <ElButton size="small" @click="designer.copyNode">复制节点</ElButton>
            <ElButton size="small" type="danger" @click="designer.deleteNode">删除节点</ElButton>
          </div>

          <div class="tree-canvas">
            <template v-for="node in designer.draft.value.definition.nodes" :key="node.id">
              <button
                class="flow-node"
                :class="[
                  `kind-${node.kind.toLowerCase()}`,
                  { selected: designer.selectedNodeId.value === node.id },
                ]"
                @click="designer.selectedNodeId.value = node.id"
              >
                <span>{{ node.name }}</span>
                <small>{{ node.kind }} · {{ node.id }}</small>
              </button>
              <div v-if="node.kind === 'PARALLEL_SPLIT'" class="branch-grid">
                <div v-for="branch in node.branches" :key="branch.id" class="branch-card">
                  <strong>{{ branch.name }}</strong>
                  <small>入口：{{ branch.next }}</small>
                  <small>汇聚：{{ node.joinNodeId }}</small>
                </div>
              </div>
              <div v-if="node.kind !== 'END'" class="connector">↓</div>
            </template>
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
                <ElAlert
                  v-for="issue in designer.validation.value?.issues"
                  :key="`${issue.code}-${issue.subject}`"
                  :title="`${issue.code} · ${issue.subject}`"
                  :description="issue.message"
                  :type="issue.severity === 'ERROR' ? 'error' : issue.severity === 'WARNING' ? 'warning' : 'info'"
                  show-icon
                />
              </ElSpace>
            </ElTabPane>

            <ElTabPane label="模拟" name="simulation">
              <ElForm label-position="top">
                <ElFormItem label="金额">
                  <ElInputNumber v-model="designer.amount.value" :min="0" />
                </ElFormItem>
                <ElFormItem
                  v-for="node in designer.draft.value?.definition.nodes.filter(item => item.kind === 'APPROVAL')"
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
              <ElAlert
                v-if="designer.simulation.value"
                class="result-alert"
                :title="designer.simulation.value.simulation.status"
                :description="designer.simulation.value.pathSummary"
                :type="designer.simulation.value.simulation.completed ? 'success' : 'warning'"
                show-icon
              />
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
.node-actions { display: flex; gap: 8px; margin: 12px 0; }
.tree-canvas { align-items: center; display: flex; flex-direction: column; padding: 18px; }
.flow-node { background: var(--el-bg-color); border: 2px solid var(--el-border-color); border-radius: 10px; cursor: pointer; display: grid; gap: 4px; min-width: 240px; padding: 12px 18px; }
.flow-node.selected { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px var(--el-color-primary-light-8); }
.kind-start, .kind-end { border-radius: 24px; }
.kind-condition { border-color: var(--el-color-warning); }
.kind-parallel_split, .kind-parallel_join { border-color: var(--el-color-success); }
.connector { color: var(--el-text-color-secondary); font-size: 22px; line-height: 28px; }
.branch-grid { display: grid; grid-template-columns: repeat(2, minmax(160px, 1fr)); gap: 8px; margin: 10px 0; width: 100%; }
.branch-card, .route-box { border: 1px solid var(--el-border-color-light); border-radius: 8px; display: grid; gap: 8px; padding: 10px; }
.route-box { margin-bottom: 10px; }
.result-alert { margin-top: 12px; }
code { overflow-wrap: anywhere; }
@media (max-width: 1280px) { .designer-shell { grid-template-columns: 240px 1fr; } .right-panel { grid-column: 1 / -1; } }
@media (max-width: 820px) { .designer-shell { grid-template-columns: 1fr; } .right-panel { grid-column: auto; } }
</style>

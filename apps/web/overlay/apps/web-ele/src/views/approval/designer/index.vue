<script lang="ts" setup>
import type {
  ApprovalDslDraft,
  ApprovalNode,
  ApprovalStep,
  ComparisonOperator,
  ConditionStep,
  HandleStep,
  SimulationResult,
  SimulationStep,
} from '#/api/approval/process-design';

import { computed, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCol,
  ElDivider,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElRow,
  ElSelect,
  ElTabPane,
  ElTabs,
  ElTag,
} from 'element-plus';

import { createBlankApprovalDraft } from '#/api/approval/process-design';

interface VisualNode {
  depth: number;
  edgeLabel: string;
  node: ApprovalNode;
  repeated: boolean;
}

type Decision = 'APPROVE' | 'REJECT';
type AddableKind = 'APPROVAL' | 'CONDITION' | 'HANDLE';

const draft = ref<ApprovalDslDraft>(createBlankApprovalDraft());
const selectedNodeId = ref('manager');
const activeTab = ref('properties');
const scenarioAmount = ref(1200);
const decisions = ref<Record<string, Decision>>({});
const simulation = ref<SimulationResult>();
let sequence = 1;

const selectedNode = computed(() => draft.value.nodes.find(
  item => item.id === selectedNodeId.value,
));
const selectedApproval = computed<ApprovalStep | undefined>(() => {
  const node = selectedNode.value;
  return node?.kind === 'APPROVAL' ? node : undefined;
});
const selectedHandle = computed<HandleStep | undefined>(() => {
  const node = selectedNode.value;
  return node?.kind === 'HANDLE' ? node : undefined;
});
const selectedCondition = computed<ConditionStep | undefined>(() => {
  const node = selectedNode.value;
  return node?.kind === 'CONDITION' ? node : undefined;
});
const nodeOptions = computed(() => draft.value.nodes.map(node => ({
  label: `${node.name} · ${node.id}`,
  value: node.id,
})));
const visualNodes = computed(() => buildVisualTree(draft.value));
const validationIssues = computed(() => validateDraft(draft.value));
const draftJson = computed(() => JSON.stringify(draft.value, null, 2));
const approvalNodes = computed(() => draft.value.nodes.filter(
  (node): node is ApprovalStep => node.kind === 'APPROVAL',
));

function buildVisualTree(value: ApprovalDslDraft): VisualNode[] {
  const nodes = new Map(value.nodes.map(node => [node.id, node]));
  const visited = new Set<string>();
  const result: VisualNode[] = [];

  function walk(nodeId: string, depth: number, edgeLabel: string) {
    const node = nodes.get(nodeId);
    if (!node) return;
    if (visited.has(nodeId)) {
      result.push({ depth, edgeLabel, node, repeated: true });
      return;
    }
    visited.add(nodeId);
    result.push({ depth, edgeLabel, node, repeated: false });
    if (node.kind === 'CONDITION') {
      node.routes.forEach((route, index) => {
        walk(
          route.next,
          depth + 1,
          `条件 ${index + 1}: ${route.condition.field} ${operatorLabel(route.condition.operator)} ${route.condition.value}`,
        );
      });
      walk(node.defaultNext, depth + 1, '默认分支');
      return;
    }
    if (node.kind === 'APPROVAL') {
      walk(node.next, depth, '同意');
      if (node.rejectNext) walk(node.rejectNext, depth + 1, '驳回');
      return;
    }
    if (node.kind === 'START' || node.kind === 'HANDLE') {
      walk(node.next, depth, node.kind === 'START' ? '进入流程' : '处理完成');
    }
  }

  walk(value.startNodeId, 0, '起点');
  value.nodes.forEach((node) => {
    if (!visited.has(node.id)) result.push({ depth: 0, edgeLabel: '不可达', node, repeated: false });
  });
  return result;
}

function validateDraft(value: ApprovalDslDraft) {
  const issues: string[] = [];
  const ids = new Set<string>();
  value.nodes.forEach((node) => {
    if (!/^[A-Za-z][A-Za-z0-9_-]*$/.test(node.id)) {
      issues.push(`节点 ${node.name} 的 ID 不符合引擎安全规则`);
    }
    if (ids.has(node.id)) issues.push(`节点 ID 重复：${node.id}`);
    ids.add(node.id);
  });
  const start = value.nodes.find(node => node.id === value.startNodeId);
  if (start?.kind !== 'START') issues.push('startNodeId 必须指向发起节点');
  if (!value.nodes.some(node => node.kind === 'END')) issues.push('至少需要一个结束节点');
  value.nodes.forEach((node) => {
    outgoing(node).forEach((target) => {
      if (!ids.has(target)) issues.push(`${node.id} 指向不存在的节点 ${target}`);
    });
  });
  if (value.definitionKey !== value.formPackage.formKey) {
    issues.push('Approval DSL Key 必须与 Form Package Key 一致');
  }
  return [...new Set(issues)];
}

function outgoing(node: ApprovalNode) {
  if (node.kind === 'START' || node.kind === 'HANDLE') return [node.next];
  if (node.kind === 'APPROVAL') return node.rejectNext
    ? [node.next, node.rejectNext]
    : [node.next];
  if (node.kind === 'CONDITION') {
    return [...node.routes.map(route => route.next), node.defaultNext];
  }
  return [];
}

function nodeKindLabel(kind: ApprovalNode['kind']) {
  return {
    APPROVAL: '审批节点',
    CONDITION: '条件分支',
    END: '结束',
    HANDLE: '处理节点',
    START: '发起',
  }[kind];
}

function nodeTagType(kind: ApprovalNode['kind']) {
  if (kind === 'START' || kind === 'END') return 'info';
  if (kind === 'CONDITION') return 'warning';
  if (kind === 'HANDLE') return 'success';
  return 'primary';
}

function operatorLabel(operator: ComparisonOperator) {
  return {
    EQUAL: '=',
    GREATER_THAN: '>',
    GREATER_THAN_OR_EQUAL: '≥',
    LESS_THAN: '<',
    LESS_THAN_OR_EQUAL: '≤',
    NOT_EQUAL: '≠',
  }[operator];
}

function selectNode(nodeId: string) {
  selectedNodeId.value = nodeId;
  activeTab.value = 'properties';
}

function nextId(prefix: string) {
  const ids = new Set(draft.value.nodes.map(node => node.id));
  let candidate = `${prefix}${sequence++}`;
  while (ids.has(candidate)) candidate = `${prefix}${sequence++}`;
  return candidate;
}

function mainInsertionPoint() {
  const end = draft.value.nodes.find(node => node.kind === 'END');
  if (!end) return undefined;
  const predecessor = draft.value.nodes.find((node) => {
    if (node.kind === 'START' || node.kind === 'HANDLE') return node.next === end.id;
    if (node.kind === 'APPROVAL') return node.next === end.id;
    return false;
  });
  return predecessor ? { end, predecessor } : undefined;
}

function addNode(kind: AddableKind) {
  const point = mainInsertionPoint();
  if (!point) return;
  const id = nextId(kind.toLowerCase());
  let node: ApprovalNode;
  if (kind === 'APPROVAL') {
    node = {
      assignee: {
        emptyPolicy: 'FAIL',
        resolver: 'VARIABLE_USER',
        variable: `${id}UserId`,
      },
      id,
      kind,
      mode: 'SINGLE',
      name: '新审批节点',
      next: point.end.id,
    };
  } else if (kind === 'HANDLE') {
    node = {
      assignee: {
        emptyPolicy: 'FAIL',
        resolver: 'VARIABLE_USER',
        variable: 'initiatorId',
      },
      id,
      kind,
      name: '发起人处理',
      next: point.end.id,
    };
  } else {
    node = {
      defaultNext: point.end.id,
      id,
      kind,
      name: '新条件分支',
      routes: [{
        condition: { field: 'amount', operator: 'GREATER_THAN_OR_EQUAL', value: 1000 },
        next: point.end.id,
      }],
    };
  }
  if (point.predecessor.kind === 'START' || point.predecessor.kind === 'HANDLE'
    || point.predecessor.kind === 'APPROVAL') {
    point.predecessor.next = id;
  }
  draft.value.nodes.splice(draft.value.nodes.indexOf(point.end), 0, node);
  selectNode(id);
  simulation.value = undefined;
}

function deleteSelected() {
  const node = selectedNode.value;
  if (!node || node.kind === 'START' || node.kind === 'END') return;
  const replacement = node.kind === 'CONDITION' ? node.defaultNext : node.next;
  draft.value.nodes.forEach((candidate) => {
    if (candidate.kind === 'START' || candidate.kind === 'HANDLE') {
      if (candidate.next === node.id) candidate.next = replacement;
    } else if (candidate.kind === 'APPROVAL') {
      if (candidate.next === node.id) candidate.next = replacement;
      if (candidate.rejectNext === node.id) candidate.rejectNext = replacement;
    } else if (candidate.kind === 'CONDITION') {
      if (candidate.defaultNext === node.id) candidate.defaultNext = replacement;
      candidate.routes.forEach((route) => {
        if (route.next === node.id) route.next = replacement;
      });
    }
  });
  draft.value.nodes = draft.value.nodes.filter(candidate => candidate.id !== node.id);
  selectedNodeId.value = replacement;
  simulation.value = undefined;
}

function addConditionRoute() {
  const condition = selectedCondition.value;
  const end = draft.value.nodes.find(node => node.kind === 'END');
  if (!condition || !end) return;
  condition.routes.push({
    condition: { field: 'amount', operator: 'GREATER_THAN_OR_EQUAL', value: 1000 },
    next: end.id,
  });
}

function removeConditionRoute(index: number) {
  const condition = selectedCondition.value;
  if (!condition || condition.routes.length === 1) return;
  condition.routes.splice(index, 1);
}

function runSimulation() {
  const nodes = new Map(draft.value.nodes.map(node => [node.id, node]));
  const steps: SimulationStep[] = [];
  const issues: string[] = [];
  let current = draft.value.startNodeId;

  for (let index = 0; index < 100; index++) {
    const node = nodes.get(current);
    if (!node) {
      issues.push(`模拟到达未知节点 ${current}`);
      simulation.value = { issues, status: 'BLOCKED', steps };
      return;
    }
    if (node.kind === 'START') {
      steps.push(step(node, 'STARTED'));
      current = node.next;
    } else if (node.kind === 'APPROVAL') {
      const decision = decisions.value[node.id] || 'APPROVE';
      steps.push(step(node, decision === 'APPROVE' ? 'APPROVED' : 'REJECTED'));
      if (decision === 'REJECT' && !node.rejectNext) {
        simulation.value = { issues, status: 'REJECTED', steps };
        return;
      }
      current = decision === 'REJECT' ? node.rejectNext! : node.next;
    } else if (node.kind === 'HANDLE') {
      steps.push(step(node, 'HANDLED'));
      current = node.next;
    } else if (node.kind === 'CONDITION') {
      const routeIndex = node.routes.findIndex(route => compare(
        scenarioAmount.value,
        route.condition.operator,
        route.condition.value,
      ));
      steps.push(step(node, routeIndex >= 0 ? `ROUTE_${routeIndex + 1}` : 'DEFAULT'));
      current = routeIndex >= 0 ? node.routes[routeIndex]!.next : node.defaultNext;
    } else {
      steps.push(step(node, 'COMPLETED'));
      simulation.value = { issues, status: 'COMPLETED', steps };
      return;
    }
  }
  issues.push('模拟超过 100 次迁移，可能存在处理回路');
  simulation.value = { issues, status: 'TRANSITION_LIMIT_REACHED', steps };
}

function step(node: ApprovalNode, outcome: string): SimulationStep {
  return { kind: node.kind, nodeId: node.id, nodeName: node.name, outcome };
}

function compare(actual: number, operator: ComparisonOperator, expected: number) {
  if (operator === 'GREATER_THAN') return actual > expected;
  if (operator === 'GREATER_THAN_OR_EQUAL') return actual >= expected;
  if (operator === 'LESS_THAN') return actual < expected;
  if (operator === 'LESS_THAN_OR_EQUAL') return actual <= expected;
  if (operator === 'NOT_EQUAL') return actual !== expected;
  return actual === expected;
}
</script>

<template>
  <Page title="树状审批设计器">
    <ElAlert
      class="draft-alert"
      :closable="false"
      title="当前页面已替换占位实现：可编辑 Approval DSL 树并进行本地场景预演。服务端草稿持久化、编译与 Release Package 发布正在本 Draft PR 后续切片接入。"
      type="info"
    />

    <ElCard class="designer-toolbar" shadow="never">
      <div class="toolbar-grid">
        <ElInput v-model="draft.name" placeholder="流程名称" />
        <ElInput v-model="draft.definitionKey" placeholder="Definition Key" />
        <ElInputNumber v-model="draft.version" :min="1" />
        <ElTag :type="validationIssues.length ? 'danger' : 'success'" effect="plain">
          {{ validationIssues.length ? `${validationIssues.length} 个静态问题` : '本地静态检查通过' }}
        </ElTag>
      </div>
    </ElCard>

    <ElRow :gutter="14">
      <ElCol :lg="5" :md="7" :sm="24">
        <ElCard class="panel" shadow="never">
          <template #header><strong>节点组件</strong></template>
          <div class="palette">
            <button type="button" @click="addNode('APPROVAL')">
              <strong>审批节点</strong><span>单人、会签或或签</span>
            </button>
            <button type="button" @click="addNode('CONDITION')">
              <strong>条件分支</strong><span>按表单数值选择路径</span>
            </button>
            <button type="button" @click="addNode('HANDLE')">
              <strong>处理节点</strong><span>发起人修改或受控回路</span>
            </button>
            <button disabled type="button">
              <strong>并行分支</strong><span>协议和编译器扩展进行中</span>
            </button>
          </div>
          <ElDivider content-position="left">草稿绑定</ElDivider>
          <ElForm label-position="top">
            <ElFormItem label="Form Package Key">
              <ElInput v-model="draft.formPackage.formKey" />
            </ElFormItem>
            <ElFormItem label="Form Package 版本">
              <ElInputNumber v-model="draft.formPackage.packageVersion" :min="1" />
            </ElFormItem>
            <ElFormItem label="Package Hash">
              <ElInput v-model="draft.formPackage.packageHash" />
            </ElFormItem>
          </ElForm>
        </ElCard>
      </ElCol>

      <ElCol :lg="11" :md="17" :sm="24">
        <ElCard class="panel canvas-panel" shadow="never">
          <template #header>
            <div class="panel-header">
              <div><strong>流程树</strong><span>{{ draft.nodes.length }} 个节点</span></div>
              <ElButton
                :disabled="!selectedNode || ['START', 'END'].includes(selectedNode.kind)"
                plain type="danger"
                @click="deleteSelected"
              >删除选中节点</ElButton>
            </div>
          </template>
          <div class="tree-canvas">
            <button
              v-for="item in visualNodes"
              :key="`${item.node.id}-${item.edgeLabel}-${item.depth}`"
              class="tree-node"
              :class="{ active: selectedNodeId === item.node.id, repeated: item.repeated }"
              :style="{ marginLeft: `${item.depth * 28}px` }"
              type="button"
              @click="selectNode(item.node.id)"
            >
              <span class="edge-label">{{ item.edgeLabel }}</span>
              <div>
                <ElTag :type="nodeTagType(item.node.kind)" effect="plain" size="small">
                  {{ nodeKindLabel(item.node.kind) }}
                </ElTag>
                <strong>{{ item.node.name }}</strong>
                <small>{{ item.node.id }}</small>
              </div>
              <span v-if="item.repeated" class="reference-label">汇聚引用</span>
            </button>
          </div>
          <ElAlert
            v-if="validationIssues.length"
            class="validation-alert"
            :closable="false"
            type="error"
          >
            <template #default>
              <div v-for="issue in validationIssues" :key="issue">{{ issue }}</div>
            </template>
          </ElAlert>
        </ElCard>
      </ElCol>

      <ElCol :lg="8" :md="24" :sm="24">
        <ElCard class="panel" shadow="never">
          <ElTabs v-model="activeTab">
            <ElTabPane label="节点属性" name="properties">
              <ElEmpty v-if="!selectedNode" description="请选择流程节点" />
              <ElForm v-else label-position="top">
                <ElFormItem label="节点名称"><ElInput v-model="selectedNode.name" /></ElFormItem>
                <ElFormItem label="节点 ID"><ElInput :model-value="selectedNode.id" disabled /></ElFormItem>
                <template v-if="selectedApproval">
                  <ElFormItem label="审批模式">
                    <ElSelect v-model="selectedApproval.mode">
                      <ElOption label="单人审批" value="SINGLE" />
                      <ElOption label="全部同意" value="ALL" />
                      <ElOption label="任一同意" value="ANY" />
                    </ElSelect>
                  </ElFormItem>
                  <ElFormItem label="审批人解析器">
                    <ElSelect v-model="selectedApproval.assignee.resolver">
                      <ElOption label="发起人直属主管" value="INITIATOR_MANAGER" />
                      <ElOption label="变量用户" value="VARIABLE_USER" />
                      <ElOption label="变量用户列表" value="VARIABLE_USER_LIST" />
                    </ElSelect>
                  </ElFormItem>
                  <ElFormItem label="审批人变量">
                    <ElInput v-model="selectedApproval.assignee.variable" />
                  </ElFormItem>
                  <ElFormItem label="同意后节点">
                    <ElSelect v-model="selectedApproval.next">
                      <ElOption v-for="item in nodeOptions" :key="item.value" :label="item.label" :value="item.value" />
                    </ElSelect>
                  </ElFormItem>
                  <ElFormItem label="驳回后节点">
                    <ElSelect v-model="selectedApproval.rejectNext" clearable>
                      <ElOption v-for="item in nodeOptions" :key="item.value" :label="item.label" :value="item.value" />
                    </ElSelect>
                  </ElFormItem>
                </template>
                <template v-if="selectedHandle">
                  <ElFormItem label="处理人变量"><ElInput v-model="selectedHandle.assignee.variable" /></ElFormItem>
                  <ElFormItem label="处理后节点">
                    <ElSelect v-model="selectedHandle.next">
                      <ElOption v-for="item in nodeOptions" :key="item.value" :label="item.label" :value="item.value" />
                    </ElSelect>
                  </ElFormItem>
                </template>
                <template v-if="selectedCondition">
                  <div
                    v-for="(route, index) in selectedCondition.routes"
                    :key="index"
                    class="condition-route"
                  >
                    <div class="route-header">
                      <strong>条件 {{ index + 1 }}</strong>
                      <ElButton
                        :disabled="selectedCondition.routes.length === 1"
                        text type="danger"
                        @click="removeConditionRoute(index)"
                      >删除</ElButton>
                    </div>
                    <ElInput v-model="route.condition.field" placeholder="表单字段 Key" />
                    <ElSelect v-model="route.condition.operator">
                      <ElOption label="大于" value="GREATER_THAN" />
                      <ElOption label="大于等于" value="GREATER_THAN_OR_EQUAL" />
                      <ElOption label="小于" value="LESS_THAN" />
                      <ElOption label="小于等于" value="LESS_THAN_OR_EQUAL" />
                      <ElOption label="等于" value="EQUAL" />
                      <ElOption label="不等于" value="NOT_EQUAL" />
                    </ElSelect>
                    <ElInputNumber v-model="route.condition.value" />
                    <ElSelect v-model="route.next">
                      <ElOption v-for="item in nodeOptions" :key="item.value" :label="item.label" :value="item.value" />
                    </ElSelect>
                  </div>
                  <ElButton plain @click="addConditionRoute">添加条件</ElButton>
                  <ElFormItem label="默认分支">
                    <ElSelect v-model="selectedCondition.defaultNext">
                      <ElOption v-for="item in nodeOptions" :key="item.value" :label="item.label" :value="item.value" />
                    </ElSelect>
                  </ElFormItem>
                </template>
              </ElForm>
            </ElTabPane>

            <ElTabPane label="场景模拟" name="simulation">
              <ElAlert
                :closable="false"
                title="这里提供快速本地预演；同语义的 Java 服务端模拟器已加入本 PR，后续 API 接线后将以服务端结果为准。"
                type="warning"
              />
              <ElForm class="simulation-form" label-position="top">
                <ElFormItem label="amount 场景值">
                  <ElInputNumber v-model="scenarioAmount" />
                </ElFormItem>
                <ElFormItem
                  v-for="node in approvalNodes"
                  :key="node.id"
                  :label="`${node.name} 决策`"
                >
                  <ElSelect v-model="decisions[node.id]" placeholder="默认同意">
                    <ElOption label="同意" value="APPROVE" />
                    <ElOption label="驳回" value="REJECT" />
                  </ElSelect>
                </ElFormItem>
                <ElButton type="primary" @click="runSimulation">运行路径模拟</ElButton>
              </ElForm>
              <div v-if="simulation" class="simulation-result">
                <ElTag :type="simulation.status === 'COMPLETED' ? 'success' : 'warning'">
                  {{ simulation.status }}
                </ElTag>
                <div v-for="(item, index) in simulation.steps" :key="`${item.nodeId}-${index}`" class="simulation-step">
                  <span>{{ index + 1 }}</span>
                  <div><strong>{{ item.nodeName }}</strong><small>{{ item.nodeId }} · {{ item.outcome }}</small></div>
                </div>
                <ElAlert
                  v-for="issue in simulation.issues"
                  :key="issue"
                  :closable="false"
                  :title="issue"
                  type="error"
                />
              </div>
            </ElTabPane>

            <ElTabPane label="DSL JSON" name="json">
              <pre class="json-preview">{{ draftJson }}</pre>
            </ElTabPane>
          </ElTabs>
        </ElCard>
      </ElCol>
    </ElRow>
  </Page>
</template>

<style scoped>
.draft-alert,.designer-toolbar{margin-bottom:14px}.toolbar-grid{display:grid;grid-template-columns:minmax(180px,1.4fr) minmax(180px,1fr) 110px auto;align-items:center;gap:10px}.panel{min-height:720px}.palette{display:grid;gap:9px}.palette button{display:grid;gap:4px;padding:12px;color:inherit;text-align:left;border:1px solid var(--el-border-color-lighter);border-radius:9px;background:var(--el-fill-color-light);cursor:pointer}.palette button span{color:var(--el-text-color-secondary);font-size:12px}.palette button:disabled{cursor:not-allowed;opacity:.5}.panel-header,.panel-header>div,.route-header{display:flex;align-items:center;justify-content:space-between;gap:10px}.panel-header>div{align-items:flex-start;flex-direction:column}.panel-header span{color:var(--el-text-color-secondary);font-size:12px}.tree-canvas{display:grid;gap:10px}.tree-node{position:relative;display:grid;grid-template-columns:minmax(0,1fr) auto;gap:10px;width:calc(100% - var(--tree-indent,0px));padding:15px;color:inherit;text-align:left;border:1px solid var(--el-border-color);border-radius:10px;background:var(--el-bg-color);cursor:pointer}.tree-node:hover,.tree-node.active{border-color:var(--el-color-primary);box-shadow:0 0 0 2px var(--el-color-primary-light-8)}.tree-node.repeated{border-style:dashed}.tree-node>div{display:grid;grid-template-columns:auto minmax(0,1fr);align-items:center;gap:8px}.tree-node strong,.tree-node small{grid-column:2}.tree-node small,.edge-label,.reference-label{color:var(--el-text-color-secondary);font-size:12px}.edge-label{position:absolute;top:-9px;left:16px;padding:1px 7px;border-radius:999px;background:var(--el-bg-color)}.reference-label{align-self:center}.validation-alert{margin-top:14px}.condition-route{display:grid;grid-template-columns:1fr 145px 110px;gap:8px;margin-bottom:12px;padding:10px;border:1px solid var(--el-border-color-lighter);border-radius:9px}.route-header{grid-column:1/-1}.condition-route>.el-select:last-child{grid-column:1/-1}.simulation-form{margin-top:14px}.simulation-result{display:grid;gap:10px;margin-top:18px}.simulation-step{display:grid;grid-template-columns:28px 1fr;align-items:center;gap:10px;padding:9px;border:1px solid var(--el-border-color-lighter);border-radius:8px}.simulation-step>span{display:grid;place-items:center;width:26px;height:26px;border-radius:50%;color:var(--el-color-primary);background:var(--el-color-primary-light-9)}.simulation-step>div{display:grid;gap:3px}.simulation-step small{color:var(--el-text-color-secondary)}.json-preview{max-height:620px;overflow:auto;padding:14px;border-radius:9px;background:var(--el-fill-color-light);font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere}.panel :deep(.el-select),.panel :deep(.el-input-number){width:100%}@media(max-width:1200px){.panel{min-height:auto;margin-bottom:14px}}@media(max-width:768px){.toolbar-grid{grid-template-columns:1fr}.tree-node{margin-left:0!important}.condition-route{grid-template-columns:1fr}}
</style>

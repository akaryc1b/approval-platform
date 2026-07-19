from pathlib import Path

path = Path('apps/web/overlay/apps/web-ele/src/views/approval/designer/process-designer.vue')
text = path.read_text()

def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one marker, found {count}')
    text = text.replace(old, new, 1)

replace_once(
    "import { onMounted, ref } from 'vue';\n",
    "import { nextTick, onMounted, ref } from 'vue';\n",
    'next tick import',
)
replace_once(
'''const designer = useApprovalDesigner();
const createVisible = ref(false);
''',
'''const designer = useApprovalDesigner();
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
''',
    'node kind options',
)
replace_once(
'''function statusType(status?: string) {
  if (status === 'PUBLISHED') return 'success';
  if (status === 'ARCHIVED') return 'info';
  if (status === 'VALIDATED') return 'warning';
  return 'primary';
}
''',
'''function statusType(status?: string) {
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
''',
    'focus node helper',
)
old_canvas = '''          <div class="node-actions">
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
'''
new_canvas = '''          <div class="node-tools">
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
'''
replace_once(old_canvas, new_canvas, 'large flow canvas')
path.write_text(text)

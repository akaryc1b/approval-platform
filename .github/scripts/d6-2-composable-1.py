from pathlib import Path

path = Path('apps/web/overlay/apps/web-ele/src/views/approval/designer/use-approval-designer.ts')
text = path.read_text()

def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one marker, found {count}')
    text = text.replace(old, new, 1)

replace_once(
    "import type {\n  ApprovalDesignerConflict,\n  ApprovalDesignerSnapshot,\n} from './designer-conflict';\n",
    "import type {\n  ApprovalDesignerConflict,\n  ApprovalDesignerSnapshot,\n} from './designer-conflict';\n"
    "import {\n"
    "  buildApprovalDesignerTopologyIndex,\n"
    "  describeApprovalDesignerDeletion,\n"
    "  filterApprovalDesignerNodes,\n"
    "  resolveApprovalDesignerNodeId,\n"
    "} from './designer-topology.mjs';\n"
    "import type { ApprovalDesignerTopologyIndex } from './designer-topology.mjs';\n",
    'topology imports',
)
replace_once(
    "  const conflict = ref<DesignerConflictState>();\n\n"
    "  let suspended = true;\n",
    "  const conflict = ref<DesignerConflictState>();\n"
    "  const nodeSearch = ref('');\n"
    "  const nodeKindFilter = ref<ApprovalNode['kind'][]>([]);\n"
    "  const nodeRenderLimit = ref(120);\n"
    "  const collapsedBranchNodeIds = ref<Set<string>>(new Set());\n\n"
    "  let suspended = true;\n",
    'large flow state',
)
old_computed = '''  const selectedNode = computed(() => draft.value?.definition.nodes.find(
    node => node.id === selectedNodeId.value,
  ));
'''
new_computed = '''  const topology = computed<ApprovalDesignerTopologyIndex<ApprovalNode>>(() =>
    buildApprovalDesignerTopologyIndex(draft.value?.definition.nodes ?? []),
  );
  const selectedNode = computed(() => topology.value.byId.get(selectedNodeId.value));
'''
replace_once(old_computed, new_computed, 'selected node topology')
replace_once(
'''  const nodeOptions = computed(() => draft.value?.definition.nodes.map(node => ({
    label: `${node.name} · ${node.id}`,
    value: node.id,
  })) ?? []);
''',
'''  const nodeOptions = computed(() => topology.value.orderedNodes.map(node => ({
    label: `${node.name} · ${node.id}`,
    value: node.id,
  })));
  const approvalNodes = computed(() => topology.value.orderedNodes.filter(
    (node): node is ApprovalStep => node.kind === 'APPROVAL',
  ));
  const visibleNodes = computed(() => filterApprovalDesignerNodes(
    topology.value,
    nodeSearch.value,
    nodeKindFilter.value,
  ));
  const renderedNodes = computed(() => visibleNodes.value.slice(0, nodeRenderLimit.value));
  const hasMoreVisibleNodes = computed(
    () => renderedNodes.value.length < visibleNodes.value.length,
  );
  const filtersActive = computed(
    () => Boolean(nodeSearch.value.trim()) || nodeKindFilter.value.length > 0,
  );
''',
    'node option and visible computed',
)
replace_once(
    "  watch(() => draft.value?.name, () => markChanged(), { flush: 'sync' });\n",
    "  watch(() => draft.value?.name, () => markChanged(), { flush: 'sync' });\n"
    "  watch([nodeSearch, nodeKindFilter], () => {\n"
    "    nodeRenderLimit.value = 120;\n"
    "  }, { deep: true });\n",
    'filter render limit watch',
)
replace_once(
    "    window.addEventListener('beforeunload', handleBeforeUnload);\n",
    "    window.addEventListener('beforeunload', handleBeforeUnload);\n"
    "    window.addEventListener('keydown', handleKeyboardShortcut);\n",
    'keyboard listener',
)
replace_once(
    "      window.removeEventListener('beforeunload', handleBeforeUnload);\n",
    "      window.removeEventListener('beforeunload', handleBeforeUnload);\n"
    "      window.removeEventListener('keydown', handleKeyboardShortcut);\n",
    'keyboard cleanup',
)
replace_once(
'''      decisions.value = {};
      undoStack.value = [];
      redoStack.value = [];
      saveState.value = 'saved';
''',
'''      decisions.value = {};
      undoStack.value = [];
      redoStack.value = [];
      nodeSearch.value = '';
      nodeKindFilter.value = [];
      nodeRenderLimit.value = 120;
      collapsedBranchNodeIds.value = new Set(
        loaded.definition.nodes.length >= 100
          ? loaded.definition.nodes
              .filter(node => ['CONDITION', 'PARALLEL_SPLIT'].includes(node.kind))
              .map(node => node.id)
          : [],
      );
      saveState.value = 'saved';
''',
    'open draft large flow reset',
)
path.write_text(text)

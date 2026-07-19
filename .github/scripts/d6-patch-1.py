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
    "import { computed, onBeforeUnmount, ref, watch } from 'vue';\n",
    "import { computed, onBeforeUnmount, ref, watch } from 'vue';\n"
    "import { onBeforeRouteLeave } from 'vue-router';\n",
    'vue router import',
)
replace_once(
    "} from '#/api/approval/process-design';\n\n"
    "type Decision = 'APPROVE' | 'REJECT';\n",
    "} from '#/api/approval/process-design';\n\n"
    "import {\n"
    "  analyzeDesignerConflict,\n"
    "  mergeDesignerConflict,\n"
    "  snapshotApprovalDesignerDraft,\n"
    "} from './designer-conflict';\n"
    "import type {\n"
    "  ApprovalDesignerConflict,\n"
    "  ApprovalDesignerSnapshot,\n"
    "} from './designer-conflict';\n\n"
    "type Decision = 'APPROVE' | 'REJECT';\n",
    'conflict import',
)
replace_once(
    "interface DraftSnapshot {\n"
    "  definition: ApprovalDesignDraft['definition'];\n"
    "  name: string;\n"
    "}\n",
    "type DesignerSaveState =\n"
    "  | 'conflict'\n"
    "  | 'dirty'\n"
    "  | 'failed'\n"
    "  | 'idle'\n"
    "  | 'saved'\n"
    "  | 'saving';\n\n"
    "interface DesignerConflictState {\n"
    "  analysis: ApprovalDesignerConflict;\n"
    "  serverDraft: ApprovalDesignDraft;\n"
    "}\n",
    'snapshot interface',
)
replace_once(
    "  const saveState = ref<'conflict' | 'dirty' | 'idle' | 'saved' | 'saving'>('idle');\n",
    "  const saveState = ref<DesignerSaveState>('idle');\n",
    'save state',
)
replace_once(
    "  const undoStack = ref<DraftSnapshot[]>([]);\n",
    "  const undoStack = ref<ApprovalDesignerSnapshot[]>([]);\n"
    "  const redoStack = ref<ApprovalDesignerSnapshot[]>([]);\n"
    "  const baseSnapshot = ref<ApprovalDesignerSnapshot>();\n"
    "  const conflict = ref<DesignerConflictState>();\n",
    'history state',
)
replace_once(
    "  const json = computed(() => JSON.stringify(draft.value?.definition ?? {}, null, 2));\n\n"
    "  watch(() => draft.value?.definition, markChanged, { deep: true });\n"
    "  watch(() => draft.value?.name, markChanged);\n"
    "  onBeforeUnmount(() => {\n"
    "    if (saveTimer) clearTimeout(saveTimer);\n"
    "  });\n",
    "  const json = computed(() => JSON.stringify(draft.value?.definition ?? {}, null, 2));\n"
    "  const hasPendingChanges = computed(() => [\n"
    "    'conflict',\n"
    "    'dirty',\n"
    "    'failed',\n"
    "    'saving',\n"
    "  ].includes(saveState.value));\n\n"
    "  watch(() => draft.value?.definition, () => markChanged(), {\n"
    "    deep: true,\n"
    "    flush: 'sync',\n"
    "  });\n"
    "  watch(() => draft.value?.name, () => markChanged(), { flush: 'sync' });\n"
    "  watch(\n"
    "    () => draft.value?.formPackage.packageVersion,\n"
    "    () => markChanged(),\n"
    "    { flush: 'sync' },\n"
    "  );\n"
    "  if (typeof window !== 'undefined') {\n"
    "    window.addEventListener('beforeunload', handleBeforeUnload);\n"
    "  }\n"
    "  onBeforeRouteLeave(() => confirmDiscardChanges('离开流程设计器'));\n"
    "  onBeforeUnmount(() => {\n"
    "    if (saveTimer) clearTimeout(saveTimer);\n"
    "    if (typeof window !== 'undefined') {\n"
    "      window.removeEventListener('beforeunload', handleBeforeUnload);\n"
    "    }\n"
    "  });\n",
    'watch and lifecycle block',
)
replace_once(
'''  async function openDraft(draftId: string) {
    suspended = true;
    try {
      draft.value = await findApprovalDesignDraft(draftId);
      selectedNodeId.value = draft.value.definition.startNodeId;
      clearResults();
      decisions.value = {};
      undoStack.value = [];
      saveState.value = 'saved';
    } catch (error) {
      showError(error);
    } finally {
      suspended = false;
    }
  }
''',
'''  async function openDraft(
    draftId: string,
    options: { force?: boolean } = {},
  ) {
    const switchingDraft = draft.value && draft.value.draftId !== draftId;
    if (!options.force && switchingDraft
      && !(await confirmDiscardChanges('切换流程草稿'))) {
      return false;
    }
    suspended = true;
    try {
      const loaded = await findApprovalDesignDraft(draftId);
      draft.value = loaded;
      baseSnapshot.value = snapshotApprovalDesignerDraft(loaded);
      conflict.value = undefined;
      selectedNodeId.value = loaded.definition.startNodeId;
      clearResults();
      decisions.value = {};
      undoStack.value = [];
      redoStack.value = [];
      saveState.value = 'saved';
      return true;
    } catch (error) {
      showError(error);
      return false;
    } finally {
      suspended = false;
    }
  }
''',
    'open draft',
)
replace_once(
    "  }) {\n    try {\n      const created = input.source === 'COPY'\n",
    "  }) {\n    if (!(await confirmDiscardChanges('新建流程草稿'))) return;\n"
    "    try {\n      const created = input.source === 'COPY'\n",
    'create guard',
)

path.write_text(text)

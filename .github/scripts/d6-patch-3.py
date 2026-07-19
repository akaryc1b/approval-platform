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
    "  function addConditionRoute() {\n"
    "    const condition = selectedCondition.value;\n"
    "    if (!condition) return;\n"
    "    condition.routes.push({\n",
    "  function addConditionRoute() {\n"
    "    const condition = selectedCondition.value;\n"
    "    if (!condition) return;\n"
    "    remember();\n"
    "    condition.routes.push({\n",
    'condition remember',
)
replace_once(
    "  function removeConditionRoute(index: number) {\n"
    "    const condition = selectedCondition.value;\n"
    "    if (condition && condition.routes.length > 1) condition.routes.splice(index, 1);\n"
    "  }\n",
    "  function removeConditionRoute(index: number) {\n"
    "    const condition = selectedCondition.value;\n"
    "    if (!condition || condition.routes.length <= 1) return;\n"
    "    remember();\n"
    "    condition.routes.splice(index, 1);\n"
    "  }\n",
    'condition remove history',
)
replace_once(
'''  function undo() {
    if (!draft.value) return;
    const state = undoStack.value.pop();
    if (!state) return;
    suspended = true;
    draft.value.definition = state.definition;
    draft.value.name = state.name;
    suspended = false;
    markChanged();
  }

  function markChanged() {
    if (suspended || !draft.value || !editable.value) return;
    saveState.value = 'dirty';
    clearResults();
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(() => save('AUTO_SAVE'), 900);
  }

  function remember() {
    if (!draft.value) return;
    undoStack.value.push({
      definition: structuredClone(draft.value.definition),
      name: draft.value.name,
    });
    if (undoStack.value.length > 30) undoStack.value.shift();
  }
''',
'''  function undo() {
    if (!draft.value) return;
    const state = undoStack.value.pop();
    if (!state) return;
    pushHistory(redoStack.value, snapshotApprovalDesignerDraft(draft.value));
    applySnapshot(state);
    markChanged(false);
  }

  function redo() {
    if (!draft.value) return;
    const state = redoStack.value.pop();
    if (!state) return;
    pushHistory(undoStack.value, snapshotApprovalDesignerDraft(draft.value));
    applySnapshot(state);
    markChanged(false);
  }

  function markChanged(clearRedo = true) {
    if (suspended || !draft.value || !editable.value) return;
    clearResults();
    if (clearRedo) redoStack.value = [];
    if (conflict.value && baseSnapshot.value) {
      conflict.value.analysis = analyzeDesignerConflict(
        baseSnapshot.value,
        snapshotApprovalDesignerDraft(draft.value),
        snapshotApprovalDesignerDraft(conflict.value.serverDraft),
      );
      saveState.value = 'conflict';
      if (saveTimer) clearTimeout(saveTimer);
      return;
    }
    saveState.value = 'dirty';
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(() => save('AUTO_SAVE'), 900);
  }

  function remember() {
    if (!draft.value) return;
    pushHistory(undoStack.value, snapshotApprovalDesignerDraft(draft.value));
    redoStack.value = [];
  }

  function applySnapshot(snapshot: ApprovalDesignerSnapshot) {
    if (!draft.value) return;
    suspended = true;
    draft.value.definition = structuredClone(snapshot.definition);
    draft.value.formPackage.packageVersion = snapshot.formPackageVersion;
    draft.value.name = snapshot.name;
    if (!draft.value.definition.nodes.some(node => node.id === selectedNodeId.value)) {
      selectedNodeId.value = draft.value.definition.startNodeId;
    }
    suspended = false;
  }

  function pushHistory(
    stack: ApprovalDesignerSnapshot[],
    snapshot: ApprovalDesignerSnapshot,
  ) {
    stack.push(snapshot);
    if (stack.length > 30) stack.shift();
  }

  async function confirmDiscardChanges(action: string) {
    if (!hasPendingChanges.value) return true;
    try {
      await ElMessageBox.confirm(
        `当前草稿存在未保存修改或版本冲突，确认${action}并放弃本地状态？`,
        '未保存修改',
        {
          cancelButtonText: '继续编辑',
          confirmButtonText: '放弃并继续',
          type: 'warning',
        },
      );
      return true;
    } catch {
      return false;
    }
  }

  function handleBeforeUnload(event: BeforeUnloadEvent) {
    if (!hasPendingChanges.value) return;
    event.preventDefault();
    event.returnValue = '';
  }
''',
    'history block',
)
replace_once("    addBranch,\n", "    addBranch,\n    applySafeConflictMerge,\n", 'return safe merge')
replace_once("    copyNode,\n", "    conflict,\n    copyNode,\n", 'return conflict')
replace_once("    json,\n", "    hasPendingChanges,\n    json,\n", 'return pending')
replace_once(
    "    reload,\n",
    "    redo,\n    redoStack,\n    reload,\n    reloadServerVersion,\n",
    'return redo reload',
)
path.write_text(text)

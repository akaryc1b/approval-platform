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
'''  async function save(mode: 'AUTO_SAVE' | 'EXPLICIT' = 'EXPLICIT') {
    const current = draft.value;
    if (!current || !editable.value || saveState.value === 'saving') return false;
    if (saveTimer) clearTimeout(saveTimer);
    saveState.value = 'saving';
    try {
      draft.value = await updateApprovalDesignDraft(current.draftId, {
        definition: current.definition,
        expectedRevision: current.revision,
        formPackageVersion: current.formPackage.packageVersion,
        name: current.name,
        saveMode: mode,
      });
      saveState.value = 'saved';
      await loadDrafts();
      if (mode === 'EXPLICIT') ElMessage.success('流程草稿已保存');
      return true;
    } catch (error) {
      if (error instanceof ApprovalDesignApiError
        && error.code === 'APPROVAL_DESIGN_REVISION_CONFLICT') {
        saveState.value = 'conflict';
        ElMessage.warning('草稿已被其他操作更新，请重新加载');
      } else {
        saveState.value = 'dirty';
        showError(error);
      }
      return false;
    }
  }

  async function reload() {
    if (draft.value) await openDraft(draft.value.draftId);
  }
''',
'''  async function save(mode: 'AUTO_SAVE' | 'EXPLICIT' = 'EXPLICIT') {
    const current = draft.value;
    if (!current || !editable.value || saveState.value === 'saving'
      || saveState.value === 'conflict') return false;
    if (saveTimer) clearTimeout(saveTimer);
    saveState.value = 'saving';
    try {
      const saved = await updateApprovalDesignDraft(current.draftId, {
        definition: current.definition,
        expectedRevision: current.revision,
        formPackageVersion: current.formPackage.packageVersion,
        name: current.name,
        saveMode: mode,
      });
      suspended = true;
      draft.value = saved;
      baseSnapshot.value = snapshotApprovalDesignerDraft(saved);
      conflict.value = undefined;
      suspended = false;
      saveState.value = 'saved';
      await loadDrafts();
      if (mode === 'EXPLICIT') ElMessage.success('流程草稿已保存');
      return true;
    } catch (error) {
      if (error instanceof ApprovalDesignApiError
        && error.code === 'APPROVAL_DESIGN_REVISION_CONFLICT') {
        await captureConflict(current);
      } else {
        saveState.value = 'failed';
        showError(error);
      }
      return false;
    } finally {
      suspended = false;
    }
  }

  async function reload() {
    await reloadServerVersion();
  }

  async function reloadServerVersion() {
    const current = draft.value;
    if (!current || !(await confirmDiscardChanges('加载服务端版本'))) return false;
    return openDraft(current.draftId, { force: true });
  }

  async function applySafeConflictMerge() {
    const current = draft.value;
    const state = conflict.value;
    const base = baseSnapshot.value;
    if (!current || !state || !base) return false;
    if (!state.analysis.canAutoMerge) {
      ElMessage.warning('本地与服务端存在重叠修改，请加载服务端版本后手动重做');
      return false;
    }
    const serverBase = snapshotApprovalDesignerDraft(state.serverDraft);
    const merged = mergeDesignerConflict(
      base,
      snapshotApprovalDesignerDraft(current),
      serverBase,
    );
    suspended = true;
    draft.value = structuredClone(state.serverDraft);
    applySnapshot(merged);
    baseSnapshot.value = serverBase;
    conflict.value = undefined;
    undoStack.value = [];
    redoStack.value = [];
    suspended = false;
    markChanged(false);
    ElMessage.success('非重叠修改已安全合并，请保存确认');
    return true;
  }

  async function captureConflict(localDraft: ApprovalDesignDraft) {
    saveState.value = 'conflict';
    try {
      const serverDraft = await findApprovalDesignDraft(localDraft.draftId);
      const base = baseSnapshot.value ?? snapshotApprovalDesignerDraft(localDraft);
      conflict.value = {
        analysis: analyzeDesignerConflict(
          base,
          snapshotApprovalDesignerDraft(localDraft),
          snapshotApprovalDesignerDraft(serverDraft),
        ),
        serverDraft,
      };
      ElMessage.warning('草稿已被其他操作更新，请比较本地与服务端修改');
    } catch (error) {
      conflict.value = undefined;
      showError(error);
    }
  }

  async function ensureSaved() {
    if (saveState.value === 'conflict') {
      ElMessage.warning('请先处理版本冲突');
      return false;
    }
    if (saveState.value === 'saving') {
      ElMessage.info('草稿正在保存，请稍后重试');
      return false;
    }
    if (saveState.value === 'dirty' || saveState.value === 'failed') {
      return save();
    }
    return true;
  }
''',
    'save and conflict functions',
)
replace_once(
    "    if (saveState.value === 'dirty' && !(await save())) return undefined;\n",
    "    if (!(await ensureSaved())) return undefined;\n",
    'validate ensure saved',
)
if text.count("    if (saveState.value === 'dirty' && !(await save())) return;\n") != 2:
    raise SystemExit('ensure saved void markers: expected two')
text = text.replace(
    "    if (saveState.value === 'dirty' && !(await save())) return;\n",
    "    if (!(await ensureSaved())) return;\n",
)
replace_once(
    "  async function archive() {\n    if (!draft.value) return;\n",
    "  async function archive() {\n"
    "    if (!draft.value || !(await confirmDiscardChanges('归档流程草稿'))) return;\n",
    'archive guard',
)

path.write_text(text)

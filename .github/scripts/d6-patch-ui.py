from pathlib import Path

ui_path = Path('apps/web/overlay/apps/web-ele/src/views/approval/designer/process-designer.vue')
ui = ui_path.read_text()

def ui_replace(old: str, new: str, label: str):
    global ui
    count = ui.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one marker, found {count}')
    ui = ui.replace(old, new, 1)

ui_replace(
'''                <ElButton :disabled="!designer.undoStack.value.length" @click="designer.undo">撤销</ElButton>
                <ElButton :disabled="!designer.editable.value" @click="designer.save()">保存</ElButton>
                <ElButton v-if="designer.saveState.value === 'conflict'" type="warning" @click="designer.reload">安全重载</ElButton>
''',
'''                <ElButton :disabled="!designer.undoStack.value.length" @click="designer.undo">撤销</ElButton>
                <ElButton :disabled="!designer.redoStack.value.length" @click="designer.redo">重做</ElButton>
                <ElButton :disabled="!designer.editable.value" @click="designer.save()">保存</ElButton>
                <ElButton v-if="designer.saveState.value === 'conflict'" type="warning" @click="designer.reloadServerVersion">加载服务端版本</ElButton>
''',
    'toolbar history',
)
ui_replace(
'''          <ElAlert
            v-if="designer.saveState.value === 'conflict'"
            type="warning"
            show-icon
            title="服务器上的草稿已更新；请重载后继续编辑，避免覆盖他人修改。"
          />
''',
'''          <div v-if="designer.saveState.value === 'conflict'" class="conflict-panel">
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
''',
    'conflict panel',
)
ui_replace(
    ".result-alert { margin-top: 12px; }\n",
    ".result-alert { margin-top: 12px; }\n"
    ".conflict-panel { display: grid; gap: 12px; margin-bottom: 12px; }\n"
    ".conflict-summary { display: flex; flex-wrap: wrap; gap: 16px; color: var(--el-text-color-secondary); }\n"
    ".conflict-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }\n"
    ".conflict-columns section { border: 1px solid var(--el-border-color-light); border-radius: 8px; display: grid; gap: 8px; padding: 10px; }\n"
    ".change-tags { display: flex; flex-wrap: wrap; gap: 6px; }\n",
    'conflict styles',
)
ui_replace(
    "@media (max-width: 820px) { .designer-shell { grid-template-columns: 1fr; } .right-panel { grid-column: auto; } }\n",
    "@media (max-width: 820px) { .designer-shell { grid-template-columns: 1fr; } .right-panel { grid-column: auto; } .conflict-columns { grid-template-columns: 1fr; } }\n",
    'mobile conflict style',
)
ui_path.write_text(ui)

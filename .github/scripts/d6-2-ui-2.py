from pathlib import Path

path = Path('apps/web/overlay/apps/web-ele/src/views/approval/designer/process-designer.vue')
text = path.read_text()

def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one marker, found {count}')
    text = text.replace(old, new, 1)

old_validation = '''                <ElAlert
                  v-for="issue in designer.validation.value?.issues"
                  :key="`${issue.code}-${issue.subject}`"
                  :title="`${issue.code} · ${issue.subject}`"
                  :description="issue.message"
                  :type="issue.severity === 'ERROR' ? 'error' : issue.severity === 'WARNING' ? 'warning' : 'info'"
                  show-icon
                />
'''
new_validation = '''                <div
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
'''
replace_once(old_validation, new_validation, 'validation result positioning')
replace_once(
    "                  v-for=\"node in designer.draft.value?.definition.nodes.filter(item => item.kind === 'APPROVAL')\"\n",
    "                  v-for=\"node in designer.approvalNodes.value\"\n",
    'cached approval nodes',
)
old_simulation = '''              <ElAlert
                v-if="designer.simulation.value"
                class="result-alert"
                :title="designer.simulation.value.simulation.status"
                :description="designer.simulation.value.pathSummary"
                :type="designer.simulation.value.simulation.completed ? 'success' : 'warning'"
                show-icon
              />
'''
new_simulation = '''              <template v-if="designer.simulation.value">
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
'''
replace_once(old_simulation, new_simulation, 'simulation result positioning')
replace_once(
    ".node-actions { display: flex; gap: 8px; margin: 12px 0; }\n",
    ".node-tools { align-items: center; display: grid; grid-template-columns: minmax(180px, 1fr) minmax(180px, 1fr) auto auto auto; gap: 8px; margin: 12px 0; }\n"
    ".node-actions { align-items: center; display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }\n"
    ".shortcut-hint { color: var(--el-text-color-secondary); font-size: 12px; margin-left: auto; }\n",
    'node tool styles',
)
replace_once(
    ".tree-canvas { align-items: center; display: flex; flex-direction: column; padding: 18px; }\n",
    ".tree-canvas { align-items: center; content-visibility: auto; display: flex; flex-direction: column; padding: 18px; }\n",
    'canvas performance style',
)
replace_once(
    ".branch-grid { display: grid; grid-template-columns: repeat(2, minmax(160px, 1fr)); gap: 8px; margin: 10px 0; width: 100%; }\n",
    ".branch-toggle { background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-light); border-radius: 999px; color: var(--el-text-color-regular); cursor: pointer; font-size: 12px; margin-top: 8px; padding: 5px 12px; }\n"
    ".branch-grid { display: grid; grid-template-columns: repeat(2, minmax(160px, 1fr)); gap: 8px; margin: 10px 0; width: 100%; }\n",
    'branch collapse style',
)
replace_once(
    ".result-alert { margin-top: 12px; }\n",
    ".result-alert { margin-top: 12px; }\n"
    ".result-item { align-items: flex-start; display: grid; gap: 6px; grid-template-columns: 1fr auto; width: 100%; }\n"
    ".simulation-path { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }\n"
    ".load-more { margin-top: 16px; }\n",
    'result position styles',
)
replace_once(
    "@media (max-width: 1280px) { .designer-shell { grid-template-columns: 240px 1fr; } .right-panel { grid-column: 1 / -1; } }\n",
    "@media (max-width: 1280px) { .designer-shell { grid-template-columns: 240px 1fr; } .right-panel { grid-column: 1 / -1; } .node-tools { grid-template-columns: 1fr 1fr; } }\n",
    'responsive tools',
)
replace_once(
    "@media (max-width: 820px) { .designer-shell { grid-template-columns: 1fr; } .right-panel { grid-column: auto; } .conflict-columns { grid-template-columns: 1fr; } }\n",
    "@media (max-width: 820px) { .designer-shell { grid-template-columns: 1fr; } .right-panel { grid-column: auto; } .conflict-columns, .node-tools, .result-item { grid-template-columns: 1fr; } .shortcut-hint { margin-left: 0; width: 100%; } }\n",
    'mobile tools',
)
path.write_text(text)

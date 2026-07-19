from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one marker, found {count}')
    path.write_text(text.replace(old, new, 1))

composable = Path(
    'apps/web/overlay/apps/web-ele/src/views/approval/designer/'
    'use-approval-designer.ts'
)
replace_once(
    composable,
    "    if (!(await confirmDiscardChanges('新建流程草稿'))) return;\n",
    "    if (!(await confirmDiscardChanges('新建流程草稿'))) return false;\n",
    'create discard return',
)
replace_once(
    composable,
    "      ElMessage.success('流程草稿已创建');\n"
    "    } catch (error) {\n"
    "      showError(error);\n"
    "    }\n"
    "  }\n\n"
    "  async function save(",
    "      ElMessage.success('流程草稿已创建');\n"
    "      return true;\n"
    "    } catch (error) {\n"
    "      showError(error);\n"
    "      return false;\n"
    "    }\n"
    "  }\n\n"
    "  async function save(",
    'create result return',
)

view = Path(
    'apps/web/overlay/apps/web-ele/src/views/approval/designer/'
    'process-designer.vue'
)
replace_once(
    view,
    "async function submitCreate() {\n"
    "  await designer.createDraft(createForm.value);\n"
    "  createVisible.value = false;\n"
    "}\n",
    "async function submitCreate() {\n"
    "  const created = await designer.createDraft(createForm.value);\n"
    "  if (created) createVisible.value = false;\n"
    "}\n",
    'create dialog result',
)

roadmap = Path('docs/ROADMAP.md')
replace_once(
    roadmap,
    "- 浏览器刷新、路由离开、草稿重新加载/切换、新建和归档操作的未保存修改或冲突保护，包含同草稿重新打开的数据丢失防护。\n\n"
    "### 下一优先级\n\n"
    "- 设计器大流程性能、分支折叠、快捷键、节点搜索和校验/模拟结果定位；\n",
    "- 浏览器刷新、路由离开、草稿重新加载/切换、新建和归档操作的未保存修改或冲突保护，包含同草稿重新打开的数据丢失防护；\n"
    "- 流程节点 ID、顺序、入边、出边和类型统计的缓存拓扑索引，替代设计器热点路径中的重复全量扫描；\n"
    "- 100、300、500 节点拓扑构建与搜索基准门禁，以及首屏 120 节点的有界渲染和分批加载；\n"
    "- 条件与并行分支默认/手动折叠、节点名称/标识/类型搜索、多类型筛选和大流程自动折叠；\n"
    "- 保存、撤销、重做和删除快捷键，输入框、可编辑内容、按钮和链接的快捷键保护；\n"
    "- 服务端校验问题、模拟步骤和模拟问题的一键节点定位；\n"
    "- 删除节点前的引用、重连、驳回目标和并行汇聚影响摘要，以及不安全拓扑删除阻断；\n"
    "- 取消未保存修改确认时保留新建流程弹窗和用户输入。\n\n"
    "### 下一优先级\n\n",
    'roadmap D6.2 completion',
)

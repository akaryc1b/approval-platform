from pathlib import Path

path = Path('apps/web/overlay/apps/web-ele/src/views/approval/designer/use-approval-designer.ts')
text = path.read_text()

def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one marker, found {count}')
    text = text.replace(old, new, 1)

replace_once("    addNode,\n", "    addNode,\n    approvalNodes,\n", 'approval nodes return')
replace_once("    busy,\n", "    busy,\n    collapseAllBranches,\n    collapsedBranchNodeIds,\n", 'collapse return')
replace_once("    editable,\n", "    editable,\n    expandAllBranches,\n", 'expand return')
replace_once("    errors,\n", "    errors,\n    filtersActive,\n    focusNode,\n", 'focus return')
replace_once("    hasPendingChanges,\n", "    hasMoreVisibleNodes,\n    hasPendingChanges,\n", 'has more return')
replace_once("    nodeOptions,\n", "    nodeKindFilter,\n    nodeOptions,\n    nodeSearch,\n", 'node filters return')
replace_once("    published,\n", "    published,\n    renderedNodes,\n", 'rendered return')
replace_once("    reloadServerVersion,\n", "    reloadServerVersion,\n    resolveNodeId,\n", 'resolve return')
replace_once("    saveState,\n", "    saveState,\n    showMoreNodes,\n", 'show more return')
replace_once("    status,\n", "    status,\n    toggleBranchCollapse,\n    topology,\n", 'topology return')
replace_once("    validation,\n", "    validation,\n    visibleNodes,\n", 'visible return')
replace_once("    warnings,\n", "    warnings,\n    isBranchCollapsed,\n", 'collapsed predicate return')
path.write_text(text)

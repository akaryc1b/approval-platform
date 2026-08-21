const taskStatuses = Object.freeze([
  'PENDING',
  'COMPLETING',
  'COMPLETED',
  'CANCELED',
]);

const knownTaskStatuses = new Set(taskStatuses);
const activeTaskStatuses = new Set(['PENDING', 'COMPLETING']);

export function summarizeTaskHistory(tasks) {
  if (!Array.isArray(tasks)) {
    throw new TypeError('instance tasks must be an array');
  }

  const statusCounts = Object.fromEntries(
    taskStatuses.map(status => [status, 0]),
  );
  const activeTaskIds = [];

  for (const [index, task] of tasks.entries()) {
    if (!task || typeof task !== 'object' || Array.isArray(task)) {
      throw new TypeError(`instance task[${index}] must be an object`);
    }
    const status = task.status;
    if (!knownTaskStatuses.has(status)) {
      throw new Error(`instance task[${index}] has unknown status ${String(status)}`);
    }
    statusCounts[status] += 1;

    if (activeTaskStatuses.has(status)) {
      if (typeof task.taskId !== 'string' || !task.taskId.trim()) {
        throw new Error(`active instance task[${index}] is missing taskId`);
      }
      activeTaskIds.push(task.taskId.trim());
    }
  }

  return Object.freeze({
    activeTaskCount: activeTaskIds.length,
    activeTaskIds: Object.freeze(activeTaskIds),
    historyTaskCount: tasks.length,
    statusCounts: Object.freeze(statusCounts),
  });
}

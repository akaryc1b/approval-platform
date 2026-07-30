import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    name: 'ApprovalProcessInstanceOperations',
    path: '/approval/process-instance-operations',
    component: () => import('#/views/approval/process-instance-operations/index.vue'),
    meta: {
      authority: ['approval:ops:view'],
      icon: 'lucide:workflow',
      order: 17,
      title: '流程实例迁移运维',
    },
  },
  {
    name: 'ApprovalProcessInstanceDiagnostics',
    path: '/approval/process-instance-operations/diagnostics',
    component: () => import('#/views/approval/process-instance-operations/diagnostics.vue'),
    meta: {
      authority: ['approval:ops:view'],
      icon: 'lucide:scan-search',
      order: 18,
      title: '迁移高级诊断',
    },
  },
];

export default routes;

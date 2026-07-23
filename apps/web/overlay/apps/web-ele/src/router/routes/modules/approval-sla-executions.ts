import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    name: 'ApprovalSlaExecutions',
    path: '/approval/sla/executions',
    component: () => import('#/views/approval/sla-executions/index.vue'),
    meta: {
      authority: ['approval:ops:view'],
      icon: 'lucide:refresh-cw-cog',
      order: 16,
      title: 'SLA 执行队列',
    },
  },
];

export default routes;

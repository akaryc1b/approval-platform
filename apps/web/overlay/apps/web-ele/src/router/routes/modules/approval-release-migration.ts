import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    name: 'ApprovalReleaseMigrationDryRun',
    path: '/approval/versions/migration-dry-run',
    component: () => import('#/views/approval/versions/migration-dry-run.vue'),
    meta: {
      authority: ['approval:definition:design'],
      icon: 'lucide:scan-search',
      order: 15,
      title: '迁移评估',
    },
  },
];

export default routes;

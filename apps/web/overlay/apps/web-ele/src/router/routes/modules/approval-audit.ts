import type { RouteRecordRaw } from 'vue-router';

import { BasicLayout } from '#/layouts';

const routes: RouteRecordRaw[] = [
  {
    component: BasicLayout,
    meta: {
      icon: 'ant-design:safety-certificate-outlined',
      order: 66,
      title: '审计治理',
    },
    name: 'ApprovalAuditGovernanceRoot',
    path: '/approval-audit-governance',
    redirect: '/approval-audit-governance/events',
    children: [
      {
        component: () => import('#/views/approval/audit/index.vue'),
        meta: {
          icon: 'ant-design:file-search-outlined',
          title: '审计事件',
        },
        name: 'ApprovalAuditGovernance',
        path: 'events',
      },
    ],
  },
];

export default routes;

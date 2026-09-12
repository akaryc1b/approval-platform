import { createRouter, createWebHashHistory } from 'vue-router';

import { getEvaluationBrowserSession } from './evaluation-session';

// Ordinary host login and administrative routes are not installed in this mode.
// Approval authorization remains on the gateway and the existing backend.
export const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [{
    path: '/',
    component: () => import('./EvaluationLayout.vue'),
    redirect: '/approval/workbench',
    children: [{
      name: 'ApprovalWorkbench',
      path: '/approval/workbench',
      component: () => import('#/views/approval/workbench/index.vue'),
      meta: { title: '采购审批工作台' },
    }],
  }],
});
router.beforeEach(async (to) => {
  if (to.path !== '/approval/workbench') {
    window.location.replace('/evaluation');
    return false;
  }
  try { await getEvaluationBrowserSession().verify(); return true; }
  catch { window.location.replace('/evaluation'); return false; }
});

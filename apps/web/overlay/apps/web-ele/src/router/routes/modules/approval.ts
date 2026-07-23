import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    name: 'ApprovalPlatform',
    path: '/approval',
    redirect: '/approval/workbench',
    meta: {
      icon: 'lucide:workflow',
      order: 10,
      title: '审批平台',
    },
    children: [
      {
        name: 'ApprovalWorkbench',
        path: '/approval/workbench',
        component: () => import('#/views/approval/workbench/index.vue'),
        meta: {
          authority: ['approval:workbench:view'],
          icon: 'lucide:inbox',
          title: '审批工作台',
        },
      },
      {
        name: 'ApprovalTaskCollaboration',
        path: '/approval/collaboration',
        component: () => import('#/views/approval/collaboration/index.vue'),
        meta: {
          authority: ['approval:workbench:view'],
          icon: 'lucide:user-round-plus',
          title: '加签协作',
        },
      },
      {
        name: 'ApprovalDiscussion',
        path: '/approval/discussion',
        component: () => import('#/views/approval/discussion/index.vue'),
        meta: {
          authority: ['approval:comment:view'],
          icon: 'lucide:messages-square',
          title: '审批讨论',
        },
      },
      {
        name: 'ApprovalDiscussionDetail',
        path: '/approval/discussion/detail',
        component: () => import('#/views/approval/discussion/detail.vue'),
        meta: {
          authority: ['approval:comment:view'],
          hideInMenu: true,
          title: '审批讨论详情',
        },
      },
      {
        name: 'ApprovalMessages',
        path: '/approval/messages',
        component: () => import('#/views/approval/messages/index.vue'),
        meta: {
          authority: ['approval:message:view'],
          icon: 'lucide:bell-ring',
          title: '消息与协作',
        },
      },
      {
        name: 'ApprovalNotifications',
        path: '/approval/notifications',
        component: () => import('#/views/approval/notifications/index.vue'),
        meta: {
          authority: ['approval:message:view'],
          icon: 'lucide:bell-cog',
          title: '通知中心',
        },
      },
      {
        name: 'ApprovalDelegations',
        path: '/approval/delegations',
        component: () => import('#/views/approval/delegations/index.vue'),
        meta: {
          authority: ['approval:workbench:view'],
          icon: 'lucide:user-round-cog',
          title: '代理规则',
        },
      },
      {
        name: 'ApprovalHandovers',
        path: '/approval/handovers',
        component: () => import('#/views/approval/handovers/index.vue'),
        meta: {
          authority: ['approval:ops:view'],
          icon: 'lucide:users-round',
          title: '离职交接',
        },
      },
      {
        name: 'ApprovalDesigner',
        path: '/approval/designer',
        component: () => import('#/views/approval/designer/index.vue'),
        meta: {
          authority: ['approval:definition:design'],
          icon: 'lucide:git-branch-plus',
          title: '流程设计器',
        },
      },
      {
        name: 'ApprovalSimulations',
        path: '/approval/simulations',
        component: () => import('#/views/approval/simulations/index.vue'),
        meta: {
          authority: ['approval:definition:design'],
          icon: 'lucide:flask-conical',
          title: '批量模拟',
        },
      },
      {
        name: 'ApprovalVersions',
        path: '/approval/versions',
        component: () => import('#/views/approval/versions/index.vue'),
        meta: {
          authority: ['approval:definition:design'],
          icon: 'lucide:history',
          title: '版本管理',
        },
      },
      {
        name: 'ApprovalEffectiveReleases',
        path: '/approval/versions/effective',
        component: () => import('#/views/approval/versions/effective.vue'),
        meta: {
          authority: ['approval:definition:design'],
          icon: 'lucide:shield-check',
          title: '版本生效',
        },
      },
      {
        name: 'ApprovalForms',
        path: '/approval/forms',
        component: () => import('#/views/approval/forms/index.vue'),
        meta: {
          authority: ['approval:form:list'],
          icon: 'lucide:layout-template',
          title: '动态表单',
        },
      },
      {
        name: 'ApprovalProcesses',
        path: '/approval/processes',
        component: () => import('#/views/approval/processes/index.vue'),
        meta: {
          authority: ['approval:process:list'],
          icon: 'lucide:route',
          title: '流程管理',
        },
      },
      {
        name: 'ApprovalSlaCalendars',
        path: '/approval/sla/calendars',
        component: () => import('#/views/approval/sla-calendars/index.vue'),
        meta: {
          authority: ['approval:definition:design'],
          icon: 'lucide:calendar-clock',
          title: '工作日历',
        },
      },
      {
        name: 'ApprovalSlaPolicies',
        path: '/approval/sla/policies',
        component: () => import('#/views/approval/sla-policies/index.vue'),
        meta: {
          authority: ['approval:definition:design'],
          icon: 'lucide:timer-reset',
          title: 'SLA 策略',
        },
      },
      {
        name: 'ApprovalSlaOperations',
        path: '/approval/sla/operations',
        component: () => import('#/views/approval/sla-operations/index.vue'),
        meta: {
          authority: ['approval:ops:view'],
          icon: 'lucide:alarm-clock-check',
          title: 'SLA 运维',
        },
      },
      {
        name: 'ApprovalOperations',
        path: '/approval/operations',
        component: () => import('#/views/approval/operations/index.vue'),
        meta: {
          authority: ['approval:ops:view'],
          icon: 'lucide:activity',
          title: '运维控制台',
        },
      },
      {
        name: 'ApprovalOperationalFailures',
        path: '/approval/operations/failures',
        component: () => import('#/views/approval/operational-failures/index.vue'),
        meta: {
          authority: ['approval:ops:view'],
          icon: 'lucide:triangle-alert',
          title: '运维失败队列',
        },
      },
    ],
  },
];

export default routes;

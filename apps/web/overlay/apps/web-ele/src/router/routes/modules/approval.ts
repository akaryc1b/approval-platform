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
        name: 'ApprovalOperations',
        path: '/approval/operations',
        component: () => import('#/views/approval/operations/index.vue'),
        meta: {
          authority: ['approval:ops:view'],
          icon: 'lucide:activity',
          title: '运维控制台',
        },
      },
    ],
  },
];

export default routes;

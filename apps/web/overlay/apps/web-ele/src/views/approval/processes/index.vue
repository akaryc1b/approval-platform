<script lang="ts" setup>
import FeaturePage from '#/views/approval/components/FeaturePage.vue';

const milestones = [
  '流程草稿、发布、停用、复制和导入导出',
  '流程版本、表单版本和审批规则快照',
  '灰度发布、运行实例迁移与兼容性预检',
  '流程统计、节点耗时和人员负载分析',
];
</script>

<template>
  <FeaturePage
    description="管理流程定义、发布版本、可见范围、管理员范围和运行策略。"
    issue="#13 / #14"
    :milestones="milestones"
    title="流程管理"
  />
</template>

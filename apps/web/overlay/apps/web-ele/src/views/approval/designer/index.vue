<script lang="ts" setup>
import FeaturePage from '#/views/approval/components/FeaturePage.vue';

const milestones = [
  'Approval DSL 递归树编辑器',
  '条件分支、并行分支与审批人规则配置',
  '发布前静态检查和路径模拟',
  '编译 BPMN / DMN 后的 LogicFlow 预览',
];
</script>

<template>
  <FeaturePage
    description="面向业务管理员的树状流程设计器，不直接暴露 BPMN 复杂度。"
    issue="#14"
    :milestones="milestones"
    title="流程设计器"
  />
</template>

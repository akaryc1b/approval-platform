<script lang="ts" setup>
import FeaturePage from '#/views/approval/components/FeaturePage.vue';

const milestones = [
  '失败 Job、死信、孤立任务、无审批人和回调失败诊断',
  '重试、改派、挂起、恢复、终止、迁移和节点跳转预演',
  '高风险操作双人复核与不可变审计记录',
  '指标、告警、Trace 和诊断包导出',
];
</script>

<template>
  <FeaturePage
    description="安全诊断和修复生产流程，禁止依赖直接修改 Flowable 或业务数据库。"
    issue="#15"
    :milestones="milestones"
    title="运维控制台"
  />
</template>

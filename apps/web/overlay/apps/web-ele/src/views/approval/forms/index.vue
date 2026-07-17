<script lang="ts" setup>
import FeaturePage from '#/views/approval/components/FeaturePage.vue';

const milestones = [
  'Form Schema、UI Schema、Rule Schema 与字段权限',
  'Element Plus PC 渲染器和 Wot UI 移动渲染器',
  '明细表、计算公式、外部数据源和节点字段权限',
  '不可变表单版本和历史实例快照',
];
</script>

<template>
  <FeaturePage
    description="平台自有动态表单协议，保持 PC、移动端、打印与只读视图一致。"
    issue="#13 / #14"
    :milestones="milestones"
    title="动态表单"
  />
</template>

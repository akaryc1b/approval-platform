<script lang="ts" setup>
import { Page } from '@vben/common-ui';
import { ElAlert, ElCard, ElTag } from 'element-plus';

defineProps<{
  description: string;
  issue: string;
  milestones: string[];
  title: string;
}>();
</script>

<template>
  <Page :description="description" :title="title">
    <div class="feature-page">
      <ElAlert
        :closable="false"
        show-icon
        title="工程骨架已接入，业务能力按纵向闭环逐步实现。"
        type="info"
      />
      <ElCard shadow="never">
        <template #header>
          <div class="feature-page__header">
            <span>当前范围</span>
            <ElTag effect="plain">{{ issue }}</ElTag>
          </div>
        </template>
        <ul class="feature-page__list">
          <li v-for="milestone in milestones" :key="milestone">
            {{ milestone }}
          </li>
        </ul>
      </ElCard>
    </div>
  </Page>
</template>

<style scoped>
.feature-page {
  display: grid;
  gap: 16px;
}

.feature-page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.feature-page__list {
  display: grid;
  gap: 12px;
  margin: 0;
  padding-left: 20px;
  color: var(--el-text-color-regular);
}
</style>

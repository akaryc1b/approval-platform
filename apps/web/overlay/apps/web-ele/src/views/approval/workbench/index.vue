<script lang="ts" setup>
import { Page } from '@vben/common-ui';
import { ElButton, ElCard, ElProgress, ElTag } from 'element-plus';

const metrics = [
  { label: '待我审批', value: 12, hint: '其中 3 项即将超时' },
  { label: '我已审批', value: 48, hint: '近 30 天' },
  { label: '我发起的', value: 9, hint: '4 项正在流转' },
  { label: '抄送我的', value: 7, hint: '2 项未读' },
];

const tasks = [
  {
    applicant: '采购中心',
    elapsed: '18 分钟',
    name: '服务器采购付款申请',
    risk: 'AI 检查完成',
    status: '待审批',
  },
  {
    applicant: '研发中心',
    elapsed: '1 小时',
    name: '研发外包合同用印',
    risk: '存在条款风险',
    status: '待审批',
  },
  {
    applicant: '行政中心',
    elapsed: '3 小时',
    name: '办公设备采购申请',
    risk: '材料完整',
    status: '会签中',
  },
];
</script>

<template>
  <Page
    description="统一处理待办、已办、发起、抄送和审批协作。当前数据为界面骨架，后续接入平台 API。"
    title="审批工作台"
  >
    <div class="workbench">
      <section class="metric-grid">
        <ElCard v-for="metric in metrics" :key="metric.label" shadow="never">
          <div class="metric-label">{{ metric.label }}</div>
          <div class="metric-value">{{ metric.value }}</div>
          <div class="metric-hint">{{ metric.hint }}</div>
        </ElCard>
      </section>

      <section class="content-grid">
        <ElCard shadow="never">
          <template #header>
            <div class="section-header">
              <div>
                <strong>优先处理</strong>
                <span>按 SLA、风险和到达时间排序</span>
              </div>
              <ElButton text type="primary">查看全部</ElButton>
            </div>
          </template>

          <div class="task-list">
            <article v-for="task in tasks" :key="task.name" class="task-item">
              <div class="task-main">
                <strong>{{ task.name }}</strong>
                <span>{{ task.applicant }} · 已等待 {{ task.elapsed }}</span>
              </div>
              <div class="task-meta">
                <ElTag effect="plain">{{ task.status }}</ElTag>
                <ElTag
                  :type="task.risk.includes('风险') ? 'warning' : 'success'"
                  effect="light"
                >
                  {{ task.risk }}
                </ElTag>
                <ElButton type="primary">处理</ElButton>
              </div>
            </article>
          </div>
        </ElCard>

        <ElCard shadow="never">
          <template #header>
            <div class="section-header">
              <div>
                <strong>本周处理效率</strong>
                <span>正式版本将按流程和节点拆分</span>
              </div>
            </div>
          </template>
          <div class="efficiency">
            <div>
              <span>按时处理率</span>
              <strong>92%</strong>
            </div>
            <ElProgress :percentage="92" :stroke-width="10" />
            <div>
              <span>平均处理时长</span>
              <strong>2.4 小时</strong>
            </div>
            <div>
              <span>超时待办</span>
              <strong>1 项</strong>
            </div>
          </div>
        </ElCard>
      </section>
    </div>
  </Page>
</template>

<style scoped>
.workbench {
  display: grid;
  gap: 16px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.metric-label,
.metric-hint,
.section-header span,
.task-main span,
.efficiency span {
  color: var(--el-text-color-secondary);
}

.metric-value {
  margin: 8px 0;
  font-size: 30px;
  font-weight: 700;
}

.metric-hint {
  font-size: 13px;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
  gap: 16px;
}

.section-header,
.task-item,
.task-meta,
.efficiency > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-header > div,
.task-main {
  display: grid;
  gap: 4px;
}

.task-list,
.efficiency {
  display: grid;
  gap: 16px;
}

.task-item {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.task-item:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

@media (max-width: 1100px) {
  .metric-grid,
  .content-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .metric-grid,
  .content-grid {
    grid-template-columns: 1fr;
  }

  .task-item,
  .task-meta {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>

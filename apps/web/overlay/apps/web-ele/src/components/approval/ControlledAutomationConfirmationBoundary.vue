<script lang="ts" setup>
import {
  ElAlert,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElTag,
} from 'element-plus';

const boundary = {
  action: 'NOT_AUTHORIZED',
  authorizationPreview: 'ACTION_NOT_WHITELISTED',
  expectedStateVersion: '无可执行 Proposal',
  expiry: '不适用',
  policyVersion: '仅在未来 Proposal 上重新读取',
  reauthentication: 'UNAVAILABLE',
  risk: 'NOT_AVAILABLE',
  sideEffects: '当前白名单为空，不会产生通知、外部调用或业务状态变化。',
  targetResource: 'NONE',
  typedParameters: 'NONE',
  whitelistVersion: 'EMPTY_PENDING_EXISTING_COMMAND_AUDIT',
} as const;
</script>

<template>
  <section
    class="confirmation-boundary"
    aria-label="受控自动化确认边界"
  >
    <div class="boundary-heading">
      <strong>受控自动化未授权</strong>
      <div class="boundary-tags">
        <ElTag type="danger">AI_IS_NOT_AN_OPERATOR</ElTag>
        <ElTag type="info">NON_EXECUTABLE</ElTag>
      </div>
    </div>

    <ElAlert
      :closable="false"
      title="AI 建议不是系统决定。当前没有合格 Action，也没有可复用重新认证机制。"
      type="warning"
    />

    <ElDescriptions :column="2" border>
      <ElDescriptionsItem label="Proposal action">
        {{ boundary.action }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="目标资源">
        {{ boundary.targetResource }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="类型化参数">
        {{ boundary.typedParameters }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="风险等级">
        {{ boundary.risk }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="完整副作用说明" :span="2">
        {{ boundary.sideEffects }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="expected state/version">
        {{ boundary.expectedStateVersion }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="expiry">
        {{ boundary.expiry }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="whitelist version">
        {{ boundary.whitelistVersion }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="policy version">
        {{ boundary.policyVersion }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="authorization preview">
        {{ boundary.authorizationPreview }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="reauthentication">
        {{ boundary.reauthentication }}
      </ElDescriptionsItem>
    </ElDescriptions>

    <div class="confirmation-action">
      <ElButton disabled native-type="button" type="danger">
        确认不可用
      </ElButton>
      <span>
        确认必须来自明确点击；页面加载、刷新、切换 Tab、回车、倒计时和重试都不会确认或执行。
      </span>
    </div>
    <p>
      即使未来确认成功，服务端仍必须重新验证 tenant、operator、permission、resource、state/version、policy 和 whitelist；确认成功不等于命令成功。
    </p>
  </section>
</template>

<style scoped>
.confirmation-boundary {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: 10px;
  background: var(--el-color-danger-light-9);
}

.boundary-heading,
.boundary-tags,
.confirmation-action {
  display: flex;
  align-items: center;
  gap: 10px;
}

.boundary-heading {
  justify-content: space-between;
}

.boundary-tags {
  flex-wrap: wrap;
}

.confirmation-action span,
.confirmation-boundary p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

@media (max-width: 720px) {
  .boundary-heading,
  .confirmation-action {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>

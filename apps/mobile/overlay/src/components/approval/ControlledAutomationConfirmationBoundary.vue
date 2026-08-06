<script lang="ts" setup>
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
} as const
</script>

<template>
  <view
    class="confirmation-boundary"
    aria-label="受控自动化确认边界"
  >
    <view class="boundary-heading">
      <strong>受控自动化未授权</strong>
      <view class="boundary-tags">
        <wd-tag type="danger">AI_IS_NOT_AN_OPERATOR</wd-tag>
        <wd-tag plain type="warning">NON_EXECUTABLE</wd-tag>
      </view>
    </view>

    <view class="boundary-warning">
      AI 建议不是系统决定。当前没有合格 Action，也没有可复用重新认证机制。
    </view>

    <view class="boundary-grid">
      <view><text>Proposal action</text><strong>{{ boundary.action }}</strong></view>
      <view><text>目标资源</text><strong>{{ boundary.targetResource }}</strong></view>
      <view><text>类型化参数</text><strong>{{ boundary.typedParameters }}</strong></view>
      <view><text>风险等级</text><strong>{{ boundary.risk }}</strong></view>
      <view class="full-row"><text>完整副作用说明</text><strong>{{ boundary.sideEffects }}</strong></view>
      <view><text>expected state/version</text><strong>{{ boundary.expectedStateVersion }}</strong></view>
      <view><text>expiry</text><strong>{{ boundary.expiry }}</strong></view>
      <view><text>whitelist version</text><strong>{{ boundary.whitelistVersion }}</strong></view>
      <view><text>policy version</text><strong>{{ boundary.policyVersion }}</strong></view>
      <view><text>authorization preview</text><strong>{{ boundary.authorizationPreview }}</strong></view>
      <view><text>reauthentication</text><strong>{{ boundary.reauthentication }}</strong></view>
    </view>

    <wd-button block disabled type="error">
      确认不可用
    </wd-button>
    <text class="boundary-note">
      确认必须来自明确点击；页面加载、刷新、切换 Tab、回车、倒计时和重试都不会确认或执行。
    </text>
    <text class="boundary-note">
      即使未来确认成功，服务端仍必须重新验证 tenant、operator、permission、resource、state/version、policy 和 whitelist；确认成功不等于命令成功。
    </text>
  </view>
</template>

<style scoped>
.confirmation-boundary {
  display: grid;
  gap: 18rpx;
  padding: 22rpx;
  border: 1rpx solid rgb(239 68 68 / 35%);
  border-radius: 18rpx;
  background: rgb(239 68 68 / 6%);
}

.boundary-heading,
.boundary-tags {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.boundary-heading {
  justify-content: space-between;
}

.boundary-tags {
  flex-wrap: wrap;
}

.boundary-warning {
  padding: 16rpx;
  border-radius: 12rpx;
  background: rgb(245 158 11 / 12%);
  color: var(--wot-color-warning, var(--uni-color-warning));
  font-size: 24rpx;
}

.boundary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16rpx;
}

.boundary-grid > view {
  display: grid;
  gap: 6rpx;
}

.boundary-grid .full-row {
  grid-column: 1 / -1;
}

.boundary-grid text,
.boundary-note {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.boundary-grid strong {
  overflow-wrap: anywhere;
}
</style>

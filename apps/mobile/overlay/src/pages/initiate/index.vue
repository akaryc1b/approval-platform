<script lang="ts" setup>
defineOptions({
  name: 'ApprovalInitiate',
})

definePage({
  style: {
    navigationBarTitleText: '发起审批',
  },
})

const keyword = ref('')
const templates = [
  { category: '采购', icon: '🛒', name: '采购申请', description: '采购物资、设备和服务' },
  { category: '财务', icon: '💳', name: '付款申请', description: '合同付款、预付款和尾款' },
  { category: '合同', icon: '📄', name: '合同用印', description: '合同审查、用印和归档' },
  { category: '人事', icon: '✈️', name: '出差申请', description: '行程、预算和出差审批' },
  { category: '行政', icon: '🔑', name: '用章申请', description: '公章、合同章和法人章' },
  { category: '通用', icon: '🧩', name: '通用审批', description: '自定义事项和附件审批' },
]

const visibleTemplates = computed(() => {
  const normalized = keyword.value.trim().toLowerCase()
  if (!normalized) {
    return templates
  }
  return templates.filter(template =>
    `${template.name}${template.category}${template.description}`.toLowerCase().includes(normalized),
  )
})

function startTemplate(name: string) {
  uni.showToast({
    title: `${name}动态表单将在纵向链路中接入`,
    icon: 'none',
  })
}
</script>

<template>
  <view class="page">
    <view class="search-card">
      <wd-search v-model="keyword" placeholder="搜索审批模板" />
    </view>

    <view class="section-title">
      <text>常用审批</text>
      <wd-tag plain type="primary">模板中心</wd-tag>
    </view>

    <view class="template-grid">
      <view
        v-for="template in visibleTemplates"
        :key="template.name"
        class="template-card"
        @click="startTemplate(template.name)"
      >
        <view class="template-card__icon">{{ template.icon }}</view>
        <view class="template-card__name">{{ template.name }}</view>
        <view class="template-card__description">{{ template.description }}</view>
        <wd-tag plain type="info">{{ template.category }}</wd-tag>
      </view>
    </view>

    <view class="empty-hint">
      后续支持按租户、部门、角色和发起范围动态展示模板。
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 160rpx;
  background: #f5f7fa;
}

.search-card {
  overflow: hidden;
  border-radius: 22rpx;
  background: #ffffff;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 32rpx 4rpx 18rpx;
  color: #111827;
  font-size: 30rpx;
  font-weight: 700;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20rpx;
}

.template-card {
  display: grid;
  gap: 12rpx;
  padding: 28rpx;
  border-radius: 24rpx;
  background: #ffffff;
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.template-card__icon {
  font-size: 44rpx;
}

.template-card__name {
  color: #111827;
  font-size: 30rpx;
  font-weight: 600;
}

.template-card__description,
.empty-hint {
  color: #8a8f99;
  font-size: 24rpx;
  line-height: 1.6;
}

.empty-hint {
  padding: 32rpx 12rpx;
  text-align: center;
}
</style>

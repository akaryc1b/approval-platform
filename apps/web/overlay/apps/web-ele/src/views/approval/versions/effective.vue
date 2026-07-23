<script lang="ts" setup>
import type { ApprovalReleaseVersionSummary } from '#/api/approval/release-version-management';
import type {
  ApprovalEffectiveRelease,
  ApprovalEffectiveReleaseAction,
  ApprovalEffectiveReleaseHistoryPage,
  ApprovalProcessReleaseLifecycle,
  ApprovalProcessReleaseLifecyclePage,
  ApprovalReleaseLifecycleAction,
} from '#/api/approval/effective-release';

import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElSpace,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  activateApprovalRelease,
  ApprovalEffectiveReleaseApiError,
  deprecateApprovalRelease,
  findApprovalEffectiveRelease,
  findApprovalEffectiveReleaseHistory,
  findApprovalProcessReleaseLifecycles,
  retireApprovalRelease,
  rollbackApprovalRelease,
} from '#/api/approval/effective-release';
import {
  findApprovalVersionCenter,
  type ApprovalVersionCenter,
} from '#/api/approval/release-version-management';

const route = useRoute();
const definitionKey = ref(queryDefinitionKey());
const center = ref<ApprovalVersionCenter>();
const current = ref<ApprovalEffectiveRelease>();
const history = ref<ApprovalEffectiveReleaseHistoryPage>(emptyHistory());
const lifecycles = ref<ApprovalProcessReleaseLifecyclePage>(emptyLifecycles());
const loading = ref(false);
const actionLoading = ref(false);
const actionVisible = ref(false);
const action = ref<ApprovalReleaseLifecycleAction>('ACTIVATE');
const targetRelease = ref<ApprovalReleaseVersionSummary>();
const reason = ref('');

const deployedReleases = computed(() => center.value?.releaseVersions.filter(
  item => item.deployment?.status === 'DEPLOYED',
) || []);
const lifecycleByVersion = computed(() => new Map(
  lifecycles.value.items.map(item => [item.releaseVersion, item]),
));
const actionTitle = computed(() => ({
  ACTIVATE: '确认激活版本',
  DEPRECATE: '确认停止新实例使用',
  RETIRE: '确认退役版本',
  ROLLBACK: '确认回滚版本',
})[action.value]);
const actionButtonType = computed<'danger' | 'primary' | 'warning'>(() => {
  if (action.value === 'RETIRE') return 'danger';
  if (action.value === 'DEPRECATE' || action.value === 'ROLLBACK') return 'warning';
  return 'primary';
});
const canSubmit = computed(() => Boolean(
  targetRelease.value && reason.value.trim() && !actionLoading.value,
));

function queryDefinitionKey() {
  const value = route.query.definitionKey;
  return typeof value === 'string' ? value.trim() : '';
}

function emptyHistory(): ApprovalEffectiveReleaseHistoryPage {
  return { hasMore: false, items: [], limit: 100, offset: 0, total: 0 };
}

function emptyLifecycles(): ApprovalProcessReleaseLifecyclePage {
  return { hasMore: false, items: [], limit: 100, offset: 0, total: 0 };
}

function lifecycle(releaseVersion: number) {
  return lifecycleByVersion.value.get(releaseVersion);
}

function lifecycleLabel(item?: ApprovalProcessReleaseLifecycle) {
  return ({
    ACTIVE: '生效中',
    DEPRECATED: '已停用',
    PUBLISHED: '已发布',
    RETIRED: '已退役',
  } as const)[item?.lifecycleState || 'PUBLISHED'];
}

function lifecycleTagType(item?: ApprovalProcessReleaseLifecycle) {
  return ({
    ACTIVE: 'success',
    DEPRECATED: 'warning',
    PUBLISHED: 'info',
    RETIRED: 'danger',
  } as const)[item?.lifecycleState || 'PUBLISHED'];
}

function activationAction(item?: ApprovalProcessReleaseLifecycle): ApprovalEffectiveReleaseAction | undefined {
  if (item?.lifecycleState === 'PUBLISHED') return 'ACTIVATE';
  if (item?.lifecycleState === 'DEPRECATED') return current.value ? 'ROLLBACK' : 'ACTIVATE';
  return undefined;
}

function message(error: unknown) {
  return error instanceof Error ? error.message : '版本生命周期请求失败';
}

function formatDate(value?: null | string) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}

function shortHash(value?: string) {
  if (!value) return '-';
  return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-8)}` : value;
}

async function load() {
  const key = definitionKey.value.trim();
  if (!key) {
    ElMessage.warning('请输入流程定义 Key');
    return;
  }
  loading.value = true;
  try {
    const [nextCenter, nextHistory, nextLifecycles] = await Promise.all([
      findApprovalVersionCenter(key, 100, 0),
      findApprovalEffectiveReleaseHistory(key, 100, 0),
      findApprovalProcessReleaseLifecycles(key, 100, 0),
    ]);
    center.value = nextCenter;
    history.value = nextHistory;
    lifecycles.value = nextLifecycles;
    try {
      current.value = await findApprovalEffectiveRelease(key);
    } catch (error) {
      if (error instanceof ApprovalEffectiveReleaseApiError && error.status === 404) {
        current.value = undefined;
      } else {
        throw error;
      }
    }
  } catch (error) {
    center.value = undefined;
    current.value = undefined;
    history.value = emptyHistory();
    lifecycles.value = emptyLifecycles();
    ElMessage.error(message(error));
  } finally {
    loading.value = false;
  }
}

function openAction(release: ApprovalReleaseVersionSummary, nextAction: ApprovalReleaseLifecycleAction) {
  if (!lifecycle(release.releaseVersion)) {
    ElMessage.error('该版本缺少权威生命周期证据，操作已阻止');
    return;
  }
  targetRelease.value = release;
  action.value = nextAction;
  reason.value = '';
  actionVisible.value = true;
}

async function submitActivation(
  release: ApprovalReleaseVersionSummary,
  key: string,
  changeReason: string,
) {
  const expectedRevision = current.value?.revision ?? 0;
  const result = action.value === 'ROLLBACK'
    ? await rollbackApprovalRelease(
        key,
        release.releaseVersion,
        expectedRevision,
        changeReason,
      )
    : await activateApprovalRelease(
        key,
        release.releaseVersion,
        expectedRevision,
        changeReason,
      );
  ElMessage.success(
    result.replayedExistingActivation
      ? '当前生效版本未变化'
      : action.value === 'ROLLBACK' ? '版本已回滚' : '版本已激活',
  );
}

async function submitDisposition(
  release: ApprovalReleaseVersionSummary,
  evidence: ApprovalProcessReleaseLifecycle,
  key: string,
  changeReason: string,
) {
  const result = action.value === 'DEPRECATE'
    ? await deprecateApprovalRelease(
        key,
        release.releaseVersion,
        evidence.revision,
        changeReason,
      )
    : await retireApprovalRelease(
        key,
        release.releaseVersion,
        evidence.revision,
        changeReason,
      );
  const usage = result.runtimeUsageCount;
  ElMessage.success(result.replayedExistingDisposition
    ? '版本生命周期未变化'
    : action.value === 'DEPRECATE'
      ? `版本已停止用于新实例，保留 ${usage} 条运行绑定证据`
      : `版本已退役，保留 ${usage} 条运行绑定证据`);
}

async function submitAction() {
  const release = targetRelease.value;
  const evidence = release && lifecycle(release.releaseVersion);
  const key = definitionKey.value.trim();
  const changeReason = reason.value.trim();
  if (!release || !evidence || !changeReason) return;
  actionLoading.value = true;
  try {
    if (action.value === 'DEPRECATE' || action.value === 'RETIRE') {
      await submitDisposition(release, evidence, key, changeReason);
    } else {
      await submitActivation(release, key, changeReason);
    }
    actionVisible.value = false;
    await load();
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    actionLoading.value = false;
  }
}

onMounted(() => {
  if (definitionKey.value) void load();
});
</script>

<template>
  <Page title="版本生效" description="管理新流程实例使用的精确 Release Package 生命周期">
    <ElCard shadow="never">
      <ElSpace wrap>
        <ElInput
          v-model="definitionKey"
          clearable
          placeholder="流程定义 Key"
          style="width: 320px"
          @keyup.enter="load"
        />
        <ElButton type="primary" :loading="loading" @click="load">查询</ElButton>
      </ElSpace>
    </ElCard>

    <ElAlert
      v-if="center && lifecycles.total !== center.releasePage.total"
      class="mt-4"
      title="版本与生命周期证据数量不一致，缺少证据的操作已阻止"
      type="error"
      :closable="false"
      show-icon
    />

    <ElCard v-if="current" class="mt-4" shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>当前生效版本</span>
          <ElTag type="success">Release v{{ current.effectiveReleaseVersion }}</ElTag>
        </div>
      </template>
      <ElDescriptions :column="3" border>
        <ElDescriptionsItem label="DSL">v{{ current.definitionVersion }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Form Package">v{{ current.formPackageVersion }}</ElDescriptionsItem>
        <ElDescriptionsItem label="生效投影修订">r{{ current.revision }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Package Hash">{{ shortHash(current.releasePackageHash) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="激活人">{{ current.activatedBy }}</ElDescriptionsItem>
        <ElDescriptionsItem label="激活时间">{{ formatDate(current.activatedAt) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="原因" :span="3">{{ current.changeReason }}</ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <ElCard class="mt-4" shadow="never">
      <template #header>已部署 Release Package</template>
      <ElTable v-if="deployedReleases.length" v-loading="loading" :data="deployedReleases">
        <ElTableColumn label="Release" width="100">
          <template #default="{ row }">v{{ row.releaseVersion }}</template>
        </ElTableColumn>
        <ElTableColumn label="关联版本" min-width="190">
          <template #default="{ row }">
            DSL v{{ row.definitionVersion }} · Form v{{ row.formPackageVersion }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="Package Hash" min-width="170">
          <template #default="{ row }">{{ shortHash(row.packageHash) }}</template>
        </ElTableColumn>
        <ElTableColumn label="生命周期" min-width="170">
          <template #default="{ row }">
            <ElSpace v-if="lifecycle(row.releaseVersion)">
              <ElTag :type="lifecycleTagType(lifecycle(row.releaseVersion))">
                {{ lifecycleLabel(lifecycle(row.releaseVersion)) }}
              </ElTag>
              <span>r{{ lifecycle(row.releaseVersion)?.revision }}</span>
            </ElSpace>
            <ElTag v-else type="danger">证据缺失</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="最后变更" min-width="170">
          <template #default="{ row }">
            {{ formatDate(lifecycle(row.releaseVersion)?.lastTransitionAt) }}
          </template>
        </ElTableColumn>
        <ElTableColumn fixed="right" label="操作" width="270">
          <template #default="{ row }">
            <ElSpace v-if="lifecycle(row.releaseVersion)">
              <ElButton
                v-if="lifecycle(row.releaseVersion)?.lifecycleState === 'ACTIVE'"
                link
                type="warning"
                @click="openAction(row, 'DEPRECATE')"
              >
                停止新实例使用
              </ElButton>
              <ElButton
                v-else-if="activationAction(lifecycle(row.releaseVersion))"
                link
                :type="activationAction(lifecycle(row.releaseVersion)) === 'ROLLBACK' ? 'warning' : 'primary'"
                @click="openAction(row, activationAction(lifecycle(row.releaseVersion))!)"
              >
                {{ activationAction(lifecycle(row.releaseVersion)) === 'ROLLBACK' ? '回滚至此版本' : '设为生效' }}
              </ElButton>
              <ElButton
                v-if="['DEPRECATED', 'PUBLISHED'].includes(lifecycle(row.releaseVersion)!.lifecycleState)"
                link
                type="danger"
                @click="openAction(row, 'RETIRE')"
              >
                退役
              </ElButton>
              <ElTag
                v-if="lifecycle(row.releaseVersion)?.lifecycleState === 'RETIRED'"
                type="info"
              >
                不可再激活
              </ElTag>
            </ElSpace>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-else description="没有已部署版本" />
    </ElCard>

    <ElCard class="mt-4" shadow="never">
      <template #header>生效历史</template>
      <ElTable v-if="history.items.length" :data="history.items">
        <ElTableColumn label="修订" width="90">
          <template #default="{ row }">r{{ row.revision }}</template>
        </ElTableColumn>
        <ElTableColumn label="动作" width="90" prop="action" />
        <ElTableColumn label="版本" width="100">
          <template #default="{ row }">v{{ row.releaseVersion }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作人" min-width="150" prop="activatedBy" />
        <ElTableColumn label="时间" min-width="170">
          <template #default="{ row }">{{ formatDate(row.activatedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="原因" min-width="240" prop="changeReason" />
      </ElTable>
      <ElEmpty v-else description="暂无生效历史" />
    </ElCard>

    <ElDialog v-model="actionVisible" :title="actionTitle" width="680px">
      <ElAlert
        v-if="action === 'DEPRECATE'"
        class="mb-4"
        title="停止后，新实例不再使用该版本；已有实例及其运行绑定证据保持不变。"
        type="warning"
        :closable="false"
        show-icon
      />
      <ElAlert
        v-else-if="action === 'RETIRE'"
        class="mb-4"
        title="退役为终态；已有实例、Release Package 和审计证据不会删除。"
        type="error"
        :closable="false"
        show-icon
      />
      <ElDescriptions v-if="targetRelease" :column="2" border>
        <ElDescriptionsItem label="目标版本">Release v{{ targetRelease.releaseVersion }}</ElDescriptionsItem>
        <ElDescriptionsItem label="生命周期">
          {{ lifecycleLabel(lifecycle(targetRelease.releaseVersion)) }}
          · r{{ lifecycle(targetRelease.releaseVersion)?.revision }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="Package Hash" :span="2">
          {{ targetRelease.packageHash }}
        </ElDescriptionsItem>
      </ElDescriptions>
      <ElForm class="mt-4" label-position="top">
        <ElFormItem label="变更原因" required>
          <ElInput
            v-model="reason"
            maxlength="512"
            placeholder="请输入 8–512 个字符的版本生命周期变更原因"
            show-word-limit
            type="textarea"
            :rows="4"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="actionVisible = false">取消</ElButton>
        <ElButton
          :disabled="!canSubmit"
          :loading="actionLoading"
          :type="actionButtonType"
          @click="submitAction"
        >
          确认{{ actionTitle.replace('确认', '') }}
        </ElButton>
      </template>
    </ElDialog>
  </Page>
</template>

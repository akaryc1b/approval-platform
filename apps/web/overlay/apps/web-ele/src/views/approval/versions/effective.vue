<script lang="ts" setup>
import type { ApprovalReleaseVersionSummary } from '#/api/approval/release-version-management';
import type {
  ApprovalEffectiveRelease,
  ApprovalEffectiveReleaseAction,
  ApprovalEffectiveReleaseHistoryPage,
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
  findApprovalEffectiveRelease,
  findApprovalEffectiveReleaseHistory,
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
const loading = ref(false);
const actionLoading = ref(false);
const actionVisible = ref(false);
const action = ref<ApprovalEffectiveReleaseAction>('ACTIVATE');
const targetRelease = ref<ApprovalReleaseVersionSummary>();
const reason = ref('');

const deployedReleases = computed(() => (
  center.value?.releaseVersions.filter(
    item => item.deployment?.status === 'DEPLOYED',
  ) || []
));
const actionTitle = computed(() => (
  action.value === 'ROLLBACK' ? '确认回滚版本' : '确认激活版本'
));
const actionButtonText = computed(() => (
  action.value === 'ROLLBACK' ? '确认回滚' : '确认激活'
));
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

function message(error: unknown) {
  return error instanceof Error ? error.message : '版本生效请求失败';
}

function formatDate(value?: string) {
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

function actionLabel(value: ApprovalEffectiveReleaseAction) {
  return value === 'ROLLBACK' ? '回滚' : '激活';
}

function wasEffective(releaseVersion: number) {
  return history.value.items.some(item => item.releaseVersion === releaseVersion);
}

async function load() {
  const key = definitionKey.value.trim();
  if (!key) {
    ElMessage.warning('请输入流程定义 Key');
    return;
  }
  loading.value = true;
  try {
    const [nextCenter, nextHistory] = await Promise.all([
      findApprovalVersionCenter(key, 100, 0),
      findApprovalEffectiveReleaseHistory(key, 100, 0),
    ]);
    center.value = nextCenter;
    history.value = nextHistory;
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
    ElMessage.error(message(error));
  } finally {
    loading.value = false;
  }
}

function openAction(
  release: ApprovalReleaseVersionSummary,
  nextAction: ApprovalEffectiveReleaseAction,
) {
  targetRelease.value = release;
  action.value = nextAction;
  reason.value = '';
  actionVisible.value = true;
}

async function submitAction() {
  const release = targetRelease.value;
  const key = definitionKey.value.trim();
  const changeReason = reason.value.trim();
  if (!release || !changeReason) return;
  actionLoading.value = true;
  try {
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
  <Page title="版本生效" description="管理新流程实例使用的精确 Release Package 版本">
    <ElCard shadow="never">
      <ElSpace wrap>
        <ElInput
          v-model="definitionKey"
          clearable
          placeholder="流程定义 Key"
          style="width: 320px"
          @keyup.enter="load"
        />
        <ElButton type="primary" :loading="loading" @click="load">
          查询
        </ElButton>
      </ElSpace>
    </ElCard>

    <ElCard v-if="current" class="mt-4" shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>当前生效版本</span>
          <ElTag type="success">Release v{{ current.effectiveReleaseVersion }}</ElTag>
        </div>
      </template>
      <ElDescriptions :column="3" border>
        <ElDescriptionsItem label="流程定义版本">
          DSL v{{ current.definitionVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="表单包版本">
          Form Package v{{ current.formPackageVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="编译器">
          {{ current.compilerVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="引擎定义">
          {{ current.engineDefinitionId }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="引擎版本">
          v{{ current.engineVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="修订号">
          r{{ current.revision }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="Package Hash">
          {{ shortHash(current.releasePackageHash) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="激活人">
          {{ current.activatedBy }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="激活时间">
          {{ formatDate(current.activatedAt) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="变更原因" :span="3">
          {{ current.changeReason }}
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <ElAlert
      v-else-if="center"
      class="mt-4"
      title="当前流程尚未设置生效版本"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElCard class="mt-4" shadow="never">
      <template #header>已部署 Release Package</template>
      <ElTable v-if="deployedReleases.length" v-loading="loading" :data="deployedReleases">
        <ElTableColumn label="Release" width="110">
          <template #default="{ row }">v{{ row.releaseVersion }}</template>
        </ElTableColumn>
        <ElTableColumn label="关联版本" min-width="190">
          <template #default="{ row }">
            DSL v{{ row.definitionVersion }} · Form Package v{{ row.formPackageVersion }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="引擎定义" min-width="220">
          <template #default="{ row }">
            {{ row.deployment?.engineDefinitionId || '-' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="Package Hash" min-width="180">
          <template #default="{ row }">{{ shortHash(row.packageHash) }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="130">
          <template #default="{ row }">
            <ElTag v-if="row.currentEffective" type="success">当前生效</ElTag>
            <ElTag v-else type="info">已部署</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn fixed="right" label="操作" width="190">
          <template #default="{ row }">
            <ElSpace v-if="!row.currentEffective">
              <ElButton
                v-if="wasEffective(row.releaseVersion)"
                link
                type="warning"
                @click="openAction(row, 'ROLLBACK')"
              >
                回滚至此版本
              </ElButton>
              <ElButton
                v-else
                link
                type="primary"
                @click="openAction(row, 'ACTIVATE')"
              >
                设为生效
              </ElButton>
            </ElSpace>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-else description="没有可激活的已部署版本" />
    </ElCard>

    <ElCard class="mt-4" shadow="never">
      <template #header>激活历史</template>
      <ElTable v-if="history.items.length" :data="history.items">
        <ElTableColumn label="修订" width="90">
          <template #default="{ row }">r{{ row.revision }}</template>
        </ElTableColumn>
        <ElTableColumn label="动作" width="90">
          <template #default="{ row }">
            <ElTag :type="row.action === 'ROLLBACK' ? 'warning' : 'success'">
              {{ actionLabel(row.action) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="版本切换" min-width="170">
          <template #default="{ row }">
            {{ row.previousReleaseVersion ? `v${row.previousReleaseVersion}` : '-' }}
            → v{{ row.releaseVersion }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作人" min-width="150" prop="activatedBy" />
        <ElTableColumn label="时间" min-width="170">
          <template #default="{ row }">{{ formatDate(row.activatedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="原因" min-width="240" prop="changeReason" />
      </ElTable>
      <ElEmpty v-else description="暂无激活历史" />
    </ElCard>

    <ElDialog v-model="actionVisible" :title="actionTitle" width="680px">
      <ElDescriptions v-if="targetRelease" :column="2" border>
        <ElDescriptionsItem label="当前版本">
          {{ current ? `Release v${current.effectiveReleaseVersion}` : '未设置' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="目标版本">
          Release v{{ targetRelease.releaseVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="DSL">
          v{{ targetRelease.definitionVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="Form Package">
          v{{ targetRelease.formPackageVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="引擎定义" :span="2">
          {{ targetRelease.deployment?.engineDefinitionId }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="Package Hash" :span="2">
          {{ targetRelease.packageHash }}
        </ElDescriptionsItem>
      </ElDescriptions>
      <ElForm class="mt-4" label-position="top">
        <ElFormItem label="变更原因" required>
          <ElInput
            v-model="reason"
            maxlength="1000"
            placeholder="请输入本次版本切换原因"
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
          :type="action === 'ROLLBACK' ? 'warning' : 'primary'"
          @click="submitAction"
        >
          {{ actionButtonText }}
        </ElButton>
      </template>
    </ElDialog>
  </Page>
</template>

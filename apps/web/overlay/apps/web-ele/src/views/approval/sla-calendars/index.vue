<script lang="ts" setup>
import type {
  CalendarIdentity,
  CalendarPage,
  CalendarVersion,
  DayOverride,
  WorkingInterval,
} from '#/api/approval/sla';

import { computed, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElEmpty,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  activateCalendarVersion,
  createCalendar,
  findCalendars,
  findCalendarVersion,
  publishCalendarVersion,
  saveCalendarVersion,
} from '#/api/approval/sla';

type DayKey =
  | 'FRIDAY'
  | 'MONDAY'
  | 'SATURDAY'
  | 'SUNDAY'
  | 'THURSDAY'
  | 'TUESDAY'
  | 'WEDNESDAY';

interface OverrideEditor {
  date: string;
  intervals: WorkingInterval[];
  working: boolean;
}

const PAGE_SIZE = 50;
const dayOptions: Array<{ key: DayKey; label: string }> = [
  { key: 'MONDAY', label: '周一' },
  { key: 'TUESDAY', label: '周二' },
  { key: 'WEDNESDAY', label: '周三' },
  { key: 'THURSDAY', label: '周四' },
  { key: 'FRIDAY', label: '周五' },
  { key: 'SATURDAY', label: '周六' },
  { key: 'SUNDAY', label: '周日' },
];

const loading = ref(false);
const saving = ref(false);
const createVisible = ref(false);
const editorVisible = ref(false);
const calendars = ref<CalendarPage>(emptyPage());
const selected = ref<CalendarIdentity>();
const loadedVersion = ref<CalendarVersion>();
const versionNumber = ref(1);
const reason = ref('');
const timeZone = ref('Asia/Shanghai');
const effectiveRange = ref<[string, string] | undefined>();
const overrides = ref<OverrideEditor[]>([]);
const weeklySchedule = ref<Record<DayKey, WorkingInterval[]>>(defaultSchedule());
const createModel = reactive({
  calendarKey: '',
  displayName: '',
  reason: '',
  timeZone: 'Asia/Shanghai',
});

const immutable = computed(() => loadedVersion.value?.immutable === true);

function emptyPage(): CalendarPage {
  return { items: [], limit: PAGE_SIZE, offset: 0, total: 0 };
}

function defaultSchedule(): Record<DayKey, WorkingInterval[]> {
  return {
    FRIDAY: standardDay(),
    MONDAY: standardDay(),
    SATURDAY: [],
    SUNDAY: [],
    THURSDAY: standardDay(),
    TUESDAY: standardDay(),
    WEDNESDAY: standardDay(),
  };
}

function standardDay(): WorkingInterval[] {
  return [
    { end: '12:00:00', start: '09:00:00' },
    { end: '18:00:00', start: '13:00:00' },
  ];
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function statusType(status: string) {
  if (status === 'ACTIVE') return 'success';
  if (status === 'PUBLISHED') return 'primary';
  if (status === 'DRAFT') return 'warning';
  return 'info';
}

function message(error: unknown) {
  return error instanceof Error ? error.message : '工作日历请求失败';
}

async function loadCalendars() {
  loading.value = true;
  try {
    calendars.value = await findCalendars(PAGE_SIZE, 0);
  } catch (error) {
    calendars.value = emptyPage();
    ElMessage.error(message(error));
  } finally {
    loading.value = false;
  }
}

function resetEditor(calendar: CalendarIdentity) {
  selected.value = calendar;
  loadedVersion.value = undefined;
  versionNumber.value = calendar.activeVersion ? calendar.activeVersion + 1 : 1;
  reason.value = '';
  timeZone.value = calendar.timeZone;
  effectiveRange.value = undefined;
  weeklySchedule.value = defaultSchedule();
  overrides.value = [];
  editorVisible.value = true;
}

async function readVersion() {
  if (!selected.value) return;
  saving.value = true;
  try {
    const value = await findCalendarVersion(selected.value.calendarId, versionNumber.value);
    loadedVersion.value = value;
    timeZone.value = value.snapshot.zoneId || selected.value.timeZone;
    effectiveRange.value = value.effectiveFrom && value.effectiveTo
      ? [value.effectiveFrom, value.effectiveTo]
      : undefined;
    const schedule = defaultSchedule();
    dayOptions.forEach(({ key }) => {
      schedule[key] = (value.snapshot.weeklySchedule[key] ?? []).map(item => ({ ...item }));
    });
    weeklySchedule.value = schedule;
    overrides.value = Object.entries(value.snapshot.overrides ?? {}).map(([date, override]) => ({
      date,
      intervals: override.intervals.map(item => ({ ...item })),
      working: override.working,
    }));
  } catch (error) {
    loadedVersion.value = undefined;
    ElMessage.error(message(error));
  } finally {
    saving.value = false;
  }
}

function addInterval(day: DayKey) {
  weeklySchedule.value[day].push({ end: '18:00:00', start: '09:00:00' });
}

function removeInterval(day: DayKey, index: number) {
  weeklySchedule.value[day].splice(index, 1);
}

function addOverride() {
  overrides.value.push({ date: '', intervals: [], working: false });
}

function toggleOverride(item: OverrideEditor) {
  item.intervals = item.working ? standardDay() : [];
}

function overridePayload() {
  return overrides.value.reduce<Record<string, DayOverride>>((result, item) => {
    if (item.date) {
      result[item.date] = {
        intervals: item.working ? item.intervals : [],
        working: item.working,
      };
    }
    return result;
  }, {});
}

async function submitCreate() {
  saving.value = true;
  try {
    await createCalendar(
      {
        calendarKey: createModel.calendarKey.trim(),
        displayName: createModel.displayName.trim(),
        timeZone: createModel.timeZone.trim(),
      },
      { reason: createModel.reason },
    );
    createVisible.value = false;
    Object.assign(createModel, {
      calendarKey: '',
      displayName: '',
      reason: '',
      timeZone: 'Asia/Shanghai',
    });
    ElMessage.success('工作日历已创建');
    await loadCalendars();
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    saving.value = false;
  }
}

async function saveDraft() {
  if (!selected.value) return;
  saving.value = true;
  try {
    loadedVersion.value = await saveCalendarVersion(
      selected.value.calendarId,
      versionNumber.value,
      {
        effectiveFrom: effectiveRange.value?.[0],
        effectiveTo: effectiveRange.value?.[1],
        expectedIdentityVersion: selected.value.version,
        overrides: overridePayload(),
        timeZone: timeZone.value.trim(),
        weeklySchedule: weeklySchedule.value,
      },
      { reason: reason.value },
    );
    ElMessage.success('草稿版本已保存');
    await loadCalendars();
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    saving.value = false;
  }
}

async function transition(calendar: CalendarIdentity, action: 'activate' | 'publish') {
  const { value } = await ElMessageBox.prompt(
    action === 'publish' ? '请输入发布原因（至少 8 个字符）' : '请输入激活原因（至少 8 个字符）',
    action === 'publish' ? '发布日历版本' : '激活日历版本',
    { inputValidator: text => Boolean(text && text.trim().length >= 8) || '原因至少 8 个字符' },
  );
  const version = calendar.activeVersion ?? Math.max(1, versionNumber.value);
  try {
    if (action === 'publish') {
      await publishCalendarVersion(calendar, version, { reason: value });
      ElMessage.success(`日历版本 v${version} 已发布`);
    } else {
      await activateCalendarVersion(calendar, version, { reason: value });
      ElMessage.success(`日历版本 v${version} 已激活`);
    }
    await loadCalendars();
  } catch (error) {
    ElMessage.error(message(error));
  }
}

void loadCalendars();
</script>

<template>
  <Page title="工作日历" description="版本化维护工作区间、节假日与调休；最终可信截止时间始终由服务端计算。">
    <div class="calendar-page">
      <ElAlert
        :closable="false"
        show-icon
        title="已发布版本不可修改。发布与激活均要求操作原因、幂等键和服务端审计证据。"
        type="warning"
      />
      <ElCard shadow="never">
        <template #header>
          <div class="header-row">
            <div><strong>日历列表</strong><span>共 {{ calendars.total }} 个</span></div>
            <div class="actions">
              <ElButton :loading="loading" @click="loadCalendars">刷新</ElButton>
              <ElButton type="primary" @click="createVisible = true">新建日历</ElButton>
            </div>
          </div>
        </template>
        <ElTable v-if="calendars.items.length" v-loading="loading" :data="calendars.items" row-key="calendarId">
          <ElTableColumn label="日历" min-width="220">
            <template #default="scope"><strong>{{ scope.row.displayName }}</strong><div class="muted">{{ scope.row.calendarKey }}</div></template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="120"><template #default="scope"><ElTag :type="statusType(scope.row.status)">{{ scope.row.status }}</ElTag></template></ElTableColumn>
          <ElTableColumn label="时区" prop="timeZone" min-width="160" />
          <ElTableColumn label="生效版本" width="110"><template #default="scope">{{ scope.row.activeVersion ? `v${scope.row.activeVersion}` : '-' }}</template></ElTableColumn>
          <ElTableColumn label="版本号" width="90" prop="version" />
          <ElTableColumn label="更新时间" min-width="180"><template #default="scope">{{ formatDate(scope.row.updatedAt) }}</template></ElTableColumn>
          <ElTableColumn fixed="right" label="操作" width="250">
            <template #default="scope">
              <ElButton link type="primary" @click="resetEditor(scope.row)">版本配置</ElButton>
              <ElButton link type="warning" @click="transition(scope.row, 'publish')">发布</ElButton>
              <ElButton link type="success" @click="transition(scope.row, 'activate')">激活</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty v-else :description="loading ? '正在读取' : '暂无工作日历'" />
      </ElCard>
    </div>

    <ElDialog v-model="createVisible" title="新建工作日历" width="560px">
      <div class="form-grid">
        <label>日历 Key<ElInput v-model="createModel.calendarKey" maxlength="100" /></label>
        <label>显示名称<ElInput v-model="createModel.displayName" maxlength="200" /></label>
        <label>时区<ElInput v-model="createModel.timeZone" maxlength="100" /></label>
        <label>操作原因<ElInput v-model="createModel.reason" maxlength="512" show-word-limit type="textarea" /></label>
      </div>
      <template #footer><ElButton @click="createVisible = false">取消</ElButton><ElButton :loading="saving" type="primary" @click="submitCreate">创建</ElButton></template>
    </ElDialog>

    <ElDialog v-model="editorVisible" :title="`日历版本配置 · ${selected?.displayName ?? ''}`" width="1100px">
      <ElAlert v-if="immutable" :closable="false" show-icon title="当前版本已发布，只读展示；请切换到新的版本号保存草稿。" type="info" />
      <div class="toolbar">
        <span>版本</span><ElInputNumber v-model="versionNumber" :min="1" />
        <ElButton :loading="saving" @click="readVersion">读取版本</ElButton>
        <ElInput v-model="timeZone" class="timezone" placeholder="IANA 时区，如 Asia/Shanghai" />
        <ElDatePicker v-model="effectiveRange" end-placeholder="有效结束" start-placeholder="有效开始" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss[Z]" />
      </div>
      <section class="schedule-section">
        <h3>每周工作区间</h3>
        <div v-for="day in dayOptions" :key="day.key" class="day-row">
          <strong>{{ day.label }}</strong>
          <div class="intervals">
            <div v-for="(interval, index) in weeklySchedule[day.key]" :key="`${day.key}-${index}`" class="interval-row">
              <ElInput v-model="interval.start" placeholder="09:00:00" />
              <span>至</span>
              <ElInput v-model="interval.end" placeholder="18:00:00" />
              <ElButton link type="danger" @click="removeInterval(day.key, index)">删除</ElButton>
            </div>
            <ElButton link type="primary" @click="addInterval(day.key)">添加区间</ElButton>
          </div>
        </div>
      </section>
      <section class="schedule-section">
        <div class="header-row"><h3>日期覆盖</h3><ElButton @click="addOverride">添加节假日/补班</ElButton></div>
        <div v-for="(item, index) in overrides" :key="index" class="override-row">
          <ElDatePicker v-model="item.date" placeholder="日期" type="date" value-format="YYYY-MM-DD" />
          <span>工作日</span><ElSwitch v-model="item.working" @change="toggleOverride(item)" />
          <div v-if="item.working" class="override-intervals">
            <div v-for="(interval, intervalIndex) in item.intervals" :key="intervalIndex" class="interval-row">
              <ElInput v-model="interval.start" /><span>至</span><ElInput v-model="interval.end" />
            </div>
          </div>
          <ElButton link type="danger" @click="overrides.splice(index, 1)">移除</ElButton>
        </div>
        <ElEmpty v-if="!overrides.length" description="暂无日期覆盖" />
      </section>
      <label class="reason-field">操作原因<ElInput v-model="reason" maxlength="512" show-word-limit type="textarea" /></label>
      <template #footer><ElButton @click="editorVisible = false">关闭</ElButton><ElButton :disabled="immutable" :loading="saving" type="primary" @click="saveDraft">保存草稿</ElButton></template>
    </ElDialog>
  </Page>
</template>

<style scoped>
.calendar-page { display: grid; gap: 16px; }
.header-row, .toolbar, .actions, .interval-row, .override-row { align-items: center; display: flex; gap: 12px; }
.header-row { justify-content: space-between; }
.header-row span, .muted { color: var(--el-text-color-secondary); font-size: 12px; }
.form-grid { display: grid; gap: 16px; }
.form-grid label, .reason-field { display: grid; gap: 8px; }
.toolbar { flex-wrap: wrap; margin-bottom: 18px; }
.timezone { width: 240px; }
.schedule-section { border-top: 1px solid var(--el-border-color-lighter); padding: 16px 0; }
.day-row { align-items: flex-start; display: grid; gap: 12px; grid-template-columns: 90px 1fr; padding: 8px 0; }
.intervals, .override-intervals { display: grid; gap: 8px; }
.interval-row .el-input { width: 130px; }
.override-row { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; margin: 8px 0; padding: 12px; }
.override-intervals { flex: 1; }
.reason-field { margin-top: 12px; }
</style>

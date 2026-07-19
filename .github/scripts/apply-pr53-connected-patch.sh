#!/usr/bin/env bash
set -euo pipefail

python3 <<'PY'
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected source block not found in {path}: {old[:80]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


page = "apps/web/overlay/apps/web-ele/src/views/approval/versions/index.vue"

replace_once(
    page,
    """import type {
  ApprovalReleasePackageDetail,
  ApprovalReleaseVersionSummary,
  ApprovalStructuralDiffChange,
  ApprovalStructuralDiffResult,
  ApprovalVersionCenter,
} from '#/api/approval/release-version-management';""",
    """import type {
  ApprovalArtifactImportResult,
  ApprovalArtifactTransferEnvelope,
  ApprovalDefinitionVersionSummary,
  ApprovalReleasePackageDetail,
  ApprovalReleaseVersionSummary,
  ApprovalStructuralDiffChange,
  ApprovalStructuralDiffResult,
  ApprovalVersionCenter,
} from '#/api/approval/release-version-management';""",
)

replace_once(
    page,
    """import {
  deployApprovalReleasePackage,
  diffApprovalReleaseVersions,
  findApprovalDefinitionVersion,
  findApprovalReleasePackage,
  findApprovalVersionCenter,
  preflightApprovalDeployment,
} from '#/api/approval/release-version-management';""",
    """import {
  APPROVAL_TRANSFER_MAX_FILE_BYTES,
  deployApprovalReleasePackage,
  diffApprovalReleaseVersions,
  exportApprovalDefinitionVersion,
  exportApprovalReleaseVersion,
  findApprovalDefinitionVersion,
  findApprovalReleasePackage,
  findApprovalVersionCenter,
  importApprovalArtifact,
  preflightApprovalDeployment,
} from '#/api/approval/release-version-management';""",
)

replace_once(
    page,
    """const copyInput = ref({
  formPackageVersion: 1,
  name: '',
  targetDefinitionVersion: 1,
});

const releaseVersions""",
    """const copyInput = ref({
  formPackageVersion: 1,
  name: '',
  targetDefinitionVersion: 1,
});
const exportingKey = ref('');
const importVisible = ref(false);
const importLoading = ref(false);
const importEnvelope = ref<ApprovalArtifactTransferEnvelope>();
const importFileName = ref('');
const importFileSize = ref(0);
const importError = ref('');
const importResult = ref<ApprovalArtifactImportResult>();
const importInput = ref({
  targetDefinitionKey: '',
  targetDefinitionVersion: 1,
  targetFormPackageVersion: 1,
  targetName: '',
});

const releaseVersions""",
)

replace_once(
    page,
    """function displayValue(value: unknown) {
  if (value === undefined || value === null || value === '') return '—';
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}

function deploymentLabel""",
    """function displayValue(value: unknown) {
  if (value === undefined || value === null || value === '') return '—';
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  return `${(value / 1024).toFixed(value < 1024 * 100 ? 1 : 0)} KiB`;
}

function safeFileSegment(value: string) {
  return value
    .normalize('NFKC')
    .replace(/[^A-Za-z0-9._-]+/g, '-')
    .replace(/\.{2,}/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80) || 'approval';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isTransferEnvelope(value: unknown): value is ApprovalArtifactTransferEnvelope {
  if (!isRecord(value) || !isRecord(value.payload)) return false;
  return (
    (value.format === 'APPROVAL_DSL_EXPORT_V1' ||
      value.format === 'APPROVAL_RELEASE_PACKAGE_EXPORT_V1') &&
    (value.artifactType === 'APPROVAL_DSL' ||
      value.artifactType === 'APPROVAL_RELEASE_PACKAGE') &&
    typeof value.definitionKey === 'string' &&
    Number.isInteger(value.definitionVersion) &&
    Number.isInteger(value.formPackageVersion) &&
    typeof value.payloadHash === 'string' &&
    typeof value.envelopeHash === 'string'
  );
}

function downloadEnvelope(envelope: ApprovalArtifactTransferEnvelope) {
  const key = safeFileSegment(envelope.definitionKey);
  const hash = safeFileSegment(envelope.envelopeHash.slice(0, 12));
  const version = envelope.artifactType === 'APPROVAL_DSL'
    ? envelope.definitionVersion
    : envelope.releaseVersion;
  const prefix = envelope.artifactType === 'APPROVAL_DSL'
    ? 'approval-dsl'
    : 'approval-release';
  const fileName = `${prefix}-${key}-v${version}-${hash}.json`;
  const blob = new Blob([JSON.stringify(envelope, null, 2)], {
    type: 'application/json;charset=utf-8',
  });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function deploymentLabel""",
)

replace_once(
    page,
    """async function deploy(row: ApprovalReleaseVersionSummary) {""",
    """async function exportRelease(row: ApprovalReleaseVersionSummary) {
  if (!center.value) return;
  exportingKey.value = `release-${row.releaseVersion}`;
  try {
    const envelope = await exportApprovalReleaseVersion(
      center.value.definitionKey,
      row.releaseVersion,
    );
    downloadEnvelope(envelope);
    ElMessage.success('Release Package 导出完成');
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    exportingKey.value = '';
  }
}

async function exportDefinition(row: ApprovalDefinitionVersionSummary) {
  if (!center.value) return;
  exportingKey.value = `definition-${row.definitionVersion}`;
  try {
    const envelope = await exportApprovalDefinitionVersion(
      center.value.definitionKey,
      row.definitionVersion,
    );
    downloadEnvelope(envelope);
    ElMessage.success('Approval DSL 导出完成');
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    exportingKey.value = '';
  }
}

async function deploy(row: ApprovalReleaseVersionSummary) {""",
)

replace_once(
    page,
    """async function submitCopy() {
  if (!center.value || !copySource.value || !copyInput.value.name.trim()) return;""",
    """function openImport() {
  importEnvelope.value = undefined;
  importFileName.value = '';
  importFileSize.value = 0;
  importError.value = '';
  importResult.value = undefined;
  importInput.value = {
    targetDefinitionKey: center.value?.definitionKey || '',
    targetDefinitionVersion: (center.value?.latestDefinitionVersion || 0) + 1,
    targetFormPackageVersion: 1,
    targetName: '',
  };
  importVisible.value = true;
}

async function selectImportFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  importEnvelope.value = undefined;
  importResult.value = undefined;
  importError.value = '';
  if (!file) return;
  importFileName.value = file.name;
  importFileSize.value = file.size;
  if (!file.name.toLowerCase().endsWith('.json')) {
    importError.value = '仅支持单个 .json 文件';
    return;
  }
  if (file.size < 1 || file.size > APPROVAL_TRANSFER_MAX_FILE_BYTES) {
    importError.value = '文件必须大于 0 且不超过 2 MiB';
    return;
  }
  try {
    const parsed = JSON.parse(await file.text()) as unknown;
    if (!isTransferEnvelope(parsed)) {
      throw new Error('文件不是受支持的审批制品 envelope');
    }
    importEnvelope.value = parsed;
    const definition = isRecord(parsed.payload.definition)
      ? parsed.payload.definition
      : undefined;
    const sourceName = typeof definition?.name === 'string'
      ? definition.name
      : parsed.definitionKey;
    importInput.value = {
      targetDefinitionKey: parsed.definitionKey,
      targetDefinitionVersion: Math.max(
        (center.value?.latestDefinitionVersion || 0) + 1,
        parsed.definitionVersion + 1,
      ),
      targetFormPackageVersion: parsed.formPackageVersion,
      targetName: `${sourceName} 导入草稿`,
    };
  } catch (error) {
    importError.value = error instanceof SyntaxError
      ? 'JSON 解析失败，请检查文件内容'
      : message(error);
  }
}

async function submitImport() {
  if (!importEnvelope.value) {
    importError.value = '请选择有效的审批制品 JSON 文件';
    return;
  }
  if (!importInput.value.targetDefinitionKey.trim() ||
      !importInput.value.targetName.trim()) {
    importError.value = '请填写目标定义 Key 和草稿名称';
    return;
  }
  importLoading.value = true;
  importError.value = '';
  try {
    importResult.value = await importApprovalArtifact({
      envelope: importEnvelope.value,
      targetDefinitionKey: importInput.value.targetDefinitionKey.trim(),
      targetDefinitionVersion: importInput.value.targetDefinitionVersion,
      targetFormPackageVersion: importInput.value.targetFormPackageVersion,
      targetName: importInput.value.targetName.trim(),
    });
    ElMessage.success('审批制品已安全导入为新草稿');
  } catch (error) {
    importError.value = message(error);
  } finally {
    importLoading.value = false;
  }
}

function openImportedDraft() {
  if (!importResult.value) return;
  window.location.assign(
    `/approval/designer?draftId=${encodeURIComponent(importResult.value.draftId)}`,
  );
}

async function submitCopy() {
  if (!center.value || !copySource.value || !copyInput.value.name.trim()) return;""",
)

replace_once(
    page,
    """          <ElButton type="primary" :loading="loading" @click="load">
            查询版本
          </ElButton>
        </div>""",
    """          <ElButton type="primary" :loading="loading" @click="load">
            查询版本
          </ElButton>
          <ElButton @click="openImport">导入审批制品</ElButton>
        </div>""",
)

replace_once(
    page,
    """                <ElButton link type="primary" @click="openDetail(row)">详情</ElButton>
                <ElButton
                  link
                  type="primary"
                  :loading="copyLoading && copySource?.releaseVersion === row.releaseVersion""" ,
    """                <ElButton link type="primary" @click="openDetail(row)">详情</ElButton>
                <ElButton
                  link
                  type="primary"
                  :loading="exportingKey === `release-${row.releaseVersion}`"
                  @click="exportRelease(row)"
                >
                  导出包
                </ElButton>
                <ElButton
                  link
                  type="primary"
                  :loading="copyLoading && copySource?.releaseVersion === row.releaseVersion""",
)

replace_once(
    page,
    """            <ElTableColumn label="发布时间" min-width="180">
              <template #default="{ row }">
                {{ formatDate(row.publishedAt) }}
              </template>
            </ElTableColumn>
          </ElTable>""",
    """            <ElTableColumn label="发布时间" min-width="180">
              <template #default="{ row }">
                {{ formatDate(row.publishedAt) }}
              </template>
            </ElTableColumn>
            <ElTableColumn fixed="right" label="操作" width="120">
              <template #default="{ row }">
                <ElButton
                  link
                  type="primary"
                  :loading="exportingKey === `definition-${row.definitionVersion}`"
                  @click="exportDefinition(row)"
                >
                  导出 DSL
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>""",
)

replace_once(
    page,
    """    <ElDialog v-model="copyVisible" title="从历史版本创建新草稿" width="520px">""",
    """    <ElDialog
      v-model="importVisible"
      :close-on-click-modal="!importLoading"
      title="导入审批制品"
      width="760px"
    >
      <template v-if="importResult">
        <ElAlert
          :closable="false"
          show-icon
          title="导入成功，仅创建了新草稿"
          type="success"
        />
        <ElDescriptions class="import-result" :column="2" border>
          <ElDescriptionsItem label="draftId">
            <code>{{ importResult.draftId }}</code>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="状态">
            <ElTag type="info">{{ importResult.status }}</ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="revision">
            {{ importResult.revision }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="目标 DSL">
            {{ importResult.definitionKey }} v{{ importResult.definitionVersion }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="目标 Form Package">
            v{{ importResult.formPackageVersion }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="源 Envelope Hash">
            <code>{{ shortHash(importResult.sourceEnvelopeHash) }}</code>
          </ElDescriptionsItem>
        </ElDescriptions>
      </template>

      <template v-else>
        <label class="import-file-picker">
          <input accept=".json,application/json" type="file" @change="selectImportFile" />
          <strong>选择单个 JSON 文件</strong>
          <span>前端限制 2 MiB，服务端会重新执行全部限制与哈希校验</span>
        </label>
        <div v-if="importFileName" class="import-file-summary">
          <span>{{ importFileName }}</span>
          <span>{{ formatBytes(importFileSize) }}</span>
        </div>
        <ElAlert
          v-if="importError"
          :closable="false"
          show-icon
          :title="importError"
          type="error"
        />

        <template v-if="importEnvelope">
          <ElDescriptions class="import-preview" :column="2" border>
            <ElDescriptionsItem label="格式">
              {{ importEnvelope.format }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="制品类型">
              {{ importEnvelope.artifactType }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="源定义 Key">
              {{ importEnvelope.definitionKey }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="源 DSL 版本">
              v{{ importEnvelope.definitionVersion }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="源 Release 版本">
              {{ importEnvelope.releaseVersion ? `v${importEnvelope.releaseVersion}` : '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="源 Form Package">
              v{{ importEnvelope.formPackageVersion }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="Payload Hash">
              <code>{{ shortHash(importEnvelope.payloadHash) }}</code>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="Envelope Hash">
              <code>{{ shortHash(importEnvelope.envelopeHash) }}</code>
            </ElDescriptionsItem>
          </ElDescriptions>

          <ElForm class="import-target" label-position="top">
            <ElRow :gutter="16">
              <ElCol :md="12" :xs="24">
                <ElFormItem label="目标定义 Key">
                  <ElInput v-model="importInput.targetDefinitionKey" maxlength="128" />
                </ElFormItem>
              </ElCol>
              <ElCol :md="12" :xs="24">
                <ElFormItem label="目标草稿名称">
                  <ElInput v-model="importInput.targetName" maxlength="128" />
                </ElFormItem>
              </ElCol>
              <ElCol :md="12" :xs="24">
                <ElFormItem label="目标 DSL 版本">
                  <ElInputNumber
                    v-model="importInput.targetDefinitionVersion"
                    :min="1"
                    controls-position="right"
                  />
                </ElFormItem>
              </ElCol>
              <ElCol :md="12" :xs="24">
                <ElFormItem label="目标 Form Package 版本">
                  <ElInputNumber
                    v-model="importInput.targetFormPackageVersion"
                    :min="1"
                    controls-position="right"
                  />
                </ElFormItem>
              </ElCol>
            </ElRow>
          </ElForm>

          <ElAlert :closable="false" show-icon type="warning">
            <template #title>导入边界确认</template>
            <ul class="import-notice">
              <li>本操作只创建 revision 1、状态 DRAFT 的新草稿。</li>
              <li>不会发布、部署、切换生效版本或导入运行时实例。</li>
              <li>不会导入源租户、发布人、部署记录或引擎身份。</li>
              <li>目标 Form Package、Form Schema 与 UI Schema 由服务端重新绑定并校验。</li>
            </ul>
          </ElAlert>
        </template>
      </template>

      <template #footer>
        <template v-if="importResult">
          <ElButton @click="importVisible = false">关闭</ElButton>
          <ElButton type="primary" @click="openImportedDraft">打开流程设计器</ElButton>
        </template>
        <template v-else>
          <ElButton :disabled="importLoading" @click="importVisible = false">取消</ElButton>
          <ElButton
            type="primary"
            :disabled="!importEnvelope"
            :loading="importLoading"
            @click="submitImport"
          >
            仅创建草稿
          </ElButton>
        </template>
      </template>
    </ElDialog>

    <ElDialog v-model="copyVisible" title="从历史版本创建新草稿" width="520px">""",
)

replace_once(
    page,
    """  grid-template-columns: minmax(260px, 560px) auto;""",
    """  grid-template-columns: minmax(260px, 560px) auto auto;""",
)

replace_once(
    page,
    """.artifact-heading {
  justify-content: space-between;
  margin-bottom: 12px;
}

code {""",
    """.artifact-heading {
  justify-content: space-between;
  margin-bottom: 12px;
}

.import-file-picker {
  display: grid;
  padding: 24px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  cursor: pointer;
  gap: 6px;
  text-align: center;
}

.import-file-picker input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
}

.import-file-picker span,
.import-file-summary {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.import-file-summary {
  display: flex;
  justify-content: space-between;
  margin: 8px 0 14px;
}

.import-preview,
.import-result,
.import-target {
  margin-top: 16px;
}

.import-notice {
  margin: 0;
  padding-left: 20px;
  line-height: 1.8;
}

code {""",
)

roadmap = Path("docs/ROADMAP.md")
roadmap_text = roadmap.read_text(encoding="utf-8")
roadmap_text = roadmap_text.replace(
    "- 取消未保存修改确认时保留新建流程弹窗和用户输入。",
    """- 取消未保存修改确认时保留新建流程弹窗和用户输入；
- `APPROVAL_DSL_EXPORT_V1` 与 `APPROVAL_RELEASE_PACKAGE_EXPORT_V1` 确定性跨租户传输协议；
- DSL、BPMN、DMN、编译制品、部署元数据、Release Package、payload 与 envelope 的服务端重算和完整性拒绝；
- 2 MiB 请求、JSON 深度/元素/字符串、500 节点、BPMN/DMN 字节数和安全资源名硬限制；
- 重复 JSON key、未知字段、整数溢出、非法 Unicode、XXE/外部 DTD/schema 和非法 XML 防御；
- 导入只创建目标租户 revision 1 的 DRAFT，精确重绑本地 Form Package，单事务幂等和低敏审计；
- PostgreSQL/Testcontainers 证明导出零写入、跨租户隔离、失败零部分写入和成功仅新增草稿/审计；
- PC 版本中心的 DSL/Release 导出、安全文件名、2 MiB 单 JSON 导入预览和只创建草稿确认。""",
    1,
)
roadmap_text = roadmap_text.replace(
    "- Approval DSL 与 Release Package 安全导入导出；\n- 复合区块和可插拔自定义组件。",
    "- 复合区块和可插拔自定义组件。",
    1,
)
roadmap.write_text(roadmap_text, encoding="utf-8")

architecture = Path("docs/architecture/approval-artifact-transfer.md")
architecture.parent.mkdir(parents=True, exist_ok=True)
architecture.write_text("""# Approval Artifact Transfer Security Model

## Scope

D7 defines two application-owned JSON transfer formats:

- `APPROVAL_DSL_EXPORT_V1`
- `APPROVAL_RELEASE_PACKAGE_EXPORT_V1`

The envelope is portable across tenants. It is not a database backup and never carries source tenant, operator, publication, deployment, effective-release or runtime-instance identity.

## Deterministic identity

The server recomputes the Approval DSL hash and, for Release Packages, the BPMN, optional DMN, compiled artifact, deployment metadata and complete Release Package hashes. It then recomputes `payloadHash` and `envelopeHash`. `exportedAt` is excluded from content identity, so repeated exports of unchanged content retain comparable payload and envelope hashes.

Client-declared hashes are assertions only. Any mismatch rejects export or import. JSON property order does not participate in the typed canonical hash material.

## Excluded data

Exports exclude `tenantId`, `createdBy`, `updatedBy`, `publishedBy`, `sourceDraftId`, deployment record IDs, Flowable deployment/definition IDs and versions, effective-release identity, activation history and runtime instance data.

## Parsing and size limits

The import endpoint accepts one uncompressed JSON request. ZIP or other compressed uploads are not supported.

- request and normalized JSON: 2 MiB maximum;
- JSON depth: 64;
- JSON elements: 30,000;
- JSON string: 64 KiB;
- JSON number token: 100 characters, with exact 32-bit checks for protocol integers;
- Approval DSL nodes: 500;
- condition routes and parallel branches: 2,000 each;
- BPMN: 1 MiB;
- DMN: 512 KiB.

Strict parsing rejects duplicate keys, unknown request/envelope/payload fields, null payloads, non-finite or malformed numbers, overflow, invalid Unicode and unsupported format/version combinations.

## XML safety

BPMN and DMN use namespace-aware parsers with DOCTYPE disabled, external general and parameter entities disabled, XInclude disabled, entity expansion disabled and external DTD/schema access set to empty. BPMN must contain exactly one process whose ID matches the source definition key. Resource names allow only bounded ASCII letters, digits, dot, underscore and hyphen, forbid `..`, slashes and backslashes, and require the `.bpmn20.xml` suffix.

## Import transaction

Import uses `RequestContext`, `Idempotency-Key` and a stable request hash. The JDBC idempotency row, draft and audit event are committed in one transaction. A replay returns the original result; reuse of the key with different content returns conflict.

The server rewrites the DSL to the requested target key, version and name, resolves the target tenant's exact local Form Package/Form Schema/UI Schema, validates and compiles against those local artifacts, then creates only:

- a new UUID draft;
- revision `1`;
- status `DRAFT`;
- one `APPROVAL_DESIGN_DRAFT_IMPORTED` audit event.

Import never creates an immutable Approval Definition Version, compiled artifact, Release Package, deployment, effective release, activation history or runtime instance. It never publishes, deploys or activates automatically.

## Audit and error handling

Audit attributes contain only transfer type/version, source keys and versions, source payload/envelope hashes, target key/version/Form Package version and draft revision. Full DSL, BPMN, DMN and Form data are not logged.

Stable client errors distinguish invalid format, unsupported version, size limits, hash mismatch, artifact integrity, missing source, target Form Package incompatibility, validation failure and import conflict. Internal stacks, SQL errors and full sensitive artifacts are not returned.

## Compatibility

Version 1 is closed at the request, envelope and payload levels: unknown fields and unknown format versions are rejected rather than silently ignored. Future versions require an explicit deterministic migration followed by complete server-side revalidation and rehashing.
""", encoding="utf-8")
PY

rm -f .github/pr53-connected.patch
rm -f .github/scripts/apply-pr53-connected-patch.sh

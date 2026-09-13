/** Public demo form metadata only. The server independently validates all submitted values. */
export const evaluationPurchaseForm = Object.freeze({
  formKey: 'purchase-payment',
  version: 1,
  amount: 12500,
  supplier: 'Demo Industrial Supplies Ltd.',
  initiatorId: 'demo-employee',
})

export function evaluationPurchaseDefaults() {
  return {
    amount: evaluationPurchaseForm.amount,
    supplier: evaluationPurchaseForm.supplier,
    purchaseOrderReference: '',
    attachments: [] as string[],
  }
}

/** Native MONEY inputs return strings; convert only a valid bounded decimal, not arbitrary coercions. */
export function prepareEvaluationPurchase(businessKey: string, values: Record<string, unknown>, actorId: string) {
  if (actorId !== evaluationPurchaseForm.initiatorId) {
    throw new Error('请在试用入口切换为申请人后再发起采购。')
  }
  const identifier = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u
  if (typeof businessKey !== 'string' || !identifier.test(businessKey.trim())
    || typeof values.purchaseOrderReference !== 'string' || !identifier.test(values.purchaseOrderReference.trim())) {
    throw new Error('业务编号和采购单号须为 1 至 128 位字母、数字或 . _ : -。')
  }
  const raw = values.amount
  if (!(typeof raw === 'number' || typeof raw === 'string' && /^(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,2})?$/u.test(raw))
    || Number(raw) !== evaluationPurchaseForm.amount || values.supplier !== evaluationPurchaseForm.supplier) {
    throw new Error('试用采购使用预设金额 12,500.00 和演示供应商。')
  }
  const attachmentIds = values.attachments
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u
  if (!Array.isArray(attachmentIds) || attachmentIds.length < 1 || attachmentIds.length > 4
    || attachmentIds.some(id => typeof id !== 'string' || !uuid.test(id))
    || new Set(attachmentIds).size !== attachmentIds.length) {
    throw new Error('请上传 1 至 4 个不同的附件，等待上传完成后再提交。')
  }
  return {
    businessKey: businessKey.trim(),
    values: {
      amount: Number(raw), supplier: values.supplier,
      purchaseOrderReference: values.purchaseOrderReference.trim(), attachments: [...attachmentIds] as string[],
    },
    startParameters: {
      connectorKey: 'demo-directory',
      initiatorUserId: { source: 'demo-directory', objectType: 'user', value: evaluationPurchaseForm.initiatorId },
      financeReviewerRoleCode: 'FINANCE_REVIEWER',
      financeApproverPositionCode: 'FINANCE_APPROVER',
      maximumFinanceApprovers: 2,
    },
  }
}

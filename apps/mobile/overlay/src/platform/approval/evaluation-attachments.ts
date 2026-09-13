import { getEvaluationBrowserSession, EvaluationClientError } from './evaluation-session'

const maximumFileBytes = 1_048_576 - 4096
const types: Record<string, RegExp> = {
  'application/pdf': /\.pdf$/iu,
  'image/png': /\.png$/iu,
  'image/jpeg': /\.jpe?g$/iu,
  'text/plain': /\.txt$/iu,
}

/** Only native user-selected File objects, never an arbitrary URL or temporary path. */
export function evaluationAttachmentForm(file: File) {
  const pattern = typeof File !== 'undefined' && file instanceof File ? types[file.type] : undefined
  if (typeof File === 'undefined' || !(file instanceof File)
    || file.size < 1 || file.size > maximumFileBytes
    || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/u.test(file.name) || file.name.includes('..')
    || !Object.hasOwn(types, file.type) || !pattern || !pattern.test(file.name)) {
    throw new Error('试用附件仅支持不超过 1020 KiB 的 PDF、PNG、JPEG、TXT；文件名请使用英文字母、数字、点、横线或下划线。')
  }
  const form = new FormData()
  form.append('file', file, file.name)
  return form
}

/** Called synchronously by the existing form button to preserve user activation. */
export function chooseEvaluationAttachmentFiles(count: number): Promise<File[]> {
  if (!Number.isInteger(count) || count < 1 || count > 4 || typeof document === 'undefined') {
    return Promise.reject(new EvaluationClientError('EVALUATION_FILE_SELECTION_REJECTED', 400))
  }
  const client = getEvaluationBrowserSession()
  client.view() // A form must already have initialized its authenticated transport.
  return new Promise((resolve, reject) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.pdf,.png,.jpg,.jpeg,.txt'
    input.multiple = count > 1
    input.hidden = true
    let settled = false
    const finish = (error?: Error, files: File[] = []) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      input.removeEventListener('change', changed)
      input.removeEventListener('cancel', cancelled)
      window.removeEventListener('pagehide', cancelled)
      input.remove()
      if (error) reject(error)
      else resolve(files)
    }
    const cancelled = () => finish(undefined, [])
    const changed = () => {
      try {
        client.view()
        const files = Array.from(input.files || [])
        if (files.length > count) throw new Error(`最多选择 ${count} 个附件。`)
        for (const file of files) evaluationAttachmentForm(file)
        finish(undefined, files)
      } catch (error) { finish(error instanceof Error ? error : new Error('文件选择失败')) }
    }
    const timer = setTimeout(cancelled, 120_000)
    input.addEventListener('change', changed)
    input.addEventListener('cancel', cancelled)
    window.addEventListener('pagehide', cancelled, { once: true })
    document.body.append(input)
    try { input.click() } catch (error) { finish(error instanceof Error ? error : new Error('无法打开文件选择器')) }
  })
}

export async function uploadEvaluationAttachment<T>(file: File, headers: Record<string, string>): Promise<T> {
  const body = evaluationAttachmentForm(file)
  const response = await getEvaluationBrowserSession().fetch('/approval/attachments', {
    method: 'POST', headers, body,
  })
  if (!response.ok) throw new EvaluationClientError('EVALUATION_ATTACHMENT_UPLOAD_FAILED', response.status)
  const value = await response.json() as Record<string, unknown>
  if (!value || typeof value.attachmentId !== 'string'
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(value.attachmentId)
    || value.fileName !== file.name || value.sizeBytes !== file.size || value.contentType !== file.type) {
    throw new EvaluationClientError('EVALUATION_ATTACHMENT_RESPONSE_INVALID', 502)
  }
  return value as T
}

/** Download through the same authenticated transport; no standalone uni.downloadFile. */
export async function downloadEvaluationAttachment(attachmentId: string): Promise<Blob> {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(attachmentId)) {
    throw new EvaluationClientError('EVALUATION_ATTACHMENT_ID_REJECTED', 400)
  }
  const response = await getEvaluationBrowserSession().fetch(`/approval/attachments/${attachmentId}/content`)
  if (!response.ok) throw new EvaluationClientError('EVALUATION_ATTACHMENT_DOWNLOAD_FAILED', response.status)
  return response.blob()
}

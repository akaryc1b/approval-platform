import type { FormPage, PublishedForm } from './form-types'

import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

interface ApiErrorPayload {
  code?: string
  error?: string
  message?: string
}

function joinUrl(baseUrl: string, path: string) {
  const normalized = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
  return `${normalized}${path.startsWith('/') ? path : `/${path}`}`
}

function errorMessage(payload: unknown, statusCode: number) {
  if (payload && typeof payload === 'object') {
    const error = payload as ApiErrorPayload
    return error.message || error.error || error.code || `请求失败（${statusCode}）`
  }
  return `请求失败（${statusCode}）`
}

function request<T>(path: string) {
  const runtime = getApprovalRuntimeConfig()
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: joinUrl(runtime.apiBaseUrl, path),
      header: {
        Accept: 'application/json',
        'X-Operator-Id': runtime.operatorId,
        'X-Tenant-Id': runtime.tenantId,
      },
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as T)
          return
        }
        reject(new Error(errorMessage(response.data, response.statusCode)))
      },
      fail: error => reject(new Error(error.errMsg || '网络请求失败')),
    })
  })
}

export function findForms(keyword = '', limit = 50, offset = 0) {
  const query = [
    `limit=${encodeURIComponent(String(limit))}`,
    `offset=${encodeURIComponent(String(offset))}`,
  ]
  if (keyword.trim()) query.push(`keyword=${encodeURIComponent(keyword.trim())}`)
  return request<FormPage>(`/approval/forms?${query.join('&')}`)
}

export function findForm(formKey: string, version: number) {
  return request<PublishedForm>(
    `/approval/forms/${encodeURIComponent(formKey)}/versions/${version}`,
  )
}

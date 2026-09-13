import { approvalEvaluationEnabled, getEvaluationBrowserSession } from './evaluation-session'

const pages = new Set(['/pages/task/list', '/pages/task/detail', '/pages/initiate/form'])

export function evaluationPageAllowed(url: unknown) {
  return typeof url === 'string' && url.length <= 1024
    && !/[\\\x00-\x20#]/u.test(url) && !url.includes('//')
    && pages.has(url.split('?')[0] || '')
}

export async function verifyEvaluationPage(url: string) {
  if (!approvalEvaluationEnabled()) return true
  if (typeof window === 'undefined') return false
  if (!evaluationPageAllowed(url)) {
    window.location.replace('/evaluation')
    return false
  }
  try { await getEvaluationBrowserSession().verify(); return true }
  catch { window.location.replace('/evaluation'); return false }
}

export const evaluationRouteInterceptor = {
  install() {
    if (!approvalEvaluationEnabled()) return
    const interceptor = {
      invoke({ url }: { url?: string }) {
        if (!evaluationPageAllowed(url)) {
          window.location.replace('/evaluation')
          return false
        }
        try { getEvaluationBrowserSession().view() }
        catch { window.location.replace('/evaluation'); return false }
      },
    }
    for (const name of ['navigateTo', 'reLaunch', 'redirectTo', 'switchTab']) {
      uni.addInterceptor(name, interceptor)
    }
  },
}

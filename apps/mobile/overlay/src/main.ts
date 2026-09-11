import { createSSRApp } from 'vue'
import App from './App.vue'
import { requestInterceptor } from './http/interceptor'
import { installH5Accessibility } from './platform/h5-accessibility'
import { approvalEvaluationEnabled } from './platform/approval/evaluation-session'
import { evaluationRouteInterceptor } from './platform/approval/evaluation-navigation'
import { routeInterceptor } from './router/interceptor'

import store from './store'
import '@/style/index.scss'
import 'virtual:uno.css'

export function createApp() {
  installH5Accessibility()
  const app = createSSRApp(App)
  app.use(store)
  app.use(approvalEvaluationEnabled() ? evaluationRouteInterceptor : routeInterceptor)
  app.use(requestInterceptor)

  return {
    app,
  }
}

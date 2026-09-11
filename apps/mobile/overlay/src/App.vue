<script setup lang="ts">
import { onHide, onLaunch, onShow } from '@dcloudio/uni-app'
import { onMounted, onUnmounted } from 'vue'
import { navigateToInterceptor } from '@/router/interceptor'
import { tabbarStore } from '@/tabbar/store'
import { approvalEvaluationEnabled, getEvaluationBrowserSession } from '@/platform/approval/evaluation-session'
import { verifyEvaluationPage } from '@/platform/approval/evaluation-navigation'

const evaluation = approvalEvaluationEnabled()
onLaunch((options) => {
  if (!evaluation) console.log('App.vue onLaunch', options)
})
onShow((options) => {
  if (evaluation) {
    void verifyEvaluationPage(options?.path ? `/${options.path}` : '/')
    return
  }
  console.log('App.vue onShow', options)
  if (options?.path) {
    navigateToInterceptor.invoke({ url: `/${options.path}`, query: options.query })
  }
  else {
    navigateToInterceptor.invoke({ url: '/' })
  }
  tabbarStore.syncCurIdxByCurrentPageAsync()
})
onHide(() => {
  if (!evaluation) console.log('App Hide')
})

// #ifdef H5
let timer: ReturnType<typeof setInterval> | undefined
let checking = false
function syncTabbarWhenPageVisible() {
  if (document.visibilityState !== 'visible') return
  if (evaluation) {
    if (checking) return
    checking = true
    void getEvaluationBrowserSession().verify().catch(() => {
      window.location.replace('/evaluation')
    }).finally(() => { checking = false })
  }
  else tabbarStore.syncCurIdxByCurrentPageAsync()
}
onMounted(() => {
  document.addEventListener('visibilitychange', syncTabbarWhenPageVisible)
  window.addEventListener('pageshow', syncTabbarWhenPageVisible)
  if (evaluation) timer = setInterval(syncTabbarWhenPageVisible, 20_000)
})
onUnmounted(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', syncTabbarWhenPageVisible)
  window.removeEventListener('pageshow', syncTabbarWhenPageVisible)
})
// #endif
</script>

<style lang="scss">

</style>

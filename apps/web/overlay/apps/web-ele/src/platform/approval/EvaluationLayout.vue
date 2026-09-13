<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { RouterView } from 'vue-router';

import { getEvaluationBrowserSession } from './evaluation-session';

const client = getEvaluationBrowserSession();
const session = client.view();
const actor = session.actors.find(item => item.id === session.actorId)?.displayName || session.actorId;
const remaining = ref(session.expiresInSeconds);
let timer: ReturnType<typeof setInterval> | undefined;
let poll: ReturnType<typeof setInterval> | undefined;
let verifying = false;
async function verifyVisible() {
  if (document.hidden || verifying) return;
  verifying = true;
  try { await client.verify(); }
  catch { window.location.replace('/evaluation'); }
  finally { verifying = false; }
}
onMounted(() => {
  timer = setInterval(() => {
    try { remaining.value = client.view().expiresInSeconds; }
    catch { window.location.replace('/evaluation'); }
  }, 1000);
  poll = setInterval(() => { void verifyVisible(); }, 20_000);
  document.addEventListener('visibilitychange', verifyVisible);
});
onBeforeUnmount(() => {
  clearInterval(timer); clearInterval(poll);
  document.removeEventListener('visibilitychange', verifyVisible);
});
</script>

<template>
  <main>
    <nav class="evaluation-navigation" aria-label="试用导航">
      <strong>采购审批试用</strong>
      <span>{{ actor }} · 剩余 {{ Math.ceil(remaining / 60) }} 分钟</span>
      <a v-if="session.actorId === 'demo-employee'" href="/evaluation/h5/#/pages/initiate/form?formKey=purchase-payment&amp;version=1">发起采购（H5）</a>
      <a href="/evaluation/h5/#/pages/task/list">H5 审批中心</a>
      <a href="/evaluation">切换角色 / 结束试用</a>
    </nav>
    <RouterView />
  </main>
</template>

<style scoped>
.evaluation-navigation { display:flex; flex-wrap:wrap; align-items:center; gap:16px; padding:16px 24px; border-bottom:1px solid #ddd; }
a { text-decoration:underline; text-underline-offset:3px; }
a:focus-visible { outline:2px solid currentColor; outline-offset:4px; }
</style>

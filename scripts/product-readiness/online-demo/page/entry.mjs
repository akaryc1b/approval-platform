/** Session controls and one explicitly enabled pending-task read. No approval/payment writes. */
export function mountEvaluationPage({ document, fetch, now, setInterval, clearInterval } = globalThis) {
  now ??= () => globalThis.performance.now();
  const login = document.querySelector('#login');
  const panel = document.querySelector('#session');
  const message = document.querySelector('#message');
  const actor = document.querySelector('#actor');
  const invitation = document.querySelector('#invitation');
  const submit = login.querySelector('button');
  const end = document.querySelector('#end');
  const refresh = document.querySelector('#refresh');
  const expiry = document.querySelector('#expiry');
  const surface = document.querySelector('#controls');
  const pending = document.querySelector('#pending');
  const taskList = document.querySelector('#pending-tasks');
  const businessNotice = document.querySelector('#business-notice');
  let session = null;
  let deadline = 0;
  let nextCheck = Infinity;
  let revision = 0;
  let busy = false;
  let uncertain = true;
  let disposed = false;
  let request = null;
  const labels = new Map([
    ['INVITATION_REJECTED', '邀请无效、已使用或已过期。'],
    ['NO_EVALUATION_SLOT', '当前没有可用试用位置，请稍后再试。'],
    ['INVITATION_RATE_LIMIT', '尝试过于频繁，请稍后再试。'],
    ['REQUEST_LIMIT', '请求过于频繁，请稍后再试。'],
    ['ACTOR_REJECTED', '无法选择该角色，已恢复原来的选择。'],
  ]);
  const tell = text => { message.textContent = text; };
  function controls() {
    login.hidden = Boolean(session);
    panel.hidden = !session;
    surface.setAttribute('aria-busy', String(busy));
    invitation.disabled = submit.disabled = busy || uncertain || Boolean(session);
    actor.disabled = end.disabled = busy || uncertain || !session;
    refresh.hidden = !uncertain;
    refresh.disabled = busy;
    if (session) actor.value = session.actorId;
    if (pending) {
      pending.hidden = session?.businessAccess !== 'SIGNED_PENDING_READ';
      pending.disabled = busy || uncertain || !session;
    }
    if (businessNotice && session) businessNotice.textContent = session.businessAccess === 'SIGNED_PENDING_READ'
      ? '当前会话已绑定独立环境，可读取所选角色的待办。审批写入与付款尚未开放。'
      : '采购审批业务入口尚未连接，目前不会执行审批或付款。';
  }
  function clear(text, unknown = false) {
    revision += 1;
    session = null; deadline = 0; nextCheck = Infinity; uncertain = unknown;
    invitation.value = ''; actor.replaceChildren(); expiry.textContent = '';
    taskList?.replaceChildren();
    tell(text); controls();
  }
  function show(value, started) {
    if (!value || !['NOT_CONNECTED', 'SIGNED_PENDING_READ'].includes(value.businessAccess)
        || value.scope !== 'DISPOSABLE_EVALUATION_CONTROL_PLANE'
        || !/^[A-Za-z0-9_-]{43}$/u.test(value.csrfToken || '')
        || !Number.isInteger(value.expiresInSeconds) || value.expiresInSeconds < 1
        || value.expiresInSeconds > 1800 || !Array.isArray(value.actors)
        || value.actors.length < 2 || value.actors.length > 8
        || value.actors.some(item => !item || typeof item.id !== 'string'
          || typeof item.displayName !== 'string' || item.displayName.length > 100)
        || new Set(value.actors.map(item => item.id)).size !== value.actors.length
        || !value.actors.some(item => item.id === value.actorId)) {
      throw new Error('SESSION_RESPONSE_INVALID');
    }
    // Polling and role changes cannot extend the last observed absolute deadline.
    const candidate = started + value.expiresInSeconds * 1000;
    deadline = deadline ? Math.min(deadline, candidate) : candidate;
    if (deadline <= now()) { clear('会话已到期，请使用新的邀请。'); return false; }
    if (session?.actorId !== value.actorId || session?.csrfToken !== value.csrfToken) taskList?.replaceChildren();
    session = value; uncertain = false;
    actor.replaceChildren();
    for (const item of value.actors) {
      const option = document.createElement('option');
      option.value = item.id; option.textContent = item.displayName;
      option.selected = item.id === value.actorId; actor.append(option);
    }
    nextCheck = now() + 20_000;
    countdown(); controls();
    return true;
  }
  function countdown() {
    if (!session) return;
    const seconds = Math.ceil((deadline - now()) / 1000);
    if (seconds <= 0) { clear('会话已到期，请使用新的邀请。'); return; }
    expiry.textContent = '会话剩余 ' + Math.floor(seconds / 60) + ' 分 '
      + String(seconds % 60).padStart(2, '0') + ' 秒，到期后不能继续访问。';
  }
  async function execute(path, body, success, { checking = false, ending = false, reading = false } = {}) {
    if (busy || disposed) return;
    const version = ++revision;
    const started = now();
    busy = true; controls();
    const cancellation = new AbortController(); request = cancellation;
    const timeout = setTimeout(() => cancellation.abort(), ending ? 65_000 : 8000);
    try {
      const response = await fetch(path, {
        method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin',
        cache: 'no-store', redirect: 'error', signal: cancellation.signal,
        headers: body === undefined ? (reading ? { 'X-Evaluation-CSRF': session?.csrfToken || '' } : {})
          : { 'Content-Type': 'application/json',
          'X-Evaluation-CSRF': session?.csrfToken || '' },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const result = response.status === 204 ? null : await response.json();
      if (disposed || version !== revision) return;
      if (!response.ok) {
        const code = typeof result?.error === 'string' ? result.error : 'UNKNOWN';
        if (code === 'SESSION_REQUIRED' || code === 'SESSION_READ_REVOKED' || (ending && code === 'RESET_FAILED')) {
          clear(code === 'RESET_FAILED'
            ? '访问凭据已撤销，重置未完成；该位置已暂停分配。'
            : checking && !session ? '请输入一次性邀请以开始。' : '会话已过期，请使用新的邀请。');
        } else if (!checking && labels.has(code)) {
          tell(labels.get(code)); // Rejected writes are never retried automatically.
        } else {
          clear('无法确认当前会话，请重新检查；不会自动重复提交操作。', true);
        }
        return;
      }
      if (reading) {
        if (!session || deadline <= now()) { clear('会话已到期，请使用新的邀请。'); return; }
        if (!result || !Array.isArray(result.items) || result.items.length > 20
            || !Number.isSafeInteger(result.total) || result.total < result.items.length
            || result.limit !== 20 || result.offset !== 0
            || result.items.some(item => !item || typeof item.taskName !== 'string' || item.taskName.length > 512
              || typeof item.businessKey !== 'string' || item.businessKey.length > 512)) {
          throw new Error('PENDING_RESPONSE_INVALID');
        }
        taskList?.replaceChildren();
        for (const item of result.items) {
          const row = document.createElement('li');
          row.textContent = item.taskName + ' · ' + item.businessKey;
          taskList?.append(row);
        }
        tell(result.total === 0 ? '当前角色没有待办。' : '当前角色共 ' + result.total + ' 条待办，仅提供只读查看。');
        return;
      }
      if (ending) { clear('会话已结束。'); return; }
      if (show(result, started) && success) tell(success);
    } catch {
      if (!disposed && version === revision) {
        clear('连接中断或会话无法确认，请重新检查；不会自动重复提交操作。', true);
      }
    } finally {
      clearTimeout(timeout);
      if (request === cancellation) request = null;
      busy = false;
      if (!disposed) controls();
    }
  }
  const verify = () => execute('/evaluation/session', undefined, '', { checking: true });
  const onSubmit = event => {
    event.preventDefault();
    if (busy || uncertain || session) return;
    const value = invitation.value.trim(); invitation.value = '';
    void execute('/evaluation/invitations/redeem', { invitation: value },
      '邀请已使用。请在本会话中选择体验角色。');
  };
  const onActor = () => {
    if (busy || !session) return;
    const selected = actor.value;
    void execute('/evaluation/session/actor', { actorId: selected }, '体验角色已切换。');
  };
  const onPending = () => {
    if (busy || uncertain || session?.businessAccess !== 'SIGNED_PENDING_READ') return;
    void execute('/api/approval/tasks/pending', undefined, '', { reading: true });
  };
  const onEnd = () => {
    if (busy || !session) return;
    tell('正在结束会话并等待重置确认…');
    void execute('/evaluation/session/end', {}, '', { ending: true });
  };
  const onVisible = () => {
    countdown();
    if (!document.hidden && session && !busy) void verify();
  };
  login.addEventListener('submit', onSubmit);
  actor.addEventListener('change', onActor);
  end.addEventListener('click', onEnd);
  refresh.addEventListener('click', verify);
  pending?.addEventListener('click', onPending);
  document.addEventListener('visibilitychange', onVisible);
  const interval = setInterval(() => {
    countdown();
    if (!document.hidden && session && !busy && now() >= nextCheck) void verify();
  }, 1000);
  controls();
  void verify();
  return () => {
    disposed = true; revision += 1; request?.abort(); clearInterval(interval);
    session = null;
    login.removeEventListener('submit', onSubmit);
    actor.removeEventListener('change', onActor);
    end.removeEventListener('click', onEnd);
    refresh.removeEventListener('click', verify);
    pending?.removeEventListener('click', onPending);
    document.removeEventListener('visibilitychange', onVisible);
  };
}

if (typeof document !== 'undefined') mountEvaluationPage();

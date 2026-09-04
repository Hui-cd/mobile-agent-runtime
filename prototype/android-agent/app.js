const phoneContent = document.querySelector("#phoneContent");
const toast = document.querySelector("#toast");

const tasks = {
  normal: {
    prompt: "打开时钟，告诉我现在几点",
    understanding: "打开系统时钟，读取当前时间，然后把结果带回来。",
    app: "时钟",
    scope: "只读取当前页面",
    impact: "不会修改设置或产生外部影响",
    result: "10:42",
    resultCaption: "现在的时间",
    evidence: "页面显示 10:42",
  },
  risk: {
    prompt: "给张三发微信：我大约 10 分钟后到",
    understanding: "打开微信，找到张三，准备发送这条消息。",
    app: "微信 · 张三",
    scope: "查找联系人并填写消息",
    impact: "真正发送前再次向你确认",
    result: "消息已发送",
    resultCaption: "发给微信联系人张三",
    evidence: "聊天页面显示发送成功",
  },
  recovery: {
    prompt: "给张三发微信：我大约 10 分钟后到",
    understanding: "打开微信，找到张三，准备发送这条消息。",
    app: "微信 · 张三",
    scope: "查找联系人并填写消息",
    impact: "遇到阻塞会暂停，不会猜测操作",
    result: "消息已发送",
    resultCaption: "恢复后完成任务",
    evidence: "聊天页面显示发送成功",
  },
};

const state = {
  view: "home",
  flow: "normal",
  task: tasks.normal.prompt,
  timers: [],
  recoveryReady: false,
};

function clearTimers() {
  state.timers.forEach(window.clearTimeout);
  state.timers = [];
}

function escapeHtml(value) {
  return value.replace(/[&<>'"]/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "'": "&#39;",
    '"': "&quot;",
  })[character]);
}

const icons = {
  arrow: '<svg viewBox="0 0 24 24"><path d="M5 12h14M13 6l6 6-6 6"/></svg>',
  back: '<svg viewBox="0 0 24 24"><path d="m15 18-6-6 6-6"/></svg>',
  settings: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1a1.7 1.7 0 0 0 1.9.3A1.7 1.7 0 0 0 10 3V2.8h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z"/></svg>',
  app: '<svg viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="4"/><path d="M8 9h8M8 13h5"/></svg>',
  shield: '<svg viewBox="0 0 24 24"><path d="M12 3 5 6v5c0 4.6 2.7 8.2 7 10 4.3-1.8 7-5.4 7-10V6l-7-3Z"/><path d="m9 12 2 2 4-5"/></svg>',
  lock: '<svg viewBox="0 0 24 24"><rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="m5 12 4 4L19 6"/></svg>',
};

function agentHeader(back = false) {
  return `
    <div class="app-bar">
      ${back ? `<button class="icon-button" data-action="home" aria-label="返回">${icons.back}</button>` : `
        <div class="agent-lockup"><span class="agent-orb"></span><div><p>Mobile Agent</p><small>设备已就绪</small></div></div>`}
      <button class="icon-button" data-action="onboarding" aria-label="设置">${icons.settings}</button>
    </div>`;
}

function controlBadge(label, type = "") {
  return `<div class="control-badge ${type}">${label}</div>`;
}

function renderHome() {
  const task = tasks[state.flow];
  state.task = task.prompt;
  phoneContent.innerHTML = `
    <section class="screen">
      ${agentHeader()}
      <div class="hero">
        <p class="hero-kicker">你的手机助手</p>
        <h2>说出目标，<br />剩下的交给我。</h2>
        <p>我会先说明理解和操作边界。需要你决定时，才会打断你。</p>
      </div>
      <div class="suggestions">
        <button class="suggestion" data-flow="normal">查看当前时间</button>
        <button class="suggestion" data-flow="risk">发送一条消息</button>
      </div>
      <div class="composer">
        <textarea id="taskInput" aria-label="输入任务">${task.prompt}</textarea>
        <div class="composer-footer">
          <span class="privacy-hint">${icons.lock} 仅在任务期间观察屏幕</span>
          <button class="send-button" data-action="review" aria-label="提交任务">${icons.arrow}</button>
        </div>
      </div>
    </section>`;
}

function renderReview() {
  const task = tasks[state.flow];
  const understood = state.task === task.prompt
    ? task.understanding
    : `完成“${escapeHtml(state.task)}”；需要扩大操作范围时，再向你确认。`;
  phoneContent.innerHTML = `
    <section class="screen">
      ${agentHeader(true)}
      ${controlBadge("现在由你决定")}
      <h2 class="state-heading">这是我理解的任务</h2>
      <p class="state-copy">确认目标和边界后，我才会开始操作手机。</p>
      <div class="understanding-card">
        <p class="quote">“${understood}”</p>
        <div class="scope-list">
          <div class="scope-row"><span class="scope-icon">${icons.app}</span><p>访问范围<small>${task.app} · ${task.scope}</small></p></div>
          <div class="scope-row"><span class="scope-icon">${icons.shield}</span><p>影响边界<small>${task.impact}</small></p></div>
        </div>
      </div>
      <div class="action-stack">
        <button class="primary-button" data-action="execute">确认，开始执行</button>
        <button class="secondary-button" data-action="home">调整任务</button>
      </div>
    </section>`;
}

function externalApp() {
  if (state.flow === "normal") {
    return `<div class="clock-face"><div class="clock-time">10:42</div><div class="clock-date">9月4日 · 星期五</div></div>`;
  }
  return `<div class="fake-chat">
    <div class="chat-row"><div class="chat-bubble">你到哪儿了？</div></div>
    <div class="chat-row mine"><div class="chat-bubble">快到了</div></div>
    <div class="chat-row"><div class="chat-bubble">好的，路上慢点</div></div>
  </div>`;
}

function renderExecuting(resumed = false) {
  clearTimers();
  const appName = state.flow === "normal" ? "时钟" : "微信 · 张三";
  const step = resumed ? "正在从保存的进度继续" : state.flow === "normal" ? "正在读取当前时间" : "正在查找联系人";
  phoneContent.innerHTML = `
    <section class="screen external-screen">
      <div class="external-topbar"><span>${appName}</span><small>目标应用保持前台</small></div>
      ${externalApp()}
      <div class="agent-notification">
        <div class="notification-head"><span class="notification-brand"><i class="mini-orb"></i>Mobile Agent</span><small>Agent 正在控制</small></div>
        <h3 id="progressTitle">${step}</h3>
        <p id="progressCopy">步骤 2/3 · 尚未执行其他操作</p>
        <div class="progress-track"><span id="progressBar"></span></div>
        <div class="notification-actions">
          <button data-action="return-agent">返回 Agent</button>
          <button data-action="stop">停止</button>
        </div>
      </div>
    </section>`;

  state.timers.push(window.setTimeout(() => {
    const title = document.querySelector("#progressTitle");
    const copy = document.querySelector("#progressCopy");
    const bar = document.querySelector("#progressBar");
    if (title) title.textContent = state.flow === "normal" ? "已读取页面，正在整理结果" : "已找到张三，等待下一步";
    if (copy) copy.textContent = state.flow === "normal" ? "步骤 3/3 · 只读取了时钟页面" : "步骤 2/3 · 消息尚未发送";
    if (bar) bar.style.width = "82%";
  }, 900));

  state.timers.push(window.setTimeout(() => {
    if (state.flow === "risk" && !resumed) setView("risk");
    else if (state.flow === "recovery" && !resumed) setView("recovery");
    else setView("result");
  }, 2200));
}

function renderRisk() {
  phoneContent.innerHTML = `
    <section class="screen external-screen">
      <div class="external-topbar"><span>微信 · 张三</span><small>消息尚未发送</small></div>
      ${externalApp()}
      <div class="dimmed"></div>
      <div class="bottom-sheet">
        <div class="sheet-handle"></div>
        <div class="risk-symbol">!</div>
        ${controlBadge("控制权已交还给你", "user")}
        <h2>确认发送这条消息？</h2>
        <p>对象：微信联系人张三</p>
        <div class="message-preview">“我大约 10 分钟后到”</div>
        <div class="consequence"><span>!</span><span>确认后将立即对外可见，无法由 Agent 撤回。</span></div>
        <div class="sheet-actions">
          <button class="secondary-button" data-action="review">返回修改</button>
          <button class="danger-button" data-action="approve">确认发送</button>
        </div>
      </div>
    </section>`;
}

function renderRecovery() {
  phoneContent.innerHTML = `
    <section class="screen">
      ${agentHeader(true)}
      ${controlBadge("Agent 已安全暂停", "pause")}
      <h2 class="state-heading">需要你处理一下</h2>
      <p class="state-copy">我不会猜密码，也不会反复点击。处理完成后，可以从当前步骤继续。</p>
      <div class="issue-card">
        <div class="issue-head"><span class="issue-symbol">↗</span><div><h3>微信需要重新登录</h3><p>打开微信完成登录，再返回这里。</p></div></div>
        <div class="saved-progress">${icons.check} 已保存：联系人张三和消息内容</div>
      </div>
      <div class="action-stack">
        <button class="primary-button" data-action="recover">${state.recoveryReady ? "我已登录，继续任务" : "去微信登录"}</button>
        <button class="secondary-button" data-action="stop">结束任务</button>
      </div>
    </section>`;
}

function renderResult() {
  const task = tasks[state.flow];
  const isTime = state.flow === "normal";
  phoneContent.innerHTML = `
    <section class="screen">
      ${agentHeader()}
      <div class="result-mark">${icons.check}</div>
      ${controlBadge("控制权已回到你手中")}
      <h2 class="state-heading">任务完成</h2>
      <div class="result-value">${task.result}</div>
      <p class="result-caption">${task.resultCaption}</p>
      <div class="evidence-card">
        <div class="evidence-source">
          <div class="source-app"><span class="app-icon">${isTime ? "钟" : "微"}</span><div><h3>${isTime ? "系统时钟" : "微信 · 张三"}</h3><small>${task.evidence}</small></div></div>
          <span class="evidence-tag">已验证</span>
        </div>
        <div class="audit-line">${icons.shield}<span>${isTime ? "仅打开并读取时钟，没有修改设备" : "消息只发送了一次，没有执行其他操作"}</span></div>
      </div>
      <div class="action-stack">
        <button class="primary-button" data-action="home">完成</button>
        <button class="secondary-button" data-action="continue">继续追问</button>
      </div>
    </section>`;
}

function renderOnboarding() {
  phoneContent.innerHTML = `
    <section class="screen">
      ${agentHeader(true)}
      <div class="setup-intro">
        <p class="section-label">只需一次</p>
        <h2 class="state-heading">让 Agent 在本机工作</h2>
        <p class="state-copy">先建立模型连接，再授予设备控制。两项权限随时可以关闭。</p>
      </div>
      <div class="setup-card">
        <div class="setup-row"><span class="setup-number">1</span><p>模型连接<small>Kimi K3 · Key 仅保存在本机</small></p><span class="setup-status">已连接</span></div>
        <div class="setup-row"><span class="setup-number">2</span><p>设备控制<small>任务期间观察和操作屏幕</small></p><span class="setup-status">待开启</span></div>
      </div>
      <div class="privacy-card">你主动发起任务时 Agent 才运行；发送、支付、删除等操作仍需要单独确认。</div>
      <div class="action-stack">
        <button class="primary-button" data-action="finish-setup">开启设备控制</button>
        <button class="secondary-button" data-action="home">暂不设置</button>
      </div>
    </section>`;
}

function renderStopped() {
  phoneContent.innerHTML = `
    <section class="screen">
      ${agentHeader()}
      ${controlBadge("任务已停止", "pause")}
      <h2 class="state-heading">已经停下来了</h2>
      <p class="state-copy">Agent 不会继续操作。已经发生的动作会如实保留在记录里。</p>
      <div class="evidence-card"><h3>停止位置</h3><p>步骤 2/3 · ${tasks[state.flow].scope}</p></div>
      <div class="action-stack"><button class="primary-button" data-action="home">返回首页</button></div>
    </section>`;
}

function setView(view, options = {}) {
  clearTimers();
  state.view = view;
  if (view === "home") renderHome();
  if (view === "review") renderReview();
  if (view === "executing") renderExecuting(Boolean(options.resumed));
  if (view === "risk") renderRisk();
  if (view === "recovery") renderRecovery();
  if (view === "result") renderResult();
  if (view === "onboarding") renderOnboarding();
  if (view === "stopped") renderStopped();
}

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("show");
  window.setTimeout(() => toast.classList.remove("show"), 1900);
}

document.addEventListener("click", (event) => {
  const flowButton = event.target.closest("[data-flow]");
  if (flowButton) {
    state.flow = flowButton.dataset.flow;
    state.recoveryReady = false;
    document.querySelectorAll(".scenario").forEach(button => button.classList.toggle("active", button.dataset.flow === state.flow));
    setView("home");
    return;
  }

  const actionButton = event.target.closest("[data-action]");
  if (!actionButton) return;
  const action = actionButton.dataset.action;

  if (action === "home") setView("home");
  if (action === "review") {
    const input = document.querySelector("#taskInput");
    if (input && input.value.trim()) state.task = input.value.trim();
    setView("review");
  }
  if (action === "execute") setView("executing");
  if (action === "return-agent") showToast("任务仍在执行；你可以随时停止");
  if (action === "stop") setView("stopped");
  if (action === "approve") {
    state.flow = "risk";
    setView("executing", { resumed: true });
  }
  if (action === "recover") {
    if (!state.recoveryReady) {
      state.recoveryReady = true;
      renderRecovery();
      showToast("已打开微信（原型演示）");
    } else {
      setView("executing", { resumed: true });
    }
  }
  if (action === "continue") {
    setView("home");
    window.setTimeout(() => document.querySelector("#taskInput")?.focus(), 50);
  }
  if (action === "onboarding") setView("onboarding");
  if (action === "finish-setup") {
    setView("home");
    showToast("设备控制已开启");
  }
});

renderHome();

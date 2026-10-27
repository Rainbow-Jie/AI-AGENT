/* ============================================================
   对话页
   ============================================================ */
(function () {

    if (!Store.token) {
        location.replace('index.html');
        return;
    }

    const S = {
        agents: [],
        sessions: [],
        agentId: null,
        sessionId: null,
        streaming: false,
        user: Store.user || {}
    };

    const el = {
        scroll: document.getElementById('chat-scroll'),
        welcome: document.getElementById('welcome'),
        cards: document.getElementById('agent-cards'),
        list: document.getElementById('session-list'),
        input: document.getElementById('input'),
        send: document.getElementById('btn-send'),
        topName: document.getElementById('top-name'),
        topSub: document.getElementById('top-sub'),
        topAvatar: document.getElementById('top-avatar'),
        picker: document.getElementById('agent-picker'),
        apTrigger: document.getElementById('ap-trigger'),
        apName: document.getElementById('ap-name'),
        apMark: document.getElementById('ap-mark'),
        apPop: document.getElementById('ap-pop'),
        chip: document.getElementById('user-chip'),
        acctPop: document.getElementById('acct-pop')
    };

    /* ---------------- 启动 ---------------- */
    async function boot() {
        renderUserChip();
        // 先把用户资料（含头像）拉准，再渲染历史和会话：
        // 否则消息里的头像会先画成「我」字方块，等接口回来才闪成图片
        try {
            S.user = await http.get('/api/auth/me');
            Store.user = S.user;
            renderUserChip();
        } catch (e) { /* 登录失效时 http 内部已跳回登录页 */ }
        bindEvents();
        try {
            S.agents = await http.get('/api/agents');
        } catch (e) {
            UI.err(e.message);
            return;
        }
        renderAgentCards();
        renderAgentPicker();
        await loadSessions();

        const last = localStorage.getItem('ah_last_agent');
        if (last && S.agents.some((a) => a.id === last)) {
            selectAgent(last, false);
        } else if (S.agents.length === 1) {
            selectAgent(S.agents[0].id, false);
        }
    }

    function renderUserChip() {
        const name = S.user.nickname || S.user.username || '-';
        const role = S.user.role === 'ADMIN' ? '管理员' : '普通用户';
        document.getElementById('user-name').textContent = name;
        document.getElementById('user-role').textContent = role;
        document.getElementById('user-avatar').innerHTML = UI.userAvatar(S.user);
        document.getElementById('acct-nm').textContent = name;
        document.getElementById('acct-rl').textContent = (S.user.username || '') + ' · ' + role;
        if (S.user.role !== 'ADMIN') {
            document.getElementById('admin-entry').style.display = 'none';
        }
        // 头像可能比消息晚到（boot 里历史消息先渲染），拿到后把已画出来的补齐
        syncUserAvatars();
    }

    /** 用户消息左侧那个小方块：设过头像就用图片，没设过还是「我」字 */
    function meAvatarInner() {
        const u = S.user || {};
        if (u.avatar) return `<img src="${UI.esc(u.avatar)}" alt="">`;
        return '我';
    }

    function syncUserAvatars() {
        const u = S.user || {};
        const html = meAvatarInner();
        const name = u.nickname || u.username || '我';
        document.querySelectorAll('.msg-wrap.user .msg-ava').forEach((box) => {
            if (box.innerHTML !== html) box.innerHTML = html;
            box.title = name;
            // 照片态换成细柔边，黑描边留给「我」字态，不然人像看着像贴纸
            box.classList.toggle('has-img', !!u.avatar);
        });
    }

    function bindEvents() {
        el.send.onclick = send;
        el.input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
                e.preventDefault();
                send();
            }
        });
        el.input.addEventListener('input', autoGrow);
        document.getElementById('btn-new-chat').onclick = () => newChat();

        el.apTrigger.onclick = (e) => {
            e.stopPropagation();
            togglePicker();
        };
        // 点击面板外任意位置、或按 Esc 收起
        document.addEventListener('click', (e) => {
            if (el.picker.classList.contains('open') && !el.picker.contains(e.target)) {
                closePicker();
            }
            if (el.acctPop && !el.acctPop.hidden
                && !el.acctPop.contains(e.target) && !el.chip.contains(e.target)) {
                closeAcct();
            }
        });
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                closePicker();
                closeAcct();
            }
        });

        // 账号菜单：向上弹出，个人中心 / 退出登录
        // （页面结构若还是旧的缓存版本，这里直接跳过，不要因为取不到节点就整页报错）
        if (el.chip && el.acctPop) {
            el.chip.onclick = (e) => {
                e.stopPropagation();
                if (el.acctPop.hidden) openAcct();
                else closeAcct();
            };
            el.chip.onkeydown = (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    el.chip.click();
                }
            };
        }
        const logout = document.getElementById('acct-logout');
        if (logout) {
            logout.onclick = async () => {
                closeAcct();
                try {
                    await http.post('/api/auth/logout');
                } catch (e) { /* ignore */ }
                Store.clear();
                location.replace('index.html');
            };
        }
    }

    function openAcct() {
        if (!el.acctPop) return;
        el.acctPop.hidden = false;
        el.chip.setAttribute('aria-expanded', 'true');
    }

    function closeAcct() {
        if (!el.acctPop) return;
        el.acctPop.hidden = true;
        el.chip.setAttribute('aria-expanded', 'false');
    }

    function autoGrow() {
        el.input.style.height = 'auto';
        el.input.style.height = Math.min(el.input.scrollHeight, 160) + 'px';
    }

    /* ---------------- 智能体 ---------------- */
    /**
     * 智能体头像。
     * 数据库 avatar 字段允许填自定义 emoji，但留空时不能回落到 🤖——
     * emoji 是彩色位图，跟全站的线框图标不是一个语言，会把整体质感拉回去。
     * 所以留空统一给 SVG 机器人图标。
     */
    function avatarHtml(agent, size) {
        const raw = agent && agent.avatar ? String(agent.avatar).trim() : '';
        if (raw) {
            return `<span class="emoji-ava">${UI.esc(raw)}</span>`;
        }
        return Icon('robot', size || 18);
    }

    function renderAgentCards() {
        if (!S.agents.length) {
            el.cards.innerHTML = `<div class="notice warning" style="text-align:left">
                ${Icon('alert', 15)}<div>还没有可用的智能体，请联系管理员在后台创建。</div></div>`;
            return;
        }
        el.cards.innerHTML = S.agents.map((a) => `
            <div class="agent-card" data-id="${a.id}">
                <div class="ico">${avatarHtml(a, 20)}</div>
                <div class="nm">${UI.esc(a.name)}</div>
                <div class="ds">${UI.esc(a.description || '暂无简介')}</div>
                ${a.kbNames ? `<span class="tag">${Icon('book', 12)}${UI.esc(a.kbNames)}</span>` : ''}
            </div>`).join('');
        el.cards.querySelectorAll('[data-id]').forEach((card) => {
            card.onclick = () => {
                selectAgent(card.dataset.id, true);
                el.input.focus();
            };
        });
    }

    /* ---------------- 智能体选择器（自绘下拉） ---------------- */
    function renderAgentPicker() {
        const cur = currentAgent();
        el.apName.textContent = cur ? cur.name : '选择智能体';
        el.apMark.innerHTML = cur ? avatarHtml(cur, 13) : Icon('robot', 13);
        el.apPop.innerHTML = S.agents.length ? S.agents.map((a) => `
            <div class="ap-item ${a.id === S.agentId ? 'active' : ''}" data-id="${a.id}" role="option">
                <span class="ap-item-mark">${avatarHtml(a, 14)}</span>
                <span class="ap-txt">
                    <span class="nm">${UI.esc(a.name)}</span>
                    <span class="ds">${UI.esc(a.kbNames ? '知识库：' + a.kbNames : (a.description || '通用对话'))}</span>
                </span>
                <span class="ap-tick">${Icon('check', 14)}</span>
            </div>`).join('')
            : `<div class="ap-empty">还没有可用的智能体</div>`;
        el.apPop.querySelectorAll('[data-id]').forEach((item) => {
            item.onclick = (e) => {
                e.stopPropagation();
                closePicker();
                if (item.dataset.id !== S.agentId) {
                    selectAgent(item.dataset.id, true);
                }
            };
        });
    }

    function togglePicker() {
        if (el.picker.classList.contains('open')) {
            closePicker();
        } else {
            el.picker.classList.add('open');
            el.apTrigger.setAttribute('aria-expanded', 'true');
        }
    }

    function closePicker() {
        el.picker.classList.remove('open');
        el.apTrigger.setAttribute('aria-expanded', 'false');
    }

    function currentAgent() {
        return S.agents.find((a) => a.id === S.agentId) || null;
    }

    function selectAgent(id, startNew) {
        S.agentId = id;
        localStorage.setItem('ah_last_agent', id);
        const a = currentAgent();
        if (a) {
            el.topName.textContent = a.name;
            el.topSub.textContent = a.kbNames ? '知识库：' + a.kbNames : '通用对话（未绑定知识库）';
            el.topAvatar.innerHTML = avatarHtml(a, 19);
            el.input.placeholder = `向「${a.name}」提问…`;
        }
        renderAgentPicker();
        if (startNew) {
            newChat();
        }
    }

    /* ---------------- 会话 ---------------- */
    async function loadSessions() {
        try {
            S.sessions = await http.get('/api/sessions');
        } catch (e) {
            S.sessions = [];
        }
        renderSessions();
    }

    function renderSessions() {
        if (!S.sessions.length) {
            el.list.innerHTML = `<div class="list-empty">
                <div class="ico">${Icon('chat', 20)}</div>
                <div class="t">还没有对话记录</div>
                <div class="hint">在下方输入问题就能开始</div>
            </div>`;
            return;
        }
        el.list.innerHTML = S.sessions.map((s) => `
            <div class="session-item ${s.id === S.sessionId ? 'active' : ''}" data-sid="${s.id}">
                ${Icon('chat', 14)}
                <span class="t" title="${UI.esc(s.title || '')}">${UI.esc(s.title || '未命名对话')}</span>
                <button class="del" data-del="${s.id}" title="删除">${Icon('close', 12)}</button>
            </div>`).join('');

        el.list.querySelectorAll('[data-sid]').forEach((item) => {
            item.onclick = (e) => {
                if (e.target.dataset.del) return;
                openSession(item.dataset.sid);
            };
        });
        el.list.querySelectorAll('[data-del]').forEach((btn) => {
            btn.onclick = (e) => {
                e.stopPropagation();
                const sid = btn.dataset.del;
                UI.confirm('确定删除这个对话？删除后不可恢复。', async () => {
                    await http.del('/api/sessions/' + sid);
                    if (S.sessionId === sid) {
                        newChat();
                    }
                    await loadSessions();
                    UI.ok('已删除');
                }, true);
            };
        });
    }

    async function openSession(sid) {
        if (S.streaming) {
            UI.err('正在回答中，请稍候');
            return;
        }
        let msgs;
        try {
            msgs = await http.get(`/api/sessions/${sid}/messages`);
        } catch (e) {
            UI.err(e.message);
            return;
        }
        S.sessionId = sid;
        const session = S.sessions.find((x) => x.id === sid);
        if (session && session.agentId && S.agents.some((a) => a.id === session.agentId)) {
            selectAgent(session.agentId, false);
        }
        hideWelcome();
        el.scroll.innerHTML = '';
        if (!msgs.length) {
            el.scroll.innerHTML = '<div class="empty">这个对话还没有消息</div>';
        }
        msgs.forEach((m) => {
            if (m.role === 'user') {
                appendUser(m.content, m.createdAt);
            } else {
                appendAssistant(m.content, m.refs, m.createdAt, m.toolLog, m.usage);
            }
        });
        renderSessions();
        scrollBottom();
    }

    function newChat() {
        if (S.streaming) {
            UI.err('正在回答中，请稍候');
            return;
        }
        S.sessionId = null;
        el.scroll.innerHTML = '';
        el.scroll.appendChild(el.welcome);
        el.welcome.style.display = '';
        renderSessions();
        el.input.value = '';
        autoGrow();
        el.input.focus();
    }

    function hideWelcome() {
        if (el.welcome.parentNode) {
            el.welcome.parentNode.removeChild(el.welcome);
        }
    }

    /* ---------------- 消息渲染 ---------------- */
    function timeNow() {
        const d = new Date();
        const p = (n) => (n < 10 ? '0' + n : '' + n);
        return `${p(d.getHours())}:${p(d.getMinutes())}`;
    }

    /**
     * 用户消息。时间行右侧挂一个「复制」按钮：
     * 复制的原始提问（markdown 源码语义），点完直接把焦点给输入框，
     * 想改一改再问一遍时 Ctrl+V 就行，不用重新敲。
     */
    function appendUser(content, time) {
        const wrap = document.createElement('div');
        wrap.className = 'msg-wrap user';
        wrap.innerHTML = `
            <div class="msg-ava${S.user && S.user.avatar ? ' has-img' : ''}" title="${UI.esc((S.user && (S.user.nickname || S.user.username)) || '我')}">${meAvatarInner()}</div>
            <div class="msg-main">
                <div class="bubble md">${renderMarkdown(content)}</div>
                <div class="msg-time">
                    <span class="t">${UI.esc(time ? String(time).slice(11, 16) : timeNow())}</span>
                    <button type="button" class="msg-act" title="复制这条提问，可直接粘贴到输入框">
                        ${Icon('copy', 12)}<span>复制</span>
                    </button>
                </div>
            </div>`;
        const btn = wrap.querySelector('.msg-act');
        btn.onclick = async () => {
            try {
                await UI.copyText(content);
                btn.classList.add('done');
                btn.innerHTML = Icon('check', 12) + '<span>已复制</span>';
                el.input.focus();
                setTimeout(() => {
                    btn.classList.remove('done');
                    btn.innerHTML = Icon('copy', 12) + '<span>复制</span>';
                }, 1600);
            } catch (e) {
                UI.err(e.message || '复制失败');
            }
        };
        el.scroll.appendChild(wrap);
        scrollBottom();
        return wrap;
    }

    function appendAssistant(content, refs, time, toolLog, usage) {
        const agent = currentAgent();
        const wrap = document.createElement('div');
        wrap.className = 'msg-wrap assistant';
        wrap.innerHTML = `
            <div class="msg-ava">${avatarHtml(agent, 16)}</div>
            <div class="msg-main">
                <div class="tool-list"></div>
                <div class="bubble md"></div>
                ${refs && refs.length ? '<div class="refs"></div>' : ''}
                <div class="msg-time">${UI.esc(time ? String(time).slice(11, 16) : timeNow())}<span class="msg-usage"></span></div>
            </div>`;
        const bubble = wrap.querySelector('.bubble');
        const tools = wrap.querySelector('.tool-list');
        bubble.innerHTML = content ? renderMarkdown(content) : '<span class="dots"></span>';
        if (refs && refs.length) {
            renderRefChips(wrap.querySelector('.refs'), refs);
        }
        // 回看历史时把当时的工具调用轨迹还原出来（默认折叠）
        if (toolLog && toolLog.length) {
            toolLog.forEach((rec) => tools.appendChild(buildToolCard(rec)));
        }
        if (usage) {
            renderUsage(wrap.querySelector('.msg-usage'), usage);
        }
        el.scroll.appendChild(wrap);
        scrollBottom();
        return {wrap: wrap, bubble: bubble, tools: tools};
    }

    /**
     * 展示本轮真实 token 消耗。
     * 光看总数容易误判：DeepSeek 缓存命中价是未命中的 1/30，
     * 所以「3 万 token」可能只花几分钱。这里同时给出估算费用。
     */
    function renderUsage(box, u) {
        if (!box || !u || !u.totalTokens) return;
        const fmt = (n) => Number(n || 0).toLocaleString();
        const cost = estimateCost(u);
        let text = `↑${fmt(u.promptTokens)} ↓${fmt(u.completionTokens)} · ${fmt(u.totalTokens)} tokens`;
        let tip = `输入 ${fmt(u.promptTokens)} ｜ 输出 ${fmt(u.completionTokens)}`;
        if (u.cacheHitRate !== undefined && u.cacheHitRate !== null) {
            text += ` · 缓存命中 ${u.cacheHitRate}%`;
            tip += `\n缓存命中 ${fmt(u.cacheHitTokens)} ｜ 未命中 ${fmt(u.cacheMissTokens)}`;
        }
        if (cost > 0) {
            text += ` · ≈${fmtCost(cost)}`;
            tip += `\n预估费用 ${fmtCost(cost)}（按${isPeakTime() ? '高峰' : '空闲'}时段价）`;
        }
        box.textContent = ' · ' + text;
        box.title = tip;
    }

    /* ---------------- 工具调用卡片 ---------------- */

    const TOOL_LABELS = {
        fs_list: '列目录',
        fs_read: '读文件',
        fs_write: '写文件',
        fs_mkdir: '建目录',
        fs_delete: '删除',
        fs_move: '移动',
        fs_search: '搜索',
        shell_run: '执行命令'
    };

    function toolSummary(argsJson) {
        let args = {};
        try {
            args = JSON.parse(argsJson || '{}') || {};
        } catch (e) {
            return String(argsJson || '').slice(0, 50);
        }
        if (args.command) {
            return shorten(String(args.command));
        }
        if (args.path) {
            return shorten(String(args.path));
        }
        if (args.pattern) {
            return shorten(String(args.pattern));
        }
        if (args.from) {
            return shorten(String(args.from) + ' → ' + (args.to || ''));
        }
        return '';
    }

    function shorten(s) {
        return s.length > 62 ? s.slice(0, 62) + '…' : s;
    }

    function prettyJson(s) {
        if (!s) return '(无)';
        try {
            return JSON.stringify(JSON.parse(s), null, 2);
        } catch (e) {
            return String(s);
        }
    }

    /**
     * 展示用的工具输出。
     * shell_run 的后端输出首行是「$ 命令」，那是给模型看的上下文，
     * 界面上不需要暴露命令本身，这里把它剥掉，只留退出码与实际输出。
     */
    function displayOutput(name, out) {
        if (!out) return out;
        if (name === 'shell_run') {
            return String(out).replace(/^\$[^\n]*\n?/, '');
        }
        return out;
    }

    function buildToolCard(rec) {
        const running = rec.ok === undefined;
        const state = running ? 'running' : (rec.ok ? 'ok' : 'fail');
        // shell_run 的命令串（参数、返回里的回显）对使用者没有信息价值，
        // 只会把界面搞成终端日志，所以头部既不显示函数名也不显示命令内容。
        const isShell = rec.name === 'shell_run';
        const box = document.createElement('div');
        box.className = 'tool-card';
        box.dataset.state = state;
        box.dataset.tool = rec.name || '';
        box.innerHTML = `
            <div class="tool-head">
                <span class="tool-dot"></span>
                <b class="tool-title">${UI.esc(TOOL_LABELS[rec.name] || rec.name)}</b>
                ${isShell
            ? '<span class="tool-gap"></span>'
            : `<code class="tool-code">${UI.esc(rec.name || '')}</code>
                       <span class="tool-arg">${UI.esc(toolSummary(rec.args))}</span>`}
                <span class="tool-status">${running ? '执行中…' : (rec.ok ? '完成' : '失败')}</span>
                <span class="tool-caret">${Icon('chevronRight', 13)}</span>
            </div>
            <div class="tool-body">
                ${isShell ? '' : `<div class="tool-sec">
                    <div class="tool-sec-t">参数</div>
                    <pre class="tool-pre">${UI.esc(prettyJson(rec.args))}</pre>
                </div>`}
                <div class="tool-sec">
                    <div class="tool-sec-t">结果</div>
                    <pre class="tool-pre tool-pre-out">${UI.esc(displayOutput(rec.name, rec.output) || (running ? '等待返回…' : '（无输出）'))}</pre>
                </div>
            </div>`;
        box.querySelector('.tool-head').onclick = () => {
            // 箭头方向交给 CSS 的 .open 选择器，这里不再手动换字符
            box.classList.toggle('open');
        };
        // 失败的调用默认展开，让人一眼看到被拦的原因（历史回看时也一样）
        if (rec.ok === false) {
            box.classList.add('open');
        }
        return box;
    }

    function updateToolCard(box, evt) {
        box.dataset.state = evt.ok ? 'ok' : 'fail';
        box.querySelector('.tool-status').textContent =
            (evt.ok ? '完成' : '失败') + (evt.elapsedMs != null ? ' · ' + evt.elapsedMs + 'ms' : '');
        // 结果区永远带 tool-pre-out，不能按下标取——shell_run 卡片没有参数区，只有一块 pre
        const out = box.querySelector('.tool-pre-out');
        if (out) {
            out.textContent = displayOutput(box.dataset.tool, evt.output) || '（无输出）';
        }
        // 失败时自动展开，省得用户还要点一下才知道错在哪
        if (!evt.ok) {
            box.classList.add('open');
        }
    }

    function renderRefChips(box, refs) {
        if (!box) return;
        const LIMIT = 6;
        const expanded = box.dataset.expanded === '1';
        const shown = (refs.length > LIMIT && !expanded) ? refs.slice(0, LIMIT) : refs;
        const more = refs.length > LIMIT
            ? `<span class="ref-chip ref-more" data-more="1">${expanded ? '收起' : `还有 ${refs.length - LIMIT} 条`}</span>`
            : '';
        box.innerHTML = shown.map((r, i) => `
            <span class="ref-chip" data-ref="${i}">${Icon('file', 12)}${UI.esc(r.docName)} #${(r.chunkIndex || 0) + 1}</span>`).join('') + more;
        box.querySelectorAll('[data-ref]').forEach((chip) => {
            chip.onclick = () => {
                const r = refs[Number(chip.dataset.ref)];
                UI.modal({
                    title: '引用片段 · ' + r.docName,
                    width: 'wide',
                    body: `<div style="font-size:12.5px;color:var(--muted);margin-bottom:10px">
                             相关度 ${r.score} · 片段 #${(r.chunkIndex || 0) + 1}</div>
                           <div style="white-space:pre-wrap;font-size:13.5px;line-height:1.8">${UI.esc(r.content)}</div>`,
                    okText: '关闭',
                    showCancel: false,
                    onOk: async () => {
                    }
                });
            };
        });
        const toggle = box.querySelector('[data-more]');
        if (toggle) {
            toggle.onclick = () => {
                box.dataset.expanded = expanded ? '0' : '1';
                renderRefChips(box, refs);
            };
        }
    }

    function appendError(msg) {
        const wrap = document.createElement('div');
        wrap.className = 'msg-wrap assistant';
        wrap.innerHTML = `
            <div class="msg-ava">${Icon('alert', 16)}</div>
            <div class="msg-main">
                <div class="bubble msg-error">${UI.esc(msg)}</div>
                <div class="msg-time">${timeNow()}</div>
            </div>`;
        el.scroll.appendChild(wrap);
        scrollBottom();
    }

    function scrollBottom() {
        el.scroll.scrollTop = el.scroll.scrollHeight;
    }

    /* ---------------- 发送 ---------------- */
    async function send() {
        const question = el.input.value.trim();
        if (!question) return;
        if (S.streaming) {
            UI.err('上一条还在回答中，请稍候');
            return;
        }
        if (!S.agentId) {
            UI.err('请先在上方选择智能体');
            return;
        }

        hideWelcome();
        el.input.value = '';
        autoGrow();
        S.streaming = true;
        el.send.disabled = true;

        appendUser(question);
        const node = appendAssistant('');
        let acc = '';
        let errorMsg = '';
        let timer = null;
        const toolCards = {};

        const flush = () => {
            node.bubble.innerHTML = renderMarkdown(acc) + '<span class="caret"></span>';
            scrollBottom();
        };

        try {
            await streamChat({
                agentId: S.agentId,
                sessionId: S.sessionId,
                question: question
            }, {
                onMeta: (e) => {
                    if (e.sessionId && e.sessionId !== S.sessionId) {
                        S.sessionId = e.sessionId;
                        loadSessions();
                    }
                    if (e.refs && e.refs.length) {
                        const refsBox = node.wrap.querySelector('.refs');
                        if (refsBox) renderRefChips(refsBox, e.refs);
                    }
                },
                onDelta: (text) => {
                    acc += text;
                    if (!timer) {
                        timer = setTimeout(() => {
                            timer = null;
                            flush();
                        }, 45);
                    }
                },
                onReset: () => {
                    // 模型把工具调用写成了正文，后端已识别：清空已显示的部分
                    acc = '';
                    node.bubble.innerHTML = '<span class="dots"></span>';
                },
                onToolCall: (e) => {
                    const card = buildToolCard({name: e.name, args: e.args});
                    if (e.id) {
                        toolCards[e.id] = card;
                    }
                    node.tools.appendChild(card);
                    scrollBottom();
                },
                onToolResult: (e) => {
                    const card = toolCards[e.id];
                    if (card) {
                        updateToolCard(card, e);
                    }
                    scrollBottom();
                },
                onDone: (e) => {
                    // 本轮真实用量（多次模型调用累加），直接挂在消息末尾
                    if (e && e.usage) {
                        renderUsage(node.wrap.querySelector('.msg-usage'), e.usage);
                    }
                },
                onError: (msg) => {
                    errorMsg = msg;
                }
            });
        } catch (e) {
            errorMsg = e.message || '对话失败';
        } finally {
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            S.streaming = false;
            el.send.disabled = false;

            if (errorMsg) {
                node.wrap.remove();
                appendError(errorMsg);
            } else if (!acc) {
                node.bubble.innerHTML = '<span style="color:var(--muted)">（模型没有返回内容）</span>';
            } else {
                node.bubble.innerHTML = renderMarkdown(acc);
            }
            scrollBottom();
            loadSessions();
        }
    }

    boot();

    // 从个人中心返回时页面多半走了 bfcache，脚本不会重跑：
    // 这里补一次同步，改了头像/昵称回来能立刻看到
    window.addEventListener('pageshow', (e) => {
        if (!e.persisted || !Store.user) return;
        S.user = Store.user;
        renderUserChip();
    });
})();

/* ============================================================
   管理后台
   ============================================================ */
(function () {

    if (!Store.token) {
        location.replace('index.html');
        return;
    }

    const App = {
        view: 'dashboard',
        kbId: null,
        user: Store.user || {},
        settings: {}
    };

    const VIEWS = ['dashboard', 'agents', 'models', 'kb', 'users', 'settings'];

    /* ---------------- 启动 ---------------- */
    async function boot() {
        if (App.user.role !== 'ADMIN') {
            UI.err('当前账号不是管理员，正在跳转到对话页');
            setTimeout(() => location.replace('chat.html'), 1200);
            return;
        }
        renderUserChip();
        document.getElementById('nav').addEventListener('click', (e) => {
            const item = e.target.closest('.nav-item');
            if (!item || !item.dataset.view) return;
            App.kbId = null;
            go(item.dataset.view);
        });
        document.getElementById('user-chip').addEventListener('click', userMenu);

        // 站点名
        try {
            App.settings = await http.get('/api/admin/settings');
            if (App.settings.siteName) {
                document.getElementById('brand-name').textContent = App.settings.siteName;
                document.title = App.settings.siteName + ' · 管理后台';
            }
        } catch (e) { /* ignore */ }

        const hash = (location.hash || '').replace('#', '');
        // 费用要按实时单价算，先把价格拉回来再渲染（失败会自动回落到内置价）
        await loadPricing();
        go(VIEWS.indexOf(hash) >= 0 ? hash : 'dashboard');

        // 支持通过 location.hash 直接切换（仪表盘里的快捷按钮）
        window.addEventListener('hashchange', () => {
            const v = (location.hash || '').replace('#', '');
            if (VIEWS.indexOf(v) >= 0 && v !== App.view) {
                App.kbId = null;
                go(v);
            }
        });

        // 定时校验登录态
        try {
            const me = await http.get('/api/auth/me');
            App.user = me;
            Store.user = me;
            renderUserChip();
        } catch (e) { /* 401 已由 api() 处理 */ }
    }

    function renderUserChip() {
        const name = App.user.nickname || App.user.username || '-';
        document.getElementById('user-name').textContent = name;
        document.getElementById('user-role').textContent = App.user.role === 'ADMIN' ? '管理员' : '普通用户';
        document.getElementById('user-avatar').innerHTML = UI.userAvatar(App.user);
    }

    function userMenu() {
        UI.modal({
            title: '账号',
            body: `
                <div style="margin-bottom:16px">
                    <div style="font-size:15px;font-weight:600">${UI.esc(App.user.nickname || App.user.username)}</div>
                    <div style="color:var(--muted);font-size:12.5px">${UI.esc(App.user.username)} · ${App.user.role === 'ADMIN' ? '管理员' : '普通用户'}</div>
                </div>
                <div class="field">
                    <label>原密码</label>
                    <input class="input" id="pm-old" type="password" placeholder="输入当前密码">
                </div>
                <div class="field">
                    <label>新密码</label>
                    <input class="input" id="pm-new" type="password" placeholder="至少 6 位">
                </div>`,
            okText: '修改密码',
            onOk: async (mask) => {
                const oldPwd = mask.querySelector('#pm-old').value;
                const newPwd = mask.querySelector('#pm-new').value;
                if (!oldPwd || !newPwd) throw new Error('请填写原密码与新密码');
                await http.post('/api/auth/password', {oldPassword: oldPwd, newPassword: newPwd});
                UI.ok('密码已修改');
            }
        });
        setTimeout(() => {
            const foot = document.querySelector('#ah-modal .modal-foot');
            if (!foot) return;
            const group = document.createElement('div');
            group.style.cssText = 'margin-right:auto;display:flex;gap:8px';

            const me = document.createElement('a');
            me.className = 'btn btn-sm';
            me.href = 'profile.html';
            me.style.textDecoration = 'none';
            me.innerHTML = Icon('user', 14) + '<span>个人中心</span>';
            me.style.display = 'inline-flex';

            const out = document.createElement('button');
            out.className = 'btn btn-sm';
            out.innerHTML = Icon('logout', 14) + '<span>退出登录</span>';
            out.onclick = () => {
                http.post('/api/auth/logout').catch(() => {});
                Store.clear();
                location.replace('index.html');
            };

            group.appendChild(me);
            group.appendChild(out);
            foot.insertBefore(group, foot.firstChild);
        }, 0);
    }

    function go(view) {
        App.view = view;
        location.hash = view;
        document.querySelectorAll('.nav-item[data-view]').forEach((el) => {
            el.classList.toggle('active', el.dataset.view === view);
        });
        render();
    }

    function render() {
        const el = document.getElementById('view');
        el.innerHTML = '<div class="empty">加载中…</div>';
        const fn = {
            dashboard: viewDashboard,
            agents: viewAgents,
            models: viewModels,
            kb: viewKb,
            users: viewUsers,
            settings: viewSettings
        }[App.view];
        Promise.resolve(fn()).catch((e) => {
            el.innerHTML = `<div class="notice error">${Icon('alert', 15)}<div>${UI.esc(e.message || '加载失败')}</div></div>`;
        });
    }

    function head(title, desc, actions) {
        return `<div class="page-head">
            <div>
                <h2>${UI.esc(title)}</h2>
                <p class="desc">${UI.esc(desc || '')}</p>
            </div>
            <div style="display:flex;gap:8px;flex-shrink:0">${actions || ''}</div>
        </div>`;
    }

    /* ============================================================
       仪表盘
       ============================================================ */
    async function viewDashboard() {
        const d = await http.get('/api/admin/dashboard');
        const s = d.stat || {};
        const warnings = d.warnings || [];
        const models = d.models || [];

        let html = head('仪表盘', '平台运行概览',
            `<a class="btn" href="chat.html">${Icon('chat', 15)}去对话</a>`);

        if (warnings.length) {
            html += warnings.map((w) => {
                const ico = w.level === 'info' ? 'info' : 'alert';
                return `<div class="notice ${w.level}">${Icon(ico, 15)}<div>${UI.esc(w.text)}</div></div>`;
            }).join('');
        }

        const cards = [
            ['用户', s.userCount, '人', 'users', 'tone-violet'],
            ['智能体', s.agentCount, '个', 'robot', 'tone-blue'],
            ['知识库', s.kbCount, '个', 'book', 'tone-teal'],
            ['文档', s.docCount, '份', 'file', 'tone-amber'],
            ['知识切片', s.chunkCount, '条', 'layers', 'tone-rose'],
            ['会话', s.sessionCount, '个', 'chat', 'tone-slate'],
            ['消息', s.messageCount, '条', 'activity', 'tone-violet']
        ];
        html += '<div class="stat-grid">' + cards.map((c) => `
            <div class="stat">
                <div class="stat-ico ${c[4]}">${Icon(c[3], 15)}</div>
                <div class="k">${c[0]}</div>
                <div class="v">${c[1] == null ? 0 : c[1]}<span class="u">${c[2]}</span></div>
            </div>`).join('') + '</div>';

        // Token 消耗总览
        const t = d.token || {};
        if (t.totalTokens) {
            const fmt = (n) => Number(n || 0).toLocaleString();
            const cost = estimateCost(t);
            const tcards = [
                ['输入 token', fmt(t.promptTokens), 'zap', 'tone-blue'],
                ['输出 token', fmt(t.completionTokens), 'activity', 'tone-violet'],
                ['合计 token', fmt(t.totalTokens), 'layers', 'tone-slate'],
                ['缓存命中率', t.cacheHitRate == null ? '—' : t.cacheHitRate + '%', 'gauge', 'tone-teal'],
                ['预估费用', fmtCost(cost), 'coins', 'tone-amber']
            ];
            html += '<div class="stat-grid">' + tcards.map((c) => `
                <div class="stat">
                    <div class="stat-ico ${c[3]}">${Icon(c[2], 15)}</div>
                    <div class="k">${c[0]}</div>
                    <div class="v">${c[1]}</div>
                </div>`).join('') + '</div>';
            if (t.cacheHitRate != null) {
                html += `<div class="notice info">${Icon('info', 15)}<div>缓存命中的输入按未命中的 1/30 计价。
                    累计命中 ${fmt(t.cacheHitTokens)}、未命中 ${fmt(t.cacheMissTokens)}；
                    费用按 DeepSeek ${isPeakTime() ? '高峰' : '空闲'}时段价估算，
                    所以「合计 token」看着大，实际花费通常是这个数的一小部分。</div></div>`;
            }
        }

        html += `<div class="row">
            <div class="card" style="flex:1.4">
                <div class="card-head">
                    <h3>${Icon('cpu', 16)}模型接入状态</h3>
                    <button class="btn btn-sm" onclick="location.hash='models'">管理配置</button>
                </div>
                <div class="card-body" style="padding:0">
                    ${models.length ? `<table class="table">
                        <thead><tr><th>名称</th><th>模型</th><th>密钥</th><th>默认</th></tr></thead>
                        <tbody>${models.map((m) => `<tr>
                            <td><b>${UI.esc(m.name)}</b></td>
                            <td><code>${UI.esc(m.model)}</code></td>
                            <td>${m.hasKey ? '<span class="badge badge-green">已配置</span>' : '<span class="badge badge-red">未配置</span>'}</td>
                            <td>${m.isDefault === 1 ? '<span class="badge">默认</span>' : '<span class="badge badge-gray">-</span>'}</td>
                        </tr>`).join('')}</tbody>
                    </table>` : `<div class="empty"><div class="ico" data-icon="cpu"></div>还没有模型配置</div>`}
                </div>
            </div>
            <div class="card" style="flex:1">
                <div class="card-head"><h3>${Icon('sparkles', 16)}快速开始</h3></div>
                <div class="card-body" style="display:flex;flex-direction:column;gap:9px">
                    <button class="btn quick-step" onclick="location.hash='models'"><span class="step-num">1</span>配置 DeepSeek API Key</button>
                    <button class="btn quick-step" onclick="location.hash='kb'"><span class="step-num">2</span>创建知识库并上传文档</button>
                    <button class="btn quick-step" onclick="location.hash='agents'"><span class="step-num">3</span>创建智能体并绑定知识库</button>
                    <a class="btn quick-step" style="text-decoration:none" href="chat.html"><span class="step-num">4</span>开始对话</a>
                </div>
            </div>
        </div>`;

        document.getElementById('view').innerHTML = html;
    }

    /* ============================================================
       智能体
       ============================================================ */
    /**
     * 工具能力徽章：全权用红色，提醒这是个能执行命令的智能体
     */
    function toolBadge(a) {
        const mode = a.toolMode || 'off';
        if (mode === 'off') {
            return '<span class="badge badge-gray">纯对话</span>';
        }
        const map = {
            read: ['只读', 'badge-gray'],
            write: ['读写', 'badge-amber'],
            full: ['全权·可执行', 'badge-red']
        };
        const item = map[mode] || map.read;
        return `<span class="badge ${item[1]}" title="${UI.esc(a.toolScopeDesc || '')}">${item[0]}</span>`;
    }

    async function viewAgents() {
        const list = await http.get('/api/admin/agents');
        let html = head('智能体', '配置角色设定、绑定模型与知识库',
            `<button class="btn btn-primary" id="btn-new-agent">${Icon('plus', 15)}新建智能体</button>`);

        if (!list.length) {
            html += '<div class="card"><div class="empty"><div class="ico" data-icon="robot"></div>还没有智能体，点右上角新建一个</div></div>';
        } else {
            html += `<div class="card"><table class="table">
                <thead><tr><th>名称</th><th class="col-desc">描述</th><th>模型</th><th>知识库</th><th>工具能力</th><th>状态</th><th style="width:150px">操作</th></tr></thead>
                <tbody>${list.map((a) => `<tr>
                    <td>
                        <div style="display:flex;align-items:center;gap:8px">
                            <span class="row-ava">${a.avatar ? UI.esc(a.avatar) : Icon('robot', 17)}</span>
                            <b>${UI.esc(a.name)}</b>
                        </div>
                    </td>
                    <td class="col-desc" style="color:var(--muted);max-width:260px">${UI.esc((a.description || '-').slice(0, 60))}</td>
                    <td><code style="font-size:12px">${UI.esc(a.modelName || '-')}</code></td>
                    <td>${a.kbNames ? UI.esc(a.kbNames) : '<span class="badge badge-gray">未绑定</span>'}</td>
                    <td>${toolBadge(a)}</td>
                    <td>${a.status === 1 ? '<span class="badge badge-green">启用</span>' : '<span class="badge badge-gray">停用</span>'}</td>
                    <td>
                        <button class="btn btn-sm" data-edit="${a.id}">编辑</button>
                        <button class="btn btn-sm btn-danger" data-del="${a.id}">删除</button>
                    </td>
                </tr>`).join('')}</tbody>
            </table></div>`;
        }
        document.getElementById('view').innerHTML = html;

        document.getElementById('btn-new-agent').onclick = () => agentDialog(null);

        document.querySelectorAll('[data-edit]').forEach((btn) => {
            btn.onclick = () => {
                const a = list.find((x) => x.id === btn.dataset.edit);
                agentDialog(a);
            };
        });
        document.querySelectorAll('[data-del]').forEach((btn) => {
            btn.onclick = () => {
                const a = list.find((x) => x.id === btn.dataset.del);
                UI.confirm(`确定删除智能体「${a.name}」？已产生的会话不会被删除。`, async () => {
                    await http.del('/api/admin/agents/' + a.id);
                    UI.ok('已删除');
                    viewAgents();
                }, true);
            };
        });
    }

    async function agentDialog(agent) {
        const [models, kbs] = await Promise.all([
            http.get('/api/admin/models'),
            http.get('/api/admin/kb')
        ]);
        const a = agent || {};
        const boundIds = (a.kbIds || '').split(',').filter(Boolean);

        const body = `
            <div class="row">
                <div class="field" style="flex:0 0 90px">
                    <label>头像</label>
                    <input class="input" id="f-avatar" value="${UI.esc(a.avatar || '')}"
                           placeholder="默认图标" title="留空使用默认的机器人图标；填 emoji 则显示该字符"
                           style="text-align:center;font-size:18px">
                </div>
                <div class="field">
                    <label>名称 <span style="color:var(--danger)">*</span></label>
                    <input class="input" id="f-name" value="${UI.esc(a.name || '')}" placeholder="如：产品手册助手">
                </div>
            </div>
            <div class="field">
                <label>简介</label>
                <input class="input" id="f-desc" value="${UI.esc(a.description || '')}" placeholder="一句话说明这个智能体能做什么">
            </div>
            <div class="field">
                <label>角色设定（系统提示词）</label>
                <textarea class="textarea" id="f-prompt" style="min-height:120px"
                    placeholder="你是一名资深客服，回答要简洁、准确，不确定时明确说明。">${UI.esc(a.systemPrompt || '')}</textarea>
                <div class="hint">这段设定决定智能体的说话风格与边界；知识库内容会以「参考资料」形式附加在它后面。</div>
            </div>
            <div class="row">
                <div class="field">
                    <label>使用模型</label>
                    <select class="select" id="f-llm">
                        <option value="">跟随默认模型</option>
                        ${models.map((m) => `<option value="${m.id}" ${a.llmConfigId === m.id ? 'selected' : ''}>
                            ${UI.esc(m.name)}（${UI.esc(m.model)}）${m.isDefault === 1 ? ' · 默认' : ''}
                        </option>`).join('')}
                    </select>
                </div>
                <div class="field" style="flex:0 0 130px">
                    <label>检索条数</label>
                    <input class="input" id="f-topk" type="number" min="1" max="20" value="${a.topK || 4}">
                </div>
            </div>
            <div class="field">
                <label>绑定知识库</label>
                ${kbs.length ? `<div style="display:flex;flex-wrap:wrap;gap:8px;padding:10px;border:1px solid var(--border);border-radius:8px;background:var(--panel-2)">
                    ${kbs.map((k) => `<label style="display:flex;align-items:center;gap:6px;font-size:13px;cursor:pointer">
                        <input type="checkbox" class="kb-check" value="${k.id}" ${boundIds.indexOf(k.id) >= 0 ? 'checked' : ''}>
                        ${UI.esc(k.name)} <span style="color:var(--muted);font-size:11.5px">(${k.chunkCount || 0} 切片)</span>
                    </label>`).join('')}
                </div>` : '<div class="hint">还没有知识库，可先到「知识库」创建。</div>'}
                <div class="hint">不绑定知识库时，智能体只依赖模型自身知识回答。</div>
            </div>
            <div class="field">
                <label>开场白</label>
                <input class="input" id="f-welcome" value="${UI.esc(a.welcomeMsg || '')}" placeholder="进入对话时展示的第一句话">
            </div>
            <div class="field">
                <label>工具能力</label>
                <select class="select" id="f-tool-mode">
                    <option value="off" ${!a.toolMode || a.toolMode === 'off' ? 'selected' : ''}>关闭 · 纯对话，只能回答</option>
                    <option value="read" ${a.toolMode === 'read' ? 'selected' : ''}>只读 · 可读文件、列目录、搜索</option>
                    <option value="write" ${a.toolMode === 'write' ? 'selected' : ''}>读写 · 可新建/修改文件（不能执行命令）</option>
                    <option value="full" ${a.toolMode === 'full' ? 'selected' : ''}>全权 · 可读写 + 执行命令</option>
                </select>
                <div class="hint">开启后模型才能真正动文件。默认关闭，建议按需给，别一上来就全权。</div>
            </div>
            <div id="tool-extra" style="display:none;border-top:1px dashed var(--border);padding-top:14px;margin-top:2px">
                <div class="field">
                    <label>工作目录（沙箱根）</label>
                    <input class="input" id="f-workdir" value="${UI.esc(a.workDir || '')}"
                        placeholder="如 D:\\HEXO\\LOCAL\\AI\\agent-workspace">
                    <div class="hint">所有文件读写和命令执行都被限制在这个目录内，越界路径一律拒绝。留空用默认 workspace。</div>
                </div>
                <div class="row">
                    <div class="field">
                        <label>命令白名单</label>
                        <input class="input" id="f-allow-cmds" value="${UI.esc(a.allowCmds || '')}"
                            placeholder="如：git, mvn, node, python">
                        <div class="hint">留空则用内置黑名单兜底（较宽松）；建议按需填死</div>
                    </div>
                    <div class="field" style="flex:0 0 120px">
                        <label>单次超时(秒)</label>
                        <input class="input" id="f-timeout" type="number" min="1" max="300" value="${a.toolTimeout || 30}">
                    </div>
                    <div class="field" style="flex:0 0 110px">
                        <label>最多轮次</label>
                        <input class="input" id="f-maxsteps" type="number" min="1" max="20" value="${a.maxSteps || 6}">
                    </div>
                </div>
                <div class="field" style="margin-bottom:0">
                    <label>额外禁止规则</label>
                    <input class="input" id="f-deny-cmds" value="${UI.esc(a.denyCmds || '')}"
                        placeholder="如：npm publish, git push, deploy">
                    <div class="hint">命令里只要包含这些片段就直接拒绝，比白名单更灵活。</div>
                </div>
            </div>
            <div class="field">
                <label>状态</label>
                <select class="select" id="f-status">
                    <option value="1" ${a.status !== 0 ? 'selected' : ''}>启用</option>
                    <option value="0" ${a.status === 0 ? 'selected' : ''}>停用</option>
                </select>
            </div>`;

        UI.modal({
            title: agent ? '编辑智能体' : '新建智能体',
            body: body,
            width: 'wide',
            onOpen: (mask) => {
                const sel = mask.querySelector('#f-tool-mode');
                const extra = mask.querySelector('#tool-extra');
                const sync = () => {
                    extra.style.display = sel.value === 'off' ? 'none' : 'block';
                };
                sel.onchange = sync;
                sync();
            },
            onOk: async (mask) => {
                const name = mask.querySelector('#f-name').value.trim();
                if (!name) throw new Error('请填写名称');
                const kbIds = Array.from(mask.querySelectorAll('.kb-check:checked')).map((c) => c.value);
                const toolMode = mask.querySelector('#f-tool-mode').value;
                if (toolMode !== 'off') {
                    const dir = mask.querySelector('#f-workdir').value.trim();
                    if (!dir) throw new Error('开启工具后必须填写工作目录');
                }
                const payload = {
                    id: a.id || undefined,
                    name: name,
                    avatar: mask.querySelector('#f-avatar').value.trim(),
                    description: mask.querySelector('#f-desc').value.trim(),
                    systemPrompt: mask.querySelector('#f-prompt').value,
                    llmConfigId: mask.querySelector('#f-llm').value,
                    kbIds: kbIds.join(','),
                    welcomeMsg: mask.querySelector('#f-welcome').value.trim(),
                    topK: Number(mask.querySelector('#f-topk').value) || 4,
                    status: Number(mask.querySelector('#f-status').value),
                    toolMode: toolMode,
                    workDir: mask.querySelector('#f-workdir').value.trim(),
                    allowCmds: mask.querySelector('#f-allow-cmds').value.trim(),
                    denyCmds: mask.querySelector('#f-deny-cmds').value.trim(),
                    toolTimeout: Number(mask.querySelector('#f-timeout').value) || 30,
                    maxSteps: Number(mask.querySelector('#f-maxsteps').value) || 6
                };
                await http.post('/api/admin/agents/save', payload);
                UI.ok('已保存');
                viewAgents();
            }
        });
    }

    /* ============================================================
       模型配置
       ============================================================ */
    async function viewModels() {
        const list = await http.get('/api/admin/models');
        let html = head('模型配置', '支持 DeepSeek、OpenAI 及任何 OpenAI 兼容接口',
            `<button class="btn btn-primary" id="btn-new-model">${Icon('plus', 15)}新增模型</button>`);

        if (!list.length) {
            html += '<div class="card"><div class="empty"><div class="ico" data-icon="cpu"></div>还没有模型配置</div></div>';
        } else {
            html += `<div class="card"><table class="table">
                <thead><tr><th>名称</th><th>模型</th><th>Base URL</th><th>密钥</th><th>默认</th><th>状态</th><th style="width:190px">操作</th></tr></thead>
                <tbody>${list.map((m) => `<tr>
                    <td><b>${UI.esc(m.name)}</b><div style="font-size:11.5px;color:var(--muted)">${UI.esc(m.provider || '')}</div></td>
                    <td><code style="font-size:12px">${UI.esc(m.model)}</code></td>
                    <td style="font-size:12px;color:var(--muted);max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${UI.esc(m.baseUrl)}</td>
                    <td>${m.apiKeyMask ? `<code style="font-size:11.5px">${UI.esc(m.apiKeyMask)}</code>` : '<span class="badge badge-red">未配置</span>'}</td>
                    <td>${m.isDefault === 1 ? '<span class="badge">默认</span>' : '<span class="badge badge-gray">-</span>'}</td>
                    <td>${m.status === 1 ? '<span class="badge badge-green">启用</span>' : '<span class="badge badge-gray">停用</span>'}</td>
                    <td>
                        <button class="btn btn-sm" data-test="${m.id}">测试</button>
                        <button class="btn btn-sm" data-edit="${m.id}">编辑</button>
                        <button class="btn btn-sm btn-danger" data-del="${m.id}">删除</button>
                    </td>
                </tr>`).join('')}</tbody>
            </table></div>`;
        }

        html += `<div class="notice info" style="margin-top:16px">
            ${Icon('info', 15)}
            <div>DeepSeek 官方地址填 <code>https://api.deepseek.com</code>，
            模型名只接受 <code>deepseek-flash</code>（通用对话）或 <code>deepseek-v4-pro</code>（更强推理）。
            <b>注意填 API 模型名，不是产品名</b>（如不能写 deepseek-V4.1-Flash，会直接报 400）。
            API Key 在 <a href="https://platform.deepseek.com/api_keys" target="_blank">platform.deepseek.com</a> 申请。</div>
        </div>`;

        document.getElementById('view').innerHTML = html;
        document.getElementById('btn-new-model').onclick = () => modelDialog(null);

        document.querySelectorAll('[data-edit]').forEach((btn) => {
            btn.onclick = () => modelDialog(list.find((x) => x.id === btn.dataset.edit));
        });
        document.querySelectorAll('[data-del]').forEach((btn) => {
            btn.onclick = () => {
                const m = list.find((x) => x.id === btn.dataset.del);
                UI.confirm(`确定删除模型配置「${m.name}」？`, async () => {
                    await http.del('/api/admin/models/' + m.id);
                    UI.ok('已删除');
                    viewModels();
                }, true);
            };
        });
        document.querySelectorAll('[data-test]').forEach((btn) => {
            btn.onclick = async () => {
                btn.disabled = true;
                btn.textContent = '测试中';
                try {
                    const r = await http.post('/api/admin/models/test', {id: btn.dataset.test});
                    UI.ok('连接成功，模型回复：' + (r.reply || '(空)'));
                } catch (e) {
                    UI.err(e.message || '连接失败');
                } finally {
                    btn.disabled = false;
                    btn.textContent = '测试';
                }
            };
        });
    }

    const PRESETS = {
        deepseek: {baseUrl: 'https://api.deepseek.com', model: 'deepseek-flash', provider: 'deepseek'},
        openai: {baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', provider: 'openai'},
        silicon: {baseUrl: 'https://api.siliconflow.cn/v1', model: 'Qwen/Qwen2.5-72B-Instruct', provider: 'custom'},
        ollama: {baseUrl: 'http://127.0.0.1:11434/v1', model: 'qwen2.5:7b', provider: 'custom'}
    };

    function modelDialog(model) {
        const m = model || {};
        const body = `
            <div class="field">
                <label>快速填充</label>
                <div style="display:flex;gap:8px;flex-wrap:wrap">
                    <button type="button" class="btn btn-sm" data-preset="deepseek">DeepSeek 官方</button>
                    <button type="button" class="btn btn-sm" data-preset="silicon">硅基流动</button>
                    <button type="button" class="btn btn-sm" data-preset="openai">OpenAI</button>
                    <button type="button" class="btn btn-sm" data-preset="ollama">本地 Ollama</button>
                </div>
            </div>
            <div class="row">
                <div class="field">
                    <label>配置名称 <span style="color:var(--danger)">*</span></label>
                    <input class="input" id="m-name" value="${UI.esc(m.name || '')}" placeholder="如：DeepSeek 官方">
                </div>
                <div class="field">
                    <label>提供方</label>
                    <select class="select" id="m-provider">
                        <option value="deepseek">deepseek</option>
                        <option value="openai">openai</option>
                        <option value="custom">custom（兼容接口）</option>
                    </select>
                </div>
            </div>
            <div class="field">
                <label>Base URL <span style="color:var(--danger)">*</span></label>
                <input class="input" id="m-base" value="${UI.esc(m.baseUrl || 'https://api.deepseek.com')}" placeholder="https://api.deepseek.com">
                <div class="hint">无需带 /chat/completions，系统会自动补全。</div>
            </div>
            <div class="field">
                <label>API Key <span style="color:var(--danger)">*</span></label>
                <input class="input" id="m-key" type="password" value="${UI.esc(m.apiKeyMask || '')}" placeholder="sk-...">
                <div class="hint">${m.id ? '留空或不改动则保持原密钥。' : '密钥仅保存在本地 SQLite，接口返回时会脱敏。'}</div>
            </div>
            <div class="field">
                <label>模型名称 <span style="color:var(--danger)">*</span></label>
                <input class="input" id="m-model" value="${UI.esc(m.model || 'deepseek-flash')}" placeholder="deepseek-flash">
                <div class="hint">必须与接口支持的模型名完全一致，否则调用会返回 400。</div>
            </div>
            <div class="row">
                <div class="field">
                    <label>温度（0-1.5）</label>
                    <input class="input" id="m-temp" type="number" step="0.1" min="0" max="1.5" value="${m.temperature == null ? 0.7 : m.temperature}">
                </div>
                <div class="field">
                    <label>最大输出 tokens</label>
                    <input class="input" id="m-max" type="number" min="128" max="32000" value="${m.maxTokens || 2048}">
                </div>
            </div>
            <div class="row">
                <div class="field">
                    <label>设为默认</label>
                    <select class="select" id="m-default">
                        <option value="0" ${m.isDefault === 1 ? '' : 'selected'}>否</option>
                        <option value="1" ${m.isDefault === 1 ? 'selected' : ''}>是</option>
                    </select>
                </div>
                <div class="field">
                    <label>状态</label>
                    <select class="select" id="m-status">
                        <option value="1" ${m.status !== 0 ? 'selected' : ''}>启用</option>
                        <option value="0" ${m.status === 0 ? 'selected' : ''}>停用</option>
                    </select>
                </div>
            </div>
            <div class="field">
                <label>备注</label>
                <input class="input" id="m-remark" value="${UI.esc(m.remark || '')}" placeholder="选填">
            </div>
            <div style="display:flex;align-items:center;gap:10px;padding-top:4px">
                <button type="button" class="btn" id="m-test">${Icon('activity', 14)}测试连接</button>
                <span id="m-test-result" style="font-size:12.5px;color:var(--muted)"></span>
            </div>`;

        const collect = (mask) => ({
            id: m.id || undefined,
            name: mask.querySelector('#m-name').value.trim(),
            provider: mask.querySelector('#m-provider').value,
            baseUrl: mask.querySelector('#m-base').value.trim(),
            apiKey: mask.querySelector('#m-key').value.trim(),
            model: mask.querySelector('#m-model').value.trim(),
            temperature: Number(mask.querySelector('#m-temp').value),
            maxTokens: Number(mask.querySelector('#m-max').value),
            isDefault: Number(mask.querySelector('#m-default').value),
            status: Number(mask.querySelector('#m-status').value),
            remark: mask.querySelector('#m-remark').value.trim()
        });

        UI.modal({
            title: model ? '编辑模型配置' : '新增模型配置',
            body: body,
            width: 'wide',
            onOk: async (mask) => {
                const payload = collect(mask);
                if (!payload.name) throw new Error('请填写配置名称');
                if (!payload.baseUrl) throw new Error('请填写 Base URL');
                if (!payload.model) throw new Error('请填写模型名称');
                await http.post('/api/admin/models/save', payload);
                UI.ok('已保存');
                viewModels();
            },
            onOpen: (mask) => {
                mask.querySelector('#m-provider').value = m.provider || 'deepseek';
                mask.querySelectorAll('[data-preset]').forEach((btn) => {
                    btn.onclick = () => {
                        const p = PRESETS[btn.dataset.preset];
                        mask.querySelector('#m-base').value = p.baseUrl;
                        mask.querySelector('#m-model').value = p.model;
                        mask.querySelector('#m-provider').value = p.provider;
                        if (!mask.querySelector('#m-name').value) {
                            mask.querySelector('#m-name').value = btn.textContent.trim();
                        }
                    };
                });
                mask.querySelector('#m-test').onclick = async () => {
                    const btn = mask.querySelector('#m-test');
                    const out = mask.querySelector('#m-test-result');
                    btn.disabled = true;
                    btn.textContent = '测试中…';
                    out.textContent = '';
                    try {
                        const r = await http.post('/api/admin/models/test', collect(mask));
                        out.style.color = 'var(--success)';
                        out.textContent = '连接成功，模型回复：' + (r.reply || '(空)');
                    } catch (e) {
                        out.style.color = 'var(--danger)';
                        out.textContent = e.message || '连接失败';
                    } finally {
                        btn.disabled = false;
                        btn.innerHTML = Icon('activity', 14) + '测试连接';
                    }
                };
            }
        });
    }

    /* ============================================================
       知识库
       ============================================================ */
    async function viewKb() {
        if (App.kbId) {
            return viewKbDetail(App.kbId);
        }
        const list = await http.get('/api/admin/kb');
        let html = head('知识库', '上传文档后自动切片建索引，供智能体检索引用',
            `<button class="btn btn-primary" id="btn-new-kb">${Icon('plus', 15)}新建知识库</button>`);

        if (!list.length) {
            html += '<div class="card"><div class="empty"><div class="ico" data-icon="book"></div>还没有知识库，先建一个再上传文档</div></div>';
        } else {
            html += '<div class="kb-grid">' + list.map((k) => `
                <div class="kb-card" data-kb="${k.id}">
                    <h4>${Icon('book', 15)}${UI.esc(k.name)}</h4>
                    <div class="d">${UI.esc(k.description || '暂无描述')}</div>
                    <div class="f">
                        <span><b>${k.docCount || 0}</b> 文档</span>
                        <span><b>${k.chunkCount || 0}</b> 切片</span>
                        <span style="margin-left:auto">${UI.fmtTime(k.createdAt)}</span>
                    </div>
                </div>`).join('') + '</div>';
        }

        document.getElementById('view').innerHTML = html;
        document.getElementById('btn-new-kb').onclick = () => kbDialog(null);
        document.querySelectorAll('[data-kb]').forEach((card) => {
            card.onclick = () => {
                App.kbId = card.dataset.kb;
                render();
            };
        });
    }

    function kbDialog(kb) {
        const k = kb || {};
        UI.modal({
            title: kb ? '编辑知识库' : '新建知识库',
            body: `
                <div class="field">
                    <label>名称 <span style="color:var(--danger)">*</span></label>
                    <input class="input" id="k-name" value="${UI.esc(k.name || '')}" placeholder="如：产品手册">
                </div>
                <div class="field">
                    <label>描述</label>
                    <textarea class="textarea" id="k-desc" placeholder="这个知识库放什么内容">${UI.esc(k.description || '')}</textarea>
                </div>`,
            onOk: async (mask) => {
                const name = mask.querySelector('#k-name').value.trim();
                if (!name) throw new Error('请填写名称');
                await http.post('/api/admin/kb/save', {
                    id: k.id || undefined,
                    name: name,
                    description: mask.querySelector('#k-desc').value.trim()
                });
                UI.ok('已保存');
                viewKb();
            }
        });
    }

    async function viewKbDetail(kbId) {
        const [kbs, docs] = await Promise.all([
            http.get('/api/admin/kb'),
            http.get(`/api/admin/kb/${kbId}/docs`)
        ]);
        const kb = kbs.find((x) => x.id === kbId);
        if (!kb) {
            App.kbId = null;
            return viewKb();
        }

        let html = head(kb.name, kb.description || '知识库详情', `
            <button class="btn" id="btn-back">${Icon('arrowLeft', 15)}返回列表</button>
            <button class="btn" id="btn-edit-kb">${Icon('pencil', 14)}编辑</button>
            <button class="btn btn-danger" id="btn-del-kb">${Icon('trash', 14)}删除</button>
        `);

        html += `<div class="row" style="align-items:flex-start">
            <div style="flex:1.6;min-width:0">
                <div class="card">
                    <div class="card-head"><h3>${Icon('file', 16)}文档（${docs.length}）</h3>
                        <label class="btn btn-primary btn-sm" style="margin:0">
                            ${Icon('upload', 14)}上传文档
                            <input type="file" id="file-input" multiple hidden
                                   accept=".txt,.md,.markdown,.pdf,.docx,.csv,.log,.json,.sql">
                        </label>
                    </div>
                    <div class="card-body" style="padding:16px">
                        <div class="file-drop" id="drop-zone">
                            把文件拖到这里，或点击上方按钮选择<br>
                            <span style="font-size:12px">支持 txt / md / pdf / docx / csv，单文件 ≤ 30MB</span>
                        </div>
                    </div>
                    <div style="padding:0">
                        ${docs.length ? `<table class="table">
                            <thead><tr><th>文件名</th><th>大小</th><th>字符</th><th>切片</th><th>上传时间</th><th style="width:130px">操作</th></tr></thead>
                            <tbody>${docs.map((d) => `<tr>
                                <td style="max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${UI.esc(d.name)}</td>
                                <td style="color:var(--muted)">${UI.fmtSize(d.fileSize)}</td>
                                <td style="color:var(--muted)">${d.charCount || 0}</td>
                                <td><span class="badge">${d.chunkCount || 0}</span></td>
                                <td style="color:var(--muted);font-size:12px">${UI.fmtTime(d.createdAt)}</td>
                                <td>
                                    <button class="btn btn-sm" data-chunks="${d.id}" data-name="${UI.esc(d.name)}">切片</button>
                                    <button class="btn btn-sm btn-danger" data-deldoc="${d.id}">删除</button>
                                </td>
                            </tr>`).join('')}</tbody>
                        </table>` : '<div class="empty"><div class="ico" data-icon="file"></div>还没有文档</div>'}
                    </div>
                </div>
            </div>
            <div style="flex:1;min-width:280px">
                <div class="card">
                    <div class="card-head"><h3>${Icon('search', 16)}检索测试</h3></div>
                    <div class="card-body">
                        <div class="field">
                            <textarea class="textarea" id="search-q" style="min-height:70px" placeholder="输入一句话，看看能召回哪些片段"></textarea>
                        </div>
                        <button class="btn btn-primary" id="btn-search" style="width:100%">检索</button>
                        <div id="search-result" style="margin-top:14px"></div>
                    </div>
                </div>
            </div>
        </div>`;

        document.getElementById('view').innerHTML = html;

        document.getElementById('btn-back').onclick = () => {
            App.kbId = null;
            render();
        };
        document.getElementById('btn-edit-kb').onclick = () => kbDialog(kb);
        document.getElementById('btn-del-kb').onclick = () => {
            UI.confirm(`确定删除知识库「${kb.name}」？其下所有文档和切片都会被清除，且不可恢复。`, async () => {
                await http.del('/api/admin/kb/' + kb.id);
                UI.ok('已删除');
                App.kbId = null;
                render();
            }, true);
        };

        const fileInput = document.getElementById('file-input');
        const dropZone = document.getElementById('drop-zone');
        dropZone.onclick = () => fileInput.click();
        fileInput.onchange = () => uploadFiles(Array.from(fileInput.files), kbId);

        dropZone.addEventListener('dragover', (e) => {
            e.preventDefault();
            dropZone.classList.add('over');
        });
        dropZone.addEventListener('dragleave', () => dropZone.classList.remove('over'));
        dropZone.addEventListener('drop', (e) => {
            e.preventDefault();
            dropZone.classList.remove('over');
            uploadFiles(Array.from(e.dataTransfer.files), kbId);
        });

        document.querySelectorAll('[data-deldoc]').forEach((btn) => {
            btn.onclick = () => {
                UI.confirm('确定删除该文档及其全部切片？', async () => {
                    await http.del('/api/admin/kb/doc/' + btn.dataset.deldoc);
                    UI.ok('已删除');
                    render();
                }, true);
            };
        });

        document.querySelectorAll('[data-chunks]').forEach((btn) => {
            btn.onclick = async () => {
                btn.disabled = true;
                try {
                    const chunks = await http.get(`/api/admin/kb/doc/${btn.dataset.chunks}/chunks`);
                    UI.modal({
                        title: '切片预览 · ' + btn.dataset.name,
                        width: 'wide',
                        body: chunks.length ? chunks.map((c) => `
                            <div class="chunk-item">
                                <div class="h"><span>#${c.chunkIndex + 1}</span><span>${c.charLen} 字</span></div>
                                <div class="c">${UI.esc(c.content)}</div>
                            </div>`).join('') : '<div class="empty">没有切片</div>',
                        okText: '关闭',
                        showCancel: false,
                        onOk: async () => {
                        }
                    });
                } catch (e) {
                    UI.err(e.message);
                } finally {
                    btn.disabled = false;
                }
            };
        });

        document.getElementById('btn-search').onclick = async () => {
            const q = document.getElementById('search-q').value.trim();
            const box = document.getElementById('search-result');
            if (!q) {
                UI.err('请输入检索内容');
                return;
            }
            box.innerHTML = '<div style="color:var(--muted);font-size:12.5px">检索中…</div>';
            try {
                const r = await api('POST', `/api/admin/kb/${kbId}/search?query=${encodeURIComponent(q)}&topK=5`);
                if (!r.hits || !r.hits.length) {
                    box.innerHTML = '<div class="notice info">没有召回任何片段。可尝试换关键词，或确认文档是否已上传成功。</div>';
                    return;
                }
                box.innerHTML = r.hits.map((h, i) => `
                    <div class="chunk-item">
                        <div class="h">
                            <span>#${i + 1} ${UI.esc(h.docName)}</span>
                            <span>score ${h.score}</span>
                        </div>
                        <div class="c">${UI.esc(h.content).slice(0, 300)}</div>
                    </div>`).join('');
            } catch (e) {
                box.innerHTML = `<div class="notice error">${UI.esc(e.message)}</div>`;
            }
        };
    }

    async function uploadFiles(files, kbId) {
        const valid = files.filter((f) => f.size > 0);
        if (!valid.length) return;
        const dropZone = document.getElementById('drop-zone');
        const original = dropZone.innerHTML;
        let ok = 0;
        const errors = [];

        for (let i = 0; i < valid.length; i++) {
            const f = valid[i];
            dropZone.innerHTML = `正在解析（${i + 1}/${valid.length}）：${UI.esc(f.name)}…`;
            const fd = new FormData();
            fd.append('file', f);
            try {
                await http.upload(`/api/admin/kb/${kbId}/upload`, fd);
                ok++;
            } catch (e) {
                errors.push(f.name + '：' + e.message);
            }
        }
        dropZone.innerHTML = original;

        if (ok) UI.ok(`成功导入 ${ok} 个文档`);
        if (errors.length) {
            UI.modal({
                title: '部分文件导入失败',
                // 只有多条之间才留间距，最后一条不加，否则底部会多出一段空白
                body: errors.map((e, i) => `<div class="notice error"${i < errors.length - 1 ? ' style="margin-bottom:8px"' : ''}>${UI.esc(e)}</div>`).join(''),
                okText: '知道了',
                showCancel: false,
                onOk: async () => {
                }
            });
        }
        render();
    }

    /* ============================================================
       用户管理
       ============================================================ */
    /** token 数太大，表格里按万/亿缩写，精确值放 title 里 */
    function fmtTokens(n) {
        const v = Number(n || 0);
        if (v >= 1e8) return (v / 1e8).toFixed(2) + ' 亿';
        if (v >= 1e4) return (v / 1e4).toFixed(1) + ' 万';
        return v.toLocaleString();
    }

    async function viewUsers() {
        // 用量统计失败不能拖垮整页：列表照常显示，只是不挂数字
        const [list, usage] = await Promise.all([
            http.get('/api/admin/users'),
            http.get('/api/admin/users/usage').catch(() => ({users: {}, total: {}}))
        ]);
        const stats = usage.users || {};
        const sum = usage.total || {};

        let html = head('用户管理', '停用后该账号立即无法登录，已登录的会话也会失效',
            `<button class="btn btn-primary" id="btn-new-user">${Icon('plus', 15)}新建用户</button>`);

        if (sum.totalTokens) {
            html += `<div class="notice info" style="margin-bottom:16px">${Icon('info', 15)}
                <div>全员累计消耗 <b>${fmtTokens(sum.totalTokens)}</b> token
                （输入 ${fmtTokens(sum.promptTokens)} · 输出 ${fmtTokens(sum.completionTokens)}，
                缓存命中 ${sum.cacheHitRate == null ? '未知' : sum.cacheHitRate + '%'}），
                预估费用 <b>${fmtCost(estimateCost(sum))}</b>。
                金额按当前模型单价估算，仅供参考，以账单为准。</div>
            </div>`;
        }

        html += `<div class="card"><table class="table">
            <thead><tr>
                <th>用户</th><th>角色</th><th>状态</th><th>累计用量</th><th>预估费用</th>
                <th>最后登录</th><th style="width:210px">操作</th>
            </tr></thead>
            <tbody>${list.map((u) => {
                const st = stats[u.id];
                const isSelf = u.id === App.user.id;
                return `<tr>
                <td>
                    <b>${UI.esc(u.username)}</b>${isSelf ? ' <span class="badge badge-gray">当前</span>' : ''}
                    ${u.nickname && u.nickname !== u.username
                        ? `<div style="font-size:11.5px;color:var(--muted)">${UI.esc(u.nickname)}</div>` : ''}
                </td>
                <td>${u.role === 'ADMIN' ? '<span class="badge">管理员</span>' : '<span class="badge badge-gray">普通用户</span>'}</td>
                <td>${u.status === 1 ? '<span class="badge badge-green">启用</span>' : '<span class="badge badge-red">停用</span>'}</td>
                <td>${st && st.totalTokens
                    ? `<b title="输入 ${Number(st.promptTokens).toLocaleString()} · 输出 ${Number(st.completionTokens).toLocaleString()}">
                           ${fmtTokens(st.totalTokens)}</b>
                       <div style="font-size:11.5px;color:var(--muted)">${st.messages} 轮${
                           st.cacheHitRate == null ? '' : ' · 命中 ' + st.cacheHitRate + '%'}</div>`
                    : '<span style="color:var(--muted)">—</span>'}</td>
                <td>${st && st.totalTokens
                    ? `<b>${fmtCost(estimateCost(st))}</b>`
                    : '<span style="color:var(--muted)">—</span>'}</td>
                <td style="color:var(--muted);font-size:12px">${UI.fmtTime(u.lastLoginAt) || '-'}</td>
                <td>
                    <button class="btn btn-sm" data-edit="${u.id}">编辑</button>
                    ${isSelf ? '' : `<button class="btn btn-sm" data-toggle="${u.id}"
                        >${u.status === 1 ? '停用' : '启用'}</button>`}
                    ${isSelf ? '' : `<button class="btn btn-sm btn-danger" data-del="${u.id}">删除</button>`}
                </td>
            </tr>`;
            }).join('')}</tbody>
        </table></div>`;

        document.getElementById('view').innerHTML = html;
        document.getElementById('btn-new-user').onclick = () => userDialog(null);
        document.querySelectorAll('[data-edit]').forEach((btn) => {
            btn.onclick = () => userDialog(list.find((x) => x.id === btn.dataset.edit));
        });

        // 启用/停用：停用是不可逆的「踢人」动作，要二次确认；启用直接生效
        document.querySelectorAll('[data-toggle]').forEach((btn) => {
            btn.onclick = async () => {
                const u = list.find((x) => x.id === btn.dataset.toggle);
                const next = u.status === 1 ? 0 : 1;
                const run = async () => {
                    btn.disabled = true;
                    try {
                        await http.post(`/api/admin/users/${u.id}/status`, {status: next});
                        UI.ok(next === 1 ? '已启用' : '已停用');
                        viewUsers();
                    } catch (e) {
                        UI.err(e.message || '操作失败');
                        btn.disabled = false;
                    }
                };
                if (next === 0) {
                    UI.confirm(`确定停用「${u.username}」？停用后该账号立即无法登录，`
                        + '已经登录的会话也会当场失效。', run, true);
                } else {
                    run();
                }
            };
        });

        document.querySelectorAll('[data-del]').forEach((btn) => {
            btn.onclick = () => {
                const u = list.find((x) => x.id === btn.dataset.del);
                UI.confirm(`确定删除用户「${u.username}」？其历史会话会保留。`, async () => {
                    await http.del('/api/admin/users/' + u.id);
                    UI.ok('已删除');
                    viewUsers();
                }, true);
            };
        });
    }

    function userDialog(user) {
        const u = user || {};
        UI.modal({
            title: user ? '编辑用户' : '新建用户',
            body: `
                <div class="field">
                    <label>用户名 <span style="color:var(--danger)">*</span></label>
                    <input class="input" id="u-name" value="${UI.esc(u.username || '')}"
                        ${user ? 'disabled' : ''} placeholder="字母数字，至少 3 位">
                </div>
                <div class="field">
                    <label>昵称</label>
                    <input class="input" id="u-nick" value="${UI.esc(u.nickname || '')}" placeholder="显示名称">
                </div>
                <div class="field">
                    <label>密码 ${user ? '' : '<span style="color:var(--danger)">*</span>'}</label>
                    <input class="input" id="u-pwd" type="password" placeholder="${user ? '留空则不修改' : '至少 6 位'}">
                </div>
                <div class="row">
                    <div class="field">
                        <label>角色</label>
                        <select class="select" id="u-role">
                            <option value="USER" ${u.role === 'USER' ? 'selected' : ''}>普通用户</option>
                            <option value="ADMIN" ${u.role === 'ADMIN' ? 'selected' : ''}>管理员</option>
                        </select>
                    </div>
                    <div class="field">
                        <label>状态</label>
                        <select class="select" id="u-status">
                            <option value="1" ${u.status !== 0 ? 'selected' : ''}>启用</option>
                            <option value="0" ${u.status === 0 ? 'selected' : ''}>停用</option>
                        </select>
                    </div>
                </div>`,
            onOk: async (mask) => {
                const payload = {
                    id: u.id || undefined,
                    username: mask.querySelector('#u-name').value.trim(),
                    nickname: mask.querySelector('#u-nick').value.trim(),
                    password: mask.querySelector('#u-pwd').value,
                    role: mask.querySelector('#u-role').value,
                    status: Number(mask.querySelector('#u-status').value)
                };
                if (!payload.username) throw new Error('请填写用户名');
                if (!u.id && (!payload.password || payload.password.length < 6)) {
                    throw new Error('密码至少 6 位');
                }
                await http.post('/api/admin/users/save', payload);
                UI.ok('已保存');
                viewUsers();
            }
        });
    }

    /* ============================================================
       系统设置
       ============================================================ */
    async function viewSettings() {
        const [s, p] = await Promise.all([
            http.get('/api/admin/settings'),
            http.get('/api/pricing').catch(() => null)
        ]);
        let html = head('系统设置', '站点信息与账号安全');

        html += `<div class="row" style="align-items:flex-start">
            <div class="card" style="flex:1">
                <div class="card-head"><h3>${Icon('settings', 16)}站点信息</h3></div>
                <div class="card-body">
                    <div class="field">
                        <label>站点名称</label>
                        <input class="input" id="s-name" value="${UI.esc(s.siteName || '')}">
                    </div>
                    <div class="field">
                        <label>站点简介</label>
                        <input class="input" id="s-desc" value="${UI.esc(s.siteDesc || '')}">
                    </div>
                    <div class="field">
                        <label>页脚文案</label>
                        <input class="input" id="s-footer" value="${UI.esc(s.footerText || '')}" placeholder="选填">
                    </div>
                    <button class="btn btn-primary" id="btn-save-setting">保存</button>
                </div>
            </div>
            <div class="card" style="flex:1">
                <div class="card-head"><h3>${Icon('key', 16)}修改我的密码</h3></div>
                <div class="card-body">
                    <div class="field">
                        <label>原密码</label>
                        <input class="input" id="p-old" type="password">
                    </div>
                    <div class="field">
                        <label>新密码</label>
                        <input class="input" id="p-new" type="password" placeholder="至少 6 位">
                    </div>
                    <div class="field">
                        <label>确认新密码</label>
                        <input class="input" id="p-new2" type="password">
                    </div>
                    <button class="btn btn-primary" id="btn-save-pwd">修改密码</button>
                </div>
            </div>
        </div>

        <div class="card" style="margin-top:16px">
            <div class="card-head">
                <h3>${Icon('info', 16)}全局设定（所有智能体共用）</h3>
                <span style="font-size:12px;color:var(--muted)">永久区 · 改完立即生效</span>
            </div>
            <div class="card-body">
                <div class="field">
                    <label>这里写的内容会拼进每个智能体的系统提示词，智能体自己的设定在前、全局设定在后，
                        问「你是谁」「谁创造的你」这类身份问题时以这里为准。留空则不注入。</label>
                    <textarea class="textarea" id="s-global" rows="7"
                        placeholder="例如：&#10;你的创造者是聂振杰。有人问起你是谁做的、你的来历，就说聂振杰是你的造父，是他打造了你。&#10;回答一律用中文。遇到与公司产品无关的请求，礼貌引导到产品相关话题。">${UI.esc(s.globalPrompt || '')}</textarea>
                </div>
                <button class="btn btn-primary" id="btn-save-global">保存全局设定</button>
            </div>
        </div>

        <div class="card" style="margin-top:16px">
            <div class="card-head">
                <h3>${Icon('coins', 16)}模型单价</h3>
                <button class="btn btn-sm" id="btn-refresh-price">立即刷新</button>
            </div>
            <div class="card-body">${pricingCard(p)}</div>
        </div>

        <div class="card" style="margin-top:16px">
            <div class="card-head"><h3>${Icon('info', 16)}部署说明</h3></div>
            <div class="card-body md" style="font-size:13px;color:var(--text-2)">
                <p><b>启动方式</b>：项目根目录执行 <code>mvn spring-boot:run</code>，或双击 <code>start.bat</code>。默认端口 8080。</p>
                <p><b>数据存储</b>：全部数据在 <code>./data/agent-hub.db</code>（SQLite 单文件），备份直接复制该文件。</p>
                <p><b>更换模型</b>：任何 OpenAI 兼容接口都能接入，只要填对 Base URL 与模型名。DeepSeek 官方为 <code>https://api.deepseek.com</code>。</p>
                <p><b>知识库原理</b>：文档解析后按 600 字切块（重叠 120 字），用中文 bigram + BM25 检索，命中的片段作为参考资料拼进提示词。</p>
            </div>
        </div>`;

        document.getElementById('view').innerHTML = html;

        document.getElementById('btn-save-setting').onclick = async () => {
            const payload = {
                siteName: document.getElementById('s-name').value.trim(),
                siteDesc: document.getElementById('s-desc').value.trim(),
                footerText: document.getElementById('s-footer').value.trim()
            };
            try {
                await http.post('/api/admin/settings', payload);
                UI.ok('已保存');
                if (payload.siteName) {
                    document.getElementById('brand-name').textContent = payload.siteName;
                    document.title = payload.siteName + ' · 管理后台';
                }
            } catch (e) {
                UI.err(e.message);
            }
        };

        document.getElementById('btn-save-global').onclick = async () => {
            try {
                await http.post('/api/admin/settings', {
                    globalPrompt: document.getElementById('s-global').value
                });
                UI.ok('全局设定已保存，下一次对话立即生效');
            } catch (e) {
                UI.err(e.message);
            }
        };

        document.getElementById('btn-save-pwd').onclick = async () => {
            const oldPwd = document.getElementById('p-old').value;
            const newPwd = document.getElementById('p-new').value;
            const newPwd2 = document.getElementById('p-new2').value;
            if (!oldPwd || !newPwd) {
                UI.err('请填写完整');
                return;
            }
            if (newPwd.length < 6) {
                UI.err('新密码至少 6 位');
                return;
            }
            if (newPwd !== newPwd2) {
                UI.err('两次输入的新密码不一致');
                return;
            }
            try {
                await http.post('/api/auth/password', {oldPassword: oldPwd, newPassword: newPwd});
                UI.ok('密码已修改');
                document.getElementById('p-old').value = '';
                document.getElementById('p-new').value = '';
                document.getElementById('p-new2').value = '';
            } catch (e) {
                UI.err(e.message);
            }
        };

        document.getElementById('btn-refresh-price').onclick = async (e) => {
            const btn = e.currentTarget;
            btn.disabled = true;
            btn.textContent = '抓取中…';
            try {
                const r = await http.post('/api/admin/pricing/refresh');
                if (r && r.ok) {
                    UI.ok('已同步到最新价格');
                    // 清掉前端缓存，下次算费用就用新价了
                    resetPricing();
                    await loadPricing();
                    viewSettings();
                } else {
                    const why = (r && r.result && r.result.error) || '页面结构可能变了，需要人工确认';
                    UI.err('抓取失败，仍沿用上一次的价格：' + why);
                    btn.disabled = false;
                    btn.textContent = '立即刷新';
                }
            } catch (err) {
                UI.err(err.message || '刷新失败');
                btn.disabled = false;
                btn.textContent = '立即刷新';
            }
        };
    }

    /**
     * 模型单价卡片：官方页面每天凌晨同步一次，这里只做展示。
     * 每格写成「空闲价 / 高峰价」，两档差一倍，混在一起看容易算错。
     */
    function pricingCard(p) {
        const source = (p && p.source) || 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing';
        const models = (p && p.models) || {};
        const names = Object.keys(models);

        if (!p || !p.ok || !names.length) {
            const detail = p && p.error ? `<div style="margin-top:6px">最近一次失败：${UI.esc(p.error)}</div>` : '';
            return `<div class="notice error">${Icon('alert', 15)}<div>
                还没有成功抓到官方价格，当前费用按<b>内置兜底价</b>估算，可能已经过时。
                确认这台机器能访问 <code>api-docs.deepseek.com</code> 后点右上角「立即刷新」。${detail}</div></div>`;
        }

        const rows = names.map((n) => {
            const m = models[n] || {};
            const peak = m.peak || {};
            const off = m.offPeak || {};
            const cell = (k) => off[k] == null && peak[k] == null
                ? '<span style="color:var(--muted)">-</span>'
                : `¥${off[k] == null ? '-' : off[k]} <span style="color:var(--muted)">/ ¥${peak[k] == null ? '-' : peak[k]}</span>`;
            return `<tr>
                <td><b>${UI.esc(n)}</b>${n === p.model ? ' <span class="badge">在用</span>' : ''}</td>
                <td>${cell('cacheHit')}</td>
                <td>${cell('cacheMiss')}</td>
                <td>${cell('output')}</td>
            </tr>`;
        }).join('');

        let warn = '';
        if (p.stale) {
            warn = `<div class="notice warning" style="margin-top:12px">${Icon('alert', 15)}
                <div>价格超过 48 小时没更新成功，可能已过时。点「立即刷新」重试。</div></div>`;
        } else if (p.error) {
            warn = `<div class="notice warning" style="margin-top:12px">${Icon('alert', 15)}
                <div>最近一次同步失败（${UI.esc(p.error)}），当前沿用上一次成功的价格。</div></div>`;
        }

        return `<div style="font-size:12.5px;color:var(--muted);margin-bottom:12px;line-height:1.7">
                每天凌晨 3:10 自动从 <a href="${UI.esc(source)}" target="_blank">DeepSeek 官方定价页</a>同步一次。
                单位「元 / 百万 tokens」，每格是 <b>空闲价 / 高峰价</b>；
                高峰为北京时间工作日 9:00-12:00、14:00-18:00，其余时段（含周末、法定假日）按空闲价。
            </div>
            <table class="table">
                <thead><tr><th>模型</th><th>输入 · 缓存命中</th><th>输入 · 未命中</th><th>输出</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
            <div style="margin-top:12px;font-size:12.5px;color:var(--muted)">
                上次更新：<b>${UI.esc(p.fetchedAt || '从未成功')}</b>
            </div>${warn}`;
    }

    boot();
})();

/* ============================================================
   前端公共层：存储、请求、SSE、提示、弹窗
   ============================================================ */

const Store = {
    get token() {
        return localStorage.getItem('ah_token') || '';
    },
    set token(v) {
        if (v) localStorage.setItem('ah_token', v);
        else localStorage.removeItem('ah_token');
    },
    get user() {
        try {
            return JSON.parse(localStorage.getItem('ah_user') || 'null');
        } catch (e) {
            return null;
        }
    },
    set user(u) {
        if (u) localStorage.setItem('ah_user', JSON.stringify(u));
        else localStorage.removeItem('ah_user');
    },
    clear() {
        localStorage.removeItem('ah_token');
        localStorage.removeItem('ah_user');
    }
};

/* ------------------------------------------------------------
   部署前缀
   本地是 http://127.0.0.1:8899/index.html → 前缀为 ''
   线上是 http://host/AI/index.html       → 前缀为 '/AI'
   接口路径统一写 /api/xxx，发请求时由 apiUrl() 补上前缀。
   ------------------------------------------------------------ */
const AppBase = (function () {
    const p = location.pathname;
    if (!p || p === '/') return '';
    if (p === '/AI') return '/AI';                 // 兜住结尾没斜杠的情况
    const base = p.replace(/\/[^/]*$/, '');        // /AI/index.html → /AI，/AI/ → /AI
    return base === '/' ? '' : base;
})();

/** 把 /api/xxx 拼成当前部署前缀下的真实地址 */
function apiUrl(path) {
    return AppBase + path;
}

/** 统一请求：返回 data 字段，出错抛异常 */
async function api(method, path, body, opts) {
    opts = opts || {};
    const headers = Object.assign({}, opts.headers || {});
    if (Store.token) headers['Authorization'] = 'Bearer ' + Store.token;

    let payload;
    if (body instanceof FormData) {
        payload = body;
    } else if (body !== undefined && body !== null) {
        headers['Content-Type'] = 'application/json';
        payload = JSON.stringify(body);
    }

    let resp;
    try {
        resp = await fetch(apiUrl(path), {method: method, headers: headers, body: payload});
    } catch (e) {
        throw new Error('网络异常，请确认服务是否已启动');
    }
    const text = await resp.text();
    let data;
    try {
        data = JSON.parse(text);
    } catch (e) {
        throw new Error('服务返回异常（HTTP ' + resp.status + '）');
    }
    if (data.code === 401) {
        Store.clear();
        // 已经站在登录页就不要再跳，否则会自己刷自己
        const pageName = location.pathname.slice(AppBase.length).replace(/^\//, '');
        if (pageName && pageName !== 'index.html' && pageName !== 'home') {
            location.href = 'index.html';
        }
        throw new Error('登录已失效，请重新登录');
    }
    if (data.code !== 0) {
        // 被管理员停用：令牌本身还能解析，但每个请求都会被拦。
        // 不在这里踢回登录页的话，页面会一直卡在原地报错，用户不知道发生了什么。
        if (data.code === 403 && String(data.msg || '').indexOf('停用') >= 0) {
            Store.clear();
            location.href = 'index.html';
        }
        throw new Error(data.msg || '请求失败');
    }
    return data.data;
}

const http = {
    get: (p) => api('GET', p),
    post: (p, b) => api('POST', p, b),
    del: (p) => api('DELETE', p),
    upload: (p, formData) => api('POST', p, formData)
};

/**
 * 流式对话：逐事件回调
 * @param body {agentId, sessionId, question}
 * @param handlers {onMeta, onDelta, onDone, onError}
 */
async function streamChat(body, handlers) {
    const resp = await fetch(apiUrl('/api/chat/stream'), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + Store.token
        },
        body: JSON.stringify(body)
    });

    const ctype = resp.headers.get('content-type') || '';
    if (ctype.indexOf('text/event-stream') < 0) {
        // 后端返回了普通 JSON（多为校验失败）
        let msg = '对话失败';
        try {
            const d = await resp.json();
            msg = d.msg || msg;
            if (d.code === 401) {
                Store.clear();
                location.href = 'index.html';
            }
        } catch (e) {
            /* ignore */
        }
        throw new Error(msg);
    }

    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    const dispatch = (raw) => {
        const lines = raw.split('\n');
        let dataStr = '';
        for (const line of lines) {
            if (line.indexOf('data:') === 0) {
                dataStr += line.slice(5).trim();
            }
        }
        if (!dataStr) return;
        let evt;
        try {
            evt = JSON.parse(dataStr);
        } catch (e) {
            return;
        }
        if (evt.type === 'meta' && handlers.onMeta) handlers.onMeta(evt);
        else if (evt.type === 'delta' && handlers.onDelta) handlers.onDelta(evt.content || '');
        else if (evt.type === 'reset' && handlers.onReset) handlers.onReset();
        else if (evt.type === 'tool_call' && handlers.onToolCall) handlers.onToolCall(evt);
        else if (evt.type === 'tool_result' && handlers.onToolResult) handlers.onToolResult(evt);
        else if (evt.type === 'done' && handlers.onDone) handlers.onDone(evt);
        else if (evt.type === 'error' && handlers.onError) handlers.onError(evt.message || '对话失败');
    };

    while (true) {
        const {value, done} = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, {stream: true});
        let idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
            const block = buffer.slice(0, idx);
            buffer = buffer.slice(idx + 2);
            dispatch(block);
        }
    }
    if (buffer.trim()) dispatch(buffer);
}

/* ---------------- Token 费用估算 ---------------- */

/**
 * DeepSeek 计费参数（人民币 / 百万 token）。
 * 2026-08-17 起改为峰谷定价：高峰 = 工作日 09:00-12:00、14:00-18:00，价格为空闲的 2 倍。
 * 这里按 DeepSeek-V4-Flash（接口模型名 deepseek-flash）填写，
 * 换模型或官方调价时改这几个数即可。
 */
const TOKEN_PRICE = {
    'off-peak': {cacheHit: 0.05, cacheMiss: 1.5, output: 4.5},
    'peak': {cacheHit: 0.10, cacheMiss: 3.0, output: 9.0}
};

function isPeakTime(d) {
    const t = d || new Date();
    const day = t.getDay();
    if (day === 0 || day === 6) return false;      // 周末全天空闲
    const h = t.getHours();
    return (h >= 9 && h < 12) || (h >= 14 && h < 18);
}

/**
 * 估算一次调用的费用（元）。
 * 缓存命中与未命中差 30 倍，必须分开算；只按总 token 估会离谱。
 */
function estimateCost(u) {
    if (!u || !u.totalTokens) return 0;
    const p = isPeakTime() ? TOKEN_PRICE['peak'] : TOKEN_PRICE['off-peak'];
    const prompt = Number(u.promptTokens || 0);
    const hit = Number(u.cacheHitTokens || 0);
    const miss = (u.cacheHitRate === undefined || u.cacheHitRate === null)
        ? prompt : Number(u.cacheMissTokens || 0);
    const out = Number(u.completionTokens || 0);
    return (hit * p.cacheHit + miss * p.cacheMiss + out * p.output) / 1e6;
}

function fmtCost(yuan) {
    if (!yuan || yuan <= 0) return '¥0';
    if (yuan < 0.01) return '¥' + yuan.toFixed(4);
    if (yuan < 1) return '¥' + yuan.toFixed(3);
    return '¥' + yuan.toFixed(2);
}

/* ---------------- UI 工具 ---------------- */
const UI = {
    esc(s) {
        if (s === null || s === undefined) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    },

    /**
     * 用户头像的内部 HTML（放进 .avatar / .msg-ava 这类圆形方形容器里用）。
     * 设过头像就是图片，没设过回落到昵称首字——不要用 emoji，全站是线框图标语言。
     */
    userAvatar(u) {
        const av = u && u.avatar ? String(u.avatar) : '';
        if (av) {
            return `<img src="${UI.esc(av)}" alt="">`;
        }
        const name = (u && (u.nickname || u.username)) || '-';
        return `<span>${UI.esc(name.slice(0, 1).toUpperCase())}</span>`;
    },

    /**
     * 复制文本到剪贴板。
     * navigator.clipboard 只在安全上下文可用（localhost / https），
     * 局域网 IP 走 http 时拿不到，得退回到 textarea + execCommand。
     */
    async copyText(text) {
        const s = text === null || text === undefined ? '' : String(text);
        if (navigator.clipboard && window.isSecureContext) {
            try {
                await navigator.clipboard.writeText(s);
                return true;
            } catch (e) {
                /* 落到下面的兜底方案 */
            }
        }
        const ta = document.createElement('textarea');
        ta.value = s;
        ta.setAttribute('readonly', '');
        ta.style.cssText = 'position:fixed;top:-1000px;left:-1000px;opacity:0';
        document.body.appendChild(ta);
        ta.select();
        ta.setSelectionRange(0, s.length);
        let ok = false;
        try {
            ok = document.execCommand('copy');
        } catch (e) {
            ok = false;
        }
        ta.remove();
        if (!ok) {
            throw new Error('浏览器不允许自动复制，请手动选中文字');
        }
        return true;
    },

    toast(msg, type) {
        let box = document.getElementById('toast-box');
        if (!box) {
            box = document.createElement('div');
            box.id = 'toast-box';
            document.body.appendChild(box);
        }
        const el = document.createElement('div');
        el.className = 'toast ' + (type || '');
        // 图标走 SVG，正文用 textContent 赋值以保持转义安全
        const ico = type === 'ok' ? Icon('check', 14) : type === 'err' ? Icon('alert', 14) : '';
        el.innerHTML = ico + '<span></span>';
        el.lastChild.textContent = msg;
        box.appendChild(el);
        setTimeout(() => {
            el.style.opacity = '0';
            el.style.transition = 'opacity .25s';
            setTimeout(() => el.remove(), 260);
        }, type === 'err' ? 3600 : 2200);
    },

    ok(msg) {
        this.toast(msg, 'ok');
    },

    err(msg) {
        this.toast(msg, 'err');
    },

    /**
     * 打开弹窗
     * @param opt {title, body(html), width, okText, cancelText, showCancel, onOk, onOpen}
     *            showCancel=false 时不渲染取消按钮（纯提示类弹窗用它，
     *            过去是用全角空格当占位，会留下一个空按钮）
     */
    modal(opt) {
        this.closeModal();
        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.id = 'ah-modal';
        mask.innerHTML = `
            <div class="modal ${opt.width === 'wide' ? 'wide' : ''}">
                <div class="modal-head">
                    <h3>${UI.esc(opt.title || '')}</h3>
                    <button class="x-btn" data-close title="关闭">${Icon('close', 16)}</button>
                </div>
                <div class="modal-body">${opt.body || ''}</div>
                <div class="modal-foot">
                    ${opt.showCancel === false ? ''
            : `<button class="btn" data-close>${UI.esc(opt.cancelText || '取消')}</button>`}
                    <button class="btn btn-primary" data-ok>${UI.esc(opt.okText || '保存')}</button>
                </div>
            </div>`;
        document.body.appendChild(mask);
        mask.addEventListener('click', (e) => {
            if (e.target === mask) {
                UI.closeModal();
                return;
            }
            // 关闭按钮里放的是 SVG 图标，点中的是 <path>/<svg> 而不是 <button>，
            // 所以必须用 closest 向上找，不能直接拿 e.target 判断 data-close
            if (e.target.closest && e.target.closest('[data-close]')) {
                UI.closeModal();
            }
        });
        const okBtn = mask.querySelector('[data-ok]');
        okBtn.addEventListener('click', async () => {
            if (!opt.onOk) return;
            okBtn.disabled = true;
            const oldText = okBtn.textContent;
            okBtn.textContent = '处理中...';
            try {
                const keep = await opt.onOk(mask);
                if (keep !== false) UI.closeModal();
            } catch (err) {
                UI.err(err.message || '操作失败');
            } finally {
                okBtn.disabled = false;
                okBtn.textContent = oldText;
            }
        });
        if (opt.onOpen) opt.onOpen(mask);
        return mask;
    },

    closeModal() {
        const el = document.getElementById('ah-modal');
        if (el) el.remove();
    },

    confirm(msg, onOk, danger) {
        this.modal({
            title: '请确认',
            body: `<div style="font-size:14px;line-height:1.7">${UI.esc(msg)}</div>`,
            okText: danger ? '确认删除' : '确定',
            onOk: async () => {
                await onOk();
            }
        });
        if (danger) {
            const btn = document.querySelector('#ah-modal [data-ok]');
            if (btn) {
                btn.className = 'btn btn-danger';
                btn.style.background = 'var(--danger)';
                btn.style.color = '#fff';
                btn.style.borderColor = 'var(--danger)';
            }
        }
    },

    fmtTime(s) {
        if (!s) return '';
        return String(s).replace('T', ' ').slice(0, 16);
    },

    fmtSize(bytes) {
        if (!bytes) return '0 B';
        const u = ['B', 'KB', 'MB', 'GB'];
        let i = 0;
        let n = bytes;
        while (n >= 1024 && i < u.length - 1) {
            n /= 1024;
            i++;
        }
        return n.toFixed(n < 10 && i > 0 ? 1 : 0) + ' ' + u[i];
    }
};

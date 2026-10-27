/* ============================================================
   个人中心：资料（昵称 / 头像）+ 密码
   ============================================================ */
(function () {

    if (!Store.token) {
        location.replace('index.html');
        return;
    }

    const S = {
        user: Store.user || {},
        /**
         * 头像草稿：
         *   null = 没改过（用服务端的值）
         *   ''   = 明确要恢复默认（昵称首字）
         *   data:image/... = 新选的图
         */
        draftAvatar: null
    };

    const el = {
        ava: document.getElementById('pf-ava'),
        avaPreview: document.getElementById('ava-preview'),
        nm: document.getElementById('pf-nm'),
        rl: document.getElementById('pf-rl'),
        tag: document.getElementById('pf-tag'),
        tagT: document.getElementById('pf-tag-t'),
        meta: document.getElementById('pf-meta'),
        username: document.getElementById('pf-username'),
        nick: document.getElementById('pf-nick'),
        file: document.getElementById('file-ava'),
        btnUpload: document.getElementById('btn-upload'),
        btnReset: document.getElementById('btn-reset'),
        btnSave: document.getElementById('btn-save'),
        btnReload: document.getElementById('btn-reload'),
        pwdOld: document.getElementById('pwd-old'),
        pwdNew: document.getElementById('pwd-new'),
        pwdNew2: document.getElementById('pwd-new2'),
        btnPwd: document.getElementById('btn-pwd')
    };

    /* ---------------- 启动 ---------------- */
    async function boot() {
        render(S.user);
        bind();
        try {
            S.user = await http.get('/api/auth/me');
            Store.user = S.user;
        } catch (e) {
            /* 401 已由 api() 统一处理 */
        }
        S.draftAvatar = null;
        render(S.user);
    }

    /** 当前生效的头像：草稿优先，其次服务端值，都没有就昵称首字 */
    function currentAvatar() {
        if (S.draftAvatar !== null) return S.draftAvatar;
        return S.user.avatar || '';
    }

    function render(u) {
        const name = u.nickname || u.username || '-';
        const role = u.role === 'ADMIN' ? '管理员' : '普通用户';

        el.nm.textContent = name;
        el.rl.textContent = (u.username || '-') + ' · ' + role;
        el.tag.hidden = u.role !== 'ADMIN';
        el.tagT.textContent = role;

        el.username.value = u.username || '';
        el.nick.value = u.nickname || u.username || '';

        const ava = currentAvatar();
        const inner = ava ? `<img src="${UI.esc(ava)}" alt="">` : `<span>${UI.esc(name.slice(0, 1).toUpperCase())}</span>`;
        el.ava.innerHTML = inner;
        el.avaPreview.innerHTML = inner;

        el.meta.innerHTML = [
            ['账号角色', role],
            ['上次登录', u.lastLoginAt ? UI.fmtTime(u.lastLoginAt) : '首次登录'],
            ['创建时间', u.createdAt ? UI.fmtTime(u.createdAt) : '-']
        ].map(([k, v]) => `<div class="kv"><span class="k">${UI.esc(k)}</span><span class="v" title="${UI.esc(v)}">${UI.esc(v)}</span></div>`).join('');
    }

    function bind() {
        el.btnUpload.onclick = () => el.file.click();
        el.file.onchange = async () => {
            const f = el.file.files && el.file.files[0];
            el.file.value = '';
            if (!f) return;
            try {
                const dataUrl = await compress(f);
                S.draftAvatar = dataUrl;
                render(S.user);
                UI.toast('头像已就绪，记得点「保存修改」');
            } catch (e) {
                UI.err(e.message || '图片处理失败');
            }
        };

        el.btnReset.onclick = () => {
            if (!currentAvatar()) {
                UI.toast('当前就是默认头像');
                return;
            }
            S.draftAvatar = '';
            render(S.user);
        };

        el.btnReload.onclick = () => reload();
        el.btnSave.onclick = save;

        el.btnPwd.onclick = changePassword;
        // 密码框里回车直接提交，比伸手点按钮自然
        [el.pwdOld, el.pwdNew, el.pwdNew2].forEach((input) => {
            input.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    changePassword();
                }
            });
        });
    }

    async function reload() {
        try {
            S.user = await http.get('/api/auth/me');
            Store.user = S.user;
            S.draftAvatar = null;
            render(S.user);
            UI.toast('已恢复到上次保存的样式');
        } catch (e) {
            UI.err(e.message);
        }
    }

    async function save() {
        const nickname = el.nick.value.trim();
        if (!nickname) {
            UI.err('昵称不能为空');
            el.nick.focus();
            return;
        }
        if (nickname.length > 24) {
            UI.err('昵称最多 24 个字符');
            return;
        }
        el.btnSave.disabled = true;
        const old = el.btnSave.textContent;
        el.btnSave.textContent = '保存中…';
        try {
            const user = await http.post('/api/auth/profile', {
                nickname: nickname,
                avatar: currentAvatar()
            });
            S.user = user;
            Store.user = user;
            S.draftAvatar = null;
            render(user);
            UI.ok('资料已保存');
        } catch (e) {
            UI.err(e.message || '保存失败');
        } finally {
            el.btnSave.disabled = false;
            el.btnSave.textContent = old;
        }
    }

    async function changePassword() {
        const oldPwd = el.pwdOld.value;
        const newPwd = el.pwdNew.value;
        const again = el.pwdNew2.value;
        if (!oldPwd) {
            UI.err('请输入当前密码');
            el.pwdOld.focus();
            return;
        }
        if (!newPwd || newPwd.length < 6) {
            UI.err('新密码至少 6 位');
            el.pwdNew.focus();
            return;
        }
        if (newPwd !== again) {
            UI.err('两次输入的新密码不一致');
            el.pwdNew2.focus();
            return;
        }
        if (newPwd === oldPwd) {
            UI.err('新密码不能与当前密码相同');
            el.pwdNew.focus();
            return;
        }
        el.btnPwd.disabled = true;
        const oldText = el.btnPwd.textContent;
        el.btnPwd.textContent = '提交中…';
        try {
            await http.post('/api/auth/password', {oldPassword: oldPwd, newPassword: newPwd});
            el.pwdOld.value = '';
            el.pwdNew.value = '';
            el.pwdNew2.value = '';
            UI.ok('密码已更新，下次登录请用新密码');
        } catch (e) {
            UI.err(e.message || '修改失败');
        } finally {
            el.btnPwd.disabled = false;
            el.btnPwd.textContent = oldText;
        }
    }

    /**
     * 把用户选的图裁成 200×200 的 JPEG data URL。
     * 直接用原图当头像会有两个问题：几 MB 的 base64 塞进库里，而且接口响应也会被撑大；
     * 头像最大显示尺寸不到 100px，200×200 已经够 2 倍屏用。
     */
    function compress(file) {
        return new Promise((resolve, reject) => {
            if (!file.type || file.type.indexOf('image/') !== 0) {
                reject(new Error('请选择图片文件'));
                return;
            }
            if (file.size > 8 * 1024 * 1024) {
                reject(new Error('图片不能超过 8MB'));
                return;
            }
            const url = URL.createObjectURL(file);
            const img = new Image();
            img.onload = () => {
                const size = 200;
                const canvas = document.createElement('canvas');
                canvas.width = size;
                canvas.height = size;
                const ctx = canvas.getContext('2d');
                // 等比缩放后居中裁剪，避免长图被拉扁
                const scale = Math.max(size / img.width, size / img.height);
                const w = img.width * scale;
                const h = img.height * scale;
                ctx.drawImage(img, (size - w) / 2, (size - h) / 2, w, h);
                URL.revokeObjectURL(url);
                try {
                    resolve(canvas.toDataURL('image/jpeg', 0.86));
                } catch (e) {
                    reject(new Error('图片转换失败'));
                }
            };
            img.onerror = () => {
                URL.revokeObjectURL(url);
                reject(new Error('图片解析失败，换一张试试'));
            };
            img.src = url;
        });
    }

    boot();
})();

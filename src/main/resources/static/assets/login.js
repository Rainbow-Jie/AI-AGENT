/* 登录页逻辑 */
(function () {
    // 已登录直接进对应首页：管理员去后台，普通用户去对话
    if (Store.token && Store.user) {
        location.replace(Store.user.role === 'ADMIN' ? 'console.html' : 'chat.html');
        return;
    }

    const form = document.getElementById('login-form');
    const btn = document.getElementById('login-btn');

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = document.getElementById('username').value.trim();
        const password = document.getElementById('password').value;
        if (!username || !password) {
            UI.err('请输入账号和密码');
            return;
        }
        btn.disabled = true;
        btn.textContent = '登录中...';
        try {
            const data = await http.post('/api/auth/login', {username: username, password: password});
            Store.token = data.token;
            Store.user = data.user;
            // 管理员进后台，普通用户直接进对话
            location.href = data.user.role === 'ADMIN' ? 'console.html' : 'chat.html';
        } catch (err) {
            UI.err(err.message || '登录失败');
            btn.disabled = false;
            btn.textContent = '登 录';
        }
    });
})();

/* ============================================================
   Agent Hub 图标系统
   ------------------------------------------------------------
   统一 24x24 视窗、1.75 描边、圆头圆角，全部走 currentColor，
   所以图标颜色跟文字色走，hover / active 不用单独处理。

   用法：
     1) 静态结构：<span data-icon="dashboard"></span>，随后自动挂载
     2) 拼字符串：Icon('trash', 15)
   ============================================================ */
(function (global) {
    'use strict';

    var ICONS = {
        /* ---------- 品牌 ---------- */
        // 中心节点 + 三个分支，对应 Hub 的语义
        logo: '<circle cx="12" cy="12" r="2.5" fill="currentColor" stroke="none"/>'
            + '<circle cx="12" cy="4.3" r="1.9"/>'
            + '<circle cx="5.3" cy="16.6" r="1.9"/>'
            + '<circle cx="18.7" cy="16.6" r="1.9"/>'
            + '<path d="M12 6.2v3.3M10.2 13.5 6.9 15.5M13.8 13.5l3.3 2"/>',

        /* ---------- 导航 ---------- */
        dashboard: '<rect x="3.2" y="3.2" width="7.4" height="7.4" rx="2.2"/>'
            + '<rect x="13.4" y="3.2" width="7.4" height="7.4" rx="2.2"/>'
            + '<rect x="3.2" y="13.4" width="7.4" height="7.4" rx="2.2"/>'
            + '<rect x="13.4" y="13.4" width="7.4" height="7.4" rx="2.2"/>',

        robot: '<rect x="3.6" y="7.4" width="16.8" height="12.2" rx="3.6"/>'
            + '<circle cx="9" cy="13.3" r="1.25" fill="currentColor" stroke="none"/>'
            + '<circle cx="15" cy="13.3" r="1.25" fill="currentColor" stroke="none"/>'
            + '<path d="M12 7.4V4.6"/><circle cx="12" cy="3.1" r="1.3"/>'
            + '<path d="M9.2 16.6h5.6"/>',

        cpu: '<rect x="6.6" y="6.6" width="10.8" height="10.8" rx="2.6"/>'
            + '<rect x="10.2" y="10.2" width="3.6" height="3.6" rx="1"/>'
            + '<path d="M9.6 3.2v3.4M14.4 3.2v3.4M9.6 17.4v3.4M14.4 17.4v3.4'
            + 'M3.2 9.6h3.4M3.2 14.4h3.4M17.4 9.6h3.4M17.4 14.4h3.4"/>',

        book: '<path d="M4 6.2A2.7 2.7 0 0 1 6.7 3.5H19.4v13.8H6.7A2.7 2.7 0 0 0 4 20V6.2Z"/>'
            + '<path d="M4 20a2.7 2.7 0 0 1 2.7-2.7h12.7v3.2H6.7"/>'
            + '<path d="M8.4 7.6h6.8"/>',

        users: '<circle cx="9.2" cy="8.2" r="3.3"/>'
            + '<path d="M3.2 19.6a6 6 0 0 1 12 0"/>'
            + '<path d="M16.1 5.3a3.3 3.3 0 0 1 0 5.9M17.4 19.6a6 6 0 0 0-2.1-4.6"/>',

        settings: '<circle cx="12" cy="12" r="3.1"/>'
            + '<path d="M19.1 14.4a1.5 1.5 0 0 0 .3 1.7l.1.1a1.9 1.9 0 1 1-2.7 2.7l-.1-.1a1.5 1.5 0 0 0-1.7-.3 1.5 1.5 0 0 0-.9 1.4v.2a1.9 1.9 0 1 1-3.8 0v-.1a1.5 1.5 0 0 0-1-1.4 1.5 1.5 0 0 0-1.7.3l-.1.1a1.9 1.9 0 1 1-2.7-2.7l.1-.1a1.5 1.5 0 0 0 .3-1.7 1.5 1.5 0 0 0-1.4-.9H3.5a1.9 1.9 0 1 1 0-3.8h.1a1.5 1.5 0 0 0 1.4-1 1.5 1.5 0 0 0-.3-1.7l-.1-.1a1.9 1.9 0 1 1 2.7-2.7l.1.1a1.5 1.5 0 0 0 1.7.3h.1a1.5 1.5 0 0 0 .9-1.4V3.5a1.9 1.9 0 1 1 3.8 0v.1a1.5 1.5 0 0 0 .9 1.4 1.5 1.5 0 0 0 1.7-.3l.1-.1a1.9 1.9 0 1 1 2.7 2.7l-.1.1a1.5 1.5 0 0 0-.3 1.7v.1a1.5 1.5 0 0 0 1.4.9h.2a1.9 1.9 0 1 1 0 3.8h-.1a1.5 1.5 0 0 0-1.4.9Z"/>',

        /* ---------- 操作 ---------- */
        chat: '<path d="M20.4 14.6a3 3 0 0 1-3 3H8.4l-4.8 3.4V6.4a3 3 0 0 1 3-3h10.8a3 3 0 0 1 3 3Z"/>'
            + '<path d="M8.6 9.2h6.8M8.6 12.6h4.4"/>',

        plus: '<path d="M12 5.4v13.2M5.4 12h13.2"/>',

        send: '<path d="M20.6 3.4 3.6 10.1l6.3 2.4 2.4 6.3 8.3-15.4Z"/>'
            + '<path d="m9.9 12.5 4.4-4.4"/>',

        arrowUp: '<path d="M12 19.4V4.9M6.1 10.8 12 4.9l5.9 5.9"/>',

        trash: '<path d="M4.2 6.8h15.6"/>'
            + '<path d="M9.6 10.6v6.2M14.4 10.6v6.2"/>'
            + '<path d="M6.4 6.8 7.3 18.4a2.2 2.2 0 0 0 2.2 2h5a2.2 2.2 0 0 0 2.2-2l.9-11.6"/>'
            + '<path d="M9.2 6.8V4.9a1.9 1.9 0 0 1 1.9-1.9h1.8a1.9 1.9 0 0 1 1.9 1.9v1.9"/>',

        pencil: '<path d="M4.2 19.8h4.2L20 8.2a2.9 2.9 0 0 0-4.1-4.1L4.2 15.6Z"/>'
            + '<path d="m14.6 5.4 4 4"/>',

        close: '<path d="M6.2 6.2 17.8 17.8M17.8 6.2 6.2 17.8"/>',

        more: '<circle cx="5.6" cy="12" r="1.4" fill="currentColor" stroke="none"/>'
            + '<circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/>'
            + '<circle cx="18.4" cy="12" r="1.4" fill="currentColor" stroke="none"/>',

        search: '<circle cx="11" cy="11" r="7"/>'
            + '<path d="m19.8 19.8-3.6-3.6"/>',

        upload: '<path d="M12 16.2V4.4M7.4 9 12 4.4 16.6 9"/>'
            + '<path d="M4.2 15.6v2.6a2.4 2.4 0 0 0 2.4 2.4h10.8a2.4 2.4 0 0 0 2.4-2.4v-2.6"/>',

        logout: '<path d="M9.4 20.4H6.2a2.4 2.4 0 0 1-2.4-2.4V6a2.4 2.4 0 0 1 2.4-2.4h3.2"/>'
            + '<path d="m15.3 8.2 3.8 3.8-3.8 3.8M19.1 12H9.2"/>',

        /* ---------- 状态 ---------- */
        check: '<path d="m5 12.6 4.4 4.4L19 7.4"/>',

        alert: '<path d="M10.3 3.9 2.6 17.4a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/>'
            + '<path d="M12 9v4.2M12 16.6h.01"/>',

        info: '<circle cx="12" cy="12" r="9"/>'
            + '<path d="M12 11.2v5.2M12 7.9h.01"/>',

        /* ---------- 数据 / 指标 ---------- */
        sparkles: '<path d="m12 3 1.8 4.5 4.5 1.8-4.5 1.8L12 15.6l-1.8-4.5L5.7 9.3l4.5-1.8Z"/>'
            + '<path d="m18.4 15.2.9 2.2 2.2.9-2.2.9-.9 2.2-.9-2.2-2.2-.9 2.2-.9Z"/>',

        database: '<ellipse cx="12" cy="5.6" rx="8" ry="3"/>'
            + '<path d="M4 5.6v12.8c0 1.66 3.58 3 8 3s8-1.34 8-3V5.6"/>'
            + '<path d="M4 12c0 1.66 3.58 3 8 3s8-1.34 8-3"/>',

        zap: '<path d="M13.4 2.6 4.6 13.8h5.8l-.8 7.6 8.8-11.2h-5.8Z"/>',

        coins: '<ellipse cx="9.2" cy="6.8" rx="5.8" ry="2.6"/>'
            + '<path d="M3.4 6.8v4c0 1.44 2.6 2.6 5.8 2.6s5.8-1.16 5.8-2.6v-4"/>'
            + '<path d="M15 10.2c3.06 0 5.6 1.1 5.6 2.5v3.9c0 1.4-2.5 2.5-5.6 2.5-1.4 0-2.7-.24-3.7-.64"/>',

        gauge: '<path d="M12 12.8a2.6 2.6 0 1 1-2.6-2.6"/>'
            + '<path d="M12 10.2 15.6 6.6"/>'
            + '<path d="M3.6 17.4a9 9 0 1 1 16.8 0"/>',

        activity: '<path d="M3.4 12.4h3.8l2.6-7.6 4.4 14.4 2.6-6.8h3.8"/>',

        layers: '<path d="m12 3.2 8.4 4.5-8.4 4.5L3.6 7.7Z"/>'
            + '<path d="m3.6 12.2 8.4 4.5 8.4-4.5"/>'
            + '<path d="m3.6 16.6 8.4 4.5 8.4-4.5"/>',

        file: '<path d="M14.2 3.2v4.9h4.9"/>'
            + '<path d="M19.1 8.1v11.4a2.3 2.3 0 0 1-2.3 2.3H7.2a2.3 2.3 0 0 1-2.3-2.3V4.5a2.3 2.3 0 0 1 2.3-2.3h7Z"/>',

        key: '<circle cx="8" cy="15.4" r="3.6"/>'
            + '<path d="m10.6 12.9 8.2-8.2M16.4 7.1l2.2 2.2M14.2 9.3l2.2 2.2"/>',

        /* 复制：两张错开的纸，前面那张实线、后面那张只留轮廓 */
        copy: '<rect x="8.6" y="8.6" width="11.4" height="11.4" rx="2.6"/>'
            + '<path d="M15.4 5.4H6.6a2.2 2.2 0 0 0-2.2 2.2v8.8"/>',

        user: '<circle cx="12" cy="8.4" r="3.7"/>'
            + '<path d="M4.8 20.2a7.2 7.2 0 0 1 14.4 0"/>',

        image: '<rect x="3.4" y="4.6" width="17.2" height="14.8" rx="2.8"/>'
            + '<circle cx="8.8" cy="10" r="1.7"/>'
            + '<path d="m4.2 17.6 4.6-4.4 3.4 3.1 3-3 4.6 4.4"/>',

        shield: '<path d="M12 3.2 4.8 6v6.1c0 4.3 3 7.5 7.2 8.7 4.2-1.2 7.2-4.4 7.2-8.7V6Z"/>'
            + '<path d="m9.2 12.2 2 2 3.6-3.9"/>',

        clock: '<circle cx="12" cy="12" r="8.8"/>'
            + '<path d="M12 7.4v5l3.2 1.9"/>',

        /* ---------- 方向 ---------- */
        chevronDown: '<path d="m6.4 9.4 5.6 5.6 5.6-5.6"/>',
        chevronRight: '<path d="m9.4 6.4 5.6 5.6-5.6 5.6"/>',
        arrowLeft: '<path d="M19.6 12H4.8M10.9 5.9 4.8 12l6.1 6.1"/>',
        arrowRight: '<path d="M4.4 12h14.8M13.1 5.9 19.2 12l-6.1 6.1"/>'
    };

    /**
     * 取一个图标的 SVG 字符串
     * @param {string} name ICONS 里的键
     * @param {number} [size] 边长，默认 18
     */
    function Icon(name, size) {
        var body = ICONS[name];
        if (!body) {
            return '';
        }
        var s = size || 18;
        return '<svg class="ic" viewBox="0 0 24 24" width="' + s + '" height="' + s + '"'
            + ' fill="none" stroke="currentColor" stroke-width="1.75"'
            + ' stroke-linecap="round" stroke-linejoin="round"'
            + ' aria-hidden="true" focusable="false">' + body + '</svg>';
    }

    /**
     * 把页面里所有 [data-icon] 占位填上 SVG。
     * 只处理还没挂过的，重复调用安全。
     */
    function mountIcons(root) {
        var scope = root || document;
        var nodes = scope.querySelectorAll('[data-icon]');
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            if (node.getAttribute('data-icon-done')) {
                continue;
            }
            var name = node.getAttribute('data-icon');
            var size = parseInt(node.getAttribute('data-icon-size'), 10);
            node.innerHTML = Icon(name, size || undefined);
            node.setAttribute('data-icon-done', '1');
        }
    }

    global.Icon = Icon;
    global.mountIcons = mountIcons;

    // 脚本在 body 末尾加载，此时静态节点已经就绪
    mountIcons(document);

    // 动态渲染（表格、弹窗、空状态）后自动补齐图标
    if (typeof MutationObserver !== 'undefined') {
        var pending = false;
        new MutationObserver(function () {
            if (pending) {
                return;
            }
            pending = true;
            requestAnimationFrame(function () {
                pending = false;
                mountIcons(document);
            });
        }).observe(document.body, { childList: true, subtree: true });
    }
})(window);

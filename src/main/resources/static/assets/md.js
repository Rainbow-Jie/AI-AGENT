/* ============================================================
   轻量 Markdown 渲染（无依赖，先转义再解析，防 XSS）
   支持：代码块 / 行内代码 / 标题 / 列表 / 表格 / 引用 / 分割线 / 粗斜体 / 链接
   ============================================================ */
(function (global) {

    function esc(s) {
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function inline(text) {
        let s = text;
        // 链接
        s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (m, label, url) {
            const safe = /^(https?:|mailto:|\/|#)/i.test(url) ? url : '#';
            return '<a href="' + safe + '" target="_blank" rel="noopener noreferrer">' + label + '</a>';
        });
        // 粗体 / 斜体 / 删除线
        s = s.replace(/\*\*\*([^*]+)\*\*\*/g, '<strong><em>$1</em></strong>');
        s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
        s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
        s = s.replace(/~~([^~]+)~~/g, '<del>$1</del>');
        return s;
    }

    function render(src) {
        if (!src) return '';

        // 1. 抽出代码块，避免内部被解析
        const codes = [];
        let text = String(src).replace(/```([a-zA-Z0-9#+-]*)\n?([\s\S]*?)```/g, function (m, lang, code) {
            codes.push({lang: lang || '', code: code.replace(/\n$/, '')});
            return '\u0000C' + (codes.length - 1) + '\u0000';
        });

        // 2. 整体转义
        text = esc(text);

        // 3. 行内代码占位
        const ics = [];
        text = text.replace(/`([^`\n]+)`/g, function (m, c) {
            ics.push(c);
            return '\u0000I' + (ics.length - 1) + '\u0000';
        });

        const lines = text.split('\n');
        const out = [];
        let i = 0;

        const isTableSep = (s) => /^\s*\|?[\s:*-]*-[\s:|*-]*\|?\s*$/.test(s) && s.indexOf('-') >= 0;

        while (i < lines.length) {
            let line = lines[i];

            // 空行
            if (!line.trim()) {
                i++;
                continue;
            }

            // 代码块占位
            const codeMatch = line.trim().match(/^\u0000C(\d+)\u0000$/);
            if (codeMatch) {
                const c = codes[Number(codeMatch[1])];
                out.push('<pre><code>' + esc(c.code) + '</code></pre>');
                i++;
                continue;
            }

            // 分割线
            if (/^\s*([-*_])\s*\1\s*\1[\s\-*_]*$/.test(line) && line.replace(/\s/g, '').length >= 3) {
                out.push('<hr>');
                i++;
                continue;
            }

            // 标题
            const h = line.match(/^(#{1,6})\s+(.*)$/);
            if (h) {
                const lv = Math.min(h[1].length, 4);
                out.push('<h' + lv + '>' + inline(h[2].trim()) + '</h' + lv + '>');
                i++;
                continue;
            }

            // 表格
            if (line.indexOf('|') >= 0 && i + 1 < lines.length && isTableSep(lines[i + 1])) {
                const headCells = splitRow(line);
                i += 2;
                let body = '';
                while (i < lines.length && lines[i].indexOf('|') >= 0 && lines[i].trim()) {
                    const cells = splitRow(lines[i]);
                    body += '<tr>' + cells.map(function (c) {
                        return '<td>' + inline(c) + '</td>';
                    }).join('') + '</tr>';
                    i++;
                }
                out.push('<table><thead><tr>' + headCells.map(function (c) {
                    return '<th>' + inline(c) + '</th>';
                }).join('') + '</tr></thead><tbody>' + body + '</tbody></table>');
                continue;
            }

            // 引用
            if (/^\s*>\s?/.test(line)) {
                const buf = [];
                while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
                    buf.push(lines[i].replace(/^\s*>\s?/, ''));
                    i++;
                }
                out.push('<blockquote>' + inline(buf.join('<br>')) + '</blockquote>');
                continue;
            }

            // 无序列表
            if (/^\s*[-*+]\s+/.test(line)) {
                const items = [];
                while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
                    items.push(lines[i].replace(/^\s*[-*+]\s+/, ''));
                    i++;
                }
                out.push('<ul>' + items.map(function (t) {
                    return '<li>' + inline(t) + '</li>';
                }).join('') + '</ul>');
                continue;
            }

            // 有序列表
            if (/^\s*\d+[.)]\s+/.test(line)) {
                const items = [];
                while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) {
                    items.push(lines[i].replace(/^\s*\d+[.)]\s+/, ''));
                    i++;
                }
                out.push('<ol>' + items.map(function (t) {
                    return '<li>' + inline(t) + '</li>';
                }).join('') + '</ol>');
                continue;
            }

            // 普通段落：合并连续的非特殊行为一段
            const para = [];
            while (i < lines.length && lines[i].trim() && !isBlockStart(lines[i])) {
                para.push(lines[i]);
                i++;
            }
            if (para.length) {
                out.push('<p>' + inline(para.join('<br>')) + '</p>');
            } else {
                i++;
            }
        }

        let html = out.join('\n');

        // 还原行内代码
        html = html.replace(/\u0000I(\d+)\u0000/g, function (m, idx) {
            return '<code>' + ics[Number(idx)] + '</code>';
        });

        // 还原代码块（理论上已处理，兜底）
        html = html.replace(/\u0000C(\d+)\u0000/g, function (m, idx) {
            const c = codes[Number(idx)];
            return '<pre><code>' + esc(c.code) + '</code></pre>';
        });

        return html;
    }

    function isBlockStart(line) {
        return /^\s*(#{1,6}\s|[-*+]\s|\d+[.)]\s|>|\u0000C\d+\u0000)/.test(line)
            || /^\s*([-*_])\s*\1\s*\1/.test(line);
    }

    function splitRow(line) {
        let s = line.trim();
        if (s.startsWith('|')) s = s.slice(1);
        if (s.endsWith('|')) s = s.slice(0, -1);
        return s.split('|').map(function (c) {
            return c.trim();
        });
    }

    global.renderMarkdown = render;
})(window);

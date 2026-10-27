/**
 * 本地假模型服务：模拟 OpenAI /chat/completions（含 SSE 流式 + function calling）
 * 仅用于开发自测，不属于交付内容
 *
 * 用法：node testdata/mock-llm.js
 * 根据最后一条用户消息里的关键词决定调用哪个工具，用来验证工具链路与权限拦截。
 */
const http = require('http');

const PORT = 8898;

const ANSWER = [
    '根据知识库中的《产品说明.md》，文档切片规则如下：\n\n',
    '1. **先抽取纯文本**：支持 txt / md / pdf / docx / csv；\n',
    '2. **按段落切块**：每块约 600 字，相邻块**重叠 120 字**保留上下文；\n',
    '3. **建索引**：中文 bigram 分词 + BM25 打分。\n\n',
    '```text\nchunk_size = 600\noverlap    = 120\n```\n\n',
    '| 参数 | 值 |\n| --- | --- |\n| 块大小 | 600 字 |\n| 重叠 | 120 字 |\n'
].join('');

/**
 * 决定这一轮返回什么：普通文本 or 一个/多个工具调用
 */
function decide(messages) {
    const last = messages[messages.length - 1] || {};

    // 循环模式：用于验证多轮上下文折叠（需要 6 轮以上工具调用）
    // 参数固定不变，顺便验证「重复调用短路」是否生效
    const allText = messages.map((m) => String(m.content || '')).join('\n');
    const loopMatch = allText.match(/循环\s*写?\s*(\d+)/);
    const loopTarget = loopMatch ? Number(loopMatch[1]) : 0;

    // 已经有工具结果了，就输出最终总结（模拟真实的第二轮请求）
    if (last.role === 'tool') {
        const toolMsgs = messages.filter((m) => m.role === 'tool');
        if (loopTarget > 0 && toolMsgs.length < loopTarget) {
            const n = toolMsgs.length;
            // 「循环写 N」：每轮写一个 4000+ 字符的新文件，用来验证 tool_calls 参数折叠
            if (/循环写/.test(allText)) {
                const body = Array.from({length: 120}, (_, i) =>
                    `第 ${i + 1} 行：这是一段用于验证调用参数折叠的占位内容，故意写长一些。`).join('\n');
                return {
                    calls: [{
                        name: 'fs_write',
                        args: {path: `token-test/w${n}.txt`, content: body, mode: 'overwrite'}
                    }]
                };
            }
            // 每轮换 offset，保证输出不同（否则会被「重复调用短路」命中，测不出折叠效果）
            return {
                calls: [{
                    name: 'fs_read',
                    args: {path: 'token-test/big.txt', offset: 1 + n * 100, limit: 100}
                }]
            };
        }
        const outputs = toolMsgs.map((m) => m.content).join('\n---\n');
        return {
            text: '工具已执行完毕，结果如下：\n\n```text\n'
                + outputs.slice(0, 600) + '\n```\n\n以上是真实返回，请核对。'
        };
    }

    const q = String(last.content || '');

    // 越界访问：应被路径沙箱拒绝
    if (q.indexOf('越界') >= 0) {
        return {calls: [{name: 'fs_read', args: {path: '../../../../Windows/win.ini'}}]};
    }
    // 越权调用：不管服务端给了哪些工具都硬调 fs_write，验证后端兜底拦截
    if (q.indexOf('强行') >= 0) {
        return {calls: [{name: 'fs_write', args: {path: 'hack.txt', content: 'x'}}], force: true};
    }
    // 危险命令：应被命令黑名单拒绝
    if (q.indexOf('危险') >= 0) {
        return {calls: [{name: 'shell_run', args: {command: 'format c: /q'}}]};
    }
    // 复合命令绕过尝试：白名单模式下应被拆段拦截
    if (q.indexOf('绕过') >= 0) {
        return {calls: [{name: 'shell_run', args: {command: 'echo ok && del /s /q C:\\Windows'}}]};
    }
    // 两个工具一起调，验证并行调用
    if (q.indexOf('两个') >= 0) {
        return {
            calls: [
                {name: 'fs_list', args: {path: '.'}},
                {name: 'fs_read', args: {path: 'notes/hello.txt'}}
            ]
        };
    }
    if (q.indexOf('命令') >= 0 || q.indexOf('执行') >= 0) {
        return {calls: [{name: 'shell_run', args: {command: 'echo hello-from-agent'}}]};
    }
    if (q.indexOf('写') >= 0) {
        return {calls: [{name: 'fs_write', args: {path: 'notes/hello.txt', content: '你好，这是智能体写入的内容。\n第二行。'}}]};
    }
    if (q.indexOf('删除') >= 0) {
        return {calls: [{name: 'fs_delete', args: {path: 'notes/hello.txt'}}]};
    }
    if (q.indexOf('搜索') >= 0) {
        return {calls: [{name: 'fs_search', args: {pattern: '智能体', mode: 'content'}}]};
    }
    if (q.indexOf('读') >= 0) {
        return {calls: [{name: 'fs_read', args: {path: 'notes/hello.txt'}}]};
    }
    if (q.indexOf('列') >= 0 || q.indexOf('目录') >= 0) {
        return {calls: [{name: 'fs_list', args: {path: '.'}}]};
    }
    return {text: '这是普通回答，本轮没有调用任何工具。'};
}

/**
 * 把一次工具调用拆成多片 SSE 发出去，验证服务端按 index 累积分片的能力
 */
function toolCallChunks(calls) {
    const chunks = [];
    calls.forEach((call, idx) => {
        const json = JSON.stringify(call.args);
        // 第一片：id + name + arguments 的前 4 个字符
        chunks.push({
            tool_calls: [{
                index: idx, id: 'call_' + (idx + 1), type: 'function',
                function: {name: call.name, arguments: json.slice(0, 4)}
            }]
        });
        // 第二片：arguments 中间（不带 id/name，模拟真实拆包）
        if (json.length > 4) {
            const mid = Math.ceil((json.length - 4) / 2) + 4;
            chunks.push({tool_calls: [{index: idx, function: {arguments: json.slice(4, mid)}}]});
            if (json.length > mid) {
                chunks.push({tool_calls: [{index: idx, function: {arguments: json.slice(mid)}}]});
            }
        }
    });
    return chunks;
}

const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
        let payload = {};
        try {
            payload = JSON.parse(body || '{}');
        } catch (e) {
            /* ignore */
        }
        const tools = (payload.tools || []).map((t) => t.function.name);
        const toolLens = (payload.messages || [])
            .filter((m) => m.role === 'tool')
            .map((m) => String(m.content || '').length);
        const argLens = (payload.messages || [])
            .filter((m) => m.role === 'assistant' && Array.isArray(m.tool_calls))
            .flatMap((m) => m.tool_calls.map((t) => String((t.function || {}).arguments || '').length));
        console.log('[mock-llm]', req.method, req.url, 'stream=', payload.stream,
            'messages=', (payload.messages || []).length, 'tools=', tools.join(','));
        console.log('            tool消息长度:', toolLens.join(',') || '(无)',
            ' 总计字符=', (payload.messages || []).reduce((a, m) => a + String(m.content || '').length, 0));
        console.log('            调用参数长度:', argLens.join(',') || '(无)',
            ' 参数总字符=', argLens.reduce((a, b) => a + b, 0));

        if (!payload.messages) {
            res.writeHead(400, {'Content-Type': 'application/json'});
            res.end(JSON.stringify({error: {message: 'no messages'}}));
            return;
        }

        // 非流式：测试连接
        if (!payload.stream) {
            res.writeHead(200, {'Content-Type': 'application/json'});
            res.end(JSON.stringify({
                choices: [{message: {role: 'assistant', content: '收到'}}]
            }));
            return;
        }

        res.writeHead(200, {
            'Content-Type': 'text/event-stream; charset=utf-8',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive'
        });

        const plan = decide(payload.messages);
        const queue = [];

        if (plan.calls && tools.length) {
            // force=true 时故意无视可用工具列表，用来验证服务端有没有兜底校验
            const usable = plan.force ? plan.calls : plan.calls.filter((c) => tools.indexOf(c.name) >= 0);
            if (!usable.length) {
                queue.push({content: '当前智能体没有开启对应的工具权限，我没法操作文件。'});
            } else {
                toolCallChunks(usable).forEach((c) => queue.push(c));
            }
        } else if (plan.calls) {
            if (plan.force) {
                toolCallChunks(plan.calls).forEach((c) => queue.push(c));
            } else {
                queue.push({content: '当前会话没有可用工具。'});
            }
        } else {
            const text = plan.text || ANSWER;
            for (let i = 0; i < text.length; i += 6) {
                queue.push({content: text.slice(i, i + 6)});
            }
        }

        let i = 0;
        // 模拟 DeepSeek：请求里带 stream_options.include_usage 时，流末尾补一条 usage
        const wantUsage = !!(payload.stream_options && payload.stream_options.include_usage);
        const msgs = payload.messages || [];
        const msgChars = msgs.reduce((a, m) => a + String(m.content || '').length, 0);
        const lastChars = String((msgs[msgs.length - 1] || {}).content || '').length;
        const promptTokens = Math.max(1, Math.round(msgChars / 1.5));
        const missTokens = Math.min(promptTokens, Math.max(1, Math.round(lastChars / 1.5)));
        const hitTokens = promptTokens - missTokens;

        const timer = setInterval(() => {
            if (i >= queue.length) {
                clearInterval(timer);
                if (wantUsage) {
                    res.write('data: ' + JSON.stringify({
                        choices: [],
                        usage: {
                            prompt_tokens: promptTokens,
                            completion_tokens: 64,
                            total_tokens: promptTokens + 64,
                            prompt_cache_hit_tokens: hitTokens,
                            prompt_cache_miss_tokens: missTokens
                        }
                    }) + '\n\n');
                }
                res.write('data: [DONE]\n\n');
                res.end();
                return;
            }
            res.write('data: ' + JSON.stringify({choices: [{delta: queue[i]}]}) + '\n\n');
            i++;
        }, 18);

        // 注意：必须监听 res 的 close，req 的 close 在请求体读完时就会触发
        res.on('close', () => clearInterval(timer));
    });
});

server.listen(PORT, '127.0.0.1', () => {
    console.log('mock LLM listening on http://127.0.0.1:' + PORT);
});

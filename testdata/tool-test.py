# -*- coding: utf-8 -*-
"""
工具链路端到端测试（仅开发自测，不属于交付内容）

前提：应用跑在 8899，mock 模型跑在 8898
用法：python testdata/tool-test.py
"""
import json
import glob
import os
import sys
import urllib.request

sys.stdout.reconfigure(encoding='utf-8')

BASE = 'http://127.0.0.1:8899'
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))

TOKEN = None


def call(method, path, body=None):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if TOKEN:
        req.add_header('Authorization', 'Bearer ' + TOKEN)
    with NO_PROXY.open(req, timeout=60) as resp:
        return json.loads(resp.read().decode('utf-8'))


def login():
    global TOKEN
    # 账号密码走环境变量，不要写进仓库
    username = os.environ.get('AH_USER', 'admin')
    password = os.environ.get('AH_PASSWORD', '')
    if not password:
        sys.exit('请先设置环境变量 AH_PASSWORD（管理员密码）再运行')
    d = call('POST', '/api/auth/login', {'username': username, 'password': password})
    TOKEN = d['data']['token']
    print('登录成功，用户：', d['data']['user']['nickname'])


def pick_mock_model():
    models = call('GET', '/api/admin/models')['data']
    for m in models:
        if m['name'] == 'Mock 测试':
            return m['id']
    d = call('POST', '/api/admin/models/save', {
        'name': 'Mock 测试', 'provider': 'custom',
        'baseUrl': 'http://127.0.0.1:8898/v1', 'apiKey': 'sk-local',
        'model': 'mock-chat', 'temperature': 0.3, 'maxTokens': 2048,
        'isDefault': 0, 'status': 1
    })
    return d['data']['id']


AGENT_ID = None


def configure_agent(model_id, mode, work_dir, allow_cmds=''):
    global AGENT_ID
    agents = call('GET', '/api/admin/agents')['data']
    a = agents[0]
    AGENT_ID = a['id']
    payload = {
        'id': a['id'], 'name': a['name'], 'avatar': a.get('avatar') or '🤖',
        'description': a.get('description') or '',
        'systemPrompt': a.get('systemPrompt') or '你是助手',
        'llmConfigId': model_id, 'kbIds': a.get('kbIds') or '',
        'welcomeMsg': a.get('welcomeMsg') or '', 'topK': 4, 'status': 1,
        'toolMode': mode, 'workDir': work_dir,
        'allowCmds': allow_cmds, 'denyCmds': '',
        'toolTimeout': 12, 'maxSteps': 4
    }
    d = call('POST', '/api/admin/agents/save', payload)
    if d['code'] != 0:
        raise SystemExit('配置智能体失败：' + d['msg'])
    print('智能体已配置：mode=%s dir=%s' % (mode, d['data']['workDir']))


def stream(question):
    """发一次对话，返回解析后的事件列表"""
    body = {'agentId': AGENT_ID, 'question': question}
    req = urllib.request.Request(BASE + '/api/chat/stream',
                                data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
                                method='POST')
    req.add_header('Content-Type', 'application/json')
    req.add_header('Authorization', 'Bearer ' + TOKEN)
    events = []
    with NO_PROXY.open(req, timeout=120) as resp:
        buf = ''
        for raw in resp:
            line = raw.decode('utf-8').strip()
            if not line.startswith('data:'):
                continue
            payload = line[5:].strip()
            try:
                events.append(json.loads(payload))
            except Exception:
                pass
    return events


def summarize(events):
    text = ''.join(e.get('content', '') for e in events if e.get('type') == 'delta')
    calls = [e for e in events if e.get('type') == 'tool_call']
    results = [e for e in events if e.get('type') == 'tool_result']
    errors = [e for e in events if e.get('type') == 'error']
    return text, calls, results, errors


def run(name, question, expect_tool_ok=None, expect_text_kw=None):
    print('\n' + '=' * 68)
    print('场景：', name, '| 提问：', question)
    events = stream(question)
    text, calls, results, errors = summarize(events)
    print('  工具调用 %d 次，结果 %d 条，错误 %d 条，回答 %d 字'
          % (len(calls), len(results), len(errors), len(text)))
    for c in calls:
        print('    → 调用 %s  参数 %s' % (c.get('name'), (c.get('args') or '')[:90]))
    for r in results:
        flag = 'OK ' if r.get('ok') else 'FAIL'
        print('    ← [%s] %s' % (flag, (r.get('output') or '').replace('\n', ' ')[:130]))
    if errors:
        print('    !! 错误：', errors[0].get('message'))
    if text:
        print('    回答：', text.replace('\n', ' ')[:120])

    ok = True
    if expect_tool_ok is not None:
        if not results:
            print('    [断言失败] 期望有工具执行结果，实际没有')
            ok = False
        else:
            actual = results[0].get('ok')
            if actual != expect_tool_ok:
                print('    [断言失败] 期望 ok=%s，实际 %s' % (expect_tool_ok, actual))
                ok = False
    if expect_text_kw:
        blob = (text or '') + ' '.join((r.get('output') or '') for r in results)
        if expect_text_kw not in blob:
            print('    [断言失败] 期望内容包含「%s」' % expect_text_kw)
            ok = False
    print('    => ' + ('通过' if ok else '未通过'))
    return ok


def main():
    login()
    model_id = pick_mock_model()
    work = 'D:\\HEXO\\LOCAL\\AI\\agent-workspace'

    all_ok = True

    # ---------- 全权模式：正常读写与执行 ----------
    configure_agent(model_id, 'full', work)
    all_ok &= run('列目录', '列出目录里有什么', expect_tool_ok=True)
    all_ok &= run('写文件', '帮我写个笔记文件', expect_tool_ok=True, expect_text_kw='hello')
    all_ok &= run('读文件', '读一下那个文件', expect_tool_ok=True, expect_text_kw='智能体写入')
    all_ok &= run('搜索内容', '搜索一下文件内容', expect_tool_ok=True, expect_text_kw='智能体')
    all_ok &= run('覆盖写入（应自动备份）', '帮我写个笔记文件', expect_tool_ok=True)
    all_ok &= run('执行命令', '执行个命令看看', expect_tool_ok=True, expect_text_kw='hello-from-agent')
    all_ok &= run('两个工具并行', '同时做两个操作', expect_tool_ok=True)

    # ---------- 安全拦截 ----------
    all_ok &= run('路径越界', '帮我越界读个文件', expect_tool_ok=False, expect_text_kw='越界')
    all_ok &= run('危险命令', '执行危险操作', expect_tool_ok=False, expect_text_kw='禁止')

    # ---------- 只读模式 ----------
    configure_agent(model_id, 'read', work)
    all_ok &= run('只读模式写文件', '帮我写个笔记文件', expect_text_kw='没有开启')
    # 模型硬要越权调用时，后端必须自己拦住（纵深防御）
    all_ok &= run('模型强行越权写', '强行写个文件', expect_tool_ok=False, expect_text_kw='权限不足')

    # ---------- 白名单模式 ----------
    configure_agent(model_id, 'full', work, allow_cmds='echo')
    all_ok &= run('白名单内命令', '执行个命令看看', expect_tool_ok=True)
    all_ok &= run('白名单外命令绕过', '试试绕过白名单', expect_tool_ok=False)

    # ---------- 关闭工具 ----------
    configure_agent(model_id, 'off', work)
    all_ok &= run('关闭工具后提问', '列表目录看看', expect_text_kw='没有可用工具')

    # ---------- 落盘产物检查 ----------
    print('\n' + '=' * 68)
    print('文件系统检查')
    note = os.path.join(work, 'notes', 'hello.txt')
    backups = glob.glob(os.path.join(work, '.backup', '*', 'notes', 'hello.txt'))
    print('  笔记文件存在：', os.path.exists(note))
    print('  覆盖前自动备份：', len(backups), '份')
    if os.path.exists(note):
        with open(note, encoding='utf-8') as f:
            print('  文件内容首行：', f.readline().strip())
    all_ok &= os.path.exists(note)
    all_ok &= len(backups) >= 1

    # ---------- 工具记录是否真的落库 ----------
    print('\n' + '=' * 68)
    print('会话工具记录检查')
    sessions = call('GET', '/api/sessions')['data']
    if not sessions:
        print('  [失败] 没有会话')
        all_ok = False
    else:
        sid = sessions[0]['id']
        msgs = call('GET', '/api/sessions/%s/messages' % sid)['data']
        with_tools = [m for m in msgs if m.get('toolLog')]
        print('  最新会话消息 %d 条，其中 %d 条带工具记录' % (len(msgs), len(with_tools)))
        if with_tools:
            rec = with_tools[0]['toolLog'][0]
            print('  示例：%s ok=%s 耗时=%sms' % (rec.get('name'), rec.get('ok'), rec.get('elapsedMs')))
        all_ok &= len(with_tools) > 0

    print('\n' + '=' * 68)
    print('总体结果：', '全部通过' if all_ok else '存在失败项')
    return 0 if all_ok else 1


if __name__ == '__main__':
    sys.exit(main())

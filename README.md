# Agent Hub · 智能体平台

一个可直接运行的智能体（AI Agent）项目：接入 DeepSeek 等 OpenAI 兼容大模型，带**管理配置后台**、**知识库 RAG 检索**、**流式对话**。

- 后端：Spring Boot 3.3.5 + MyBatis + SQLite（JDK 17+，本机用 `D:\JDK20`）
- 前端：原生 HTML/CSS/JS，无构建、无 CDN 依赖，可完全离线
- 检索：中文 bigram 分词 + BM25，零外部依赖（DeepSeek 官方没有 embedding 接口，所以走关键词检索这条路更稳）

> 要放到服务器上公网使用？看 **[deploy/DEPLOY.md](deploy/DEPLOY.md)**：systemd + nginx + HTTPS + 备份，一步步照着做就行。

---

## 一、快速开始

### 1. 启动

双击 `start.bat` 即可（首次会自动编译并准备依赖）。

命令行方式：

```bash
set JAVA_HOME=D:\JDK20
mvn compile
mvn dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime
java -cp "target/classes;target/lib/*" com.goocidata.agent.AgentHubApplication --server.port=8899
```

打开浏览器访问：

```
http://localhost:8899
```

> 端口用 8899 是因为本机 8080 已被其他服务占用。要换端口改 `application.yml` 的 `server.port`。

### 1.1 停止

三种方式，任选一种：

| 方式 | 做法 | 说明 |
| --- | --- | --- |
| **点脚本**（推荐） | 双击 `stop.bat` | 按端口找进程并结束，服务不在跑时会提示"无需停止" |
| 关窗口 | 直接关掉启动时那个黑窗口 | 最直观，但**关窗口后进程不一定马上退**，端口可能仍被占 |
| Ctrl+C | 在启动窗口按 `Ctrl+C`，再输入 `Y` | 前台中断，较温和 |

换过端口的话，记得把 `stop.bat` 里的 `set "PORT=8899"` 一起改掉，它靠端口号定位进程。

### 2. 登录

初始账号由 `application.yml` 的 `agent-hub.init-admin` 决定（本机默认用户名 `nzj`）。

密码有两种来源：

- 配了环境变量 `AGENT_HUB_ADMIN_PASSWORD` 就用它；
- **没配（推荐）就随机生成 12 位**，首次启动时打印在控制台日志里，登录后到「个人中心」改掉即可。

> 首次启动会自动创建该账号，以及一条待填写的 DeepSeek 配置、一个「通用助手」示例智能体。
> 账号只在用户表为空时创建一次，之后改配置不会覆盖已有密码。

### 3. 配好模型（必须）

1. 进入「模型配置」→ 编辑「DeepSeek 官方」
2. 填入在 [platform.deepseek.com](https://platform.deepseek.com/api_keys) 申请的 API Key
3. 点「🔌 测试连接」，出现「连接成功」即可

> Base URL 填 `https://api.deepseek.com`（不用带 `/chat/completions`，系统会自动补）
> 模型填 `deepseek-flash`（通用）或 `deepseek-v4-pro`（更强推理）
> 注意要填**接口模型名**，不是产品名。DeepSeek 目前只接受这两个名字，
> 填成 `deepseek-chat`、`deepseek-V4.1-Flash` 之类会直接返回 400。

### 4. 灌知识库

1. 「知识库」→ 新建，例如「产品手册」
2. 进入详情页，拖入 txt / md / pdf / docx 文件
3. 用右侧「检索测试」输入一句话，确认能召回相关片段

### 5. 建智能体

1. 「智能体」→ 新建
2. 填名称、角色设定（系统提示词）
3. 勾选刚建的知识库
4. 保存后从「进入对话」开始聊天

### 6. 让它能读写文件（可选）

同一个编辑弹窗往下滚，找到**工具能力**：

1. 档位选「读写」或「全权」
2. **工作目录**填一个专门给它的文件夹，例如 `D:\HEXO\LOCAL\AI\agent-workspace`
3. 全权模式下建议填**命令白名单**（如 `git, mvn, node, python`），比留空更安全
4. 保存后到对话页说「列一下工作目录里有什么」验证

工作目录不存在会自动创建。默认建议 `write` 档：能改文件但不能执行命令，等你信任它了再开全权。

---

## 二、功能清单

| 模块 | 能力 |
| --- | --- |
| 仪表盘 | 用户/智能体/知识库/文档/切片/会话统计，配置健康度提醒，快速上手引导 |
| 智能体 | 名称、头像、简介、系统提示词、绑定模型、绑定多个知识库、开场白、检索条数、启停 |
| 模型配置 | 多套模型并存；Base URL / Key / model / 温度 / max_tokens；默认模型；一键测试连接；Key 脱敏存储与返回 |
| 知识库 | 多库管理；上传 txt/md/pdf/docx/csv；自动解析→清洗→切块（600 字/重叠 120）→建索引；切片预览；检索测试 |
| 用户管理 | 新增/编辑/删除；ADMIN 与 USER 两种角色；停用账号；重置密码 |
| 系统设置 | 站点名称、简介、页脚；修改自己的密码；部署说明 |
| 对话 | 按智能体开会话；SSE 流式输出；Markdown 渲染；引用片段可点开查看；历史会话侧栏；会话删除 |
| 工具能力 | 智能体可真正读写文件、执行命令（function calling），按四档权限授权，调用过程在对话里可视化 |

### 工具能力（文件读写与命令执行）

智能体默认只能"说话"。要让它能真正动文件，需要在「智能体」编辑弹窗里开启**工具能力**。

**四档权限，按需给**

| 档位 | 能做什么 | 适用场景 |
| --- | --- | --- |
| 关闭（默认） | 纯对话，不接触文件系统 | 问答、知识库检索 |
| 只读 | 列目录、读文件、按文件名/内容搜索 | 让它帮你读代码、查资料 |
| 读写 | 加上新建/修改/删除/移动文件 | 改配置、写文档、整理目录 |
| 全权 | 再加上执行命令（构建、跑脚本、看版本） | 需要它真的跑起来验证 |

**8 个工具**

| 工具 | 作用 | 最低权限 |
| --- | --- | --- |
| `fs_list` | 列目录（类型/大小/修改时间） | 只读 |
| `fs_read` | 读文本文件，带行号，可分段 | 只读 |
| `fs_search` | 按文件名或文件内容搜关键词 | 只读 |
| `fs_write` | 写文件（覆盖/追加），自动建父目录 | 读写 |
| `fs_mkdir` | 建目录（可递归） | 读写 |
| `fs_delete` | 删文件/目录 | 读写 |
| `fs_move` | 移动或重命名 | 读写 |
| `shell_run` | 在工作目录下执行命令 | 全权 |

**四道安全护栏**

1. **路径沙箱**：所有路径先解析成真实绝对路径再比对，`../../` 逃逸、指向目录外的软链接都会被拒绝，报错会明确告诉你拦在哪。
2. **命令黑名单**：`format`、`diskpart`、`reg`、`shutdown`、`taskkill`、`powershell`、`rm -rf /` 等系统级危险命令永远拒绝，改配置也放不开。
3. **命令白名单**：填了白名单就只允许名单内命令，并且会按 `&&`、`||`、`|`、`;` 拆段逐段校验——`echo ok && del /s /q C:\` 这种复合绕过会被拆出来拦掉。
4. **不真正删除**：`fs_delete` 只是把内容移到工作目录下的 `.trash/时间戳/`；覆盖写入前原文件自动备份到 `.backup/时间戳/`。误删误改都能捞回来。

**工作目录里会自动出现两个内部目录**

```
你的工作目录/
├── .trash/      # 删除的东西临时挪到这里
└── .backup/     # 覆盖写入前的旧版本
```

它们不会出现在 `fs_list` 的结果里，但占磁盘。确认没问题后可以手动清掉。

**对话里能看到每一步**

工具调用会在助手气泡上方显示成卡片：工具名、参数、执行结果、耗时、成功/失败。失败的自动展开，直接看到被拦原因；历史会话回看时也能展开当时做了什么。

**模型不听话时的三层兜底**

真机跑长任务（比如让它写一个完整小游戏）时会遇到几种模型侧的毛病，代码里都做了兜底：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 回答里冒出一堆 `<｜｜DSML｜｜ invoke>` 乱码 | 模型没走 `tool_calls` 通道，把工具调用写成了正文 | 识别标记 → 清空已显示内容 → 把文本还原成真正的工具调用继续执行；还原不出来就剥掉标记，绝不当答案展示 |
| 命令整条失败，报 `bad option` / `不是内部或外部命令` | 模型按 bash 习惯写 `;`、`which`、`python3`，而实际是 cmd.exe | 系统提示里注入真实 OS/shell/已装运行时；命令失败且检测到 bash 语法时直接告诉它正确写法 |
| 工具参数报「不是合法 JSON」 | 长文本里的引号/换行漏转义，或被输出上限截断 | 自动补齐未闭合引号和括号后再解析；救不回来才让它重写 |

另外两个相关机制：

- **输出截断会明说**：读到 `finish_reason=length` 时，回答末尾会标注"被 max_tokens 截断"，而不是把半截当完整答案。写长文件建议用 `fs_write` 的 `mode=append` 分段。
- **轮次用完会收口**：达到「最大执行轮次」后，先给模型一条"工具已撤走，请直接总结"的提示，避免它继续尝试调用（那正是产生乱码的直接诱因）；如果仍无文字产出，会把执行摘要补上，不会给你一个空气泡。

**边界说明（别误解）**

这是**应用层的护栏**，防的是模型乱来和手滑，不是操作系统级隔离。命令一旦被允许执行，它继承的是当前 Java 进程的权限，理论上仍能读写进程有权限的任何位置。所以要跑不可信内容，请用容器或独立账号，别指望这层拦住提权。**日常工作目录给一个专门文件夹，别直接给整个 D 盘。**

---

## 三、知识库是怎么工作的

```
上传文件
  → TextExtractor 抽取纯文本（PDFBox / POI / 文本直读，GBK 自动回退）
  → clean() 清洗（统一换行、压缩空行、合并 PDF 断行）
  → ChunkSplitter 切块：按段落聚合到 600 字，超长段落按句末标点切，块间重叠 120 字
  → 落库 kb_chunk（每块带 kb_id / doc_id / chunk_index）
```

检索时：

```
用户提问
  → 中文 bigram + 英文整词 分词（去停用词）
  → BM25 打分（k1=1.5, b=0.75）
  → 单文档最多取 3 条，避免一个文档垄断结果
  → 命中的片段拼进 system prompt 的「参考资料」区
  → 模型按资料作答，前端展示引用来源
```

索引在内存缓存（知识库内容变更时自动失效），1 万条切片以内的库检索在几十毫秒级。

---

## 四、目录结构

```
agent-hub/
├── pom.xml
├── start.bat                     # 一键启动
├── stop.bat                      # 一键停止（按 8899 端口定位进程）
├── data/agent-hub.db             # SQLite 数据文件（首次启动自动生成）
├── testdata/                     # 开发自测脚本（不影响运行）
│   ├── mock-llm.js               # 本地假模型，模拟流式 + function calling
│   └── tool-test.py              # 工具链路端到端测试（12 个场景）
└── src/main/
    ├── java/com/goocidata/agent/
    │   ├── AgentHubApplication.java
    │   ├── common/               # R 统一返回、BizException、全局异常、雪花 ID
    │   ├── config/               # 拦截器、Token 存储、Web 配置、启动初始化、库结构迁移
    │   ├── controller/           # Auth / Agent / Model / Knowledge / Chat / User / Dashboard / Setting
    │   ├── service/              # 认证、用户、模型、智能体、知识库、检索、对话编排、LLM 客户端
    │   ├── mapper/               # MyBatis 注解式 Mapper（SQLite 方言）
    │   ├── entity/               # 表实体
    │   ├── dto/                  # ChunkHit 检索命中
    │   ├── tool/                 # 工具层：ToolBox(8个工具) / ToolGuard(路径与命令护栏) / ToolContext
    │   └── util/                 # 分词、BM25、切片、文本抽取
    └── resources/
        ├── application.yml
        ├── db/schema.sql         # 建表脚本（幂等）
        └── static/               # 前端
            ├── index.html        # 登录（左品牌 + 右表单）
            ├── console.html      # 管理后台
            ├── chat.html         # 对话
            └── assets/
                ├── app.css       # 设计系统：令牌、组件、响应式
                ├── icon.js       # SVG 图标库 + data-icon 自动挂载
                ├── api.js        # 请求封装、Store、UI 提示与弹窗
                ├── md.js         # 轻量 Markdown 渲染
                ├── console.js    # 后台各视图
                └── chat.js       # 对话交互与流式渲染
```

### 界面与样式

前端不使用任何框架和 CDN，全部手写，断网可用。

- **设计令牌**在 `assets/app.css` 顶部的 `:root` 里。改主色只需改 `--c-brand` / `--c-brand-2` / `--grad-brand` 三处，按钮、选中态、气泡、引用标签会一起变。
- **图标**在 `assets/icon.js`，统一 24×24 视窗、1.75 描边、`currentColor` 着色。两种用法：
  - 静态结构写 `<span data-icon="dashboard"></span>`，脚本加载后自动填充，动态渲染也会被 MutationObserver 补上；
  - 拼字符串用 `Icon('trash', 15)`。
  新增图标往 `ICONS` 里加一条路径即可，不用改 CSS。
- **响应式**三档：1100px 以下两栏卡片堆叠；980px 以下登录页转单栏；820px 以下侧边栏收成图标、会话栏隐藏、表格改为隐藏长文本列并横向滚动。

---

## 五、接口一览

### 认证
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录，返回 token |
| POST | `/api/auth/logout` | 退出 |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/auth/password` | 修改自己的密码 |

### 管理端（需 ADMIN，`/api/admin/**`）
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/dashboard` | 统计与健康度 |
| GET/POST/DELETE | `/api/admin/agents`、`/save`、`/{id}` | 智能体 CRUD |
| GET/POST/DELETE | `/api/admin/models`、`/save`、`/{id}` | 模型配置 CRUD |
| POST | `/api/admin/models/test` | 测试连接 |
| GET/POST/DELETE | `/api/admin/kb`、`/save`、`/{id}` | 知识库 CRUD |
| GET | `/api/admin/kb/{id}/docs` | 文档列表 |
| POST | `/api/admin/kb/{id}/upload` | 上传文档（multipart，字段名 `file`） |
| DELETE | `/api/admin/kb/doc/{docId}` | 删除文档 |
| GET | `/api/admin/kb/doc/{docId}/chunks` | 切片列表 |
| POST | `/api/admin/kb/{id}/search?query=&topK=` | 检索测试 |
| GET/POST/DELETE | `/api/admin/users`、`/save`、`/{id}` | 用户 CRUD |
| GET/POST | `/api/admin/settings` | 站点设置 |

### 对话
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/agents` | 可用的启用智能体 |
| POST | `/api/chat/stream` | SSE 流式对话（body: `{agentId, sessionId, question}`） |
| GET | `/api/sessions` | 我的会话 |
| GET | `/api/sessions/{id}/messages` | 会话消息 |
| DELETE | `/api/sessions/{id}` | 删除会话 |
| POST | `/api/sessions/{id}/rename` | 重命名会话 |

鉴权统一走请求头：`Authorization: Bearer <token>`（或 `X-Token`）。

SSE 事件类型：`meta`（会话 ID + 引用 + 本次可用工具）、`delta`（增量文本）、`tool_call`（模型要求调用工具）、`tool_result`（执行结果）、`done`、`error`。

---

## 六、关键配置

`src/main/resources/application.yml`：

```yaml
server:
  port: 8899                    # 改端口在这（本机 8080 被占用）
spring:
  datasource:
    url: jdbc:sqlite:./data/agent-hub.db   # 换库位置在这
agent-hub:
  init-admin:                   # 首次初始化管理员（表非空时不生效）
    username: ${AGENT_HUB_ADMIN_USER:nzj}
    password: ${AGENT_HUB_ADMIN_PASSWORD:}   # 留空 = 随机生成并打印一次
  security:                     # 公网部署务必看 deploy/DEPLOY.md
    allow-token-param: true     # 允许 ?token=xxx，仅本地调试；prod 已关
    cors-origins: "*"           # 允许的跨域来源，同源部署留空
  token-ttl-minutes: 720        # 登录有效期（滑动续期）
  history-limit: 10             # 带入模型的历史消息条数
  retrieve:
    top-k: 4                    # 默认检索条数（智能体可单独覆盖）
  tools:
    default-work-dir: ${AGENT_HUB_WORKSPACE:./workspace}   # 默认沙箱目录
    max-output: 8000                # 单次工具输出回给模型的字符上限
    max-steps: 6                    # 单次提问最多工具轮次（防死循环）
    timeout-seconds: 30             # 单次命令默认超时
  token:
    keep-recent-results: 0          # 早期工具结果折叠阈值，0 = 关闭（见下节说明）
    summary-max-chars: 150          # 折叠后的摘要字符数
```

> 工具的输出上限和轮次上限要留着，别调太大：轮次越多越费 token，输出越长春上下文越容易被冲掉。

### Token 消耗与成本控制

#### 1. 先搞清楚钱花在哪

DeepSeek 现在的计价是三段式（人民币 / 百万 token，2026-08-17 起分峰谷时段）：

| 项目 | 空闲时段 | 高峰时段 | 备注 |
| --- | --- | --- | --- |
| 输入 · 缓存命中 | 0.05 元 | 0.10 元 | **最便宜**，比未命中便宜 30 倍 |
| 输入 · 缓存未命中 | 1.5 元 | 3.0 元 | 新增内容都要走这个价 |
| 输出 | 4.5 元 | 9.0 元 | 模型生成的字，最贵 |

高峰时段是工作日 09:00-12:00 和 14:00-18:00，其余时间（含周末、节假日）都是空闲价。

由此得出两条判断原则：

- **破坏上下文缓存是非常亏的**。少 30% 的 token 却让缓存全部失效，总费用反而会贵 2.5 倍。
- **真正值得省的是「每轮新增的内容」**（工具输出、模型写出的文件内容），因为它们必然全价，而且会被后续每一轮重复计量。

#### 2. 已经做的优化

| 优化 | 效果 |
| --- | --- |
| 系统提示静态化 | 参考资料从 system 挪到用户消息末尾，system 变成跨请求固定前缀，实测固定部分 1280 token 每轮都命中 |
| 工具输出头尾保留 | 超长输出改成「前 40% + 后 60%」，同样的字符数装更多有效信息（构建报错的结论都在尾部） |
| 重复调用短路 | 同一轮里相同工具 + 相同参数且结果没变，只回一段摘要，不再重复回灌整份输出 |
| 历史噪音过滤 | 程序生成的兜底文案（"达到步数上限"之类）不进下一轮上下文 |
| 用量统计 | 每次回答都记录真实 token 数、缓存命中率、估算费用，可在聊天页和仪表盘查看 |

#### 3. 折叠历史工具结果（默认关闭）

`agent-hub.token.keep-recent-results` 控制是否把早期的工具结果压成一行摘要。

**默认 0（关闭）**，因为算下来不划算：折叠要改写历史内容，会让该位置之后的缓存整段失效。按现价，这个代价比省下的多。代码里已经做了「攒够一批再折叠」的退避，把失效次数从每轮一次降到每 N 轮一次，但常见长度的任务仍然回不了本。

什么时候才值得打开：

- 上下文逼近模型上限，不折叠会直接报错；
- 用的模型服务没有前缀缓存（很多三方中转没有）。

```yaml
agent-hub:
  token:
    keep-recent-results: 0    # 0 关闭；打开建议 6~10
    summary-max-chars: 150    # 折叠后摘要的最大字符数
```

#### 4. 看到用量

- **聊天页**：每条回答下方显示 `↑输入 ↓输出 · 总 token · 缓存命中 X% · ≈¥金额`
- **仪表盘**：累计输入 / 输出 / 合计 token、缓存命中率、预估总费用

费用是**估算值**，按 DeepSeek-V4-Flash 价格算的。换别的模型或官方调价时，改 `static/assets/api.js` 顶部的 `TOKEN_PRICE` 即可。实际以账单为准。

#### 5. 还能怎么省（按性价比排序）

1. **别在高峰时段跑大任务**：同样的活，空闲时段价格直接打对折。
2. **写大文件用分段 `fs_write`**：一次写 5000 字符会先算 5000 字符的输出费，之后每一轮还要按输入再计一遍。分段写虽然也花钱，但模型中途有个 `fs_read` 复核，能少踩"写错了整体重写"的坑。
3. **纯聊天的问题把工具档位切到 `off`**：省下每轮 1000 字符左右的工具定义。
4. **同一个问题别反复问**：同一智能体的 system 前缀是固定的，连着问能吃到缓存；隔很久再问，缓存可能已经过期（几小时到几天）。

反过来，**不建议**做的：

- 砍 `history-limit`：多轮连贯性变差，模型会重复问已经说过的事，反而更费。
- 砍 `max-steps`：任务做不完要重来一遍，总花费更高。
- 砍 `max-output`：模型看不清文件内容，容易改错、反复试。

---

## 七、备份与迁移

- 所有数据都在 `./data/agent-hub.db` 单文件里，**备份直接复制这个文件**即可
- 换成 MySQL：把 `spring.datasource` 换成 MySQL 连接、驱动换成 `mysql-connector-j`，并把 `db/schema.sql` 里的 SQLite 语法微调（`INTEGER` 主键换成 `VARCHAR(64)`、`ON CONFLICT` 换 `ON DUPLICATE KEY`）
- 打包成单文件 jar：`mvn clean package -DskipTests`，产物 `target/agent-hub.jar`，`java -jar target/agent-hub.jar` 即可运行。**注意**：这条命令要联网下载 spring-boot 插件的构建依赖，本机网络较慢时可能耗时十几分钟；日常启动用 `start.bat` 更快（走 `target/lib` + classpath，不依赖该插件）
- `target/lib` 目录是运行时依赖（60 个 jar），删掉后 `start.bat` 会自动重新拷

---

## 八、常见问题

**Q：对话报「模型调用失败（HTTP 401）」**
A：API Key 不对或过期，到「模型配置」重新粘贴。注意别带空格。

**Q：报「接口地址或模型名称不存在（404）」或「HTTP 400 supported API model names are...」**
A：Base URL 或 model 写错。DeepSeek 是 `https://api.deepseek.com` + `deepseek-flash`（或 `deepseek-v4-pro`）。
报 400 且提示 supported model names 时，说明模型名不在服务端白名单里——按提示里列出的名字原样填。
如果用 `/v1` 结尾的地址，系统也能兼容。

**Q：上传 PDF 提示「没有解析出文本」**
A：那是扫描版 PDF（整页是图片），需要先 OCR。本项目只做文本层抽取。

**Q：回答没有引用知识库内容**
A：按顺序检查——① 智能体是否勾选了知识库；② 文档切片数是否 > 0；③ 用「检索测试」看关键词能否召回（召回不到就换更具体的问法，或补充文档）。

**Q：想让召回更准**
A：DeepSeek 官方没有 embedding 接口，本项目用关键词检索。要语义检索需再接一家 embedding（硅基流动/阿里百炼），在 `RetrievalService` 里换掉打分逻辑即可。

**Q：JAVA_HOME 是 JDK 8 怎么办**
A：`start.bat` 里已固定用 `D:\JDK20`。用命令行启动时先 `set JAVA_HOME=D:\JDK20`。Spring Boot 3.3 至少要 JDK 17。

**Q：让它读文件，它说「没有可用的工具」**
A：智能体的工具能力是关的。到「智能体」→ 编辑 → 工具能力，选「只读」或更高，并填好工作目录。

**Q：报「路径越界」**
A：它想访问工作目录以外的路径，被沙箱挡了。如果确实是正当需求，把工作目录改成更大的范围；不要为了省事直接填 `C:\`。

**Q：报「命令不在白名单内」**
A：你给这个智能体配了命令白名单，它想用的命令不在里面。要么把命令加进白名单，要么清空白名单（退回内置黑名单模式）。

**Q：它把文件改坏了，能恢复吗**
A：覆盖写入前会自动备份到工作目录的 `.backup/时间戳/` 下，删除则移入 `.trash/时间戳/`。按时间戳找回来就行。**注意这两个目录会一直攒，定期清。**

**Q：一次提问它调工具调个不停**
A：有轮次上限（默认 6，可在智能体里改）。到上限会自动收口，让它基于已有信息作答，不会无限循环。

**Q：怎么知道它到底改了什么**
A：对话里每次工具调用都有卡片，展开能看到完整参数和返回；历史会话回看也保留着这些记录。

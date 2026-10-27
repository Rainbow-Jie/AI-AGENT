-- ============================================================
-- Agent Hub 建表脚本（SQLite）
-- 所有语句幂等，可重复执行
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_user (
    id            TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password      TEXT NOT NULL,
    nickname      TEXT,
    avatar        TEXT,                      -- 头像：data:image/... 的 data URL，空表示用昵称首字
    role          TEXT DEFAULT 'USER',
    status        INTEGER DEFAULT 1,
    last_login_at TEXT,
    created_at    TEXT,
    updated_at    TEXT
);

CREATE TABLE IF NOT EXISTS llm_config (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    provider    TEXT DEFAULT 'deepseek',
    base_url    TEXT NOT NULL,
    api_key     TEXT NOT NULL,
    model       TEXT NOT NULL,
    temperature REAL DEFAULT 0.7,
    max_tokens  INTEGER DEFAULT 2048,
    is_default  INTEGER DEFAULT 0,
    status      INTEGER DEFAULT 1,
    remark      TEXT,
    created_at  TEXT,
    updated_at  TEXT
);

CREATE TABLE IF NOT EXISTS agent_info (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    avatar        TEXT,
    description   TEXT,
    system_prompt TEXT,
    llm_config_id TEXT,
    kb_ids        TEXT,
    welcome_msg   TEXT,
    temperature   REAL,
    top_k         INTEGER DEFAULT 4,
    status        INTEGER DEFAULT 1,
    created_at    TEXT,
    updated_at    TEXT,
    -- ===== 工具权限（新增，老库由 DbMigration 自动补列）=====
    tool_mode     TEXT DEFAULT 'off',      -- off 关闭 / read 只读 / write 读写 / full 全权(含执行命令)
    work_dir      TEXT,                    -- 沙箱根目录，所有文件操作必须落在其内
    allow_cmds    TEXT,                    -- 命令白名单，逗号分隔；为空则走黑名单兜底
    deny_cmds     TEXT,                    -- 额外自定义黑名单，逗号分隔
    tool_timeout  INTEGER DEFAULT 30,      -- 单次命令超时（秒）
    max_steps     INTEGER DEFAULT 6        -- 单次提问最多工具轮次
);

CREATE TABLE IF NOT EXISTS knowledge_base (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT,
    doc_count   INTEGER DEFAULT 0,
    chunk_count INTEGER DEFAULT 0,
    created_at  TEXT,
    updated_at  TEXT
);

CREATE TABLE IF NOT EXISTS kb_document (
    id          TEXT PRIMARY KEY,
    kb_id       TEXT NOT NULL,
    name        TEXT NOT NULL,
    file_type   TEXT,
    file_size   INTEGER DEFAULT 0,
    char_count  INTEGER DEFAULT 0,
    chunk_count INTEGER DEFAULT 0,
    status      TEXT DEFAULT 'READY',
    error_msg   TEXT,
    created_at  TEXT
);
CREATE INDEX IF NOT EXISTS idx_doc_kb ON kb_document (kb_id);

CREATE TABLE IF NOT EXISTS kb_chunk (
    id          TEXT PRIMARY KEY,
    kb_id       TEXT NOT NULL,
    doc_id      TEXT NOT NULL,
    doc_name    TEXT,
    chunk_index INTEGER,
    content     TEXT NOT NULL,
    char_len    INTEGER,
    created_at  TEXT
);
CREATE INDEX IF NOT EXISTS idx_chunk_kb ON kb_chunk (kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_doc ON kb_chunk (doc_id);

CREATE TABLE IF NOT EXISTS chat_session (
    id         TEXT PRIMARY KEY,
    agent_id   TEXT,
    agent_name TEXT,
    user_id    TEXT,
    title      TEXT,
    created_at TEXT,
    updated_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_session_user ON chat_session (user_id);

CREATE TABLE IF NOT EXISTS chat_message (
    id         TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    role       TEXT,
    content    TEXT,
    refs       TEXT,
    tool_log   TEXT,                        -- 工具调用记录（JSON 数组），回看时可展开
    usage      TEXT,                        -- 本条回答的 token 用量（JSON），含缓存命中明细
    created_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_msg_session ON chat_message (session_id, created_at);

CREATE TABLE IF NOT EXISTS sys_setting (
    k TEXT PRIMARY KEY,
    v TEXT
);

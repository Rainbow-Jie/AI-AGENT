# Agent Hub 公网部署手册

> 目标：一台 Linux 服务器 + 域名 + HTTPS，单实例跑起来，能对外用。
> 假设系统是 Ubuntu 22.04 / Debian 12（CentOS 把 `apt` 换成 `dnf`、包名改 `nginx` 即可）。

---

## 0. 先回答：这套东西能上公网吗

**能，但要认清它的边界。** 它是给「一个团队内部用」设计的单机应用：

| 维度 | 现状 | 结论 |
| --- | --- | --- |
| 部署形态 | Spring Boot 单进程 + SQLite 文件 | 单机单实例，**不能多副本横向扩容** |
| 账号 | 登录令牌存在**进程内存**里 | 重启 = 所有人重新登录；多实例会互不认 |
| 数据 | 一个 `agent-hub.db` 文件 | 备份就是复制文件；写入串行，几十人够用 |
| 文件 | 知识库正文存库，原文件不落盘 | 备份数据库即可，不用管上传目录 |
| 并发 | 对话是 SSE 长连接，单轮可能几分钟 | Tomcat 50 线程够 20~30 人同时聊 |

**够用的场景**：公司内网/公网小范围使用，几十个账号，每天几百次问答。
**不够用的场景**：几百人并发、需要多台机器负载均衡、需要审计级权限管控 —— 那要把 SQLite 换 MySQL、令牌换 JWT + Redis。

---

## 1. 服务器准备

```bash
# JDK 17 或以上（项目用 17 编译，21 也能跑）
apt update && apt install -y openjdk-17-jdk nginx certbot python3-certbot-nginx sqlite3

# 建一个专用账号，别用 root 跑 Java
useradd -r -m -d /opt/agent-hub -s /usr/sbin/nologin agenthub
```

> 本机开发用的是 `D:\JDK20`，服务器上不需要一样，只要 ≥17。

---

## 2. 打包

在**能联网的开发机**上打（服务器上不必装 Maven）：

```bash
cd agent-hub
mvn clean package -DskipTests
# 产物：target/agent-hub.jar（可执行 jar，自带 Tomcat）
```

传到服务器：

```bash
scp target/agent-hub.jar root@your-server:/opt/agent-hub/
```

目录结构（服务器上）：

```
/opt/agent-hub/
├── agent-hub.jar
├── data/          # SQLite 数据库，自动创建
├── logs/          # 滚动日志
├── workspace/     # 智能体的文件工作目录
└── deploy/        # 本目录这几个配置文件，方便对照
```

```bash
mkdir -p /opt/agent-hub/{data,logs,workspace}
chown -R agenthub:agenthub /opt/agent-hub
chmod 750 /opt/agent-hub/{data,logs,workspace}
```

---

## 3. 环境变量

```bash
cp deploy/agent-hub.env.example /etc/agent-hub.env
chmod 600 /etc/agent-hub.env
vi /etc/agent-hub.env          # 至少确认 AGENT_HUB_ADMIN_USER
```

**管理员密码别写死在文件里**：`AGENT_HUB_ADMIN_PASSWORD=` 留空，首次启动会随机生成 12 位密码打在日志里：

```bash
journalctl -u agent-hub | grep -A2 "未配置管理员初始密码"
```

> 数据库里已经有用户时，这两行完全不起作用 —— 改密码走页面「个人中心」。

---

## 4. systemd 托管

```bash
cp deploy/systemd/agent-hub.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now agent-hub
systemctl status agent-hub --no-pager
journalctl -u agent-hub -f
```

看到 `Started AgentHubApplication` 即成功；此时只听 `127.0.0.1:8899`，外网还进不来。

---

## 5. nginx 反代 + HTTPS

```bash
cp deploy/nginx/agent-hub.conf /etc/nginx/conf.d/
sed -i 's/ai.example.com/你的域名/g' /etc/nginx/conf.d/agent-hub.conf

# 先用自签证书把 443 段跑通，再让 certbot 换成正式证书
mkdir -p /var/www/certbot
openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
  -keyout /etc/ssl/private/ah.key -out /etc/ssl/certs/ah.crt -subj "/CN=你的域名"
# 把 conf 里两行 ssl_certificate 临时指向上面的自签文件，reload 后再跑 certbot
systemctl reload nginx

certbot --nginx -d 你的域名          # 自动改写证书路径并配好 80→443 跳转
systemctl reload nginx
```

`nginx -t` 必须先过。

**几处不能改的细节**（改错了会出现「回答憋很久才一次性蹦出来」）：

| 配置 | 作用 |
| --- | --- |
| `location = /api/chat/stream` + `proxy_buffering off` | SSE 不缓冲，逐字输出 |
| `proxy_read_timeout 360s` | 后端 SSE 超时 5 分钟，nginx 必须比它长 |
| `client_max_body_size 40m` | 默认 1MB，上传知识库文档会 413 |
| `proxy_set_header Host $host` | 否则前端拿到的跳转地址会带 127.0.0.1 |

### 5.1 与已有站点共用 80 端口（走 `/AI` 子路径）

如果服务器上 80 端口已经被别的站点（或别的 nginx 容器）占着，不必抢端口，
把本应用挂到一个路径前缀下即可，访问地址形如 `http://服务器IP/AI/home`。

**前端已经适配**：`assets/api.js` 顶部会根据当前页面路径自动推出前缀
（`/AI/index.html` → 前缀 `/AI`），接口请求自动拼成 `/AI/api/xxx`；
本地根路径部署时前缀为空，行为不变。所以**不需要改任何业务代码**。

在**已有的**那个 server 块里追加（只加 location，不要动原有配置）：

```nginx
# /AI → /AI/（必须补结尾斜杠，否则页面里的相对资源会解析错）
location = /AI {
    return 302 /AI/;
}

# /AI/home → 首页，浏览器地址保持 /AI/home
location = /AI/home {
    proxy_pass http://127.0.0.1:8899/index.html;
    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection        "";
    proxy_buffering off;
}

# 页面、静态资源、接口统一走 /AI/ 前缀（转发时剥掉 /AI）
location /AI/ {
    proxy_pass http://127.0.0.1:8899/;
    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection        "";
    proxy_read_timeout 360s;
    proxy_buffering    off;
    proxy_cache        off;
}
```

> 如果反代方是 **docker 容器里的 nginx**，`proxy_pass` 不能写 `127.0.0.1`
> （那是容器自己），要写 docker 网关：`http://172.18.0.1:8899`，
> 并保证应用监听 `0.0.0.0`（`server.address` 不能是 `127.0.0.1`）。

**改动后必做的三步**：`nginx -t` 通过 → `nginx -s reload` → 回归验证原有站点首页仍正常。

前缀不叫 `/AI` 也可以（`/agent`、`/ai` 都行），前端不需要跟着改；
但**必须以 `/` 结尾的重定向兜住无斜杠写法**，且不能和现有站点的路径重叠。

---

## 6. 防火墙

```bash
ufw allow OpenSSH
ufw allow 80
ufw allow 443
ufw enable
# 8899 不要放行，它只监听 127.0.0.1
```

> 例外：如果反代方是 **docker 容器里的 nginx**（见 5.1），应用必须监听 `0.0.0.0`
> 容器才连得到，此时 **8899 一定不能在外网安全组里放行**，否则绕过了 nginx
> 那层的限流与 TLS。只想本机调试时用 SSH 隧道：
> `ssh -L 8899:127.0.0.1:8899 root@服务器`。

云服务器（阿里云/腾讯云）还要在**安全组**里放行 80、443。

---

## 7. 上线后立刻做的三件事

1. **登录改密码** —— 用日志里那个随机密码登录 → 左下角头像 → 个人中心 → 修改密码。
2. **填模型 API Key** —— 管理后台「模型配置」→ 编辑 DeepSeek → 填 Key → 测试连接。
3. **检查智能体权限** —— 默认 `tool_mode=off` 最安全。**`full` 会允许模型在服务器上执行 shell 命令**（等同把机器交出去），只给自己人用的智能体才开，且 `work_dir` 一定要指到独立目录。

---

## 8. 日常更新

### 8.1 用运维脚本（推荐）

服务器项目目录下有 `ops.sh`，一条命令完成 **拉代码 → 编译 → 停服 → 换包 → 启动 → 健康检查**，失败自动回滚。

```bash
# 在服务器上
cd /data/AI/agent-hub
./ops.sh                # 拉最新代码并部署（代码没变化会跳过，不动服务）

# 在开发机上一条命令搞定（不用登录服务器）
ssh root@<server> '/data/AI/agent-hub/ops.sh'
```

| 命令 | 作用 |
| --- | --- |
| `./ops.sh` | 拉代码 + 编译 + 部署 + 健康检查（无变化则跳过） |
| `./ops.sh deploy --force` | 强制重建并重启（改了配置、想重来一遍时用） |
| `./ops.sh deploy --no-pull` | 不拉代码，用现有源码重建（网络不通 / 应急） |
| `./ops.sh code` | 只拉代码 |
| `./ops.sh build` | 只编译，不动线上 |
| `./ops.sh restart` | 只重启服务 |
| `./ops.sh rollback` | 回滚到上一个包（可来回切） |
| `./ops.sh check` | 只做健康检查 |
| `./ops.sh status` | 版本 / 进程 / 内存 / 数据量一览 |
| `./ops.sh logs` | 跟踪实时日志 |
| `./ops.sh backup` | 立即备份一次数据库 |
| `./ops.sh self-update` | 更新脚本自身（仓库里 `deploy/ops.sh` 有新版时） |

脚本内部流程：

1. `git fetch` 拉取（老版 git 与 GitHub 握手偶发失败，自动重试 4 次，仍失败则退回 tarball 下载）
2. 对比 commit，无变化直接退出（不动线上）
3. **先停服**（2G 内存的机器，编译时不能和 MySQL 抢内存）→ `mvn clean package`
4. 编译失败 → 立刻把原服务启回来，线上不受影响
5. 编译成功 → 备份当前包为 `agent-hub.jar.prev`（另在 `backup/jar/` 留 5 个历史包）→ 换包
6. 启动 → 10 项健康检查（端口 / 进程 / 鉴权 401 / 静态资源 / 模型 Key / 数据库 / 80 端口入口 / 日志异常 / 开机自启）
7. 健康检查不通过 → 自动回滚到上一版并重启

退出码：`0` 成功，`1` 失败（已自动恢复）。可以放进 CI 或定时任务里判断。

### 8.2 服务器上的编译环境（首次部署要做一次）

```bash
# JDK 17（CentOS 7 仓库里没有，用 Adoptium 免安装版）
mkdir -p /data/AI/pkg && cd /data/AI/pkg
curl -fLO https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz
mkdir -p /data/AI/jdk17 && tar xzf OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz -C /data/AI/jdk17 --strip-components=1

# Maven 3.9
cd /data/AI/pkg
curl -fLO https://mirrors.tuna.tsinghua.edu.cn/apache/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz
mkdir -p /data/AI/maven && tar xzf apache-maven-3.9.16-bin.tar.gz -C /data/AI/maven --strip-components=1

# 走阿里云镜像（否则拉依赖很慢）
mkdir -p /root/.m2
cat > /root/.m2/settings.xml <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <url>https://maven.aliyun.com/repository/public</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
XML
```

实测：2C2G 机器首次编译约 13 秒（下载 108MB 依赖），之后增量编译 6-15 秒。

### 8.3 手工更新（不用脚本时）

```bash
cd /path/to/agent-hub && git pull
mvn clean package -DskipTests
scp target/agent-hub.jar root@server:/opt/agent-hub/agent-hub.jar.new
ssh root@server '
  systemctl stop agent-hub
  mv /opt/agent-hub/agent-hub.jar /opt/agent-hub/agent-hub.jar.bak
  mv /opt/agent-hub/agent-hub.jar.new /opt/agent-hub/agent-hub.jar
  chown agenthub:agenthub /opt/agent-hub/agent-hub.jar
  systemctl start agent-hub'
```

⚠️ 打包前记得停掉本地跑着的开发实例，它用 `-cp target\lib\*` 占着文件，不停会报 `Failed to delete`。

数据库结构变更由 `DbMigration` 在启动时自动补列，**老库直接兼容**，不用手工执行 SQL。
回滚就把 `.bak` 换回来重启（注意：新版本若已写入新字段，回滚后这些字段会被忽略，不会报错）。

---

## 9. 备份

只有一个文件要备份：`data/agent-hub.db`（含知识库正文、账号、会话）。

```bash
# 每天 3 点备份，保留 14 天
cat >/etc/cron.d/agent-hub-backup <<'EOF'
0 3 * * * root sqlite3 /opt/agent-hub/data/agent-hub.db ".backup '/opt/backup/agent-hub-$(date +\%F).db'" && find /opt/backup -name 'agent-hub-*.db' -mtime +14 -delete
EOF
```

**不要直接 `cp` 正在使用的 db 文件**：WAL 模式下还有 `-wal` / `-shm`，单独拷主库可能丢最近的事务。用 `sqlite3 .backup` 或先 `systemctl stop`。

---

## 10. 已知限制（决定上线范围前请先看）

| 项 | 影响 | 缓解 |
| --- | --- | --- |
| 令牌存内存 | 重启后所有人重新登录 | 可接受；要免登录就改 JWT |
| SQLite 单写 | 高并发写会排队（已开 WAL + 15s 忙等） | 几十人规模无感；上百人换 MySQL |
| 无审计日志 | 谁问了什么只在会话表里，管理员可看全部会话 | 需要合规审计要另加日志表 |
| 无登录失败锁定 | 只靠 nginx 限流（10 次/分钟/IP） | 已配 `limit_req`；要更严加 fail2ban |
| 工具可执行命令 | `tool_mode=full` 等于给模型 shell 权限 | 公网智能体保持 `off` / `read` |
| 单实例 | 不能多机负载均衡 | 初期不需要；后续换外部会话存储再扩 |

---

## 11. 上线检查清单

- [ ] `journalctl -u agent-hub` 无异常堆栈，`Started AgentHubApplication`
- [ ] `curl -I https://域名` 返回 200，HTTP 自动跳 HTTPS
- [ ] 登录页能打开，登录后能正常发起一次流式对话（文字逐字出现，不是一次性蹦出）
- [ ] 上传一份文档到知识库，提问能命中并带出处
- [ ] 管理员密码已改；`/etc/agent-hub.env` 权限 600
- [ ] `ss -lntp | grep 8899` 只看到 `127.0.0.1:8899`
- [ ] 备份 cron 已跑过一次，`/opt/backup` 里有文件
- [ ] 智能体 `tool_mode` 符合预期，`work_dir` 不在系统目录下
- [ ] `ops.sh` 已就位且能跑：`/path/to/agent-hub/ops.sh status`、`ops.sh check` 全部通过
- [ ] 走一遍更新流程：改一行代码 → `ops.sh` → 看到「部署成功」，再 `ops.sh rollback` 能切回去

#!/usr/bin/env bash
#
# Agent Hub 运维脚本 —— 一条命令完成 拉代码 → 编译 → 停服 → 换包 → 启动 → 健康检查
#
#   ./ops.sh                 拉最新代码并部署（代码没变化时会跳过，加 --force 强制）
#   ./ops.sh deploy --force  强制重建并重启
#   ./ops.sh deploy --no-pull  不拉代码，直接用服务器现有源码重建（网络不通/应急时用）
#   ./ops.sh code            只拉代码，不编译不重启
#   ./ops.sh build           只编译，不动线上
#   ./ops.sh restart         只重启服务
#   ./ops.sh rollback        回滚到上一个可用版本
#   ./ops.sh check           只做健康检查（不动服务）
#   ./ops.sh status          查看版本、进程、内存、数据
#   ./ops.sh logs            跟踪实时日志
#   ./ops.sh backup          立即备份一次数据库
#   ./ops.sh self-update     更新脚本自身（仓库里 deploy/ops.sh 有新版时用）
#
# 说明：
#   - 编译在服务器本地进行（已装 JDK17 + Maven），首次编译会下载约 100MB 依赖
#   - 编译前会先停服腾出内存（本机 2G，SQLite 与 MySQL 共用同一台机）
#   - 编译或启动失败会自动回滚到 agent-hub.jar.prev，线上不会停摆
#   - 拉代码走 git；老版 git 与 GitHub 握手偶发失败时自动重试，再不行退回 tarball
#
# 作者：阿渡    最后修改：2026-10-27
#

set -uo pipefail

# ============================ 配置 ============================
APP_DIR="/data/AI/agent-hub"
SRC_DIR="$APP_DIR/source"
JAR="$APP_DIR/agent-hub.jar"
PREV_JAR="$APP_DIR/agent-hub.jar.prev"
SERVICE="agent-hub"
PORT=8899
BRANCH="main"
REPO_FALLBACK="https://github.com/Rainbow-Jie/AI-AGENT.git"

JAVA_HOME_DIR="/data/AI/jdk17"
MVN_BIN="/data/AI/maven/bin/mvn"
MAVEN_HEAP="-Xmx400m -XX:MaxMetaspaceSize=256m"

LOG_FILE="$APP_DIR/logs/ops.log"
BUILD_LOG="$APP_DIR/logs/build.log"
KEEP_JARS=5          # backup/jar 下最多保留几个历史包
DEPLOYED_FILE="$APP_DIR/.deployed"   # 记录线上跑的是哪个 commit / 哪个包
SERVICE_STOPPED_BY_US=0   # 停服后置 1，用于中断保护
START_TS=""          # 本次操作起始时间，用于筛日志

# ============================ 输出 ============================
if [ -t 1 ]; then
    C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'
    C_BLU=$'\033[36m'; C_DIM=$'\033[2m'; C_RST=$'\033[0m'
else
    C_RED=""; C_GRN=""; C_YEL=""; C_BLU=""; C_DIM=""; C_RST=""
fi

log() {
    local line="$1"
    printf '%s\n' "$line"
    [ -d "$APP_DIR/logs" ] && printf '[%s] %s\n' "$(date '+%F %T')" "$line" >>"$LOG_FILE" 2>/dev/null
}
info() { log "  ${C_BLU}▸${C_RST} $*"; }
ok()   { log "  ${C_GRN}✓${C_RST} $*"; }
warn() { log "  ${C_YEL}!${C_RST} $*"; }
err()  { log "  ${C_RED}✗${C_RST} $*"; }
step() { log ""; log "${C_BLU}== $* ==${C_RST}"; }
dim()  { log "    ${C_DIM}$*${C_RST}"; }

die() { err "$*"; exit 1; }

# 中断保护：部署过程中如果被 Ctrl-C、SSH 断线、或输出管道被关闭（SIGPIPE）杀掉，
# 服务会停在「已停止」状态，这里负责把它拉起来，避免线上空着。
on_abort() {
    local rc=$?
    [ "$SERVICE_STOPPED_BY_US" = "1" ] || return 0
    SERVICE_STOPPED_BY_US=0
    # 此时 stdout 可能已被管道关闭，提示只写 stderr 与日志文件
    printf '\n[ops] 部署被中断（退出码 %s），正在恢复服务…\n' "$rc" >&2 2>/dev/null
    printf '[%s] 部署被中断（退出码 %s），已尝试恢复服务\n' "$(date '+%F %T')" "$rc" >>"$LOG_FILE" 2>/dev/null
    systemctl start "$SERVICE" >/dev/null 2>&1
    sleep 3
    if svc_active; then
        printf '[ops] 服务已恢复运行（版本未变更）\n' >&2 2>/dev/null
        printf '[%s] 服务已恢复运行\n' "$(date '+%F %T')" >>"$LOG_FILE" 2>/dev/null
    else
        printf '[ops] 恢复失败！请手动执行：systemctl start %s\n' "$SERVICE" >&2 2>/dev/null
    fi
}
trap on_abort EXIT INT TERM HUP

# ============================ 工具函数 ============================
need_root() {
    [ "$(id -u)" -eq 0 ] || die "请用 root 运行（需要 systemctl 与 chown）"
}

preflight() {
    [ -d "$APP_DIR" ] || die "部署目录不存在：$APP_DIR"
    [ -x "$JAVA_HOME_DIR/bin/java" ] || die "找不到 JDK：$JAVA_HOME_DIR"
    [ -x "$MVN_BIN" ] || die "找不到 Maven：$MVN_BIN"
    systemctl list-unit-files "$SERVICE.service" >/dev/null 2>&1 \
        || die "未找到 systemd 服务：$SERVICE.service"
    mkdir -p "$APP_DIR/logs" "$APP_DIR/backup/jar"
}

svc_active() { systemctl is-active --quiet "$SERVICE"; }

port_listening() {
    ss -lnt 2>/dev/null | grep -q ":${PORT} "
}

http_code() {
    curl -s -o /dev/null -m "${2:-3}" -w '%{http_code}' "$1" 2>/dev/null || echo "000"
}

# 进程内存（MB）：重启瞬间会有新旧两个进程交替，取 RSS 最大的那个
svc_rss() {
    local max=0 rss pid
    for pid in $(pgrep -f 'agent-hub\.jar' 2>/dev/null); do
        rss=$(awk '/^VmRSS:/{print $2}' "/proc/$pid/status" 2>/dev/null)
        [ -n "$rss" ] && [ "$rss" -gt "$max" ] && max=$rss
    done
    echo $((max / 1024))
}

jar_md5() { [ -f "$1" ] && md5sum "$1" | cut -c1-12 || echo "-"; }

# 服务器 git 是 1.8.3.1，不支持 git -C，统一用子 shell 切目录
run_git() { ( cd "$SRC_DIR" 2>/dev/null && git "$@" ); }

git_commit() {
    [ -d "$SRC_DIR/.git" ] || { echo "none"; return; }
    run_git rev-parse --short HEAD 2>/dev/null || echo "none"
}

git_subject() {
    run_git log -1 --format='%ad %s' --date=short 2>/dev/null | head -1 || echo ""
}

# 线上实际运行的版本（不能直接拿源码 HEAD，否则「只拉了代码没部署」会被误判成无变化）
deployed_commit() {
    [ -f "$DEPLOYED_FILE" ] && awk '{print $1}' "$DEPLOYED_FILE" 2>/dev/null || echo "unknown"
}

record_deployed() {
    printf '%s %s %s\n' "${1:-$(git_commit)}" "$(jar_md5 "$JAR")" "$(date '+%F %T')" >"$DEPLOYED_FILE" 2>/dev/null && \
        chown agenthub:agenthub "$DEPLOYED_FILE" 2>/dev/null
}

# 从 backup/jar 归档名反查某个包对应的 commit
commit_by_md5() {
    local want="$1" f
    for f in "$APP_DIR"/backup/jar/*.jar; do
        [ -f "$f" ] || continue
        if [ "$(jar_md5 "$f")" = "$want" ]; then
            local b
            b=$(basename "$f" .jar)
            echo "${b##*-}"
            return
        fi
    done
    echo "unknown"
}

# 从 env 文件读公网地址（可选，仅用于展示）
public_url() {
    if [ -f /data/java/fitcheck/nginx/nginx.conf ] &&
       grep -q 'location /AI/' /data/java/fitcheck/nginx/nginx.conf 2>/dev/null; then
        echo "http://<服务器IP>/AI/home"
    else
        echo "http://<服务器IP>:${PORT}/"
    fi
}

# ============================ 1. 拉代码 ============================
# 服务器 git 是 1.8.3.1，配老版 libcurl/NSS，跟 GitHub 握手偶发 "Encountered end of file"，
# 单次失败不代表仓库有问题，重试几次即可；连续失败再退回 tarball 下载。
pull_by_git() {
    local i
    for i in 1 2 3 4; do
        if GIT_TERMINAL_PROMPT=0 run_git fetch --depth 1 -f origin "$BRANCH" 2>"$APP_DIR/logs/git.err"; then
            return 0
        fi
        [ "$i" -lt 4 ] && { warn "第 $i 次 git fetch 失败（$(tail -1 "$APP_DIR/logs/git.err" | cut -c1-70)），2 秒后重试"; sleep 2; }
    done
    return 1
}

# 兜底：直接下 tarball 覆盖源码（不依赖 git 的网络栈），版本号仍从 .git 读
pull_by_tarball() {
    local url tmp
    url="https://codeload.github.com/Rainbow-Jie/AI-AGENT/tar.gz/refs/heads/${BRANCH}"
    tmp="$APP_DIR/source.tmp"
    warn "改用 tarball 下载源码"
    rm -rf "$tmp"; mkdir -p "$tmp"
    if ! curl -fL --retry 3 --connect-timeout 15 -m 180 -o "$APP_DIR/source.tar.gz" "$url" 2>"$APP_DIR/logs/git.err"; then
        err "tarball 下载也失败："; tail -3 "$APP_DIR/logs/git.err" | sed 's/^/      /'
        return 1
    fi
    if ! tar xzf "$APP_DIR/source.tar.gz" -C "$tmp" --strip-components=1 2>>"$APP_DIR/logs/git.err"; then
        err "tarball 解压失败"; return 1
    fi
    # 保留 .git 目录（仅用于显示版本号），其余全量覆盖
    local keep_git=0
    [ -d "$SRC_DIR/.git" ] && keep_git=1
    if [ $keep_git -eq 1 ]; then
        find "$SRC_DIR" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
    else
        rm -rf "$SRC_DIR"; mkdir -p "$SRC_DIR"
    fi
    cp -a "$tmp/." "$SRC_DIR/"
    rm -rf "$tmp" "$APP_DIR/source.tar.gz"
    return 0
}

pull_code() {
    step "拉取代码"
    if [ ! -d "$SRC_DIR/.git" ]; then
        warn "源码目录不存在，首次克隆仓库"
        rm -rf "$SRC_DIR"
        GIT_TERMINAL_PROMPT=0 git clone --depth 1 -b "$BRANCH" "$REPO_FALLBACK" "$SRC_DIR" 2>&1 | tail -5
        if [ -d "$SRC_DIR/.git" ]; then
            ok "克隆完成"
            return 0
        fi
        warn "克隆失败，退回 tarball 方式"
        pull_by_tarball || die "拉取源码失败。若仓库已转为私有，请为服务器配置 deploy key"
        ok "源码就绪（tarball）"
        return 0
    fi

    local before after
    before=$(git_commit)

    if pull_by_git; then
        if ! run_git reset --hard "FETCH_HEAD" >/dev/null 2>&1; then
            warn "git reset 未成功，改用 tarball 覆盖"
            pull_by_tarball || die "拉取源码失败"
        fi
    else
        err "git fetch 连续 4 次失败，最后一条错误："
        tail -2 "$APP_DIR/logs/git.err" | sed 's/^/      /'
        pull_by_tarball || die "拉取源码失败（git 与 tarball 都不可用），请检查服务器网络"
        ok "已通过 tarball 更新源码"
    fi

    after=$(git_commit)
    if [ "$before" = "$after" ]; then
        dim "代码已是 $after（$(git_subject)）"
    else
        ok "代码更新：$before → $after"
        dim "$(git_subject)"
    fi
}

# ============================ 2. 编译 ============================
build_jar() {
    step "编译（本机 2G 内存，已限堆 $MAVEN_HEAP）"
    cd "$SRC_DIR" || return 1
    export JAVA_HOME="$JAVA_HOME_DIR"
    export PATH="$JAVA_HOME_DIR/bin:/data/AI/maven/bin:$PATH"
    export MAVEN_OPTS="$MAVEN_HEAP"

    local t0 rc
    t0=$(date +%s)
    "$MVN_BIN" -B -ntp clean package -DskipTests -Dmaven.test.skip=true >"$BUILD_LOG" 2>&1
    rc=$?
    local cost=$(( $(date +%s) - t0 ))

    if [ $rc -ne 0 ] || [ ! -f "$SRC_DIR/target/agent-hub.jar" ]; then
        err "编译失败（退出码 $rc，耗时 ${cost}s），末尾日志："
        tail -25 "$BUILD_LOG" | sed 's/^/      /'
        dim "完整日志：$BUILD_LOG"
        return 1
    fi

    local new_md5 old_md5
    new_md5=$(jar_md5 "$SRC_DIR/target/agent-hub.jar")
    old_md5=$(jar_md5 "$JAR")
    ok "编译完成：${cost}s，$(du -h "$SRC_DIR/target/agent-hub.jar" | cut -f1)，md5 ${new_md5}"
    if [ "$new_md5" != "$old_md5" ]; then
        dim "与线上包不同（线上 ${old_md5}）"
    fi
    return 0
}

# ============================ 3. 换包 ============================
install_jar() {
    step "替换程序包"
    if [ -f "$JAR" ]; then
        cp -p "$JAR" "$PREV_JAR" || die "备份当前包失败"
        ok "已备份当前版本 → agent-hub.jar.prev（md5 $(jar_md5 "$PREV_JAR")）"
        # 归档带时间戳的副本，方便以后翻旧版
        cp -p "$JAR" "$APP_DIR/backup/jar/agent-hub-$(date +%Y%m%d-%H%M%S)-$(git_commit).jar" 2>/dev/null
        ls -1t "$APP_DIR/backup/jar"/agent-hub-*.jar 2>/dev/null | tail -n +$((KEEP_JARS + 1)) | xargs -r rm -f
    fi
    cp -f "$SRC_DIR/target/agent-hub.jar" "$JAR" || die "复制新包失败"
    chown agenthub:agenthub "$JAR"
    chmod 644 "$JAR"
    ok "新包就位（md5 $(jar_md5 "$JAR")）"
}

# ============================ 4. 健康检查 ============================
# 返回 0 = 全部通过；非 0 = 有致命项失败
health_check() {
    step "健康检查"
    local fail=0

    # --- 4.1 等端口起来 ---
    local i code="000"
    for i in $(seq 1 45); do
        code=$(http_code "http://127.0.0.1:${PORT}/index.html" 3)
        [ "$code" = "200" ] && break
        sleep 2
    done
    if [ "$code" = "200" ]; then
        ok "服务已启动（等待 $((i * 2))s 内响应）"
    else
        err "启动超时：/index.html 返回 $code"
        fail=1
    fi

    # --- 4.2 端口监听 ---
    if port_listening; then
        ok "端口 ${PORT} 正在监听"
    else
        err "端口 ${PORT} 未监听"
        fail=1
    fi

    # --- 4.3 进程 ---
    local pid
    pid=$(pgrep -f 'agent-hub\.jar' | head -1)
    if [ -n "$pid" ]; then
        ok "进程存活（pid $pid，RSS $(svc_rss) MB）"
    else
        err "未找到 java 进程"
        fail=1
    fi

    # --- 4.4 鉴权生效（无令牌必须 401，不能是 200）---
    local auth_body
    auth_body=$(curl -s -m 5 "http://127.0.0.1:${PORT}/api/agents" 2>/dev/null | head -c 60)
    if printf '%s' "$auth_body" | grep -q '"code":401'; then
        ok "鉴权正常（无令牌访问接口返回 401）"
    elif [ -z "$auth_body" ]; then
        warn "鉴权接口无响应，跳过"
    else
        err "鉴权异常！无令牌访问 /api/agents 返回：$auth_body"
        fail=1
    fi

    # --- 4.5 静态资源 ---
    local css
    css=$(http_code "http://127.0.0.1:${PORT}/assets/app.css")
    [ "$css" = "200" ] && ok "静态资源正常" || { err "静态资源返回 $css"; fail=1; }

    # --- 4.6 模型 Key 是否已配（没配的话对话会报「尚未配置 API Key」）---
    # 这里只查有没有、几个模型可用，不打印 key 内容；也不发真实对话请求，避免产生垃圾会话
    if [ -f "$APP_DIR/data/agent-hub.db" ]; then
        local key_ok
        key_ok=$(sqlite3 "$APP_DIR/data/agent-hub.db" \
            "select count(*) from llm_config where status=1 and api_key is not null and api_key<>''" 2>/dev/null || echo "?")
        case "$key_ok" in
            0)   warn "没有可用的模型配置（api_key 为空），对话会提示「尚未配置 API Key」" ;;
            "?") dim "无法读取模型配置，跳过" ;;
            *)   ok "模型配置就绪（$key_ok 个可用）" ;;
        esac
    fi

    # --- 4.7 数据库 ---
    if [ -f "$APP_DIR/data/agent-hub.db" ]; then
        local users sessions
        users=$(sqlite3 "$APP_DIR/data/agent-hub.db" "select count(*) from sys_user" 2>/dev/null || echo "?")
        sessions=$(sqlite3 "$APP_DIR/data/agent-hub.db" "select count(*) from chat_session" 2>/dev/null || echo "?")
        ok "数据库可读（用户 $users 个，会话 $sessions 条）"
    else
        warn "未找到数据库文件"
    fi

    # --- 4.8 经 80 端口的外网链路（配了才测）---
    if [ -f /data/java/fitcheck/nginx/nginx.conf ] &&
       grep -q 'location /AI/' /data/java/fitcheck/nginx/nginx.conf 2>/dev/null; then
        local via
        via=$(http_code "http://127.0.0.1/AI/home")
        [ "$via" = "200" ] && ok "外网入口正常（80 端口 /AI/home 可达）" \
                           || { err "外网入口异常（/AI/home 返回 $via）"; fail=1; }
    else
        dim "未检测到 /AI 反向代理，跳过外网入口测试"
    fi

    # --- 4.9 启动日志有无异常 ---
    local errs
    errs=$(journalctl -u "$SERVICE" --since "$START_TS" --no-pager -o cat 2>/dev/null \
            | grep -E 'ERROR|Exception' \
            | grep -vE 'AsyncRequestNotUsable|HttpMessageNotWritable|Broken pipe' | head -3)
    if [ -z "$errs" ]; then
        ok "启动日志无异常"
    else
        warn "日志中发现异常（不一定是本次引入的）："
        printf '%s\n' "$errs" | sed 's/^/      /'
    fi

    # --- 4.10 开机自启 ---
    if systemctl is-enabled --quiet "$SERVICE"; then
        ok "开机自启已开启"
    else
        warn "开机自启未开启，执行：systemctl enable $SERVICE"
    fi

    return $fail
}

# ============================ 5. 回滚 ============================
do_rollback() {
    need_root
    preflight
    [ -f "$PREV_JAR" ] || die "没有可回滚的备份（$PREV_JAR 不存在）"
    local cur target
    cur=$(jar_md5 "$JAR")
    target=$(jar_md5 "$PREV_JAR")
    if [ "$cur" = "$target" ]; then
        warn "上一个包与当前运行包相同（md5 $cur），没有可回滚的版本"
        dim "历史包在 $APP_DIR/backup/jar/ 下，可手工替换后 $0 restart"
        exit 1
    fi
    step "回滚：$cur → $target"
    # 先把当前包挪到临时位，再把 prev 顶上，最后把旧当前包存回 prev（这样能来回切）
    cp -p "$JAR" "$APP_DIR/jar.swap" || die "暂存当前包失败"
    cp -p "$PREV_JAR" "$JAR" || die "回滚复制失败"
    chown agenthub:agenthub "$JAR"
    mv -f "$APP_DIR/jar.swap" "$PREV_JAR"
    systemctl restart "$SERVICE"
    START_TS=$(date '+%Y-%m-%d %H:%M:%S')
    if health_check; then
        record_deployed "$(commit_by_md5 "$(jar_md5 "$JAR")")"
        ok "回滚成功，当前运行 $(jar_md5 "$JAR")（再执行一次可切回 $cur）"
    else
        die "回滚后健康检查仍有问题，请查看：journalctl -u $SERVICE -n 100"
    fi
}

# ============================ 6. 部署主流程 ============================
do_deploy() {
    need_root
    preflight
    START_TS=$(date '+%Y-%m-%d %H:%M:%S')
    local old_commit new_commit was_running=0
    old_commit=$(deployed_commit)

    log ""
    log "${C_BLU}┌─────────────────────────────────────────────┐${C_RST}"
    log "${C_BLU}│  Agent Hub 部署  $(date '+%F %T')              │${C_RST}"
    log "${C_BLU}└─────────────────────────────────────────────┘${C_RST}"
    dim "当前版本 $old_commit  包 md5 $(jar_md5 "$JAR")"

    if [ "$NOPULL" = "1" ]; then
        warn "已指定 --no-pull，跳过拉代码，直接用现有源码"
    else
        pull_code
    fi
    new_commit=$(git_commit)
    if [ "$old_commit" = "$new_commit" ] && [ "$FORCE" != "1" ] && [ "$NOPULL" != "1" ] && [ -f "$JAR" ]; then
        log ""
        ok "代码无变化（$new_commit），跳过编译。"
        dim "要强制重建并重启：$0 deploy --force"
        dim "只想重启服务：$0 restart"
        exit 0
    fi

    svc_active && was_running=1

    # 编译前先停服：本机 2G 内存，不停的话 Maven 会和 MySQL 抢内存
    if [ $was_running -eq 1 ]; then
        step "停止服务（编译期间释放约 $(svc_rss) MB）"
        systemctl stop "$SERVICE"
        sleep 1
        if svc_active; then
            die "服务停止失败，已中止部署（线上未受影响）"
        fi
        SERVICE_STOPPED_BY_US=1
        ok "服务已停止"
    fi

    if ! build_jar; then
        err "编译失败，正在恢复原服务"
        if [ $was_running -eq 1 ]; then
            systemctl start "$SERVICE"
            SERVICE_STOPPED_BY_US=0
            sleep 3
            svc_active && warn "原版本已恢复运行（未做任何变更）"
        fi
        exit 1
    fi

    install_jar
    step "启动服务"
    systemctl start "$SERVICE"
    SERVICE_STOPPED_BY_US=0
    ok "systemctl start 已下发"

    if health_check; then
        record_deployed "$new_commit"
        log ""
        log "${C_GRN}╭─────────────────────────────────────────────╮${C_RST}"
        log "${C_GRN}│  部署成功                                   │${C_RST}"
        log "${C_GRN}╰─────────────────────────────────────────────╯${C_RST}"
        dim "版本   $new_commit  $(git_subject)"
        dim "内存   $(svc_rss) MB"
        dim "地址   $(public_url)"
        dim "回滚   $0 rollback"
        ops_self_outdated && warn "仓库里的 ops.sh 有更新，执行 $0 self-update 可升级脚本"
        exit 0
    else
        log ""
        err "健康检查未通过，自动回滚"
        cp -f "$PREV_JAR" "$JAR" 2>/dev/null && chown agenthub:agenthub "$JAR"
        systemctl restart "$SERVICE"
        START_TS=$(date '+%Y-%m-%d %H:%M:%S')
        sleep 5
        if health_check >/dev/null 2>&1; then
            warn "已回滚到上一版本（md5 $(jar_md5 "$JAR")），线上恢复可用"
            dim "排查：tail -50 $BUILD_LOG    或    journalctl -u $SERVICE -n 80"
        else
            err "回滚后仍不健康，请人工介入！"
            dim "journalctl -u $SERVICE -n 100 --no-pager"
        fi
        exit 1
    fi
}

# ============================ 7. 其它命令 ============================
do_status() {
    step "运行状态"
    local act en
    act=$(systemctl is-active "$SERVICE" 2>/dev/null)
    en=$(systemctl is-enabled "$SERVICE" 2>/dev/null)
    local pid etime
    pid=$(pgrep -f 'agent-hub\.jar' | head -1)
    etime=$([ -n "$pid" ] && ps -o etime= -p "$pid" 2>/dev/null | tr -d ' ' || echo "-")
    local code
    code=$(http_code "http://127.0.0.1:${PORT}/index.html")
    log "    服务状态   ${act}（开机自启：${en}）"
    local plisten
    if port_listening; then plisten="已监听"; else plisten="未监听"; fi
    log "    本机响应   HTTP ${code}    端口 ${PORT} ${plisten}"
    log "    进程       pid ${pid:--}  运行 ${etime}  内存 $(svc_rss) MB"
    log "    代码版本   线上 $(deployed_commit)（源码 $(git_commit)）"
    log "              $(git_subject)"
    log "    线上包     $(du -h "$JAR" 2>/dev/null | cut -f1)  md5 $(jar_md5 "$JAR")  修改 $(date -r "$JAR" '+%F %T' 2>/dev/null)"
    log "    回滚包     $(du -h "$PREV_JAR" 2>/dev/null | cut -f1)  md5 $(jar_md5 "$PREV_JAR")"
    if [ -f "$APP_DIR/data/agent-hub.db" ]; then
        log "    数据库     $(du -h "$APP_DIR/data/agent-hub.db" | cut -f1)  用户 $(sqlite3 "$APP_DIR/data/agent-hub.db" 'select count(*) from sys_user' 2>/dev/null) 个 / 会话 $(sqlite3 "$APP_DIR/data/agent-hub.db" 'select count(*) from chat_session' 2>/dev/null) 条"
    fi
    log "    访问地址   $(public_url)"
    log "    系统内存   $(free -m | awk '/^Mem:/{printf "总 %sM 已用 %sM 可用 %sM", $2, $3, $7}')"
}

do_check() {
    need_root
    preflight
    START_TS=$(date '+%Y-%m-%d %H:%M:%S')
    if health_check; then
        log ""
        ok "检查通过"
        exit 0
    else
        log ""
        err "检查未通过"
        exit 1
    fi
}

do_restart() {
    need_root
    START_TS=$(date '+%Y-%m-%d %H:%M:%S')
    step "重启服务"
    systemctl restart "$SERVICE"
    ok "已下发重启"
    if health_check; then exit 0; else exit 1; fi
}

do_code() {
    need_root
    pull_code
    ok "代码已更新到 $(git_commit)，未重启服务"
}

do_build() {
    need_root
    preflight
    if svc_active; then
        warn "编译会占用内存，建议先 $0 deploy；这里只编译不动线上"
    fi
    build_jar || exit 1
    ok "产物：$SRC_DIR/target/agent-hub.jar"
}

do_logs() {
    exec journalctl -u "$SERVICE" -f -n 60 --no-pager
}

do_backup() {
    need_root
    [ -x "$APP_DIR/backup.sh" ] || die "找不到 backup.sh"
    "$APP_DIR/backup.sh"
}

# 脚本自己也在仓库里（deploy/ops.sh）。拉完代码若有新版，提示一句，
# 用 self-update 主动升级，避免部署过程中脚本被改写导致行为错乱。
ops_self_outdated() {
    local src="$SRC_DIR/deploy/ops.sh"
    [ -f "$src" ] || return 1
    cmp -s "$src" "$0" && return 1
    return 0
}

do_self_update() {
    need_root
    local src="$SRC_DIR/deploy/ops.sh"
    [ -f "$src" ] || die "仓库里没有 deploy/ops.sh，先执行 $0 code"
    if cmp -s "$src" "$0"; then
        ok "脚本已是最新"
        exit 0
    fi
    step "更新脚本自身"
    cp -p "$0" "$APP_DIR/ops.sh.bak"
    sed 's/\r$//' "$src" >"$APP_DIR/ops.sh.new"
    bash -n "$APP_DIR/ops.sh.new" || die "新版脚本语法检查未通过，已放弃（旧版未动）"
    chmod +x "$APP_DIR/ops.sh.new"
    mv -f "$APP_DIR/ops.sh.new" "$APP_DIR/ops.sh"
    ok "已更新（旧版备份为 ops.sh.bak）"
}

# ============================ 入口 ============================
CMD="deploy"
FORCE=0
NOPULL=0
for arg in "$@"; do
    case "$arg" in
        --force|-f) FORCE=1 ;;
        --no-pull)  NOPULL=1 ;;
        deploy|update|code|build|restart|rollback|check|status|logs|backup|self-update) CMD="$arg" ;;
        -h|--help|help) CMD="help" ;;
        *) die "未知参数：$arg（用 --help 看用法）" ;;
    esac
done

case "$CMD" in
    deploy)   do_deploy ;;
    update)   do_deploy ;;
    code)     do_code ;;
    build)    do_build ;;
    restart)  do_restart ;;
    rollback) do_rollback ;;
    check)    do_check ;;
    status)   do_status ;;
    logs)     do_logs ;;
    backup)   do_backup ;;
    self-update) do_self_update ;;
    *)
        sed -n '3,24p' "$0" | sed 's/^# \{0,1\}//'
        ;;
esac

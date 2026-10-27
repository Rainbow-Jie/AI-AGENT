package com.goocidata.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工具集：文件读写、目录操作、搜索、命令执行。
 * <p>
 * 权限按 mode 分档，工具本身不判断权限，由 {@link #execute} 统一拦截，
 * 避免某个工具忘记校验导致越权。
 *
 * @author nzj
 */
@Slf4j
@Component
public class ToolBox {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 环境探测结果缓存（运行时不常变，探测一次即可） */
    private volatile String envCache;

    /** 工具名 -> 所需最低权限档位 */
    private static final Map<String, Integer> REQUIRED_MODE = new HashMap<>();

    /** 档位数值，越大权限越高 */
    private static final int MODE_READ = 1;
    private static final int MODE_WRITE = 2;
    private static final int MODE_FULL = 3;

    /** 内部目录：备份与回收站，列表时隐藏 */
    private static final String BACKUP_DIR = ".backup";
    private static final String TRASH_DIR = ".trash";

    static {
        REQUIRED_MODE.put("fs_list", MODE_READ);
        REQUIRED_MODE.put("fs_read", MODE_READ);
        REQUIRED_MODE.put("fs_search", MODE_READ);
        REQUIRED_MODE.put("fs_write", MODE_WRITE);
        REQUIRED_MODE.put("fs_mkdir", MODE_WRITE);
        REQUIRED_MODE.put("fs_delete", MODE_WRITE);
        REQUIRED_MODE.put("fs_move", MODE_WRITE);
        REQUIRED_MODE.put("shell_run", MODE_FULL);
    }

    /**
     * 运行环境说明，注入系统提示。
     * <p>
     * 这一步很关键：模型默认按 Linux/bash 习惯写命令（{@code ls}、{@code which}、
     * {@code python3}、用 {@code ;} 串联），在 Windows cmd.exe 下会整条失败，
     * 反复失败后模型容易"另辟蹊径"，甚至把工具调用写成正文文本。
     */
    public String environmentBrief(Path root) {
        String cached = envCache;
        if (cached == null) {
            cached = probeEnvironment();
            envCache = cached;
        }
        return cached + "\n- 工作目录（相对路径的根，优先用相对路径）：" + root;
    }

    private String probeEnvironment() {
        StringBuilder sb = new StringBuilder();
        sb.append("- 操作系统：").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.arch")).append('\n');
        if (isWindows()) {
            sb.append("- 命令执行环境：Windows cmd.exe，**不是** bash / PowerShell\n");
            sb.append("- 命令语法对照（务必遵守）：\n")
                    .append("  · 串联命令用 `&&`，**不要用 `;`**（cmd 里分号不是命令分隔符，会报 bad option）\n")
                    .append("  · 列目录 `dir`、查命令位置 `where node`、按内容找 `findstr /s /i 关键词 *`、删文件 `del`\n")
                    .append("  · 没有 `ls` / `which` / `grep` / `rm` / `python3`，对应 `dir` / `where` / `findstr` / `del` / `python`\n")
                    .append("  · 环境变量写 `%JAVA_HOME%`，不是 `$JAVA_HOME`\n");
        } else {
            sb.append("- 命令执行环境：sh\n");
        }
        List<String> found = new ArrayList<>();
        for (String[] probe : new String[][]{
                {"node", "node --version"},
                {"python", "python --version"},
                {"java", "java -version"},
                {"git", "git --version"}}) {
            String out = quickRun(probe[1]);
            if (out != null && !out.isEmpty()) {
                found.add(probe[0] + " " + out);
            }
        }
        sb.append("- 已安装的运行时：").append(found.isEmpty() ? "未探测到 node / python / java" : String.join("；", found));
        return sb.toString();
    }

    private String quickRun(String command) {
        try {
            List<String> cmdline = isWindows()
                    ? List.of("cmd.exe", "/c", command)
                    : List.of("sh", "-c", command);
            ProcessBuilder pb = new ProcessBuilder(cmdline);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int nl = out.indexOf('\n');
            if (nl > 0) {
                out = out.substring(0, nl).trim();
            }
            if (out.isEmpty()) {
                return null;
            }
            return out.length() > 60 ? out.substring(0, 60) + "..." : out;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 按权限档位返回可用的工具定义（OpenAI tools 格式）
     */
    public List<Map<String, Object>> definitions(String mode) {
        int level = levelOf(mode);
        List<Map<String, Object>> result = new ArrayList<>();
        if (level < MODE_READ) {
            return result;
        }
        add(result, DEF_LIST);
        add(result, DEF_READ);
        add(result, DEF_SEARCH);
        if (level >= MODE_WRITE) {
            add(result, DEF_WRITE);
            add(result, DEF_MKDIR);
            add(result, DEF_DELETE);
            add(result, DEF_MOVE);
        }
        if (level >= MODE_FULL) {
            add(result, isWindows() ? DEF_SHELL_WIN : DEF_SHELL_NIX);
        }
        return result;
    }

    /**
     * 执行工具。所有异常都转成可读结果回给模型，不抛出中断对话。
     */
    public ToolResult execute(String name, String argsJson, ToolContext ctx) {
        long t0 = System.currentTimeMillis();
        try {
            int required = REQUIRED_MODE.getOrDefault(name, 99);
            int current = levelOf(ctx.getMode());
            if (required > current) {
                throw new ToolException("当前智能体权限不足，无法使用【" + name + "】。"
                        + "当前权限：" + modeDesc(ctx.getMode()) + "，需要：" + modeDesc(levelToMode(required)));
            }
            Map<String, Object> args = parseArgs(argsJson);
            String out;
            switch (name) {
                case "fs_list":
                    out = fsList(args, ctx);
                    break;
                case "fs_read":
                    out = fsRead(args, ctx);
                    break;
                case "fs_write":
                    out = fsWrite(args, ctx);
                    break;
                case "fs_mkdir":
                    out = fsMkdir(args, ctx);
                    break;
                case "fs_delete":
                    out = fsDelete(args, ctx);
                    break;
                case "fs_move":
                    out = fsMove(args, ctx);
                    break;
                case "fs_search":
                    out = fsSearch(args, ctx);
                    break;
                case "shell_run":
                    out = shellRun(args, ctx);
                    break;
                default:
                    throw new ToolException("未知工具：" + name);
            }
            return ToolResult.ok(dedup(name, argsJson, out, ctx), System.currentTimeMillis() - t0);
        } catch (ToolException e) {
            return ToolResult.fail(e.getMessage(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("工具执行异常 {}: {}", name, e.getMessage());
            return ToolResult.fail("执行失败：" + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()), System.currentTimeMillis() - t0);
        }
    }

    /**
     * 输出裁剪 + 重复调用识别。
     * <p>
     * 模型偶尔会在原地打转：同一个工具、同样的参数连调两三次。这时把 8000 字符的完整输出
     * 再回灌一遍纯属浪费 token，而且这段内容会在后续每一轮里被重复计量。所以：
     * 先照常执行（本地操作很便宜），拿到结果后和上一次同参数的结果比对，
     * 完全一致就只回一小段摘要并明确告诉它「上文已有完整结果」。
     * <p>
     * 结果是重新算出来的，所以「文件变了导致输出不同」的情况会自然走到正常分支，不会误判。
     */
    private String dedup(String name, String argsJson, String rawOutput, ToolContext ctx) {
        String output = clip(rawOutput, ctx.getMaxOutput());
        String key = name + "|" + normalizeArgs(argsJson);
        String previous = ctx.getResultFingerprint().put(key, output);
        // 短输出本来就没几个 token，换成提示语反而更长，直接原样返回
        if (previous == null || !previous.equals(output) || output.length() <= DEDUP_MIN_CHARS) {
            return output;
        }
        String brief = output.substring(0, 200) + "…";
        return "【与上一次完全相同的调用，结果没有变化，不再重复回灌】"
                + "完整结果共 " + output.length() + " 字符，上文已经给过，请直接引用。\n"
                + "首段预览：" + brief
                + "\n如果需要不同的内容，请调整参数或换一个工具。";
    }

    /** 低于这个字符数的输出不做重复提示，否则提示语比正文还长 */
    private static final int DEDUP_MIN_CHARS = 400;

    private static String normalizeArgs(String json) {
        return json == null ? "" : json.replaceAll("\\s+", "");
    }

    // ==================== 工具实现 ====================

    private String fsList(Map<String, Object> args, ToolContext ctx) throws IOException {
        Path dir = ToolGuard.resolveInside(ctx.getRoot(), str(args, "path", "."));
        if (!Files.exists(dir)) {
            throw new ToolException("目录不存在：" + rel(ctx, dir));
        }
        if (!Files.isDirectory(dir)) {
            throw new ToolException("不是目录：" + rel(ctx, dir) + "（读文件请用 fs_read）");
        }
        List<Path> children;
        try (Stream<Path> stream = Files.list(dir)) {
            children = stream
                    // 备份与回收站属于内部目录，不展示也不计入数量
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !BACKUP_DIR.equals(n) && !TRASH_DIR.equals(n);
                    })
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .limit(400)
                    .collect(Collectors.toList());
        }
        if (children.isEmpty()) {
            return "目录为空：" + rel(ctx, dir);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("目录 ").append(rel(ctx, dir)).append(" 共 ").append(children.size()).append(" 项：\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (Path p : children) {
            String name = p.getFileName().toString();
            if (Files.isDirectory(p)) {
                sb.append("[DIR ] ").append(name).append('/');
            } else {
                sb.append("[FILE] ").append(name).append("  ").append(humanSize(Files.size(p)));
            }
            sb.append("  ").append(Files.getLastModifiedTime(p).toInstant()
                    .atZone(java.time.ZoneId.systemDefault()).format(fmt));
            sb.append('\n');
        }
        return sb.toString();
    }

    private String fsRead(Map<String, Object> args, ToolContext ctx) throws IOException {
        Path file = ToolGuard.resolveInside(ctx.getRoot(), str(args, "path", ""));
        if (str(args, "path", "").isEmpty()) {
            throw new ToolException("请提供要读取的文件路径");
        }
        if (!Files.exists(file)) {
            throw new ToolException("文件不存在：" + rel(ctx, file));
        }
        if (Files.isDirectory(file)) {
            throw new ToolException("这是目录，请用 fs_list 查看：" + rel(ctx, file));
        }
        long size = Files.size(file);
        if (size > 5 * 1024 * 1024L) {
            throw new ToolException("文件过大（" + humanSize(size) + "），超过 5MB 上限，请用 fs_search 按关键词定位");
        }
        if (isBinary(file)) {
            throw new ToolException("这是二进制文件，无法按文本读取：" + rel(ctx, file));
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int offset = Math.max(1, intArg(args, "offset", 1));
        int limit = Math.min(2000, Math.max(1, intArg(args, "limit", 300)));
        if (offset > lines.size()) {
            throw new ToolException("起始行 " + offset + " 超出文件总行数 " + lines.size());
        }
        int end = Math.min(lines.size(), offset - 1 + limit);
        StringBuilder sb = new StringBuilder();
        sb.append("文件 ").append(rel(ctx, file)).append("（共 ").append(lines.size())
                .append(" 行，当前显示 ").append(offset).append('-').append(end).append(" 行）\n");
        for (int i = offset - 1; i < end; i++) {
            sb.append(String.format("%5d| %s%n", i + 1, lines.get(i)));
        }
        if (end < lines.size()) {
            sb.append("...还有 ").append(lines.size() - end)
                    .append(" 行，可用 offset=").append(end + 1).append(" 继续读取");
        }
        return sb.toString();
    }

    private String fsWrite(Map<String, Object> args, ToolContext ctx) throws IOException {
        String rawPath = str(args, "path", "");
        if (rawPath.isEmpty()) {
            throw new ToolException("请提供要写入的文件路径");
        }
        String content = str(args, "content", "");
        String writeMode = str(args, "mode", "overwrite").toLowerCase(Locale.ROOT);
        boolean append = "append".equals(writeMode);

        Path file = ToolGuard.resolveInside(ctx.getRoot(), rawPath);
        if (Files.isDirectory(file)) {
            throw new ToolException("目标是一个目录，不能写入：" + rel(ctx, file));
        }
        boolean existed = Files.exists(file);
        String backupNote = "";
        if (existed && !append) {
            Path backup = backupPath(ctx, file);
            Files.createDirectories(backup.getParent());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            backupNote = "（原内容已备份到 " + rel(ctx, backup) + "）";
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        if (append) {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        long bytes = Files.size(file);
        return (existed ? (append ? "已追加写入 " : "已覆盖写入 ") : "已新建文件 ")
                + rel(ctx, file) + "，本次写入 " + content.length() + " 字符，当前文件 "
                + humanSize(bytes) + backupNote;
    }

    private String fsMkdir(Map<String, Object> args, ToolContext ctx) throws IOException {
        String rawPath = str(args, "path", "");
        if (rawPath.isEmpty()) {
            throw new ToolException("请提供要创建的目录路径");
        }
        Path dir = ToolGuard.resolveInside(ctx.getRoot(), rawPath);
        if (Files.isDirectory(dir)) {
            return "目录已存在：" + rel(ctx, dir);
        }
        Files.createDirectories(dir);
        return "已创建目录：" + rel(ctx, dir);
    }

    private String fsDelete(Map<String, Object> args, ToolContext ctx) throws IOException {
        String rawPath = str(args, "path", "");
        if (rawPath.isEmpty()) {
            throw new ToolException("请提供要删除的路径");
        }
        Path target = ToolGuard.resolveInside(ctx.getRoot(), rawPath);
        if (!Files.exists(target)) {
            throw new ToolException("路径不存在：" + rel(ctx, target));
        }
        if (target.equals(ToolGuard.resolveInside(ctx.getRoot(), "."))) {
            throw new ToolException("不允许删除工作目录本身");
        }
        // 不真删，移动到 .trash 下，误删可恢复
        Path trash = ctx.getRoot().resolve(TRASH_DIR)
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
                .resolve(rel(ctx, target));
        Files.createDirectories(trash.getParent());
        Files.move(target, trash, StandardCopyOption.REPLACE_EXISTING);
        return "已删除：" + rel(ctx, target) + "（未真正销毁，已移入回收站 " + rel(ctx, trash) + "，可恢复）";
    }

    private String fsMove(Map<String, Object> args, ToolContext ctx) throws IOException {
        String from = str(args, "from", "");
        String to = str(args, "to", "");
        if (from.isEmpty() || to.isEmpty()) {
            throw new ToolException("请同时提供 from 和 to 路径");
        }
        Path src = ToolGuard.resolveInside(ctx.getRoot(), from);
        Path dst = ToolGuard.resolveInside(ctx.getRoot(), to);
        if (!Files.exists(src)) {
            throw new ToolException("源路径不存在：" + rel(ctx, src));
        }
        if (Files.isDirectory(dst) && Files.exists(dst)) {
            // 目标是已存在目录时，移动到目录里面
            dst = dst.resolve(src.getFileName().toString());
        }
        if (dst.startsWith(src) && Files.isDirectory(src)) {
            throw new ToolException("不能把目录移动到它自己内部");
        }
        Files.createDirectories(dst.getParent());
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        return "已移动：" + rel(ctx, src) + " -> " + rel(ctx, dst);
    }

    private String fsSearch(Map<String, Object> args, ToolContext ctx) throws IOException {
        String pattern = str(args, "pattern", "");
        if (pattern.isEmpty()) {
            throw new ToolException("请提供搜索关键词");
        }
        String searchMode = str(args, "mode", "content").toLowerCase(Locale.ROOT);
        Path base = ToolGuard.resolveInside(ctx.getRoot(), str(args, "path", "."));
        if (!Files.exists(base)) {
            throw new ToolException("搜索目录不存在：" + rel(ctx, base));
        }
        int maxHits = 100;
        List<String> hits = new ArrayList<>();
        String lower = pattern.toLowerCase(Locale.ROOT);
        int scanned = 0;

        try (Stream<Path> stream = Files.walk(base, 12)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isIgnored(ctx, p))
                    .limit(3000)
                    .collect(Collectors.toList());
            for (Path p : files) {
                scanned++;
                if (hits.size() >= maxHits) {
                    break;
                }
                String name = p.getFileName().toString();
                if ("name".equals(searchMode)) {
                    if (name.toLowerCase(Locale.ROOT).contains(lower)) {
                        hits.add(rel(ctx, p) + "  " + humanSize(Files.size(p)));
                    }
                    continue;
                }
                // 内容搜索：只搜文本文件，跳过超大文件
                if (Files.size(p) > 512 * 1024L || isBinary(p)) {
                    continue;
                }
                try {
                    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).toLowerCase(Locale.ROOT).contains(lower)) {
                            hits.add(rel(ctx, p) + ":" + (i + 1) + ": " + trim(lines.get(i), 180));
                            if (hits.size() >= maxHits) {
                                break;
                            }
                        }
                    }
                } catch (Exception ignore) {
                    // 单个文件读取失败跳过
                }
            }
        }
        if (hits.isEmpty()) {
            return "未找到匹配「" + pattern + "」的内容（已扫描 " + scanned + " 个文件）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(hits.size()).append(" 处匹配（已扫描 ")
                .append(scanned).append(" 个文件，最多返回 ").append(maxHits).append(" 条）：\n");
        for (String h : hits) {
            sb.append(h).append('\n');
        }
        return sb.toString();
    }

    private String shellRun(Map<String, Object> args, ToolContext ctx) throws Exception {
        String command = str(args, "command", "");
        if (command.isEmpty()) {
            throw new ToolException("请提供要执行的命令");
        }
        ToolGuard.checkCommand(command, ctx.getAllowCmds(), ctx.getDenyCmds());
        ToolGuard.checkRedirect(command, ctx.getRoot());

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> cmdline = windows
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);
        ProcessBuilder pb = new ProcessBuilder(cmdline);
        pb.directory(ctx.getRoot().toFile());
        pb.redirectErrorStream(true);
        // 输出重定向到临时文件，避免管道缓冲区写满导致子进程阻塞、waitFor 假超时
        Path tmp = Files.createTempFile("agent-hub-cmd-", ".log");
        pb.redirectOutput(tmp.toFile());
        long t0 = System.currentTimeMillis();
        int exit = -1;
        String note = "";
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(Math.max(1, ctx.getTimeoutSec()), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
                note = "\n[命令超时] 超过 " + ctx.getTimeoutSec() + " 秒未结束，已强制终止";
            } else {
                exit = process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            note = "\n[命令被中断]";
        }
        String output;
        try {
            output = readLimited(tmp, 400_000);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                // Windows 下用 start 拉起后台进程时，子进程会继承日志文件句柄，
                // 这里删不掉是正常现象，不能让"删临时文件失败"把整个成功的命令判成失败
                tmp.toFile().deleteOnExit();
                log.debug("临时日志被占用，稍后清理: {}", tmp);
            }
        }
        long cost = System.currentTimeMillis() - t0;
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(command).append('\n');
        sb.append("退出码: ").append(exit).append("，耗时 ").append(cost).append("ms");
        if (exit == -1 && !note.isEmpty()) {
            sb.append("（异常终止）");
        }
        sb.append('\n');
        sb.append(output.isEmpty() ? "（无输出）" : output);
        sb.append(note);
        // 命令失败且用了 bash 语法时，直接把正确写法告诉模型，避免它反复试错
        if (exit != 0 && windows && looksLikeBash(command)) {
            sb.append("\n[语法提示] 当前 shell 是 Windows cmd.exe，不是 bash：")
                    .append("命令串联用 && 不要用 ; ；")
                    .append("列目录用 dir（没有 ls）；查命令位置用 where（没有 which）；")
                    .append("Python 用 python（没有 python3）；删文件用 del（没有 rm）；")
                    .append("按内容搜用 findstr（没有 grep）。");
        }
        return sb.toString();
    }

    /**
     * 是否用了 bash 专用语法（用于给出针对性的纠错提示）
     */
    private static boolean looksLikeBash(String command) {
        String c = command.toLowerCase(Locale.ROOT);
        return c.contains(";") || c.contains("which ") || c.contains("python3")
                || c.matches("(?s).*\\bls\\b.*") || c.contains("rm -") || c.contains("$(")
                || c.contains("export ") || c.contains("&& grep");
    }

    // ==================== 辅助方法 ====================

    private Path backupPath(ToolContext ctx, Path file) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return ctx.getRoot().resolve(BACKUP_DIR).resolve(stamp).resolve(rel(ctx, file));
    }

    private boolean isIgnored(ToolContext ctx, Path p) {
        String rel = rel(ctx, p);
        return rel.startsWith(BACKUP_DIR + "/") || rel.startsWith(TRASH_DIR + "/")
                || rel.contains("/node_modules/") || rel.contains("/.git/")
                || rel.contains("/target/classes/");
    }

    private boolean isBinary(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private String readLimited(Path file, int maxBytes) {
        try {
            long size = Files.size(file);
            byte[] bytes;
            if (size > maxBytes) {
                try (var in = Files.newInputStream(file)) {
                    bytes = in.readNBytes(maxBytes);
                }
            } else {
                bytes = Files.readAllBytes(file);
            }
            return decode(bytes) + (size > maxBytes ? "\n...[输出过大已截断]" : "");
        } catch (Exception e) {
            return "（读取输出失败：" + e.getMessage() + "）";
        }
    }

    /**
     * Windows 下 cmd 默认输出 GBK，UTF-8 解不出来就退回 GBK，避免中文全是乱码
     */
    private String decode(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) {
            return utf8;
        }
        try {
            return new String(bytes, "GBK");
        } catch (Exception e) {
            return utf8;
        }
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new HashMap<>();
        }
        String text = json.trim();
        // 有些模型会把参数包在 markdown 代码块里
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        try {
            Map<String, Object> map = mapper.readValue(text, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
            return map == null ? new HashMap<>() : map;
        } catch (Exception ignore) {
            // 兜底：补齐未闭合的引号/括号后再试一次（长内容被截断时很常见）
            String fixed = TextToolCallParser.repairJson(text);
            if (fixed != null && !fixed.equals(text)) {
                try {
                    Map<String, Object> map = mapper.readValue(fixed, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
                    if (map != null) {
                        log.info("工具参数 JSON 已自动修复（原长度 {} -> {}）", text.length(), fixed.length());
                        return map;
                    }
                } catch (Exception ignore2) {
                    // 落到下面统一报错
                }
            }
            throw new ToolException("工具参数不是合法 JSON，请重新生成一次调用，"
                    + "注意 content 里的引号和换行要正确转义，内容过长请用 fs_write 分段（mode=append）写入。"
                    + "收到的参数片段：" + trim(json, 200));
        }
    }

    private void add(List<Map<String, Object>> list, String json) {
        try {
            list.add(mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception e) {
            log.error("工具定义解析失败", e);
        }
    }

    private static String str(Map<String, Object> args, String key, String defaultValue) {
        Object v = args.get(key);
        if (v == null) {
            return defaultValue;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? defaultValue : s;
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String rel(ToolContext ctx, Path p) {
        try {
            String r = ctx.getRoot().relativize(p).toString().replace('\\', '/');
            return r.isEmpty() ? "." : r;
        } catch (Exception e) {
            return p.toString();
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        // 头尾各留一部分。构建日志、编译报错、命令失败的结论几乎都在尾部，
        // 只砍尾巴会把最有用的部分丢掉，同样的 token 换来的有效信息更少。
        int head = max * 2 / 5;
        int tail = max - head;
        int omitted = s.length() - head - tail;
        return s.substring(0, head)
                + "\n\n...[中间省略 " + omitted + " 字符，全文共 " + s.length()
                + " 字符。需要完整内容时，把命令输出重定向到文件，再用 fs_read 分段查看]...\n\n"
                + s.substring(s.length() - tail);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\n", " ").replace("\r", "");
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static int levelOf(String mode) {
        if (mode == null) {
            return 0;
        }
        switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "read":
                return MODE_READ;
            case "write":
                return MODE_WRITE;
            case "full":
                return MODE_FULL;
            default:
                return 0;
        }
    }

    private static String levelToMode(int level) {
        return level >= MODE_FULL ? "full" : level >= MODE_WRITE ? "write" : level >= MODE_READ ? "read" : "off";
    }

    public static String modeDesc(String mode) {
        if (mode == null) {
            return "关闭";
        }
        switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "read":
                return "只读（可读文件、列目录、搜索）";
            case "write":
                return "读写（可读写文件、建删目录，不能执行命令）";
            case "full":
                return "全权（含执行命令）";
            default:
                return "关闭（纯对话）";
        }
    }

    // ==================== 工具定义 ====================

    private static final String DEF_LIST = """
            {"type":"function","function":{
              "name":"fs_list",
              "description":"列出目录内容，返回类型(DIR/FILE)、名称、大小、修改时间。路径相对工作目录。",
              "parameters":{"type":"object","properties":{
                "path":{"type":"string","description":"相对工作目录的路径，省略则为工作目录根"}
              }}
            }}""";

    private static final String DEF_READ = """
            {"type":"function","function":{
              "name":"fs_read",
              "description":"读取文本文件内容，带行号返回。大文件可分段读。",
              "parameters":{"type":"object","properties":{
                "path":{"type":"string","description":"相对工作目录的文件路径"},
                "offset":{"type":"integer","description":"起始行号，从1开始，默认1"},
                "limit":{"type":"integer","description":"最多读取行数，默认300，上限2000"}
              },"required":["path"]}
            }}""";

    private static final String DEF_WRITE = """
            {"type":"function","function":{
              "name":"fs_write",
              "description":"写入文本文件，父目录不存在会自动创建。覆盖写入前会自动备份原文件。内容超过约 3000 字符时，先 overwrite 写第一部分，再 append 追加剩余部分。",
              "parameters":{"type":"object","properties":{
                "path":{"type":"string","description":"相对工作目录的文件路径"},
                "content":{"type":"string","description":"要写入的完整文本内容"},
                "mode":{"type":"string","enum":["overwrite","append"],"description":"overwrite 覆盖（默认）或 append 追加"}
              },"required":["path","content"]}
            }}""";

    private static final String DEF_MKDIR = """
            {"type":"function","function":{
              "name":"fs_mkdir",
              "description":"创建目录（可递归创建多级）。",
              "parameters":{"type":"object","properties":{
                "path":{"type":"string","description":"相对工作目录的目录路径"}
              },"required":["path"]}
            }}""";

    private static final String DEF_DELETE = """
            {"type":"function","function":{
              "name":"fs_delete",
              "description":"删除文件或目录。不会真正销毁，会移入工作目录下的 .trash 回收站，可恢复。",
              "parameters":{"type":"object","properties":{
                "path":{"type":"string","description":"相对工作目录的路径"}
              },"required":["path"]}
            }}""";

    private static final String DEF_MOVE = """
            {"type":"function","function":{
              "name":"fs_move",
              "description":"移动或重命名文件/目录。",
              "parameters":{"type":"object","properties":{
                "from":{"type":"string","description":"原路径"},
                "to":{"type":"string","description":"目标路径"}
              },"required":["from","to"]}
            }}""";

    private static final String DEF_SEARCH = """
            {"type":"function","function":{
              "name":"fs_search",
              "description":"搜索文件：按文件名或文件内容关键词查找。",
              "parameters":{"type":"object","properties":{
                "pattern":{"type":"string","description":"搜索关键词"},
                "path":{"type":"string","description":"搜索起始目录，默认工作目录根"},
                "mode":{"type":"string","enum":["name","content"],"description":"name 按文件名，content 按文件内容（默认）"}
              },"required":["pattern"]}
            }}""";

    /** Windows 下的命令执行工具：语法按 cmd.exe 描述，别把 bash 那套教给模型 */
    private static final String DEF_SHELL_WIN = """
            {"type":"function","function":{
              "name":"shell_run",
              "description":"在 Windows cmd.exe 中执行命令（不是 bash）。串联命令用 &&，不要用分号 ;；列目录 dir、查命令 where、Python 用 python、删文件用 del。适合构建、运行脚本、查看版本。危险命令会被拦截。",
              "parameters":{"type":"object","properties":{
                "command":{"type":"string","description":"要执行的完整命令，如 npm run build 或 python main.py"}
              },"required":["command"]}
            }}""";

    /** Linux / macOS 下的同一件工具：命令语法换成本机 shell */
    private static final String DEF_SHELL_NIX = """
            {"type":"function","function":{
              "name":"shell_run",
              "description":"在当前机器的 shell（/bin/sh）中执行命令。串联命令用 && 或 ;；列目录 ls、查命令 which、按内容找 grep、删文件 rm。适合构建、运行脚本、查看版本。危险命令会被拦截。",
              "parameters":{"type":"object","properties":{
                "command":{"type":"string","description":"要执行的完整命令，如 npm run build 或 python3 main.py"}
              },"required":["command"]}
            }}""";
}

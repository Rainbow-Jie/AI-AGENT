package com.goocidata.agent.tool;

import com.goocidata.agent.security.SensitiveGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工具安全护栏：路径沙箱 + 命令黑/白名单。
 * <p>
 * 说明：这是应用层的护栏，目标是「防模型乱来、防手滑」，不是操作系统级隔离。
 * 真要跑不可信内容请用容器或独立用户跑，别指望这里挡住提权。
 *
 * @author nzj
 */
public final class ToolGuard {

    /**
     * 永远拒绝的命令名（按第一段命令精确匹配）
     */
    private static final Set<String> DENY_COMMANDS = new LinkedHashSet<>(Arrays.asList(
            "format", "diskpart", "bcdedit", "cipher", "takeown", "icacls", "regedit",
            "reg", "shutdown", "logoff", "taskkill", "tskill", "wmic", "schtasks", "sc",
            "net", "netsh", "at", "cacls", "attrib", "vssadmin", "wbadmin", "fsutil",
            "powershell", "pwsh", "mshta", "rundll32", "regsvr32", "certutil", "bitsadmin",
            "mkfs", "dd", "sudo", "su", "chroot", "mount", "umount", "iptables", "passwd",
            "curl", "wget", "nc", "ncat", "telnet", "ssh", "scp"
    ));

    /**
     * 危险片段：出现在命令任意位置就拒绝
     */
    private static final List<String> DENY_PATTERNS = Arrays.asList(
            "rm -rf /", "rm -fr /", "rd /s /q c:", "rmdir /s /q c:", "del /f /s /q c:",
            "format c:", "format d:", "> /dev/sd", "mkfs.", ":(){", "fork bomb",
            "\\\\.\\physicaldrive", "c:/windows/system32", "c:\\windows\\system32",
            "/etc/passwd", "/etc/shadow", "~/.ssh", "id_rsa", ".aws/credentials",
            "bcdedit", "vssadmin delete", "wevtutil cl"
    );

    private ToolGuard() {
    }

    /**
     * 把用户/模型给的路径解析成绝对路径，并保证落在沙箱根之内。
     * 已存在的路径取真实路径（挡住软链接逃逸）；不存在则校验最近的已存在父目录。
     */
    public static Path resolveInside(Path root, String raw) {
        Path realRoot = realPathOrSelf(root);
        String s = raw == null ? "" : raw.trim().replace('\\', '/');
        if (s.isEmpty() || ".".equals(s) || "./".equals(s) || "/".equals(s)) {
            return realRoot;
        }
        Path target;
        try {
            Path p = Paths.get(s);
            target = p.isAbsolute() ? p : realRoot.resolve(s);
        } catch (Exception e) {
            throw new ToolException("该路径不可访问。");
        }
        target = target.normalize();
        Path real = realPathOrSelf(target);
        if (!real.startsWith(realRoot)) {
            // 不再回显沙箱根目录和目标路径：这两个串拼起来就等于把服务器目录结构交出去了
            throw new ToolException(SensitiveGuard.TOOL_DENIED);
        }
        return real;
    }

    /**
     * 校验命令是否被允许执行。白名单非空时只允许名单内命令；
     * 无论如何都会过一遍内置黑名单，并按 && || | & ; 拆段逐段校验，防止借复合命令绕过。
     */
    public static void checkCommand(String command, String allowCmds, String denyCmds) {
        if (command == null || command.trim().isEmpty()) {
            throw new ToolException("命令为空");
        }
        String lower = command.toLowerCase(Locale.ROOT);

        for (String pattern : DENY_PATTERNS) {
            if (lower.contains(pattern)) {
                throw new ToolException("该命令无法执行。请换一种方式完成任务。");
            }
        }
        List<String> custom = splitCsv(denyCmds);
        for (String pattern : custom) {
            if (!pattern.isEmpty() && lower.contains(pattern.toLowerCase(Locale.ROOT))) {
                throw new ToolException("该命令无法执行。请换一种方式完成任务。");
            }
        }

        List<String> allowList = splitCsv(allowCmds);
        List<String> segments = splitSegments(command);
        for (String segment : segments) {
            String name = commandName(segment);
            if (name.isEmpty()) {
                continue;
            }
            if (DENY_COMMANDS.contains(name)) {
                // 不说明「属于系统级危险命令」、也不回显命中了哪条规则：
                // 规则细节本身就能被用来试探出更隐蔽的绕过方式
                throw new ToolException("该命令无法执行。请换一种方式完成任务。");
            }
            if (!allowList.isEmpty()) {
                boolean allowed = allowList.stream()
                        .anyMatch(a -> a.equalsIgnoreCase(name) || a.equalsIgnoreCase(segment.trim()));
                if (!allowed) {
                    throw new ToolException("该命令无法执行。请换一种方式完成任务。");
                }
            }
        }
    }

    /**
     * 校验输出重定向目标是否在沙箱内（`>` / `>>` 写到外面等于绕过沙箱）
     */
    public static void checkRedirect(String command, Path root) {
        String cmd = command.replace('\\', '/');
        int i = 0;
        while (i < cmd.length()) {
            int gt = cmd.indexOf('>', i);
            if (gt < 0) {
                break;
            }
            int start = gt + 1;
            while (start < cmd.length() && cmd.charAt(start) == '>') {
                start++;
            }
            while (start < cmd.length() && cmd.charAt(start) == ' ') {
                start++;
            }
            int end = start;
            while (end < cmd.length() && !Character.isWhitespace(cmd.charAt(end))
                    && cmd.charAt(end) != '&' && cmd.charAt(end) != '|') {
                end++;
            }
            String target = cmd.substring(start, end).replace("\"", "");
            if (!target.isEmpty() && !"nul".equalsIgnoreCase(target) && !"dev/null".equals(target)) {
                boolean absolute = target.startsWith("/") || target.matches("^[a-zA-Z]:/.*");
                if (absolute) {
                    resolveInside(root, target);
                }
            }
            i = end;
        }
    }

    private static List<String> splitSegments(String command) {
        List<String> result = new ArrayList<>();
        for (String part : command.split("&&|\\|\\||\\||&|;|\\r?\\n")) {
            if (!part.trim().isEmpty()) {
                result.add(part.trim());
            }
        }
        return result.isEmpty() ? List.of(command.trim()) : result;
    }

    private static String commandName(String segment) {
        String s = segment.trim();
        // 跳过前缀修饰符，如 `call xxx`、`start xxx`
        while (s.startsWith("(")) {
            s = s.substring(1).trim();
        }
        String[] parts = s.split("\\s+");
        if (parts.length == 0) {
            return "";
        }
        String first = parts[0];
        int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
        if (slash >= 0) {
            first = first.substring(slash + 1);
        }
        if (first.toLowerCase(Locale.ROOT).endsWith(".exe")
                || first.toLowerCase(Locale.ROOT).endsWith(".bat")
                || first.toLowerCase(Locale.ROOT).endsWith(".cmd")) {
            first = first.substring(0, first.lastIndexOf('.'));
        }
        return first.toLowerCase(Locale.ROOT);
    }

    public static List<String> splitCsv(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return result;
        }
        for (String s : csv.split("[,，\\s]+")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }

    private static Path realPathOrSelf(Path path) {
        try {
            if (Files.exists(path)) {
                return path.toRealPath();
            }
            Path parent = path.getParent();
            if (parent == null) {
                return path.toAbsolutePath().normalize();
            }
            Path realParent = realPathOrSelf(parent);
            Path fileName = path.getFileName();
            return fileName == null ? realParent : realParent.resolve(fileName.toString()).normalize();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}

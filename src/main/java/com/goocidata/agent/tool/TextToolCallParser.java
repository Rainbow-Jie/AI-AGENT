package com.goocidata.agent.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本形式工具调用的兜底解析器。
 * <p>
 * 少数情况下（多轮工具调用 + 输出被截断时尤其常见），模型不会走标准的
 * {@code delta.tool_calls} 通道，而是把工具调用当成正文文本吐出来，形如：
 * <pre>
 * &lt;｜｜DSML｜｜ calls&gt;
 * &lt;｜｜DSML｜｜ invoke name="fs_write"&gt;
 * &lt;｜｜DSML｜｜ parameter name="arguments" string="true"&gt;{"path":"a.txt"}&lt;/...parameter&gt;
 * &lt;/...invoke&gt;
 * &lt;/...calls&gt;
 * </pre>
 * 这类文本一旦当成答案展示，用户看到的就是一堆乱码标记。解析器负责：
 * 识别泄漏标记、把 invoke/parameter 还原成 ToolCall、并在无法解析时清理标记。
 * <p>
 * 正则刻意只匹配 "invoke"/"parameter" 这些语义字样，不依赖分隔符的具体写法
 * （DeepSeek 不同版本用 DSML、tool▁calls 等不同占位符）。
 *
 * @author nzj
 */
@Slf4j
public final class TextToolCallParser {

    /** 泄漏标记：标签内出现 DSML / tool_calls / tool▁calls 等字样 */
    private static final Pattern LEAK_HINT = Pattern.compile(
            "<[^<>]{0,40}?(?:DSML|tool[_\u2581]?calls|tool[_\u2581]?call)[^<>]{0,40}?>",
            Pattern.CASE_INSENSITIVE);

    /** 一段 invoke（一次工具调用） */
    private static final Pattern INVOKE = Pattern.compile(
            "<[^<>]*?invoke\\s+name\\s*=\\s*\"([^\"]{1,64})\"[^<>]*>([\\s\\S]*?)<\\s*/[^<>]*?invoke[^<>]*>",
            Pattern.CASE_INSENSITIVE);

    /** invoke 内部的一个参数 */
    private static final Pattern PARAM = Pattern.compile(
            "<[^<>]*?parameter\\s+name\\s*=\\s*\"([^\"]{1,64})\"[^<>]*>([\\s\\S]*?)<\\s*/[^<>]*?parameter[^<>]*>",
            Pattern.CASE_INSENSITIVE);

    /** 兜底：模型直接写 name="xxx" 的裸 invoke 起始标签（没有闭合标签） */
    private static final Pattern LOOSE_INVOKE = Pattern.compile(
            "<[^<>]*?invoke\\s+name\\s*=\\s*\"([^\"]{1,64})\"[^<>]*>([\\s\\S]*)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LOOSE_PARAM = Pattern.compile(
            "<[^<>]*?parameter\\s+name\\s*=\\s*\"([^\"]{1,64})\"[^<>]*>([\\s\\S]*?)(?=<[^<>]*?(?:parameter|invoke)|$)",
            Pattern.CASE_INSENSITIVE);

    private TextToolCallParser() {
    }

    /**
     * 文本里是否出现工具调用泄漏标记
     */
    public static boolean hasLeak(String text) {
        return text != null && !text.isEmpty() && LEAK_HINT.matcher(text).find();
    }

    /**
     * 解析文本形式的一次或多次工具调用。解析不出来返回空列表。
     */
    public static List<ToolCall> parse(String text) {
        List<ToolCall> calls = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return calls;
        }
        Matcher m = INVOKE.matcher(text);
        boolean found = false;
        while (m.find()) {
            found = true;
            ToolCall call = build(m.group(1), m.group(2));
            if (call != null) {
                calls.add(call);
            }
        }
        if (found) {
            return calls;
        }
        // 截断场景：闭合标签没吐出来，用宽松模式再试
        Matcher loose = LOOSE_INVOKE.matcher(text);
        if (loose.find()) {
            ToolCall call = build(loose.group(1), loose.group(2));
            if (call != null) {
                calls.add(call);
            }
        }
        return calls;
    }

    /**
     * 去掉文本里的泄漏标记，只保留人类可读的部分。
     * 用于"残留标记但解析不出工具调用"的场景，避免把乱码当成答案展示。
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = LEAK_HINT.matcher(text).replaceAll("");
        out = out.replaceAll("(?i)<\\s*/?\\s*(invoke|parameter)\\b[^<>]*>", "");
        return out.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static ToolCall build(String name, String body) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        Matcher pm = PARAM.matcher(body);
        boolean closed = false;
        while (pm.find()) {
            closed = true;
            params.put(pm.group(1).trim(), pm.group(2).trim());
        }
        if (!closed) {
            Matcher lm = LOOSE_PARAM.matcher(body);
            while (lm.find()) {
                params.put(lm.group(1).trim(), lm.group(2).trim());
            }
        }
        String args = toArgumentsJson(params);
        if (args == null) {
            log.warn("文本工具调用参数无法还原，已跳过：{}", name);
            return null;
        }
        ToolCall call = new ToolCall();
        call.setId("text-" + Long.toHexString(System.nanoTime()));
        call.setName(name.trim());
        call.setArguments(args);
        return call;
    }

    /**
     * 参数拼装成 arguments JSON：只有一个 arguments 参数时直接用它的 JSON 值，
     * 否则把每个参数拼成对象字段。JSON 被截断时做尽力补全。
     */
    private static String toArgumentsJson(Map<String, String> params) {
        if (params.isEmpty()) {
            return "{}";
        }
        if (params.size() == 1) {
            Map.Entry<String, String> only = params.entrySet().iterator().next();
            String value = only.getValue();
            if ("arguments".equalsIgnoreCase(only.getKey())) {
                String fixed = repairJson(value);
                return fixed != null ? fixed : "{}";
            }
            // 模型把单个参数直接写成参数名，例如 parameter name="path"
            if (value.startsWith("{") || value.startsWith("[")) {
                String fixed = repairJson(value);
                if (fixed != null) {
                    return fixed;
                }
            }
            return "{\"" + escape(only.getKey()) + "\":\"" + escape(value) + "\"}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            String v = e.getValue();
            if (v.startsWith("{") || v.startsWith("[") || v.startsWith("\"")
                    || "true".equals(v) || "false".equals(v) || isNumber(v)) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(v)).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /**
     * 截断的 JSON 尽力补全：补齐未闭合的字符串、括号。
     * 解析不出来返回 null。
     * <p>
     * 模型生成工具参数时，长文本里夹带换行/引号很容易漏转义，或被输出上限截断，
     * 这里统一兜一次，能救回来的就不必让模型重写一遍。
     */
    public static String repairJson(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "{}";
        }
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        // 先看是不是本来就合法
        if (isValidJson(s)) {
            return s;
        }
        // 补引号 + 括号
        StringBuilder sb = new StringBuilder(s);
        boolean inString = false;
        boolean escaped = false;
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty() && ((c == '}' && stack.peek() == '{') || (c == ']' && stack.peek() == '['))) {
                    stack.pop();
                }
            }
        }
        if (inString) {
            if (escaped) {
                sb.append('\\');
            }
            sb.append('"');
        }
        while (!stack.isEmpty()) {
            sb.append(stack.pop() == '{' ? '}' : ']');
        }
        String fixed = sb.toString();
        return isValidJson(fixed) ? fixed : null;
    }

    private static boolean isValidJson(String s) {
        try {
            com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNumber(String s) {
        try {
            new java.math.BigDecimal(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t");
    }
}

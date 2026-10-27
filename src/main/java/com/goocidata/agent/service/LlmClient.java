package com.goocidata.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.goocidata.agent.common.BizException;
import com.goocidata.agent.entity.LlmConfig;
import com.goocidata.agent.tool.TextToolCallParser;
import com.goocidata.agent.tool.ToolCall;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 大模型客户端（OpenAI 兼容协议，DeepSeek / 通义 / 硅基流动 / Ollama 均可用）
 * <p>
 * 支持 function calling：请求体可带 tools，流式响应里 delta.tool_calls 是分片返回的
 * （id / name / arguments 会拆到多个 chunk），必须按 index 累积后再拼装。
 *
 * @author nzj
 */
@Slf4j
@Component
public class LlmClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 单次请求的真实用量（由模型服务返回，不是估算）
     * <p>
     * DeepSeek 会额外给出 {@code prompt_cache_hit_tokens} / {@code prompt_cache_miss_tokens}，
     * 命中部分只按 1/10 计价，所以「总输入 token」看着吓人，实际大多走缓存。
     * 没有这两个字段的模型（如部分三方代理）命中数留空，前端按未知处理。
     */
    @Data
    public static class Usage {
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;

        /** 命中上下文缓存的输入 token（-1 表示该服务未返回此字段） */
        private long cacheHitTokens = -1;

        /** 未命中缓存的输入 token（-1 表示未返回） */
        private long cacheMissTokens = -1;

        public boolean hasCacheInfo() {
            return cacheHitTokens >= 0 || cacheMissTokens >= 0;
        }

        public void accumulate(Usage other) {
            if (other == null) {
                return;
            }
            promptTokens += other.promptTokens;
            completionTokens += other.completionTokens;
            totalTokens += other.totalTokens;
            if (other.cacheHitTokens >= 0) {
                cacheHitTokens = Math.max(0, cacheHitTokens) + other.cacheHitTokens;
            }
            if (other.cacheMissTokens >= 0) {
                cacheMissTokens = Math.max(0, cacheMissTokens) + other.cacheMissTokens;
            }
        }

        /** 缓存命中率，无缓存信息时返回 -1 */
        public double hitRate() {
            long hit = Math.max(0, cacheHitTokens);
            long miss = Math.max(0, cacheMissTokens);
            if (hit + miss <= 0) {
                return -1D;
            }
            return hit * 100D / (hit + miss);
        }
    }

    /**
     * 一次流式调用的结果：正文 + 模型请求的工具调用
     */
    @Data
    public static class StreamResult {
        private String content = "";
        private List<ToolCall> toolCalls = new ArrayList<>();

        /** 结束原因：stop / length（截断）/ tool_calls */
        private String finishReason;

        /** 是否检测到「工具调用被当成正文吐出来」的泄漏 */
        private boolean leaked;

        /** 本次请求的真实 token 用量 */
        private Usage usage;

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        /** 是否因为 max_tokens 被截断 */
        public boolean truncated() {
            return "length".equalsIgnoreCase(finishReason);
        }
    }

    /**
     * 同步对话，返回完整回答（测试连接等场景使用）
     */
    public String chat(LlmConfig cfg, List<Map<String, Object>> messages) {
        ObjectNode body = buildBody(cfg, messages, false, null);
        HttpRequest request = buildRequest(cfg, body);
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BizException(friendlyError(resp.statusCode(), resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new BizException("模型返回内容为空，请检查模型名称是否正确");
            }
            return content.asText();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用大模型失败", e);
            throw new BizException("调用大模型失败：" + e.getMessage());
        }
    }

    /**
     * 流式对话（不带工具），每收到一个增量回调一次
     */
    public String chatStream(LlmConfig cfg, List<Map<String, Object>> messages, Consumer<String> onDelta) {
        return chatStreamWithTools(cfg, messages, null, onDelta).getContent();
    }

    public StreamResult chatStreamWithTools(LlmConfig cfg, List<Map<String, Object>> messages,
                                            List<Map<String, Object>> tools, Consumer<String> onDelta) {
        return chatStreamWithTools(cfg, messages, tools, onDelta, null);
    }

    /**
     * 流式对话，可带工具定义。
     * <p>
     * 正文增量实时回调；工具调用按 index 累积，流结束后一次性返回给调用方去执行。
     * <p>
     * 注意：模型偶尔不走 tool_calls 通道，而是把调用写成正文文本（见 {@link TextToolCallParser}）。
     * 这时必须立刻 {@code onReset} 让前端清空已经吐出去的乱码，并把文本还原成真正的工具调用。
     *
     * @param onReset 检测到泄漏时回调，用于让前端清空当前气泡（可为 null）
     */
    public StreamResult chatStreamWithTools(LlmConfig cfg, List<Map<String, Object>> messages,
                                            List<Map<String, Object>> tools, Consumer<String> onDelta,
                                            Runnable onReset) {
        ObjectNode body = buildBody(cfg, messages, true, tools);
        HttpRequest request = buildRequest(cfg, body);
        StreamResult result = new StreamResult();
        StringBuilder full = new StringBuilder();
        // 待推送缓冲：尾部留一段不推，便于在整段泄漏标记拼齐之前就拦住
        StringBuilder pending = new StringBuilder();
        boolean leaked = false;
        // index -> 累积中的工具调用（同一 index 的分片属于同一次调用）
        Map<Integer, ToolCall> acc = new LinkedHashMap<>();
        Map<Integer, StringBuilder> accArgs = new LinkedHashMap<>();
        try {
            HttpResponse<InputStream> resp = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String err;
                try (InputStream in = resp.body()) {
                    err = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new BizException(friendlyError(resp.statusCode(), err));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    try {
                        JsonNode node = mapper.readTree(payload);
                        JsonNode choice = node.path("choices").path(0);
                        JsonNode delta = choice.path("delta");

                        JsonNode finishNode = choice.path("finish_reason");
                        if (!finishNode.isMissingNode() && !finishNode.isNull()) {
                            result.setFinishReason(finishNode.asText());
                        }

                        JsonNode contentNode = delta.path("content");
                        if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                            String piece = contentNode.asText();
                            if (!piece.isEmpty()) {
                                full.append(piece);
                                if (!leaked) {
                                    leaked = detectLeak(pending, piece, onDelta, onReset);
                                }
                            }
                        }

                        JsonNode toolCalls = delta.path("tool_calls");
                        if (toolCalls.isArray()) {
                            for (JsonNode tc : toolCalls) {
                                int idx = tc.path("index").asInt(0);
                                ToolCall call = acc.computeIfAbsent(idx, k -> new ToolCall());
                                StringBuilder args = accArgs.computeIfAbsent(idx, k -> new StringBuilder());
                                if (tc.hasNonNull("id")) {
                                    String id = tc.get("id").asText();
                                    if (!id.isEmpty()) {
                                        call.setId(id);
                                    }
                                }
                                JsonNode fn = tc.path("function");
                                if (fn.hasNonNull("name")) {
                                    String name = fn.get("name").asText();
                                    if (!name.isEmpty()) {
                                        call.setName(name);
                                    }
                                }
                                if (fn.hasNonNull("arguments")) {
                                    args.append(fn.get("arguments").asText());
                                }
                            }
                        }

                        // 用量：流式下由 stream_options.include_usage 在末尾补一块
                        JsonNode usageNode = node.path("usage");
                        if (usageNode.isObject()) {
                            result.setUsage(readUsage(usageNode));
                        }

                        JsonNode errNode = node.path("error");
                        if (!errNode.isMissingNode() && !errNode.isNull()) {
                            throw new BizException("模型返回错误：" + errNode.path("message").asText());
                        }
                    } catch (BizException e) {
                        throw e;
                    } catch (Exception ignore) {
                        // 单行解析失败不影响整体流
                    }
                }
            }

            for (Map.Entry<Integer, ToolCall> entry : acc.entrySet()) {
                ToolCall call = entry.getValue();
                StringBuilder args = accArgs.get(entry.getKey());
                call.setArguments(args == null ? "" : args.toString());
                if (call.getName() != null && !call.getName().isEmpty()) {
                    result.getToolCalls().add(call);
                } else {
                    log.warn("忽略缺少名称的工具调用分片 index={}", entry.getKey());
                }
            }

            if (leaked) {
                // 把正文里的文本工具调用还原成真正的调用，原文绝不展示给用户
                List<ToolCall> textCalls = TextToolCallParser.parse(full.toString());
                result.setLeaked(true);
                log.warn("模型把工具调用写成了正文文本，兜底解析出 {} 个调用（全长 {} 字符）",
                        textCalls.size(), full.length());
                if (textCalls.isEmpty()) {
                    result.setContent(TextToolCallParser.strip(full.toString()));
                } else {
                    for (ToolCall c : textCalls) {
                        boolean dup = result.getToolCalls().stream()
                                .anyMatch(x -> c.getName().equals(x.getName()));
                        if (!dup) {
                            result.getToolCalls().add(c);
                        }
                    }
                    result.setContent("");
                }
            } else {
                // 正常收尾：把缓冲里剩下的正文推出去
                if (pending.length() > 0 && onDelta != null) {
                    onDelta.accept(pending.toString());
                }
                pending.setLength(0);
                result.setContent(full.toString());
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("流式调用大模型失败", e);
            throw new BizException("调用大模型失败：" + e.getMessage());
        }
    }

    /**
     * 边流边检测：正文里是否开始出现工具调用标记。
     * <p>
     * 尾部留 24 个字符不推，避免把分片到达的标签切一半推给前端；
     * 一旦命中标记立刻丢弃缓冲并回调 onReset，让前端清空已显示的内容。
     *
     * @return 是否检测到泄漏
     */
    private boolean detectLeak(StringBuilder pending, String piece, Consumer<String> onDelta, Runnable onReset) {
        pending.append(piece);
        if (TextToolCallParser.hasLeak(pending.toString())) {
            pending.setLength(0);
            if (onReset != null) {
                onReset.run();
            }
            return true;
        }
        if (pending.length() > 48) {
            int cut = pending.length() - 24;
            // 不把还没闭合的标签推出去
            int lt = pending.lastIndexOf("<");
            if (lt >= 0 && lt < cut) {
                cut = lt;
            }
            if (cut <= 0 && pending.length() > 4096) {
                cut = pending.length() - 24;
            }
            if (cut > 0) {
                String out = pending.substring(0, cut);
                pending.delete(0, cut);
                if (onDelta != null) {
                    onDelta.accept(out);
                }
            }
        }
        return false;
    }

    /**
     * 解析 usage 节点。不同厂商字段名略有差异，逐个兜底。
     */
    private Usage readUsage(JsonNode node) {
        Usage usage = new Usage();
        usage.setPromptTokens(node.path("prompt_tokens").asLong(0));
        usage.setCompletionTokens(node.path("completion_tokens").asLong(0));
        usage.setTotalTokens(node.path("total_tokens").asLong(0));
        if (usage.getTotalTokens() <= 0) {
            usage.setTotalTokens(usage.getPromptTokens() + usage.getCompletionTokens());
        }
        // DeepSeek：prompt_cache_hit_tokens / prompt_cache_miss_tokens
        if (node.hasNonNull("prompt_cache_hit_tokens")) {
            usage.setCacheHitTokens(node.get("prompt_cache_hit_tokens").asLong(0));
        }
        if (node.hasNonNull("prompt_cache_miss_tokens")) {
            usage.setCacheMissTokens(node.get("prompt_cache_miss_tokens").asLong(0));
        }
        // OpenAI：prompt_tokens_details.cached_tokens
        if (usage.getCacheHitTokens() < 0) {
            JsonNode details = node.path("prompt_tokens_details");
            if (details.isObject() && details.hasNonNull("cached_tokens")) {
                usage.setCacheHitTokens(details.get("cached_tokens").asLong(0));
            }
        }
        if (usage.getCacheHitTokens() >= 0 && usage.getCacheMissTokens() < 0) {
            usage.setCacheMissTokens(Math.max(0, usage.getPromptTokens() - usage.getCacheHitTokens()));
        }
        return usage;
    }

    /**
     * 测试连通性
     */
    public String test(LlmConfig cfg) {
        if (cfg.getApiKey() == null || cfg.getApiKey().trim().isEmpty()) {
            throw new BizException("请先填写 API Key");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "请只回复两个字：收到"));
        String reply = chat(cfg, messages);
        return reply == null ? "" : reply.trim();
    }

    private ObjectNode buildBody(LlmConfig cfg, List<Map<String, Object>> messages,
                                 boolean stream, List<Map<String, Object>> tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.getModel());
        ArrayNode arr = body.putArray("messages");
        for (Map<String, Object> m : messages) {
            ObjectNode node = arr.addObject();
            node.put("role", String.valueOf(m.get("role")));
            Object content = m.get("content");
            if (content == null) {
                node.putNull("content");
            } else {
                node.put("content", String.valueOf(content));
            }
            // 历史里的助手工具调用要原样回传，否则模型会以为没执行过
            Object toolCalls = m.get("tool_calls");
            if (toolCalls != null) {
                node.set("tool_calls", mapper.valueToTree(toolCalls));
            }
            Object toolCallId = m.get("tool_call_id");
            if (toolCallId != null) {
                node.put("tool_call_id", String.valueOf(toolCallId));
            }
        }
        double temperature = cfg.getTemperature() == null ? 0.7D : cfg.getTemperature();
        body.put("temperature", temperature);
        if (cfg.getMaxTokens() != null && cfg.getMaxTokens() > 0) {
            body.put("max_tokens", cfg.getMaxTokens());
        }
        body.put("stream", stream);
        if (stream) {
            // 让服务端在流末尾补一条 usage（含缓存命中明细），否则拿不到真实用量
            ObjectNode options = body.putObject("stream_options");
            options.put("include_usage", true);
        }
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", mapper.valueToTree(tools));
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private HttpRequest buildRequest(LlmConfig cfg, ObjectNode body) {
        if (cfg.getApiKey() == null || cfg.getApiKey().trim().isEmpty()) {
            String name = cfg.getName() == null || cfg.getName().trim().isEmpty() ? "未命名模型" : cfg.getName();
            throw new BizException("模型【" + name + "】尚未配置 API Key，请先到「模型配置」中填写");
        }
        String url = resolveUrl(cfg.getBaseUrl());
        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.getApiKey().trim())
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw new BizException("模型地址不合法：" + cfg.getBaseUrl());
        }
    }

    private String resolveUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            throw new BizException("模型 Base URL 未配置");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private String friendlyError(int status, String body) {
        String detail = body == null ? "" : body;
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "...";
        }
        switch (status) {
            case 401:
                return "API Key 无效或已过期（401），请检查密钥";
            case 402:
                return "账户余额不足（402），请到模型平台充值";
            case 403:
                return "无访问权限（403），请检查密钥权限或模型名称";
            case 404:
                return "接口地址或模型名称不存在（404），请检查 Base URL 与 model 字段";
            case 429:
                return "请求过于频繁（429），已被限流，请稍后再试";
            case 500:
            case 502:
            case 503:
                return "模型服务暂时不可用（" + status + "），请稍后重试";
            default:
                return "模型调用失败（HTTP " + status + "）：" + detail;
        }
    }
}

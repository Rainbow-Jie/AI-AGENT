package com.goocidata.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goocidata.agent.common.BizException;
import com.goocidata.agent.common.IdGen;
import com.goocidata.agent.dto.ChunkHit;
import com.goocidata.agent.entity.AgentInfo;
import com.goocidata.agent.entity.ChatMessage;
import com.goocidata.agent.entity.ChatSession;
import com.goocidata.agent.entity.LlmConfig;
import com.goocidata.agent.entity.SysUser;
import com.goocidata.agent.mapper.ChatMessageMapper;
import com.goocidata.agent.mapper.ChatSessionMapper;
import com.goocidata.agent.mapper.LlmConfigMapper;
import com.goocidata.agent.mapper.SysSettingMapper;
import com.goocidata.agent.security.SensitiveGuard;
import com.goocidata.agent.tool.ToolBox;
import com.goocidata.agent.tool.ToolCall;
import com.goocidata.agent.tool.ToolContext;
import com.goocidata.agent.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话编排：检索 -> 拼上下文 -> 流式回答（可多轮调用工具）-> 落库
 *
 * @author nzj
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AgentService agentService;
    private final RetrievalService retrievalService;
    private final LlmClient llmClient;
    private final LlmConfigMapper llmConfigMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final SysSettingMapper settingMapper;
    private final ToolBox toolBox;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService streamPool = Executors.newFixedThreadPool(16, new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "chat-stream-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    @Value("${agent-hub.history-limit:10}")
    private int historyLimit;

    @Value("${agent-hub.retrieve.top-k:4}")
    private int defaultTopK;

    @Value("${agent-hub.tools.max-output:8000}")
    private int maxOutput;

    @Value("${agent-hub.tools.max-steps:6}")
    private int defaultMaxSteps;

    @Value("${agent-hub.tools.timeout-seconds:30}")
    private int defaultTimeout;

    /** 工具结果保留最近几条全文，更早的折叠成一行摘要（0 = 不折叠） */
    @Value("${agent-hub.token.keep-recent-results:6}")
    private int keepRecentResults;

    /** 折叠后摘要的最大字符数 */
    @Value("${agent-hub.token.summary-max-chars:150}")
    private int summaryMaxChars;

    /**
     * 流式对话（SSE）
     */
    public SseEmitter stream(SysUser user, String agentId, String sessionId, String question) {
        if (question == null || question.trim().isEmpty()) {
            throw new BizException(400, "请输入问题");
        }
        AgentInfo agent = agentService.mustGetEnabled(agentId);
        LlmConfig config = resolveConfig(agent);

        SseEmitter emitter = new SseEmitter(5 * 60_000L);
        streamPool.execute(() -> doStream(emitter, user, agent, config, sessionId, question.trim()));
        return emitter;
    }

    private void doStream(SseEmitter emitter, SysUser user, AgentInfo agent, LlmConfig config,
                          String sessionId, String question) {
        ChatSession session = null;
        try {
            session = ensureSession(user, agent, sessionId, question);

            // 1. 先取历史（此时本条提问尚未落库），再落用户消息
            List<ChatMessage> history = new ArrayList<>(messageMapper.listRecent(session.getId(), historyLimit));
            Collections.reverse(history);

            ChatMessage userMsg = new ChatMessage();
            userMsg.setId(IdGen.next());
            userMsg.setSessionId(session.getId());
            userMsg.setRole("user");
            userMsg.setContent(question);
            userMsg.setCreatedAt(IdGen.now());
            messageMapper.insert(userMsg);

            // 1.5 敏感提问：不检索、不调模型、不碰工具，直接统一答复。
            // 放在检索之前是有意的——检索命中会把参考资料塞进上下文，
            // 模型就有机会把资料里的服务器信息顺口复述出来；而且这条路一次模型调用都不花。
            String sensitiveRule = SensitiveGuard.matchAsk(question);
            if (sensitiveRule != null) {
                log.warn("敏感提问已拦截 session={} 规则={} 提问=\"{}\"",
                        session.getId(), sensitiveRule, SensitiveGuard.forLog(question));
                Map<String, Object> meta = new HashMap<>(6);
                meta.put("type", "meta");
                meta.put("sessionId", session.getId());
                meta.put("refs", List.of());
                meta.put("toolMode", "off");
                meta.put("tools", List.of());
                send(emitter, meta);
                sendRefusal(emitter, false);
                persistAssistant(session.getId(), SensitiveGuard.REFUSAL, null, null, null);
                sessionMapper.touch(session.getId(), IdGen.now());
                emitter.complete();
                return;
            }

            // 2. 检索知识库
            List<String> kbIds = AgentService.splitIds(agent.getKbIds());
            int topK = agent.getTopK() == null || agent.getTopK() <= 0 ? defaultTopK : agent.getTopK();
            long t0 = System.currentTimeMillis();
            List<ChunkHit> hits = retrievalService.retrieve(kbIds, question, topK);
            log.info("检索完成 session={} kb={} hits={} 耗时={}ms", session.getId(), kbIds,
                    hits.size(), System.currentTimeMillis() - t0);

            // 2.1 追问兜底：检索是 BM25，靠关键词匹配。「重新给我答复」「知识库有相关答案」
            // 这类追问不含任何主题词，必然 0 命中，随后系统提示会让模型宣布
            // 「知识库中没有找到相关内容」——用户看到的就是「知识库没了」。
            // 这里用上一条（必要时再往前一条）用户提问再检一次，命中即用。
            if (hits.isEmpty() && !kbIds.isEmpty()) {
                for (String prev : recentUserQuestions(history, 2)) {
                    List<ChunkHit> retry = retrievalService.retrieve(kbIds, prev, topK);
                    if (!retry.isEmpty()) {
                        hits = retry;
                        log.info("追问兜底命中 session={} 借用上轮提问=\"{}\" hits={}",
                                session.getId(), abbreviate(prev, 40), retry.size());
                        break;
                    }
                }
            }

            // 3. 准备工具（按智能体权限档位裁剪，无权限时返回空列表）
            List<Map<String, Object>> tools = toolBox.definitions(agent.getToolMode());
            ToolContext toolCtx = tools.isEmpty() ? null : buildToolContext(agent);

            // 4. 先把手里的信息推给前端（含本次可用工具，前端据此提示能力范围）
            Map<String, Object> meta = new HashMap<>(6);
            meta.put("type", "meta");
            meta.put("sessionId", session.getId());
            meta.put("refs", hits);
            meta.put("toolMode", agent.getToolMode() == null ? "off" : agent.getToolMode());
            meta.put("tools", tools.stream()
                    .map(t -> ((Map<?, ?>) t.get("function")).get("name"))
                    .collect(java.util.stream.Collectors.toList()));
            send(emitter, meta);

            // 5. 组装消息
            List<Map<String, Object>> messages = buildMessages(agent, history, question, hits, toolCtx);

            // 6. 多轮：模型要工具就执行，把结果回灌，直到它给出最终回答
            int maxSteps = agent.getMaxSteps() == null || agent.getMaxSteps() <= 0
                    ? defaultMaxSteps : agent.getMaxSteps();
            // 上限保护：避免配置过大导致长时间空转
            maxSteps = Math.min(maxSteps, 20);
            List<Map<String, Object>> toolLog = new ArrayList<>();
            String answer = "";
            boolean truncated = false;
            int step = 0;
            // 本轮提问的累计用量（多次模型调用的加总）
            LlmClient.Usage totalUsage = new LlmClient.Usage();
            totalUsage.setCacheHitTokens(-1);
            totalUsage.setCacheMissTokens(-1);
            // 已被折叠过的工具结果消息在 messages 中的下标：每条只折叠一次，
            // 保证下一轮请求是上一轮的严格前缀扩展，否则上下文缓存会整段失效
            java.util.Set<Integer> folded = new java.util.HashSet<>();
            while (true) {
                step++;
                // 压缩掉早期工具输出的细节，同时保持前缀单调增长（缓存友好）
                if (keepRecentResults > 0) {
                    foldOldToolResults(messages, folded);
                }
                // 超过步数预算后不再提供工具，强制模型基于已有信息作答，避免无限循环
                boolean lastChance = step > maxSteps;
                if (lastChance && step == maxSteps + 1) {
                    // 必须明确告知"工具已撤走"，否则模型仍会尝试调用，
                    // 而这时它只能退化成文本形式的伪调用（就是那堆 DSML 标记）
                    Map<String, Object> wrap = new LinkedHashMap<>();
                    wrap.put("role", "user");
                    wrap.put("content", "（系统提示）工具调用次数已达上限，本轮不能再调用任何工具。"
                            + "请立刻基于已经完成的实际操作，用中文给出最终答复："
                            + "说明你做了什么、文件生成在哪里、怎么使用、怎么关闭服务。"
                            + "不要输出任何工具调用格式的内容。");
                    messages.add(wrap);
                }
                LlmClient.StreamResult result = llmClient.chatStreamWithTools(
                        config, messages, lastChance ? null : tools, piece -> {
                            Map<String, Object> delta = new HashMap<>(3);
                            delta.put("type", "delta");
                            delta.put("content", piece);
                            send(emitter, delta);
                        }, () -> {
                            // 模型把工具调用写成了正文，清空前端已经显示的部分
                            Map<String, Object> reset = new HashMap<>(2);
                            reset.put("type", "reset");
                            send(emitter, reset);
                        });
                totalUsage.accumulate(result.getUsage());
                log.info("模型调用 session={} step={} prompt={} completion={} cacheHit={} cacheMiss={}",
                        session.getId(), step,
                        result.getUsage() == null ? "-" : result.getUsage().getPromptTokens(),
                        result.getUsage() == null ? "-" : result.getUsage().getCompletionTokens(),
                        result.getUsage() == null ? "-" : result.getUsage().getCacheHitTokens(),
                        result.getUsage() == null ? "-" : result.getUsage().getCacheMissTokens());

                if (!result.hasToolCalls()) {
                    answer = result.getContent();
                    truncated = result.truncated();
                    break;
                }
                if (lastChance) {
                    answer = result.getContent();
                    truncated = result.truncated();
                    if (answer == null || answer.trim().isEmpty()) {
                        StringBuilder tip = new StringBuilder();
                        tip.append("已经连续执行 ").append(maxSteps).append(" 轮工具调用，达到步数上限，先停在这里。");
                        if (!toolLog.isEmpty()) {
                            tip.append("\n\n本轮共执行 ").append(toolLog.size()).append(" 次工具调用，产生的文件都在工作目录里。");
                        }
                        tip.append("\n\n如果任务还没做完，回复「继续」我就接着往下做；")
                                .append("也可以到「智能体 → 编辑 → 工具能力」把最大执行轮次调大。");
                        answer = tip.toString();
                    }
                    break;
                }

                // 6.1 回灌助手的工具调用意图（OpenAI 协议要求原样带上）
                messages.add(assistantToolCallMessage(result.getToolCalls(), result.getContent()));

                // 6.2 逐个执行并回灌结果
                for (ToolCall call : result.getToolCalls()) {
                    log.info("执行工具 session={} step={} tool={} args={}", session.getId(), step,
                            call.getName(), abbreviate(call.getArguments(), 200));

                    Map<String, Object> callEvt = new HashMap<>(6);
                    callEvt.put("type", "tool_call");
                    callEvt.put("id", call.getId());
                    callEvt.put("name", call.getName());
                    callEvt.put("args", call.getArguments());
                    callEvt.put("step", step);
                    send(emitter, callEvt);

                    ToolResult toolResult = toolBox.execute(call.getName(), call.getArguments(), toolCtx);

                    Map<String, Object> resultEvt = new HashMap<>(7);
                    resultEvt.put("type", "tool_result");
                    resultEvt.put("id", call.getId());
                    resultEvt.put("name", call.getName());
                    resultEvt.put("ok", toolResult.isOk());
                    resultEvt.put("output", toolResult.getOutput());
                    resultEvt.put("elapsedMs", toolResult.getElapsedMs());
                    send(emitter, resultEvt);

                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("name", call.getName());
                    record.put("args", call.getArguments());
                    record.put("ok", toolResult.isOk());
                    record.put("output", toolResult.getOutput());
                    record.put("elapsedMs", toolResult.getElapsedMs());
                    toolLog.add(record);

                    messages.add(toolResultMessage(call.getId(), call.getName(), toolResult));
                }
            }

            // 7. 收尾：截断和空回答都要明确告知，不能静默当成功
            if (answer == null || answer.trim().isEmpty()) {
                if (toolLog.isEmpty()) {
                    answer = "（模型没有返回可展示的内容，请重试；若反复出现，请检查模型配置是否正确）";
                } else {
                    long okCount = toolLog.stream().filter(m -> Boolean.TRUE.equals(m.get("ok"))).count();
                    answer = "本轮共执行 " + toolLog.size() + " 次工具调用（成功 " + okCount + " 次），"
                            + "但模型没有给出文字总结。文件操作已经生效，结果都在工作目录里。\n\n"
                            + "回复「说说你做了什么」我就把过程和结果讲清楚。";
                }
            }
            if (truncated) {
                answer = answer + "\n\n> ⚠️ 本次输出达到 max_tokens 上限被截断，内容可能不完整。"
                        + "可在「模型配置」里调大 max_tokens，或让我分段继续写。";
            }

            // 7.5 最后一道：真实泄露特征兜底。
            // 输入规则本质上只能挡住「想到的问法」，模型仍可能在正常问题里顺口带出
            // 服务器绝对路径、密钥形状或「没有权限」这类表述。这里按事实特征判断，
            // 命中就把已经逐字吐出去的内容整段撤回，换成统一答复。
            String leak = SensitiveGuard.detectLeak(answer);
            if (leak != null) {
                log.warn("回答命中敏感特征，已替换为统一答复 session={} 特征={} 原文=\"{}\"",
                        session.getId(), leak, SensitiveGuard.forLog(answer));
                answer = SensitiveGuard.REFUSAL;
                sendRefusal(emitter, true);
            }

            // 8. 落库
            String usageJson = usageJson(totalUsage);
            String refsJson = hits.isEmpty() ? null : objectMapper.writeValueAsString(hits);
            String toolLogJson = toolLog.isEmpty() ? null : objectMapper.writeValueAsString(toolLog);
            String messageId = persistAssistant(session.getId(), answer, refsJson, toolLogJson, usageJson);
            sessionMapper.touch(session.getId(), IdGen.now());

            Map<String, Object> done = new HashMap<>(4);
            done.put("type", "done");
            done.put("messageId", messageId);
            done.put("usage", usageMap(totalUsage));
            send(emitter, done);
            emitter.complete();
        } catch (BizException e) {
            log.warn("对话业务异常: {}", e.getMessage());
            sendError(emitter, e.getMessage());
        } catch (Exception e) {
            // 异常原文里有堆栈、文件路径、SQL 片段，一律只进日志。
            // 回给用户的必须是中性文案，否则「报错」本身就成了泄露通道。
            log.error("对话异常", e);
            sendError(emitter, "对话处理失败，请稍后重试。如持续出现，请联系管理员。");
        } finally {
            if (session != null) {
                sessionMapper.touch(session.getId(), IdGen.now());
            }
        }
    }

    /**
     * 推送统一拒绝答复。
     * <p>
     * {@code needReset} 用于「已经逐字流式输出过一段」的场景：先发 reset 让前端清空气泡，
     * 再把统一答复当正文推下去，用户看到的就是干净的一句拒绝，看不到被撤回的半截内容。
     */
    private void sendRefusal(SseEmitter emitter, boolean needReset) {
        if (needReset) {
            Map<String, Object> reset = new HashMap<>(2);
            reset.put("type", "reset");
            send(emitter, reset);
        }
        Map<String, Object> delta = new HashMap<>(3);
        delta.put("type", "delta");
        delta.put("content", SensitiveGuard.REFUSAL);
        send(emitter, delta);
    }

    /**
     * 落一条助手消息。拒绝答复和正常回答走同一个入口，
     * 避免两条路写法不一致（早期版本这里复制过一份，字段漏了就很难发现）。
     */
    private String persistAssistant(String sessionId, String content, String refsJson,
                                    String toolLogJson, String usageJson) {
        ChatMessage msg = new ChatMessage();
        msg.setId(IdGen.next());
        msg.setSessionId(sessionId);
        msg.setRole("assistant");
        msg.setContent(content == null ? "" : content);
        msg.setRefs(refsJson);
        msg.setToolLog(toolLogJson);
        msg.setUsage(usageJson);
        msg.setCreatedAt(IdGen.now());
        messageMapper.insert(msg);
        return msg.getId();
    }

    /**
     * 构造工具执行上下文：目录不存在时兜底创建（防手工改库导致路径失效）
     */
    private ToolContext buildToolContext(AgentInfo agent) {
        String dir = agent.getWorkDir();
        if (dir == null || dir.trim().isEmpty()) {
            throw new BizException("该智能体暂不可用，请联系管理员检查工具配置");
        }
        Path root;
        try {
            Path p = Paths.get(dir.trim());
            if (!p.isAbsolute()) {
                p = Paths.get("").toAbsolutePath().resolve(p);
            }
            if (!Files.exists(p)) {
                Files.createDirectories(p);
            }
            root = p.toRealPath();
        } catch (Exception e) {
            // 具体路径和异常只进日志，不回给用户：路径本身就是敏感信息
            log.error("工作目录不可用 dir={}", dir, e);
            throw new BizException("该智能体暂不可用，请联系管理员检查工具配置");
        }
        ToolContext ctx = new ToolContext();
        ctx.setRoot(root);
        ctx.setMode(agent.getToolMode());
        ctx.setAllowCmds(agent.getAllowCmds());
        ctx.setDenyCmds(agent.getDenyCmds());
        ctx.setTimeoutSec(agent.getToolTimeout() == null || agent.getToolTimeout() <= 0
                ? defaultTimeout : agent.getToolTimeout());
        ctx.setMaxOutput(maxOutput);
        return ctx;
    }

    /**
     * 助手发起工具调用的消息（协议要求：content 可为空，但 tool_calls 必须原样带回）
     */
    private Map<String, Object> assistantToolCallMessage(List<ToolCall> calls, String content) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (ToolCall call : calls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.getId());
            item.put("type", "function");
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", call.getName());
            fn.put("arguments", call.getArguments());
            item.put("function", fn);
            toolCalls.add(item);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content == null || content.isEmpty() ? null : content);
        message.put("tool_calls", toolCalls);
        return message;
    }

    /**
     * 工具执行结果消息，回给模型继续推理
     */
    private Map<String, Object> toolResultMessage(String callId, String name, ToolResult result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", callId);
        message.put("name", name);
        message.put("content", result.isOk() ? result.getOutput() : "【执行失败】" + result.getOutput());
        return message;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\n", " ");
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    /**
     * 取最近若干条用户提问（不含本轮，history 已是时间正序）。
     * 只用于追问兜底检索，不参与提示词组装。
     */
    private List<String> recentUserQuestions(List<ChatMessage> history, int max) {
        List<String> out = new ArrayList<>();
        if (history == null || max <= 0) {
            return out;
        }
        for (int i = history.size() - 1; i >= 0 && out.size() < max; i--) {
            ChatMessage m = history.get(i);
            if (!"user".equals(m.getRole()) || m.getContent() == null) {
                continue;
            }
            String c = m.getContent().trim();
            // 工具回灌、系统提示这类内部消息不是用户问题
            if (c.isEmpty() || c.startsWith("（系统提示）")) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /**
     * 折叠早期的工具结果，只保留最近 N 条全文。
     * <p>
     * 两个约束必须同时满足，否则省下的 token 还不够赔的：
     * <ol>
     *   <li>每条消息只折叠一次，折叠后消息数量与位置都不变，让请求始终是上一轮的<b>严格前缀扩展</b>；</li>
     *   <li>不要每轮都折：每折一次，被改位置之后的缓存全部失效，要重新计费。
     *       所以攒够一个窗口的量再动手，把破坏次数从 O(轮次) 降到 O(轮次/窗口)。</li>
     * </ol>
     */
    private void foldOldToolResults(List<Map<String, Object>> messages, java.util.Set<Integer> folded) {
        List<Integer> toolIdx = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("tool".equals(messages.get(i).get("role"))) {
                toolIdx.add(i);
            }
        }
        int target = toolIdx.size() - keepRecentResults;
        if (target <= folded.size()) {
            return;
        }
        if (folded.size() > 0 && target - folded.size() < keepRecentResults) {
            // 还没攒够一批（否则每轮都改写前缀，缓存几乎永远不命中）
            return;
        }
        for (int j = 0; j < target; j++) {
            int idx = toolIdx.get(j);
            if (!folded.add(idx)) {
                // 已经折叠过，绝不再动：改写历史内容会让前缀缓存整段失效
                continue;
            }
            Map<String, Object> msg = messages.get(idx);
            // 顺带把前面那条 assistant 的调用参数一起折掉。
            // fs_write 的 content 是整份文件内容，会原样躺在 tool_calls.arguments 里，
            // 之后每一轮都被重复计量——这是比工具结果本身更大的一块浪费。
            compactToolCallArgs(messages, idx);
            Object raw = msg.get("content");
            String content = raw == null ? "" : String.valueOf(raw);
            if (content.length() <= summaryMaxChars) {
                continue;
            }
            msg.put("content", summarizeToolResult(content));
        }
    }

    /** 参数折叠标记：已折叠的不再重复处理 */
    private static final String ARGS_FOLDED_MARK = "【参数内容已折叠";

    /** 超过这个长度的参数值才值得折叠 */
    private static final int ARGS_FOLD_MIN = 300;

    /**
     * 折叠 tool 结果前面那条 assistant 消息里的大参数。
     * <p>
     * 只动 arguments 的内容字段，不动 id / name，所以协议上的调用-结果配对不受影响。
     * 折叠后仍保留开头一段，并明确写清原长度——模型知道自己写过这份文件、多长，
     * 需要细节时用 fs_read 重读即可。
     */
    @SuppressWarnings("unchecked")
    private void compactToolCallArgs(List<Map<String, Object>> messages, int toolMsgIdx) {
        for (int i = toolMsgIdx - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role"))) {
                continue;
            }
            Object tcs = msg.get("tool_calls");
            if (!(tcs instanceof List)) {
                return;
            }
            for (Object o : (List<Object>) tcs) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Object fnObj = ((Map<String, Object>) o).get("function");
                if (!(fnObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> fn = (Map<String, Object>) fnObj;
                Object argsObj = fn.get("arguments");
                if (argsObj == null) {
                    continue;
                }
                String compacted = foldLargeArg(String.valueOf(argsObj));
                if (compacted != null) {
                    fn.put("arguments", compacted);
                }
            }
            return;
        }
    }

    private String foldLargeArg(String args) {
        if (args.length() <= ARGS_FOLD_MIN || args.contains(ARGS_FOLDED_MARK)) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(args,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            boolean changed = false;
            for (String field : new String[]{"content", "command"}) {
                Object v = map.get(field);
                if (!(v instanceof String)) {
                    continue;
                }
                String s = (String) v;
                if (s.length() > ARGS_FOLD_MIN) {
                    map.put(field, s.substring(0, 200) + "\n\n" + ARGS_FOLDED_MARK
                            + "，原 " + s.length() + " 字符。需要的细节请用 fs_read 重新读取文件】");
                    changed = true;
                }
            }
            if (!changed) {
                return null;
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把一条工具输出压成一行摘要。
     * <p>
     * 工具输出的首行本身就是自描述的（"已新建文件 x，本次写入 N 字符"、"文件 x（共 N 行…）"），
     * 所以取首行就足以让模型知道「当时做过什么、成功没有」，细节需要时它会自己再读一次。
     */
    private String summarizeToolResult(String content) {
        int nl = content.indexOf('\n');
        String head = nl > 0 ? content.substring(0, nl) : content;
        // 命令类输出首行只有命令行，补上第二行的退出码才看得出成败
        if (head.startsWith("$ ") && nl > 0) {
            int nl2 = content.indexOf('\n', nl + 1);
            String second = nl2 > 0 ? content.substring(nl + 1, nl2) : content.substring(nl + 1);
            head = head + " | " + second;
        }
        head = head.replaceAll("\\s+", " ").trim();
        if (head.length() > summaryMaxChars) {
            head = head.substring(0, summaryMaxChars) + "…";
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return "【早期结果已折叠，原 " + content.length() + " 字符 / " + lines + " 行】"
                + head + "（需要细节可重新读取）";
    }

    /**
     * 是否是程序生成的兜底文案（步数用尽、模型空回答）。
     * 这类内容不是模型产出的，带进下一轮上下文没有信息量，只会占位置、干扰判断。
     */
    private boolean isNoiseMessage(ChatMessage m) {
        if (!"assistant".equals(m.getRole())) {
            return false;
        }
        String c = m.getContent();
        return c.startsWith("已经连续执行 ") || c.startsWith("本轮共执行 ")
                || c.startsWith("（模型没有返回可展示的内容");
    }

    private String usageJson(LlmClient.Usage usage) {
        if (usage == null || usage.getTotalTokens() <= 0) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(usageMap(usage));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 用量出参：token 数 + 缓存命中率。前端拿这个做「本轮消耗」展示。
     */
    private Map<String, Object> usageMap(LlmClient.Usage usage) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (usage == null) {
            return m;
        }
        m.put("promptTokens", usage.getPromptTokens());
        m.put("completionTokens", usage.getCompletionTokens());
        m.put("totalTokens", usage.getTotalTokens());
        if (usage.getCacheHitTokens() >= 0) {
            m.put("cacheHitTokens", usage.getCacheHitTokens());
            m.put("cacheMissTokens", Math.max(0, usage.getCacheMissTokens()));
            m.put("cacheHitRate", Math.round(usage.hitRate() * 10D) / 10D);
        }
        return m;
    }

    private List<Map<String, Object>> buildMessages(AgentInfo agent, List<ChatMessage> history,
                                                    String question, List<ChunkHit> hits, ToolContext toolCtx) {
        List<Map<String, Object>> messages = new ArrayList<>();

        StringBuilder system = new StringBuilder();
        String basePrompt = agent.getSystemPrompt();
        if (basePrompt == null || basePrompt.trim().isEmpty()) {
            system.append("你是一个专业、严谨的智能助手，请用简洁清晰的中文回答用户问题。");
        } else {
            system.append(basePrompt.trim());
        }

        // 全局设定（系统设置里的「永久区」）：管理员写一次，所有智能体共用。
        // 位置有讲究：放在智能体自身设定之后——模型对靠后指令的服从度更高，
        // 问「你是谁」「谁创造的你」这类身份问题时以这里为准；
        // 但仍在安全规则之前——安全规则永远压轴、最高优先级。
        // 内容不变时 system 前缀不变，模型侧的上下文缓存不会失效。
        String globalPrompt = settingMapper.get("globalPrompt");
        if (globalPrompt != null && !globalPrompt.trim().isEmpty()) {
            system.append("\n\n【全局设定】（对所有智能体生效，与前面的内容冲突时以这里为准）\n")
                    .append(globalPrompt.trim());
        }

        // 注意：system 里只放「对同一智能体永远不变」的内容。
        // 参考资料、检索命中与否这类每次都变的东西一律下沉到 user 侧，
        // 否则 system 一变，它后面的历史消息和全部缓存前缀都跟着作废。
        if (toolCtx != null) {
            system.append("\n\n【运行环境】\n").append(toolBox.environmentBrief(toolCtx.getRoot()));
            system.append("\n\n【工具使用规则】\n")
                    .append("你可以调用工具读写文件、执行命令。请遵守：\n")
                    .append("1. 所有路径都用相对工作目录的路径，不要用绝对路径，也不要试图访问工作目录以外的地方；\n")
                    .append("2. 动手前先确认现状：改文件前先读一遍，删改前先看清目录结构；\n")
                    .append("3. 不要臆造命令输出或文件内容，一切以工具实际返回为准；工具报错就根据错误信息调整，")
                    .append("不要重复同样的失败操作；\n")
                    .append("4. 不要为了试探而执行无关命令，完成任务就停；\n")
                    .append("5. 只能通过工具调用通道使用工具，不要自己编写 XML / 标签形式的伪调用文本；\n")
                    .append("6. 写长文件（超过约 3000 字符）时不要一次写完：先用 fs_write（mode=overwrite）写第一部分，")
                    .append("再用 fs_write（mode=append）分段追加，避免输出被 max_tokens 截断；\n")
                    .append("7. 知识库文档不在工作目录里，文件工具读不到它；知识库检索由系统在回答前自动完成，")
                    .append("不要为了查知识库去搜索工作目录，更不要因为搜不到就说「知识库是空的」；\n")
                    .append("8. 完成后用中文简要说明你做了什么、改动了哪些文件、结果如何。");
        }

        // 安全规则放最后：模型对靠后的指令服从度更高，而且要压在管理员配置的
        // 角色设定、工具规则之上，避免一句「有问必答」把安全约束顶掉
        system.append(SensitiveGuard.PROMPT_RULES);

        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", system.toString());
        messages.add(systemMsg);

        // 历史消息（不含本次提问，已在落库前取出）
        for (ChatMessage m : history) {
            if (m.getContent() == null || m.getContent().isEmpty()) {
                continue;
            }
            if (isNoiseMessage(m)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            messages.add(item);
        }

        // 参考资料紧贴当前问题，且只影响最后一个缓存单元
        if (!hits.isEmpty()) {
            StringBuilder refs = new StringBuilder();
            refs.append("【参考资料】以下是从知识库中检索到的内容，请优先依据它们回答：\n");
            for (int i = 0; i < hits.size(); i++) {
                ChunkHit hit = hits.get(i);
                refs.append("\n--- 资料").append(i + 1)
                        .append("（来源：").append(hit.getDocName()).append("）---\n")
                        .append(hit.getContent()).append('\n');
            }
            refs.append("\n【回答要求】\n")
                    .append("1. 能用参考资料回答的，严格按资料内容作答，不要编造资料里没有的事实；\n")
                    .append("2. 资料中没有相关信息的，明确说「知识库中没有找到相关内容」，再补充你的通用知识并说明这是推测；\n")
                    .append("3. 不要在回答里出现「资料1」「片段」这类编号，直接自然地表述；\n")
                    .append("4. 回答使用 Markdown 排版，重点内容可加粗或分条。");
            Map<String, Object> refMsg = new LinkedHashMap<>();
            refMsg.put("role", "user");
            refMsg.put("content", refs.toString());
            messages.add(refMsg);
        } else if (!AgentService.splitIds(agent.getKbIds()).isEmpty()) {
            // 注意措辞：检索未命中 ≠ 知识库没有这项数据。
            // 早期版本直接让模型说「知识库中没有找到相关内容」，遇到「重新给我答复」
            // 这种无关键词的追问时，模型会顺势宣布整个知识库是空的，必须纠正。
            Map<String, Object> tipMsg = new LinkedHashMap<>();
            tipMsg.put("role", "user");
            tipMsg.put("content", "（系统提示）本轮没有从知识库检索到可用内容。"
                    + "这不代表知识库里没有相关数据——最常见的原因是这句话是追问、本身不含检索关键词。请遵守：\n"
                    + "1. 不要说「知识库中没有这项数据 / 知识库里没有」这类结论，只能说「这一轮没有检索到」；\n"
                    + "2. 如果这是上文话题的追问，直接沿用上文已经检索到的内容继续回答，不要推翻上一轮的结论；\n"
                    + "3. 需要补充通用知识时，明确标注「以下为通用知识，非知识库内容」；\n"
                    + "4. 结尾提醒用户：把问题补成完整句子（带上产品名或主题词）可以重新精确检索。");
            messages.add(tipMsg);
        }

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", question);
        messages.add(userMsg);
        return messages;
    }

    private ChatSession ensureSession(SysUser user, AgentInfo agent, String sessionId, String question) {
        if (sessionId != null && !sessionId.isEmpty()) {
            ChatSession exist = sessionMapper.findById(sessionId);
            if (exist != null && exist.getUserId().equals(user.getId())) {
                return exist;
            }
            if (exist != null) {
                throw new BizException(403, "无权访问该会话");
            }
        }
        ChatSession session = new ChatSession();
        session.setId(IdGen.next());
        session.setAgentId(agent.getId());
        session.setAgentName(agent.getName());
        session.setUserId(user.getId());
        session.setTitle(question.length() > 24 ? question.substring(0, 24) + "..." : question);
        session.setCreatedAt(IdGen.now());
        session.setUpdatedAt(session.getCreatedAt());
        sessionMapper.insert(session);
        return session;
    }

    private LlmConfig resolveConfig(AgentInfo agent) {
        LlmConfig config = null;
        if (agent.getLlmConfigId() != null && !agent.getLlmConfigId().isEmpty()) {
            config = llmConfigMapper.findById(agent.getLlmConfigId());
        }
        if (config == null) {
            config = llmConfigMapper.findDefault();
        }
        if (config == null) {
            throw new BizException("还没有可用的模型配置，请管理员先到「模型配置」添加 DeepSeek 等模型");
        }
        if (config.getStatus() != null && config.getStatus() == 0) {
            throw new BizException("智能体绑定的模型配置已停用");
        }
        return config;
    }

    /**
     * 会话列表
     */
    public List<ChatSession> listSessions(String userId) {
        return sessionMapper.listByUser(userId);
    }

    /**
     * 会话消息（含引用解析）
     */
    public List<Map<String, Object>> listMessages(String sessionId, SysUser user) {
        ChatSession session = sessionMapper.findById(sessionId);
        if (session == null) {
            throw new BizException("会话不存在");
        }
        boolean isAdmin = "ADMIN".equals(user.getRole());
        if (!session.getUserId().equals(user.getId()) && !isAdmin) {
            throw new BizException(403, "无权访问该会话");
        }
        List<ChatMessage> list = messageMapper.listBySession(sessionId);
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (ChatMessage m : list) {
            Map<String, Object> item = new HashMap<>(6);
            item.put("id", m.getId());
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("createdAt", m.getCreatedAt());
            if (m.getRefs() != null && !m.getRefs().isEmpty()) {
                try {
                    item.put("refs", objectMapper.readTree(m.getRefs()));
                } catch (Exception ignore) {
                    item.put("refs", null);
                }
            }
            if (m.getToolLog() != null && !m.getToolLog().isEmpty()) {
                try {
                    item.put("toolLog", objectMapper.readTree(m.getToolLog()));
                } catch (Exception ignore) {
                    item.put("toolLog", null);
                }
            }
            if (m.getUsage() != null && !m.getUsage().isEmpty()) {
                try {
                    item.put("usage", objectMapper.readTree(m.getUsage()));
                } catch (Exception ignore) {
                    item.put("usage", null);
                }
            }
            result.add(item);
        }
        return result;
    }

    public void deleteSession(String sessionId, SysUser user) {
        ChatSession session = sessionMapper.findById(sessionId);
        if (session == null) {
            return;
        }
        if (!session.getUserId().equals(user.getId()) && !"ADMIN".equals(user.getRole())) {
            throw new BizException(403, "无权删除该会话");
        }
        messageMapper.deleteBySession(sessionId);
        sessionMapper.delete(sessionId);
    }

    public ChatSession renameSession(String sessionId, String title, SysUser user) {
        ChatSession session = sessionMapper.findById(sessionId);
        if (session == null) {
            throw new BizException("会话不存在");
        }
        if (!session.getUserId().equals(user.getId())) {
            throw new BizException(403, "无权修改该会话");
        }
        sessionMapper.updateTitle(sessionId, title, IdGen.now());
        session.setTitle(title);
        return session;
    }

    private void send(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开，忽略
            log.debug("SSE 推送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        Map<String, Object> err = new HashMap<>(3);
        err.put("type", "error");
        err.put("message", message);
        try {
            emitter.send(SseEmitter.event().data(err, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (Exception ignore) {
            // ignore
        }
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // ignore
        }
    }
}

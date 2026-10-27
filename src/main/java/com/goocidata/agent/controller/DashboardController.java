package com.goocidata.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goocidata.agent.common.R;
import com.goocidata.agent.entity.LlmConfig;
import com.goocidata.agent.mapper.AgentInfoMapper;
import com.goocidata.agent.mapper.ChatMessageMapper;
import com.goocidata.agent.mapper.ChatSessionMapper;
import com.goocidata.agent.mapper.KbChunkMapper;
import com.goocidata.agent.mapper.KbDocumentMapper;
import com.goocidata.agent.mapper.KnowledgeBaseMapper;
import com.goocidata.agent.mapper.LlmConfigMapper;
import com.goocidata.agent.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘统计（管理端）
 *
 * @author nzj
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final SysUserMapper userMapper;
    private final AgentInfoMapper agentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final KbDocumentMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final LlmConfigMapper llmConfigMapper;

    @GetMapping
    public R<Map<String, Object>> dashboard() {
        Map<String, Object> data = new LinkedHashMap<>();

        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("userCount", userMapper.count());
        stat.put("agentCount", agentMapper.count());
        stat.put("kbCount", kbMapper.count());
        stat.put("docCount", docMapper.count());
        stat.put("chunkCount", chunkMapper.countAll());
        stat.put("sessionCount", sessionMapper.count());
        stat.put("messageCount", messageMapper.count());
        data.put("stat", stat);

        // 模型配置健康度
        List<LlmConfig> configs = llmConfigMapper.listAll();
        List<Map<String, Object>> warnings = new ArrayList<>();
        boolean allEmptyKey = configs.stream().allMatch(c -> c.getApiKey() == null || c.getApiKey().isEmpty());
        if (configs.isEmpty()) {
            warnings.add(Map.of("level", "error", "text", "还没有配置任何大模型，请先到「模型配置」添加"));
        } else if (allEmptyKey) {
            warnings.add(Map.of("level", "warning", "text", "模型配置尚未填写 API Key，对话会失败"));
        }
        if (kbMapper.count() == 0) {
            warnings.add(Map.of("level", "info", "text", "还没有知识库，创建后上传文档即可让智能体引用"));
        }
        data.put("warnings", warnings);

        // 模型列表（脱敏）
        List<Map<String, Object>> models = new ArrayList<>();
        for (LlmConfig c : configs) {
            Map<String, Object> m = new HashMap<>(6);
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("model", c.getModel());
            m.put("isDefault", c.getIsDefault());
            m.put("hasKey", c.getApiKey() != null && !c.getApiKey().isEmpty());
            models.add(m);
        }
        data.put("models", models);

        // 最近会话
        data.put("recentAgents", agentMapper.list(null, true));

        // Token 消耗总览：缓存命中率是关键指标——命中部分按 1/10 计价，
        // 只看总 token 数会严重高估实际花费
        data.put("token", tokenStats());
        return R.ok(data);
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> tokenStats() {
        long prompt = 0;
        long completion = 0;
        long total = 0;
        long hit = 0;
        long miss = 0;
        boolean hasCache = false;
        for (String json : messageMapper.listUsages()) {
            try {
                com.fasterxml.jackson.databind.JsonNode n = mapper.readTree(json);
                prompt += n.path("promptTokens").asLong(0);
                completion += n.path("completionTokens").asLong(0);
                total += n.path("totalTokens").asLong(0);
                if (n.hasNonNull("cacheHitTokens")) {
                    hit += n.get("cacheHitTokens").asLong(0);
                    hasCache = true;
                }
                if (n.hasNonNull("cacheMissTokens")) {
                    miss += n.get("cacheMissTokens").asLong(0);
                    hasCache = true;
                }
            } catch (Exception ignore) {
                // 单条解析失败不影响汇总
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("promptTokens", prompt);
        r.put("completionTokens", completion);
        r.put("totalTokens", total);
        if (hasCache && hit + miss > 0) {
            r.put("cacheHitTokens", hit);
            r.put("cacheMissTokens", miss);
            r.put("cacheHitRate", Math.round(hit * 1000D / (hit + miss)) / 10D);
        }
        return r;
    }
}
